package com.limingyuan.bookkeeping.transfer.application;

import com.limingyuan.bookkeeping.account.application.AccountService;
import com.limingyuan.bookkeeping.account.persistence.AccountEntity;
import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.common.api.ApiErrors;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.idempotency.persistence.IdempotencyRecord;
import com.limingyuan.bookkeeping.idempotency.persistence.IdempotencyRepository;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.transfer.persistence.TransferEntity;
import com.limingyuan.bookkeeping.transfer.persistence.TransferRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransferService {
    static final String CREATE_SCOPE = "CREATE_TRANSFER";
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    private static final int ACCOUNT_MOVE_ATTEMPTS = 4;
    private final TransferRepository transfers;
    private final AccountService accounts;
    private final IdempotencyRepository idempotency;
    private final TrustedUserContext context;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public TransferService(TransferRepository transfers, AccountService accounts, IdempotencyRepository idempotency,
            TrustedUserContext context, Clock clock, PlatformTransactionManager transactionManager) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.idempotency = idempotency;
        this.context = context;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record TransferView(long id, long fromAccountId, long toAccountId, String amount, String fromBusinessTime,
            String toBusinessTime, long firstSaveOrder) { }
    public record Draft(long fromAccountId, long toAccountId, String amount, String fromBusinessTime, String toBusinessTime) { }
    public record CreateOutcome(int status, TransferView body, boolean replay) { }

    public CreateOutcome create(String operationId, Draft draft) {
        long userId = context.userId();
        String key = operationKey(operationId);
        var fields = fields(draft);
        byte[] hash = hash(fields);
        try {
            return transaction.execute(status -> createOnce(userId, key, fields, hash));
        } catch (DataIntegrityViolationException ex) {
            var winner = idempotency.findByUserIdAndScopeAndOperationId(userId, CREATE_SCOPE, key).orElse(null);
            if (winner == null) throw ex;
            return transaction.execute(status -> outcome(winner, hash));
        }
    }

    public TransferView get(long transferId) {
        return view(load(transferId));
    }

    public TransferView update(long transferId, Draft draft) {
        var fields = fields(draft);
        for (int attempt = 0; attempt < ACCOUNT_MOVE_ATTEMPTS; attempt++) {
            var updated = transaction.execute(status -> updateOnce(transferId, fields));
            if (updated != null) return updated;
        }
        throw stillMoving();
    }

    public void delete(long transferId) {
        for (int attempt = 0; attempt < ACCOUNT_MOVE_ATTEMPTS; attempt++) {
            var deleted = transaction.execute(status -> deleteOnce(transferId));
            if (Boolean.TRUE.equals(deleted)) return;
        }
        throw stillMoving();
    }

    private CreateOutcome createOnce(long userId, String key, Parsed fields, byte[] hash) {
        var locked = accounts.lockOwnedInIdOrder(List.of(fields.fromAccountId(), fields.toAccountId()));
        var existing = idempotency.findByUserIdAndScopeAndOperationId(userId, CREATE_SCOPE, key).orElse(null);
        if (existing != null) return outcome(existing, hash);
        for (var account : locked) accounts.rejectIfArchived(account);
        long order = accounts.assignSharedFirstSaveOrder(fields.fromAccountId(), fields.toAccountId());
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var saved = transfers.saveAndFlush(new TransferEntity(userId, fields.fromAccountId(), fields.toAccountId(),
                fields.amount(), fields.fromBusinessTime(), fields.toBusinessTime(), now, order));
        idempotency.saveAndFlush(new IdempotencyRecord(userId, CREATE_SCOPE, key, hash, "CREATED", saved.getId(), now));
        return new CreateOutcome(201, view(saved), false);
    }

    private TransferView updateOnce(long transferId, Parsed fields) {
        var seen = load(transferId);
        var ids = new TreeSet<Long>();
        ids.add(seen.getFromAccountId());
        ids.add(seen.getToAccountId());
        ids.add(fields.fromAccountId());
        ids.add(fields.toAccountId());
        var locked = accounts.lockOwnedInIdOrder(ids);
        var current = transfers.lockByIdAndUserId(transferId, context.userId()).orElseThrow(this::missing);
        if (!ids.contains(current.getFromAccountId()) || !ids.contains(current.getToAccountId())) {
            return null;
        }
        requireActive(locked, current.getFromAccountId(), current.getToAccountId(), fields.fromAccountId(), fields.toAccountId());
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        current.revise(fields.fromAccountId(), fields.toAccountId(), fields.amount(), fields.fromBusinessTime(),
                fields.toBusinessTime(), now);
        transfers.saveAndFlush(current);
        return view(current);
    }

    private Boolean deleteOnce(long transferId) {
        var seen = load(transferId);
        var ids = new TreeSet<>(List.of(seen.getFromAccountId(), seen.getToAccountId()));
        var locked = accounts.lockOwnedInIdOrder(ids);
        var current = transfers.lockByIdAndUserId(transferId, context.userId()).orElseThrow(this::missing);
        if (!ids.contains(current.getFromAccountId()) || !ids.contains(current.getToAccountId())) {
            return false;
        }
        requireActive(locked, current.getFromAccountId(), current.getToAccountId());
        transfers.delete(current);
        transfers.flush();
        return true;
    }

    private void requireActive(List<AccountEntity> locked, long... accountIds) {
        var needed = new HashSet<Long>();
        for (long accountId : accountIds) needed.add(accountId);
        for (var account : locked) {
            if (needed.contains(account.getId())) accounts.rejectIfArchived(account);
        }
    }

    private CreateOutcome outcome(IdempotencyRecord record, byte[] hash) {
        if (!record.sameRequest(hash)) {
            throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT", "同一个 Idempotency-Key 已用于不同的创建请求");
        }
        if (record.getResourceId() == null) {
            throw missing().header("Idempotent-Replay", "true");
        }
        var saved = transfers.findByIdAndUserId(record.getResourceId(), context.userId()).orElse(null);
        if (saved == null) throw missing().header("Idempotent-Replay", "true");
        return new CreateOutcome(200, view(saved), true);
    }

    private TransferEntity load(long transferId) {
        return transfers.findByIdAndUserId(transferId, context.userId()).orElseThrow(this::missing);
    }

    private TransferView view(TransferEntity entity) {
        return new TransferView(entity.getId(), entity.getFromAccountId(), entity.getToAccountId(),
                Money.format(entity.getAmount()), format(entity.getFromBusinessTime()), format(entity.getToBusinessTime()),
                entity.getFirstSaveOrder());
    }

    private static String format(LocalDateTime time) {
        return TIME.format(time.atZone(BEIJING));
    }

    private byte[] hash(Parsed fields) {
        return TransferRequestHash.sha256(fields.fromAccountId(), fields.toAccountId(), Money.format(fields.amount()),
                format(fields.fromBusinessTime()), format(fields.toBusinessTime()));
    }

    private Parsed fields(Draft draft) {
        if (draft.fromAccountId() <= 0 || draft.toAccountId() <= 0) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "请求参数不合法");
        }
        if (draft.fromAccountId() == draft.toAccountId()) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "转出账户和转入账户必须不同",
                    List.of(new ApiErrors.Detail("toAccountId", "MUST_DIFFER")));
        }
        var amount = Money.parse(draft.amount());
        if (amount.signum() <= 0) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "转账金额必须为正数",
                    List.of(new ApiErrors.Detail("amount", "MUST_BE_POSITIVE")));
        }
        return new Parsed(draft.fromAccountId(), draft.toAccountId(), amount, businessTime(draft.fromBusinessTime()),
                businessTime(draft.toBusinessTime()));
    }

    private static String operationKey(String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.trim().length() > 64) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "Idempotency-Key 不能为空且最长 64 个字符");
        }
        return raw.trim();
    }

    private LocalDateTime businessTime(String raw) {
        OffsetDateTime parsed;
        try { parsed = OffsetDateTime.parse(raw); }
        catch (DateTimeParseException | NullPointerException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "业务时间必须是带 +08:00 的秒级时间");
        }
        if (!parsed.getOffset().equals(ZoneOffset.ofHours(8)) || parsed.getNano() != 0) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "业务时间必须是带 +08:00 的秒级时间");
        }
        return parsed.toLocalDateTime();
    }

    private ApiFailure missing() {
        return new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private ApiFailure stillMoving() {
        return new ApiFailure(409, "VALIDATION_FAILED", "转账正在被其他操作修改，请重试");
    }

    private record Parsed(long fromAccountId, long toAccountId, BigDecimal amount, LocalDateTime fromBusinessTime,
            LocalDateTime toBusinessTime) { }
}
