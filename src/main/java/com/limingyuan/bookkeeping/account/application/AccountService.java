package com.limingyuan.bookkeeping.account.application;

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
    private final TrustedUserContext context;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public AccountService(AccountRepository accounts, TrustedUserContext context, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts; this.context = context; this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }
    public record AccountView(long id, String name, String currency, String openingBalance,
                              String currentBalance, String createdTime) { }
    @Transactional(readOnly = true)
    public List<AccountView> list() {
        return accounts.findByUserId(context.userId()).map(this::view).stream().toList();
    }
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
    private ApiFailure alreadyInitialized() {
        return new ApiFailure(409, "ACCOUNT_ALREADY_INITIALIZED", "资金账户已经初始化");
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
        String balance = Money.format(account.getOpeningBalance());
        // Phase 2 has no transaction or adjustment facts to aggregate.
        return new AccountView(account.getId(), account.getName(), account.getCurrency(), balance, balance,
                TIME.format(account.getCreatedTime().atZone(BEIJING)));
    }
}
