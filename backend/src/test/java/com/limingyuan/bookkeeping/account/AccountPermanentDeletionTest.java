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
    void analysisRecordsBlockDeletion() {
        var store = new InMemoryDeletionStore(1L);
        store.putAccount(archived(20L, 1L, "归档", AccountLifecycle.ARCHIVED));
        store.analysisCount = 2;
        var service = service(store, 1L);
        var preview = service.preview(20L);
        assertThat(preview.analysisRecordCount()).isEqualTo(2);
        assertThatThrownBy(() -> service.execute(20L, preview.previewToken()))
                .isInstanceOf(ApiFailure.class)
                .satisfies(ex -> assertThat(((ApiFailure) ex).code()).isEqualTo("ANALYSIS_RECORDS_REMAIN"));
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
        int analysisCount;
        boolean deleteInvoked;
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
        public int countAnalysisRecords(long accountId) {
            return analysisCount;
        }

        @Override
        public void deleteTransactionTags(List<Long> transactionIds) { }

        @Override
        public void deleteTransactions(long accountId, long userId) {
            deletedTransactionIds.addAll(transactionIds);
            transactionIds.clear();
        }

        @Override
        public void deleteAdjustments(long accountId, long userId) {
            deletedAdjustmentIds.addAll(adjustmentIds);
            adjustmentIds.clear();
        }

        @Override
        public void deleteTransfers(long userId, List<Long> transferIds) {
            deletedTransferIds.addAll(transferIds);
            transfers.removeIf(t -> transferIds.contains(t.id()));
        }

        @Override
        public void deleteAccount(AccountEntity account) {
            deleteInvoked = true;
            accounts.remove(account.getId());
        }
    }

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
}
