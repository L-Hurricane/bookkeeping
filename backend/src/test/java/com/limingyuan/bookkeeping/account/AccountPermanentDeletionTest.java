package com.limingyuan.bookkeeping.account;

import com.limingyuan.bookkeeping.account.application.AccountDeletionLocks;
import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionPreviewToken.TransferBasis;
import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionService;
import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionStore;
import com.limingyuan.bookkeeping.account.domain.AccountKind;
import com.limingyuan.bookkeeping.account.domain.AccountLifecycle;
import com.limingyuan.bookkeeping.account.persistence.AccountEntity;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.TrustedUserContext.Identity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountPermanentDeletionTest {
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeAccountCannotPreviewOrDelete() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(10L, 1L, "归档", AccountLifecycle.ACTIVE));
        var service = service(store, 1L);
        assertThatThrownBy(() -> service.preview(10L))
                .isInstanceOf(ApiFailure.class)
                .satisfies(ex -> {
                    var failure = (ApiFailure) ex;
                    assertThat(failure.status()).isEqualTo(409);
                    assertThat(failure.code()).isEqualTo("ACCOUNT_NOT_ARCHIVED");
                });
        assertThat(store.deleteInvoked).isFalse();
    }

    @Test
    void crossUserAccountIsMissing() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(10L, 2L, "他人", AccountLifecycle.ARCHIVED));
        var service = service(store, 1L);
        assertThatThrownBy(() -> service.preview(10L))
                .isInstanceOf(ApiFailure.class)
                .satisfies(ex -> assertThat(((ApiFailure) ex).code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }

    @Test
    void previewShowsCounterpartBalanceChangeForWholeTransfer() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(20L, 1L, "归档账户", AccountLifecycle.ARCHIVED));
        store.putAccount(archived(5L, 1L, "转入侧", AccountLifecycle.ACTIVE));
        store.putAccount(archived(30L, 1L, "转出侧", AccountLifecycle.ACTIVE));
        store.transfers.add(new TransferBasis(100L, 30L, 20L, bd("15.00")));
        store.transfers.add(new TransferBasis(101L, 20L, 5L, bd("4.50")));
        store.balances.put(5L, bd("104.50"));
        store.balances.put(30L, bd("85.00"));
        var service = service(store, 1L);
        var preview = service.preview(20L);
        assertThat(preview.transferCount()).isEqualTo(2);
        assertThat(preview.affectedAccounts()).hasSize(2);
        assertThat(preview.affectedAccounts().get(0).accountId()).isEqualTo(5L);
        assertThat(preview.affectedAccounts().get(0).balanceBefore()).isEqualTo("104.50");
        assertThat(preview.affectedAccounts().get(0).balanceAfter()).isEqualTo("100.00");
        assertThat(preview.affectedAccounts().get(0).change()).isEqualTo("-4.50");
        assertThat(preview.affectedAccounts().get(1).accountId()).isEqualTo(30L);
        assertThat(preview.affectedAccounts().get(1).balanceBefore()).isEqualTo("85.00");
        assertThat(preview.affectedAccounts().get(1).balanceAfter()).isEqualTo("100.00");
        assertThat(preview.affectedAccounts().get(1).change()).isEqualTo("15.00");
    }

    @Test
    void staleTokenRejectsWithoutDeleting() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(20L, 1L, "归档", AccountLifecycle.ARCHIVED));
        store.transactionIds.add(9L);
        var service = service(store, 1L);
        var preview = service.preview(20L);
        store.transactionIds.add(10L);
        assertThatThrownBy(() -> service.execute(20L, preview.previewToken()))
                .isInstanceOf(ApiFailure.class)
                .satisfies(ex -> assertThat(((ApiFailure) ex).code()).isEqualTo("PREVIEW_STALE"));
        assertThat(store.deleteInvoked).isFalse();
    }

    @Test
    void previewCountsAndDeletesAccountAnalysisRowsInOrder() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(20L, 1L, "归档", AccountLifecycle.ARCHIVED));
        store.addSnapshot(20L, 1L, 301L);
        store.addSnapshot(20L, 1L, 302L);
        store.addGeneration(20L, 1L, 401L, 301L);
        store.addGeneration(20L, 1L, 402L, null);
        store.transactionIds.add(1L);
        var service = service(store, 1L);
        var preview = service.preview(20L);
        assertThat(preview.analysisSnapshotCount()).isEqualTo(2);
        assertThat(preview.analysisGenerationCount()).isEqualTo(2);
        service.execute(20L, preview.previewToken());
        assertThat(store.deletionSteps).containsExactly(
                "analysis_generations",
                "analysis_snapshots",
                "transaction_tags",
                "transactions",
                "adjustments",
                "transfers",
                "account");
        assertThat(store.findAnalysisSnapshotIds(20L, 1L)).isEmpty();
        assertThat(store.findAnalysisGenerationIds(20L, 1L)).isEmpty();
        assertThat(store.findAccount(20L, 1L)).isEmpty();
    }

    @Test
    void analysisRowsAllowConfirmAndDelete() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(20L, 1L, "归档", AccountLifecycle.ARCHIVED));
        store.addGeneration(20L, 1L, 501L, null);
        var service = service(store, 1L);
        var preview = service.preview(20L);
        assertThat(preview.analysisGenerationCount()).isEqualTo(1);
        service.execute(20L, preview.previewToken());
        assertThat(store.deleteInvoked).isTrue();
    }

    @Test
    void otherAccountOtherUserNullQuotaAndLocksRemainUntouched() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(20L, 1L, "归档", AccountLifecycle.ARCHIVED));
        store.putAccount(archived(21L, 1L, "其他账户", AccountLifecycle.ARCHIVED));
        store.addSnapshot(20L, 1L, 601L);
        store.addSnapshot(21L, 1L, 602L);
        store.addSnapshot(20L, 2L, 603L);
        store.addSnapshot(null, 1L, 604L);
        store.addGeneration(20L, 1L, 701L, 601L);
        store.addGeneration(21L, 1L, 702L, 602L);
        store.addGeneration(null, 1L, 703L, 604L);
        store.quotaDays.put(1L, 1);
        store.userLocks.add(1L);
        var service = service(store, 1L);
        var preview = service.preview(20L);
        service.execute(20L, preview.previewToken());
        assertThat(store.snapshotExists(601L)).isFalse();
        assertThat(store.snapshotExists(602L)).isTrue();
        assertThat(store.snapshotExists(603L)).isTrue();
        assertThat(store.snapshotExists(604L)).isTrue();
        assertThat(store.generationExists(701L)).isFalse();
        assertThat(store.generationExists(702L)).isTrue();
        assertThat(store.generationExists(703L)).isTrue();
        assertThat(store.quotaDays.get(1L)).isEqualTo(1);
        assertThat(store.userLocks).containsExactly(1L);
    }

    @Test
    void crossAccountGenerationReferenceRejectsWithoutDeleting() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(20L, 1L, "归档", AccountLifecycle.ARCHIVED));
        store.putAccount(archived(21L, 1L, "其他", AccountLifecycle.ARCHIVED));
        store.addSnapshot(21L, 1L, 801L);
        store.addGeneration(20L, 1L, 901L, 801L);
        var service = service(store, 1L);
        var preview = service.preview(20L);
        assertThatThrownBy(() -> service.execute(20L, preview.previewToken()))
                .isInstanceOf(ApiFailure.class)
                .satisfies(ex -> {
                    var failure = (ApiFailure) ex;
                    assertThat(failure.code()).isEqualTo("ANALYSIS_REFERENCE_CONFLICT");
                    assertThat(failure.getMessage()).contains("跨账户");
                });
        assertThat(store.deleteInvoked).isFalse();
        assertThat(store.deletionSteps).isEmpty();
        assertThat(store.findAccount(20L, 1L)).isPresent();
    }

    @Test
    void inboundCrossAccountSnapshotReferenceRejectsWithoutDeleting() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(20L, 1L, "归档", AccountLifecycle.ARCHIVED));
        store.addSnapshot(20L, 1L, 811L);
        store.addGeneration(null, 1L, 911L, 811L);
        var service = service(store, 1L);
        var preview = service.preview(20L);
        assertThatThrownBy(() -> service.execute(20L, preview.previewToken()))
                .isInstanceOf(ApiFailure.class)
                .satisfies(ex -> assertThat(((ApiFailure) ex).code()).isEqualTo("ANALYSIS_REFERENCE_CONFLICT"));
        assertThat(store.deleteInvoked).isFalse();
    }

    @Test
    void analysisIdsAddedAfterPreviewRejectAsStale() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(20L, 1L, "归档", AccountLifecycle.ARCHIVED));
        var service = service(store, 1L);
        var preview = service.preview(20L);
        store.addSnapshot(20L, 1L, 999L);
        assertThatThrownBy(() -> service.execute(20L, preview.previewToken()))
                .isInstanceOf(ApiFailure.class)
                .satisfies(ex -> assertThat(((ApiFailure) ex).code()).isEqualTo("PREVIEW_STALE"));
        assertThat(store.deleteInvoked).isFalse();
    }

    @Test
    void executeDeletesWholeTransfersAndAccount() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(20L, 1L, "归档", AccountLifecycle.ARCHIVED));
        store.putAccount(archived(5L, 1L, "对手", AccountLifecycle.ACTIVE));
        store.transfers.add(new TransferBasis(200L, 5L, 20L, bd("10.00")));
        store.transactionIds.add(1L);
        store.adjustmentIds.add(2L);
        var service = service(store, 1L);
        var preview = service.preview(20L);
        service.execute(20L, preview.previewToken());
        assertThat(store.deleteInvoked).isTrue();
        assertThat(store.deletedTransferIds).containsExactly(200L);
        assertThat(store.deletedTransactionIds).containsExactly(1L);
        assertThat(store.deletedAdjustmentIds).containsExactly(2L);
        assertThat(store.findAccount(20L, 1L)).isEmpty();
        assertThat(store.transfers).isEmpty();
    }

    @Test
    void failureBeforeAccountDeleteRollsBackTransaction() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(20L, 1L, "归档", AccountLifecycle.ARCHIVED));
        store.transactionIds.add(1L);
        store.failOnStep = "analysis_generations";
        var tx = new RecordingTransactionManager();
        var service = new AccountPermanentDeletionService(store, new RecordingLocks(store), userContext(1L), tx);
        var preview = service.preview(20L);
        assertThatThrownBy(() -> service.execute(20L, preview.previewToken())).isInstanceOf(RuntimeException.class);
        assertThat(tx.committed).isFalse();
        assertThat(tx.rolledBack).isTrue();
        assertThat(store.findAccount(20L, 1L)).isPresent();
        assertThat(store.transactionIds).containsExactly(1L);
        assertThat(store.deleteInvoked).isFalse();
    }

    @Test
    void locksAccountsInAscendingIdOrder() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(50L, 1L, "归档", AccountLifecycle.ARCHIVED));
        store.putAccount(archived(10L, 1L, "A", AccountLifecycle.ACTIVE));
        store.putAccount(archived(40L, 1L, "B", AccountLifecycle.ACTIVE));
        store.transfers.add(new TransferBasis(1L, 40L, 50L, bd("1.00")));
        store.transfers.add(new TransferBasis(2L, 50L, 10L, bd("2.00")));
        var locks = new RecordingLocks(store);
        var service = new AccountPermanentDeletionService(store, locks, userContext(1L), directTransactions());
        var preview = service.preview(50L);
        service.execute(50L, preview.previewToken());
        assertThat(locks.seenLockOrders).hasSize(1);
        assertThat(locks.seenLockOrders.get(0)).containsExactly(10L, 40L, 50L);
    }

    private static AccountPermanentDeletionService service(InMemoryDeletionStore store, long userId) {
        return new AccountPermanentDeletionService(store, new RecordingLocks(store), userContext(userId),
                directTransactions());
    }

    private static TrustedUserContext userContext(long userId) {
        authenticate(userId);
        return new TrustedUserContext();
    }

    private static void authenticate(long userId) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(new Identity(userId, 0L), null, List.of()));
    }

    private static PlatformTransactionManager directTransactions() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) { }

            @Override
            protected void doCommit(DefaultTransactionStatus status) { }

            @Override
            protected void doRollback(DefaultTransactionStatus status) { }
        };
    }

    private static AccountEntity archived(long id, long userId, String name, AccountLifecycle lifecycle) {
        var entity = new AccountEntity(userId, name, AccountKind.NORMAL, bd("0.00"),
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.ofInstant(
                        LocalDateTime.of(2026, 1, 1, 0, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        setId(entity, id);
        if (lifecycle == AccountLifecycle.ARCHIVED) {
            entity.archive(LocalDateTime.of(2026, 2, 1, 0, 0));
        }
        return entity;
    }

    private static void setId(AccountEntity entity, long id) {
        try {
            var field = AccountEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static final class InMemoryDeletionStore implements AccountPermanentDeletionStore {
        private final long ownerUserId;
        private final Map<Long, AccountEntity> accounts = new HashMap<>();
        final List<Long> transactionIds = new ArrayList<>();
        final List<Long> adjustmentIds = new ArrayList<>();
        final List<TransferBasis> transfers = new ArrayList<>();
        final Map<Long, BigDecimal> balances = new HashMap<>();
        final Map<Long, AnalysisSnapshotRow> snapshots = new LinkedHashMap<>();
        final Map<Long, AnalysisGenerationRow> generations = new LinkedHashMap<>();
        final Map<Long, Integer> quotaDays = new HashMap<>();
        final List<Long> userLocks = new ArrayList<>();
        final List<String> deletionSteps = new ArrayList<>();
        boolean deleteInvoked;
        String failOnStep;
        final List<Long> deletedTransferIds = new ArrayList<>();
        final List<Long> deletedTransactionIds = new ArrayList<>();
        final List<Long> deletedAdjustmentIds = new ArrayList<>();

        InMemoryDeletionStore(long ownerUserId) {
            this.ownerUserId = ownerUserId;
        }

        void putAccount(AccountEntity account) {
            accounts.put(account.getId(), account);
            balances.putIfAbsent(account.getId(), account.getOpeningBalance());
        }

        void addSnapshot(Long accountId, long userId, long id) {
            snapshots.put(id, new AnalysisSnapshotRow(id, userId, accountId));
        }

        void addGeneration(Long accountId, long userId, long id, Long snapshotId) {
            generations.put(id, new AnalysisGenerationRow(id, userId, accountId, snapshotId));
        }

        boolean snapshotExists(long id) {
            return snapshots.containsKey(id);
        }

        boolean generationExists(long id) {
            return generations.containsKey(id);
        }

        @Override
        public Optional<AccountEntity> findAccount(long accountId, long userId) {
            var account = accounts.get(accountId);
            if (account == null || account.getUserId() != userId) {
                return Optional.empty();
            }
            return Optional.of(account);
        }

        @Override
        public List<Long> findTransactionIds(long accountId, long userId) {
            return findAccount(accountId, userId).isPresent() ? List.copyOf(transactionIds) : List.of();
        }

        @Override
        public List<Long> findAdjustmentIds(long accountId, long userId) {
            return findAccount(accountId, userId).isPresent() ? List.copyOf(adjustmentIds) : List.of();
        }

        @Override
        public List<TransferBasis> findTransfersTouchingAccount(long accountId, long userId) {
            if (findAccount(accountId, userId).isEmpty()) {
                return List.of();
            }
            return transfers.stream()
                    .filter(t -> t.fromAccountId() == accountId || t.toAccountId() == accountId)
                    .toList();
        }

        @Override
        public BigDecimal currentBalance(long accountId) {
            return balances.getOrDefault(accountId, bd("0.00"));
        }

        @Override
        public List<Long> findAnalysisSnapshotIds(long accountId, long userId) {
            return snapshots.values().stream()
                    .filter(row -> row.userId == userId && row.accountId != null && row.accountId == accountId)
                    .map(AnalysisSnapshotRow::id)
                    .sorted()
                    .toList();
        }

        @Override
        public List<Long> findAnalysisGenerationIds(long accountId, long userId) {
            return generations.values().stream()
                    .filter(row -> row.userId == userId && row.accountId != null && row.accountId == accountId)
                    .map(AnalysisGenerationRow::id)
                    .sorted()
                    .toList();
        }

        @Override
        public boolean hasAnalysisReferenceConflict(long accountId, long userId) {
            for (var generation : generations.values()) {
                if (generation.userId != userId || generation.accountId == null || generation.accountId != accountId) {
                    continue;
                }
                if (generation.snapshotId == null) {
                    continue;
                }
                var snapshot = snapshots.get(generation.snapshotId);
                if (snapshot == null || snapshot.userId != userId || snapshot.accountId == null
                        || snapshot.accountId != accountId) {
                    return true;
                }
            }
            for (var snapshot : snapshots.values()) {
                if (snapshot.userId != userId || snapshot.accountId == null || snapshot.accountId != accountId) {
                    continue;
                }
                for (var generation : generations.values()) {
                    if (generation.snapshotId != null && generation.snapshotId == snapshot.id) {
                        if (generation.userId != userId || generation.accountId == null
                                || generation.accountId != accountId) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        @Override
        public void deleteAnalysisGenerations(long accountId, long userId) {
            recordStep("analysis_generations");
            generations.entrySet().removeIf(entry -> entry.getValue().userId == userId
                    && entry.getValue().accountId != null
                    && entry.getValue().accountId == accountId);
        }

        @Override
        public void deleteAnalysisSnapshots(long accountId, long userId) {
            recordStep("analysis_snapshots");
            snapshots.entrySet().removeIf(entry -> entry.getValue().userId == userId
                    && entry.getValue().accountId != null
                    && entry.getValue().accountId == accountId);
        }

        @Override
        public void deleteTransactionTags(List<Long> transactionIds) {
            recordStep("transaction_tags");
        }

        @Override
        public void deleteTransactions(long accountId, long userId) {
            recordStep("transactions");
            deletedTransactionIds.addAll(transactionIds);
            transactionIds.clear();
        }

        @Override
        public void deleteAdjustments(long accountId, long userId) {
            recordStep("adjustments");
            deletedAdjustmentIds.addAll(adjustmentIds);
            adjustmentIds.clear();
        }

        @Override
        public void deleteTransfers(long userId, List<Long> transferIds) {
            recordStep("transfers");
            deletedTransferIds.addAll(transferIds);
            transfers.removeIf(t -> transferIds.contains(t.id()));
        }

        @Override
        public void deleteAccount(AccountEntity account) {
            recordStep("account");
            if ("account".equals(failOnStep)) {
                throw new RuntimeException("simulated account delete failure");
            }
            deleteInvoked = true;
            accounts.remove(account.getId());
        }

        private void recordStep(String step) {
            if (step.equals(failOnStep)) {
                throw new RuntimeException("simulated failure at " + step);
            }
            deletionSteps.add(step);
        }
    }

    private record AnalysisSnapshotRow(long id, long userId, Long accountId) { }

    private record AnalysisGenerationRow(long id, long userId, Long accountId, Long snapshotId) { }

    private static final class RecordingLocks implements AccountDeletionLocks {
        private final InMemoryDeletionStore store;
        final List<List<Long>> seenLockOrders = new ArrayList<>();

        RecordingLocks(InMemoryDeletionStore store) {
            this.store = store;
        }

        @Override
        public List<AccountEntity> lockOwnedInIdOrder(java.util.Collection<Long> accountIds) {
            var ordered = new ArrayList<>(new TreeSet<>(accountIds));
            seenLockOrders.add(ordered);
            var locked = new ArrayList<AccountEntity>();
            for (long accountId : ordered) {
                locked.add(store.accounts.get(accountId));
            }
            return locked;
        }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        boolean committed;
        boolean rolledBack;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack = true;
        }
    }
}
