package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.account.domain.AccountKind;
import com.limingyuan.bookkeeping.account.domain.AccountLifecycle;
import com.limingyuan.bookkeeping.account.domain.AccountNames;
import com.limingyuan.bookkeeping.account.persistence.*;
import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountService {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    private final AccountRepository accounts;
    private final CurrentBalance balances;
    private final TrustedUserContext context;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public AccountService(AccountRepository accounts, CurrentBalance balances, TrustedUserContext context, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts; this.balances = balances; this.context = context; this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }
    public record AccountView(long id, String name, String kind, String currency, String openingBalance,
                              String currentBalance, String createdTime, String lifecycle) { }
    @Transactional(readOnly = true)
    public List<AccountView> list() {
        return list(null);
    }

    /** Absent lifecycle means the normal active list. Archived accounts stay out of that list. */
    @Transactional(readOnly = true)
    public List<AccountView> list(String lifecycleRaw) {
        AccountLifecycle lifecycle = AccountLifecycle.ACTIVE;
        if (lifecycleRaw != null && !lifecycleRaw.isBlank()) {
            try {
                lifecycle = AccountLifecycle.valueOf(lifecycleRaw);
            } catch (IllegalArgumentException ex) {
                throw new ApiFailure(400, "VALIDATION_FAILED", "账户生命周期必须为 ACTIVE 或 ARCHIVED");
            }
        }
        return accounts.findAllByUserIdAndLifecycleOrderByIdAsc(context.userId(), lifecycle).stream().map(this::view).toList();
    }

    public AccountView archive(long accountId) {
        return changeLifecycle(accountId, AccountLifecycle.ARCHIVED);
    }

    public AccountView restore(long accountId) {
        return changeLifecycle(accountId, AccountLifecycle.ACTIVE);
    }

    private AccountView changeLifecycle(long accountId, AccountLifecycle target) {
        long userId = context.userId();
        return transaction.execute(status -> {
            var account = accounts.lockByIdAndUserId(accountId, userId)
                    .orElseThrow(() -> new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在"));
            if (account.getLifecycle() != target) {
                var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
                if (target == AccountLifecycle.ARCHIVED) account.archive(now);
                else account.restore(now);
                accounts.saveAndFlush(account);
            }
            return view(account);
        });
    }

    /** Legacy first-account path: creates 「微信」 NORMAL when the user has no accounts yet. */
    public AccountView initialize(String amount) {
        long userId = context.userId();
        var openingBalance = Money.parse(amount);
        try {
            return transaction.execute(status -> {
                if (accounts.existsByUserId(userId)) throw alreadyInitialized();
                Instant now = clock.instant();
                var account = new AccountEntity(userId, openingBalance,
                        LocalDateTime.ofInstant(now, BEIJING).truncatedTo(ChronoUnit.SECONDS),
                        LocalDateTime.ofInstant(now, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
                return view(accounts.saveAndFlush(account));
            });
        } catch (DataIntegrityViolationException ex) {
            // The losing concurrent request is handled only after its transaction has rolled back.
            if (accounts.existsByUserId(userId)) throw alreadyInitialized();
            throw ex;
        }
    }

    public AccountView create(String rawName, String rawKind, String amount) {
        long userId = context.userId();
        String name = name(rawName);
        AccountKind kind = kind(rawKind);
        var openingBalance = Money.parse(amount);
        try {
            return transaction.execute(status -> {
                if (accounts.existsByUserIdAndName(userId, name)) throw nameConflict();
                Instant now = clock.instant();
                var account = new AccountEntity(userId, name, kind, openingBalance,
                        LocalDateTime.ofInstant(now, BEIJING).truncatedTo(ChronoUnit.SECONDS),
                        LocalDateTime.ofInstant(now, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
                return view(accounts.saveAndFlush(account));
            });
        } catch (DataIntegrityViolationException ex) {
            if (accounts.existsByUserIdAndName(userId, name)) throw nameConflict();
            throw ex;
        }
    }

    public AccountView rename(long accountId, String rawName) {
        long userId = context.userId();
        String name = name(rawName);
        try {
            return transaction.execute(status -> {
                var account = accounts.lockByIdAndUserId(accountId, userId)
                        .orElseThrow(() -> new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在"));
                if (!account.getName().equals(name) && accounts.existsByUserIdAndNameAndIdNot(userId, name, accountId)) {
                    throw nameConflict();
                }
                var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
                account.rename(name, now);
                return view(accounts.saveAndFlush(account));
            });
        } catch (DataIntegrityViolationException ex) {
            if (accounts.existsByUserIdAndNameAndIdNot(userId, name, accountId)) throw nameConflict();
            throw ex;
        }
    }

    private String name(String rawName) {
        try {
            return AccountNames.required(rawName);
        } catch (IllegalArgumentException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "账户名称去除首尾空白后不能为空，且最长 64 个字符");
        }
    }

    private AccountKind kind(String rawKind) {
        try {
            return AccountKind.valueOf(rawKind);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "账户类型必须为 NORMAL 或 CREDIT");
        }
    }

    private ApiFailure alreadyInitialized() {
        return new ApiFailure(409, "ACCOUNT_ALREADY_INITIALIZED", "资金账户已经初始化");
    }

    private ApiFailure nameConflict() {
        return new ApiFailure(409, "ACCOUNT_NAME_CONFLICT", "账户名称已存在");
    }

    private void requireActive(AccountEntity account) {
        if (account.getLifecycle() != AccountLifecycle.ACTIVE) throw archived();
    }

    private ApiFailure archived() {
        return new ApiFailure(409, "ACCOUNT_ARCHIVED", "账户已归档，恢复后才能修改关联业务");
    }

    public record LockedAccount(long id, LocalDateTime createdTime) { }

    /** Account-row lock for a balance-changing write. Caller owns the MySQL transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedAccount lockForWrite(long accountId) {
        var account = locked(accountId);
        return new LockedAccount(account.getId(), account.getCreatedTime());
    }

    /**
     * Lock one or two accounts in ascending id order so cross-account edits cannot deadlock.
     * Both accounts must belong to the current user.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAccountsForWrite(long accountId, long otherAccountId) {
        long first = Math.min(accountId, otherAccountId);
        long second = Math.max(accountId, otherAccountId);
        var firstAccount = locked(first);
        var secondAccount = second == first ? firstAccount : locked(second);
        requireActive(firstAccount);
        if (second != first) requireActive(secondAccount);
    }

    /**
     * Lock the given accounts in ascending id order. Does not check lifecycle.
     * Caller owns the MySQL transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AccountEntity> lockOwnedInIdOrder(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "请求参数不合法");
        }
        var lockedAccounts = new ArrayList<AccountEntity>();
        for (long accountId : new TreeSet<>(accountIds)) {
            lockedAccounts.add(locked(accountId));
        }
        return lockedAccounts;
    }

    /** Reject an archived account that is already locked in this transaction. */
    public void rejectIfArchived(AccountEntity account) {
        requireActive(account);
    }

    /**
     * One shared first-save order for two already locked accounts.
     * Both counters move to {@code max(last) + 1}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long assignSharedFirstSaveOrder(long leftAccountId, long rightAccountId) {
        var left = locked(leftAccountId);
        var right = locked(rightAccountId);
        rejectIfArchived(left);
        rejectIfArchived(right);
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        long order = Math.max(left.getLastFirstSaveOrder(), right.getLastFirstSaveOrder()) + 1;
        left.raiseFirstSaveOrderTo(order, now);
        right.raiseFirstSaveOrderTo(order, now);
        accounts.saveAndFlush(left);
        accounts.saveAndFlush(right);
        return order;
    }

    /** Lock, then allocate the next immutable account-scoped order. Create only. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Allocation allocateFirstSaveOrder(long accountId) {
        var account = locked(accountId);
        requireActive(account);
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        long order = account.allocateNextFirstSaveOrder(now);
        accounts.saveAndFlush(account);
        return new Allocation(account.getId(), account.getCreatedTime(), order);
    }

    public record Allocation(long accountId, LocalDateTime createdTime, long firstSaveOrder) { }

    /** Lock a business write and reject an archived account. Reads use {@link #requireOwned(long)}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireWritable(long accountId) {
        requireActive(locked(accountId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireOwned(long accountId) {
        if (!accounts.existsByIdAndUserId(accountId, context.userId())) {
            throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
        }
    }

    private AccountEntity locked(long accountId) {
        return accounts.lockByIdAndUserId(accountId, context.userId())
                .orElseThrow(() -> new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在"));
    }

    private AccountView view(AccountEntity account) {
        String opening = Money.format(account.getOpeningBalance());
        return new AccountView(account.getId(), account.getName(), account.getKind().name(), account.getCurrency(), opening,
                Money.format(balances.of(account.getId())), TIME.format(account.getCreatedTime().atZone(BEIJING)),
                account.getLifecycle().name());
    }
}
