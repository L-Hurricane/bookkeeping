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

    public record PreviewView(int transactionCount, int adjustmentCount, int transferCount, int analysisRecordCount,
            List<AffectedAccountView> affectedAccounts, String previewToken) { }

    public PreviewView preview(long accountId) {
        long userId = context.userId();
        var account = store.findAccount(accountId, userId).orElseThrow(this::missing);
        requireArchived(account);
        var snapshot = snapshotFor(account);
        var impacts = AccountPermanentDeletionImpacts.counterpartImpacts(
                accountId,
                snapshot.transfers(),
                snapshot.accountNames(),
                snapshot.counterpartBalanceBefore());
        return new PreviewView(
                snapshot.transactionIds().size(),
                snapshot.adjustmentIds().size(),
                snapshot.transfers().size(),
                store.countAnalysisRecords(accountId),
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
            var snapshot = snapshotFor(account);
            var lockIds = new TreeSet<Long>();
            lockIds.add(accountId);
            for (var transfer : snapshot.transfers()) {
                if (transfer.fromAccountId() != accountId) {
                    lockIds.add(transfer.fromAccountId());
                }
                if (transfer.toAccountId() != accountId) {
                    lockIds.add(transfer.toAccountId());
                }
            }
            locks.lockOwnedInIdOrder(lockIds);
            var refreshed = store.findAccount(accountId, userId).orElseThrow(this::missing);
            requireArchived(refreshed);
            var current = snapshotFor(refreshed);
            if (!AccountPermanentDeletionPreviewToken.matches(current.basis(), token)) {
                throw previewStale();
            }
            if (store.countAnalysisRecords(accountId) > 0) {
                throw analysisRecordsRemain();
            }
            deleteAll(refreshed, current);
        });
    }

    private void deleteAll(AccountEntity account, DeletionSnapshot snapshot) {
        long accountId = account.getId();
        long userId = context.userId();
        store.deleteTransactionTags(snapshot.transactionIds());
        store.deleteTransactions(accountId, userId);
        store.deleteAdjustments(accountId, userId);
        store.deleteTransfers(userId, snapshot.transferIds());
        store.deleteAccount(account);
    }

    private DeletionSnapshot snapshotFor(AccountEntity account) {
        long accountId = account.getId();
        long userId = context.userId();
        List<Long> transactionIds = store.findTransactionIds(accountId, userId);
        List<Long> adjustmentIds = store.findAdjustmentIds(accountId, userId);
        List<TransferBasis> transferRows = store.findTransfersTouchingAccount(accountId, userId);
        var balanceBefore = AccountPermanentDeletionImpacts.counterpartBalanceBefore(
                accountId, transferRows, store::currentBalance);
        var names = new LinkedHashMap<Long, String>();
        for (long counterpartId : balanceBefore.keySet()) {
            names.put(counterpartId, store.findAccount(counterpartId, userId)
                    .orElseThrow(this::missing)
                    .getName());
        }
        var basis = new Basis(accountId, transactionIds, adjustmentIds, transferRows, balanceBefore);
        return new DeletionSnapshot(transactionIds, adjustmentIds, transferRows, balanceBefore, names, basis);
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

    private ApiFailure analysisRecordsRemain() {
        return new ApiFailure(409, "ANALYSIS_RECORDS_REMAIN", "该账户仍有分析记录，本次未删除任何数据");
    }

    private record DeletionSnapshot(
            List<Long> transactionIds,
            List<Long> adjustmentIds,
            List<TransferBasis> transfers,
            Map<Long, java.math.BigDecimal> counterpartBalanceBefore,
            Map<Long, String> accountNames,
            Basis basis) {
        List<Long> transferIds() {
            return transfers.stream().map(TransferBasis::id).toList();
        }
    }
}
