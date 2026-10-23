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
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JpaAccountPermanentDeletionStore implements AccountPermanentDeletionStore {
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final TransactionTagRepository transactionTags;
    private final BalanceAdjustmentRepository adjustments;
    private final TransferRepository transfers;
    private final CurrentBalance balances;
    private final AccountPermanentDeletionAnalysisCounter analysisRecords;
    private final TrustedUserContext context;

    public JpaAccountPermanentDeletionStore(AccountRepository accounts, TransactionRepository transactions,
            TransactionTagRepository transactionTags, BalanceAdjustmentRepository adjustments,
            TransferRepository transfers, CurrentBalance balances,
            AccountPermanentDeletionAnalysisCounter analysisRecords, TrustedUserContext context) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.transactionTags = transactionTags;
        this.adjustments = adjustments;
        this.transfers = transfers;
        this.balances = balances;
        this.analysisRecords = analysisRecords;
        this.context = context;
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
    public int countAnalysisRecords(long accountId) {
        return analysisRecords.countForAccount(accountId);
    }

    @Override
    public void deleteTransactionTags(List<Long> transactionIds) {
        for (long transactionId : transactionIds) {
            transactionTags.deleteByTransactionId(transactionId);
        }
        transactionTags.flush();
    }

    @Override
    public void deleteTransactions(long accountId, long userId) {
        transactions.deleteAllByAccountIdAndUserId(accountId, userId);
        transactions.flush();
    }

    @Override
    public void deleteAdjustments(long accountId, long userId) {
        adjustments.deleteAllByAccountIdAndUserId(accountId, userId);
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
}
