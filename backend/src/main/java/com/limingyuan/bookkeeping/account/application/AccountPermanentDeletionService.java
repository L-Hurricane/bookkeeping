package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionPreviewToken.Basis;
import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionPreviewToken.TransferBasis;
import com.limingyuan.bookkeeping.account.domain.AccountLifecycle;
import com.limingyuan.bookkeeping.account.persistence.AccountEntity;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongFunction;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountPermanentDeletionService {
    private final AccountPermanentDeletionStore store;
    private final AccountDeletionLocks locks;
    private final TrustedUserContext context;
    private final TransactionTemplate transaction;

    public AccountPermanentDeletionService(AccountPermanentDeletionStore store, AccountDeletionLocks locks,
            TrustedUserContext context, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.locks = locks;
        this.context = context;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record AffectedAccountView(long accountId, String name, String balanceBefore, String balanceAfter,
            String change) { }

    public record PreviewView(int transactionCount, int adjustmentCount, int transferCount, int analysisSnapshotCount,
            int analysisGenerationCount, List<AffectedAccountView> affectedAccounts, String previewToken) { }

    public PreviewView preview(long accountId) {
        long userId = context.userId();
        var account = store.findAccount(accountId, userId).orElseThrow(this::missing);
        requireArchived(account);
        var snapshot = snapshotFor(account, false);
        var impacts = AccountPermanentDeletionImpacts.counterpartImpacts(
                accountId,
                snapshot.transfers(),
                snapshot.accountNames(),
                snapshot.counterpartBalanceBefore());
        return new PreviewView(
                snapshot.transactionIds().size(),
                snapshot.adjustmentIds().size(),
                snapshot.transfers().size(),
                snapshot.analysisSnapshotIds().size(),
                snapshot.analysisGenerationIds().size(),
                AccountPermanentDeletionImpacts.views(impacts),
                AccountPermanentDeletionPreviewToken.issue(snapshot.basis()));
    }

    public void execute(long accountId, String previewToken) {
        if (previewToken == null || previewToken.trim().isEmpty()) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "previewToken 不能为空");
        }
        String token = previewToken.trim();
        long userId = context.userId();
        transaction.executeWithoutResult(status -> {
            var account = store.findAccount(accountId, userId).orElseThrow(this::missing);
            requireArchived(account);
            // Plain read: choose the account lock set only. REPEATABLE READ keeps this snapshot.
            var discovered = store.findTransfersTouchingAccount(accountId, userId);
            var lockIds = new TreeSet<Long>();
            lockIds.add(accountId);
            for (var transfer : discovered) {
                if (transfer.fromAccountId() != accountId) {
                    lockIds.add(transfer.fromAccountId());
                }
                if (transfer.toAccountId() != accountId) {
                    lockIds.add(transfer.toAccountId());
                }
            }
            var lockedAccounts = locks.lockOwnedInIdOrder(lockIds);
            AccountEntity refreshed = null;
            for (var candidate : lockedAccounts) {
                if (candidate != null && candidate.getId() == accountId) {
                    refreshed = candidate;
                }
            }
            if (refreshed == null) {
                throw missing();
            }
            requireArchived(refreshed);
            // FOR UPDATE after the account locks. This current read sees commits that landed
            // before the lock. Account writers block on the account row. Analysis writers do not
            // lock the account, so the analysis FOR UPDATE also holds the next-key range until
            // this transaction ends. Deletes name these ids. A foreign-key failure rolls back.
            var current = snapshotFor(refreshed, true);
            if (!AccountPermanentDeletionPreviewToken.matches(current.basis(), token)) {
                throw previewStale();
            }
            if (store.lockAnalysisReferenceConflict(accountId, userId)) {
                throw analysisReferenceConflict();
            }
            try {
                deleteAll(refreshed, current);
            } catch (DataIntegrityViolationException ex) {
                throw previewStale();
            }
        });
    }

    private void deleteAll(AccountEntity account, DeletionSnapshot snapshot) {
        long accountId = account.getId();
        long userId = context.userId();
        store.deleteAnalysisGenerations(accountId, userId, snapshot.analysisGenerationIds());
        store.deleteAnalysisSnapshots(accountId, userId, snapshot.analysisSnapshotIds());
        store.deleteTransactionTags(snapshot.transactionIds());
        store.deleteTransactions(accountId, userId, snapshot.transactionIds());
        store.deleteAdjustments(accountId, userId, snapshot.adjustmentIds());
        store.deleteTransfers(userId, snapshot.transferIds());
        store.deleteAccount(account);
    }

    private DeletionSnapshot snapshotFor(AccountEntity account, boolean locking) {
        long accountId = account.getId();
        long userId = context.userId();
        List<Long> transactionIds = locking
                ? store.lockTransactionIds(accountId, userId)
                : store.findTransactionIds(accountId, userId);
        List<Long> adjustmentIds = locking
                ? store.lockAdjustmentIds(accountId, userId)
                : store.findAdjustmentIds(accountId, userId);
        List<Long> analysisSnapshotIds = locking
                ? store.lockAnalysisSnapshotIds(accountId, userId)
                : store.findAnalysisSnapshotIds(accountId, userId);
        List<Long> analysisGenerationIds = locking
                ? store.lockAnalysisGenerationIds(accountId, userId)
                : store.findAnalysisGenerationIds(accountId, userId);
        List<TransferBasis> transferRows = locking
                ? store.lockTransfersTouchingAccount(accountId, userId)
                : store.findTransfersTouchingAccount(accountId, userId);
        LongFunction<java.math.BigDecimal> balances = locking ? store::lockCurrentBalance : store::currentBalance;
        var balanceBefore = AccountPermanentDeletionImpacts.counterpartBalanceBefore(
                accountId, transferRows, balances);
        var names = new LinkedHashMap<Long, String>();
        for (long counterpartId : balanceBefore.keySet()) {
            names.put(counterpartId, store.findAccount(counterpartId, userId)
                    .orElseThrow(this::missing)
                    .getName());
        }
        var basis = new Basis(accountId, transactionIds, adjustmentIds, analysisSnapshotIds, analysisGenerationIds,
                transferRows, balanceBefore);
        return new DeletionSnapshot(transactionIds, adjustmentIds, analysisSnapshotIds, analysisGenerationIds,
                transferRows, balanceBefore, names, basis);
    }

    private void requireArchived(AccountEntity account) {
        if (account.getLifecycle() != AccountLifecycle.ARCHIVED) {
            throw notArchived();
        }
    }

    private ApiFailure missing() {
        return new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private ApiFailure notArchived() {
        return new ApiFailure(409, "ACCOUNT_NOT_ARCHIVED", "只有已归档账户可以永久删除");
    }

    private ApiFailure previewStale() {
        return new ApiFailure(409, "PREVIEW_STALE", "删除影响已变化，请重新预览并确认");
    }

    private ApiFailure analysisReferenceConflict() {
        return new ApiFailure(409, "ANALYSIS_REFERENCE_CONFLICT", "分析记录存在跨账户或跨用户引用，本次未删除任何数据");
    }

    private record DeletionSnapshot(
            List<Long> transactionIds,
            List<Long> adjustmentIds,
            List<Long> analysisSnapshotIds,
            List<Long> analysisGenerationIds,
            List<TransferBasis> transfers,
            Map<Long, java.math.BigDecimal> counterpartBalanceBefore,
            Map<Long, String> accountNames,
            Basis basis) {
        List<Long> transferIds() {
            return transfers.stream().map(TransferBasis::id).toList();
        }
    }
}
