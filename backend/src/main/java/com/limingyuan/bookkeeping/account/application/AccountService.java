package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.account.domain.AccountKind;
import com.limingyuan.bookkeeping.account.domain.AccountNames;
import com.limingyuan.bookkeeping.account.persistence.*;
import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
                              String currentBalance, String createdTime) { }
    @Transactional(readOnly = true)
    public List<AccountView> list() {
        return accounts.findAllByUserIdOrderByIdAsc(context.userId()).stream().map(this::view).toList();
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

    public record LockedAccount(long id, LocalDateTime createdTime) { }

    /** Account-row lock for a balance-changing write. Caller owns the MySQL transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedAccount lockForWrite(long accountId) {
        var account = locked(accountId);
        return new LockedAccount(account.getId(), account.getCreatedTime());
    }

    /** Lock, then allocate the next immutable account-scoped order. Create only. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Allocation allocateFirstSaveOrder(long accountId) {
        var account = locked(accountId);
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        long order = account.allocateNextFirstSaveOrder(now);
        accounts.saveAndFlush(account);
        return new Allocation(account.getId(), account.getCreatedTime(), order);
    }

    public record Allocation(long accountId, LocalDateTime createdTime, long firstSaveOrder) { }

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
                Money.format(balances.of(account.getId())), TIME.format(account.getCreatedTime().atZone(BEIJING)));
    }
}
