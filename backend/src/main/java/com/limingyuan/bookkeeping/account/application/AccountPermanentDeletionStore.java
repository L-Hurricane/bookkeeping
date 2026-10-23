package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionPreviewToken.TransferBasis;
import com.limingyuan.bookkeeping.account.persistence.AccountEntity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface AccountPermanentDeletionStore {
    Optional<AccountEntity> findAccount(long accountId, long userId);

    List<Long> findTransactionIds(long accountId, long userId);

    List<Long> findAdjustmentIds(long accountId, long userId);

    List<TransferBasis> findTransfersTouchingAccount(long accountId, long userId);

    BigDecimal currentBalance(long accountId);

    List<Long> findAnalysisSnapshotIds(long accountId, long userId);

    List<Long> findAnalysisGenerationIds(long accountId, long userId);

    boolean hasAnalysisReferenceConflict(long accountId, long userId);

    void deleteAnalysisGenerations(long accountId, long userId);

    void deleteAnalysisSnapshots(long accountId, long userId);

    void deleteTransactionTags(List<Long> transactionIds);

    void deleteTransactions(long accountId, long userId);

    void deleteAdjustments(long accountId, long userId);

    void deleteTransfers(long userId, List<Long> transferIds);

    void deleteAccount(AccountEntity account);
}
