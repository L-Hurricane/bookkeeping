package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionPreviewToken.TransferBasis;
import com.limingyuan.bookkeeping.account.persistence.AccountEntity;
import com.limingyuan.bookkeeping.account.persistence.AccountRepository;
import com.limingyuan.bookkeeping.adjustment.persistence.BalanceAdjustmentRepository;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.transaction.persistence.TransactionRepository;
import com.limingyuan.bookkeeping.transaction.persistence.TransactionTagRepository;
import com.limingyuan.bookkeeping.transfer.persistence.TransferRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class JpaAccountPermanentDeletionStore implements AccountPermanentDeletionStore {
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final TransactionTagRepository transactionTags;
    private final BalanceAdjustmentRepository adjustments;
    private final TransferRepository transfers;
    private final CurrentBalance balances;
    private final AccountPermanentDeletionAnalysisCounter analysis;
    private final TrustedUserContext context;
    private final JdbcClient jdbc;

    public JpaAccountPermanentDeletionStore(AccountRepository accounts, TransactionRepository transactions,
            TransactionTagRepository transactionTags, BalanceAdjustmentRepository adjustments,
            TransferRepository transfers, CurrentBalance balances,
            AccountPermanentDeletionAnalysisCounter analysis, TrustedUserContext context, JdbcClient jdbc) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.transactionTags = transactionTags;
        this.adjustments = adjustments;
        this.transfers = transfers;
        this.balances = balances;
        this.analysis = analysis;
        this.context = context;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccountEntity> findAccount(long accountId, long userId) {
        return accounts.findByIdAndUserId(accountId, userId);
    }

    @Override
    public List<Long> findTransactionIds(long accountId, long userId) {
        return transactions.findAllIdsByAccountIdAndUserId(accountId, userId);
    }

    @Override
    public List<Long> findAdjustmentIds(long accountId, long userId) {
        return adjustments.findAllIdsByAccountIdAndUserId(accountId, userId);
    }

    @Override
    public List<TransferBasis> findTransfersTouchingAccount(long accountId, long userId) {
        return transfers.findAllTouchingAccount(userId, accountId).stream()
                .map(entity -> new TransferBasis(entity.getId(), entity.getFromAccountId(), entity.getToAccountId(),
                        entity.getAmount()))
                .toList();
    }

    @Override
    public BigDecimal currentBalance(long accountId) {
        return balances.of(accountId);
    }

    @Override
    public List<Long> findAnalysisSnapshotIds(long accountId, long userId) {
        requireUser(userId);
        return analysis.snapshotIdsForAccount(accountId);
    }

    @Override
    public List<Long> findAnalysisGenerationIds(long accountId, long userId) {
        requireUser(userId);
        return analysis.generationIdsForAccount(accountId);
    }

    @Override
    public boolean hasAnalysisReferenceConflict(long accountId, long userId) {
        requireUser(userId);
        return analysis.hasReferenceConflict(accountId);
    }

    @Override
    public List<Long> lockTransactionIds(long accountId, long userId) {
        return jdbc.sql("""
                SELECT id FROM transactions
                WHERE account_id = :accountId AND user_id = :userId
                ORDER BY id
                FOR UPDATE
                """).param("accountId", accountId).param("userId", userId).query(Long.class).list();
    }

    @Override
    public List<Long> lockAdjustmentIds(long accountId, long userId) {
        return jdbc.sql("""
                SELECT id FROM balance_adjustments
                WHERE account_id = :accountId AND user_id = :userId
                ORDER BY id
                FOR UPDATE
                """).param("accountId", accountId).param("userId", userId).query(Long.class).list();
    }

    @Override
    public List<TransferBasis> lockTransfersTouchingAccount(long accountId, long userId) {
        var byId = new LinkedHashMap<Long, TransferBasis>();
        for (var row : lockTransferSide("""
                SELECT id, from_account_id, to_account_id, amount
                FROM transfers
                WHERE user_id = :userId AND from_account_id = :accountId
                ORDER BY id
                FOR UPDATE
                """, accountId, userId)) {
            byId.put(row.id(), row);
        }
        for (var row : lockTransferSide("""
                SELECT id, from_account_id, to_account_id, amount
                FROM transfers
                WHERE user_id = :userId AND to_account_id = :accountId
                ORDER BY id
                FOR UPDATE
                """, accountId, userId)) {
            byId.putIfAbsent(row.id(), row);
        }
        return byId.values().stream().sorted(Comparator.comparing(TransferBasis::id)).toList();
    }

    @Override
    public BigDecimal lockCurrentBalance(long accountId) {
        return balances.ofLocked(accountId);
    }

    @Override
    public List<Long> lockAnalysisSnapshotIds(long accountId, long userId) {
        requireUser(userId);
        return analysis.lockSnapshotIdsForAccount(accountId);
    }

    @Override
    public List<Long> lockAnalysisGenerationIds(long accountId, long userId) {
        requireUser(userId);
        return analysis.lockGenerationsForAccount(accountId).stream()
                .map(AccountPermanentDeletionAnalysisCounter.LockedGeneration::id)
                .toList();
    }

    @Override
    public boolean lockAnalysisReferenceConflict(long accountId, long userId) {
        requireUser(userId);
        var snapshots = analysis.lockSnapshotIdsForAccount(accountId);
        var generations = analysis.lockGenerationsForAccount(accountId);
        if (AccountPermanentDeletionAnalysisCounter.outboundConflict(snapshots, generations)) {
            return true;
        }
        return analysis.lockInboundReferenceConflict(accountId, snapshots);
    }

    @Override
    public void deleteAnalysisGenerations(long accountId, long userId, List<Long> ids) {
        requireUser(userId);
        analysis.deleteGenerationsByIds(accountId, ids);
    }

    @Override
    public void deleteAnalysisSnapshots(long accountId, long userId, List<Long> ids) {
        requireUser(userId);
        analysis.deleteSnapshotsByIds(accountId, ids);
    }

    @Override
    public List<Long> findLedgerAnalysisSnapshotIds(long userId) {
        requireUser(userId);
        return analysis.ledgerSnapshotIds();
    }

    @Override
    public List<Long> findLedgerAnalysisGenerationIds(long userId) {
        requireUser(userId);
        return analysis.ledgerGenerationIds();
    }

    @Override
    public List<Long> lockLedgerAnalysisSnapshotIds(long userId) {
        requireUser(userId);
        return analysis.lockLedgerSnapshotIds();
    }

    @Override
    public List<Long> lockLedgerAnalysisGenerationIds(long userId) {
        requireUser(userId);
        return analysis.lockLedgerGenerationIds();
    }

    @Override
    public void deleteLedgerAnalysisGenerations(long userId, List<Long> ids) {
        requireUser(userId);
        analysis.deleteLedgerGenerations(ids);
    }

    @Override
    public void deleteLedgerAnalysisSnapshots(long userId, List<Long> ids) {
        requireUser(userId);
        analysis.deleteLedgerSnapshots(ids);
    }

    @Override
    public void deleteTransactionTags(List<Long> transactionIds) {
        for (long transactionId : transactionIds) {
            transactionTags.deleteByTransactionId(transactionId);
        }
        transactionTags.flush();
    }

    @Override
    public void deleteTransactions(long accountId, long userId, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        transactions.deleteByAccountIdAndUserIdAndIdIn(accountId, userId, ids);
        transactions.flush();
    }

    @Override
    public void deleteAdjustments(long accountId, long userId, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        adjustments.deleteByAccountIdAndUserIdAndIdIn(accountId, userId, ids);
        adjustments.flush();
    }

    @Override
    public void deleteTransfers(long userId, List<Long> transferIds) {
        if (transferIds.isEmpty()) {
            return;
        }
        transfers.deleteAllByUserIdAndIdIn(userId, transferIds);
        transfers.flush();
    }

    @Override
    public void deleteAccount(AccountEntity account) {
        accounts.delete(account);
        accounts.flush();
    }

    private List<TransferBasis> lockTransferSide(String sql, long accountId, long userId) {
        return jdbc.sql(sql).param("accountId", accountId).param("userId", userId)
                .query((row, rowNum) -> new TransferBasis(row.getLong("id"), row.getLong("from_account_id"),
                        row.getLong("to_account_id"), row.getBigDecimal("amount")))
                .list();
    }

    private void requireUser(long userId) {
        if (userId != context.userId()) {
            throw new IllegalStateException("user scope mismatch");
        }
    }
}
