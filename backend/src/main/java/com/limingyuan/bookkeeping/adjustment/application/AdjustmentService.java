package com.limingyuan.bookkeeping.adjustment.application;

import com.limingyuan.bookkeeping.account.application.AccountService;
import com.limingyuan.bookkeeping.account.application.CurrentBalance;
import com.limingyuan.bookkeeping.adjustment.persistence.BalanceAdjustmentEntity;
import com.limingyuan.bookkeeping.adjustment.persistence.BalanceAdjustmentRepository;
import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.idempotency.persistence.IdempotencyRecord;
import com.limingyuan.bookkeeping.idempotency.persistence.IdempotencyRepository;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdjustmentService {
    static final String CREATE_SCOPE = "CREATE_BALANCE_ADJUSTMENT";
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    private final BalanceAdjustmentRepository adjustments;
    private final AccountService accounts;
    private final CurrentBalance balances;
    private final IdempotencyRepository idempotency;
    private final TrustedUserContext context;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AdjustmentService(BalanceAdjustmentRepository adjustments, AccountService accounts, CurrentBalance balances,
            IdempotencyRepository idempotency, TrustedUserContext context, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.adjustments = adjustments;
        this.accounts = accounts;
        this.balances = balances;
        this.idempotency = idempotency;
        this.context = context;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record AdjustmentView(long id, String adjustmentAmount, String businessTime, String remark) { }
    public record CreateBody(boolean created, String reason, String currentBalanceBefore, String targetBalance,
            AdjustmentView adjustment, String currentBalanceAfter, String currentBalance) { }
    public record CreateOutcome(int status, CreateBody body, boolean replay) { }
    public record Draft(String targetBalance, String businessTime, String remark) { }

    public CreateOutcome create(long accountId, String operationId, Draft draft) {
        long userId = context.userId();
        String key = operationKey(operationId);
        var target = Money.parse(draft.targetBalance());
        var time = parseBusinessTime(draft.businessTime());
        String remark = text(draft.remark());
        byte[] hash = AdjustmentRequestHash.sha256(accountId, Money.format(target), TIME.format(time.atZone(BEIJING)), remark);
        try {
            return transaction.execute(status -> createOnce(userId, accountId, key, target, time, remark, hash));
        } catch (DataIntegrityViolationException ex) {
            var winner = idempotency.findByUserIdAndScopeAndOperationId(userId, CREATE_SCOPE, key).orElse(null);
            if (winner == null) throw ex;
            return transaction.execute(status -> outcome(accountId, winner, hash, target));
        }
    }

    private CreateOutcome createOnce(long userId, long accountId, String key, BigDecimal target, LocalDateTime time,
            String remark, byte[] hash) {
        accounts.lockForWrite(accountId);
        var existing = idempotency.findByUserIdAndScopeAndOperationId(userId, CREATE_SCOPE, key).orElse(null);
        if (existing != null) return outcome(accountId, existing, hash, target);
        accounts.requireWritable(accountId);
        var before = balances.of(accountId);
        var delta = target.subtract(before);
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (delta.signum() == 0) {
            idempotency.saveAndFlush(new IdempotencyRecord(userId, CREATE_SCOPE, key, hash, "NO_CHANGE", null, now));
            return noChange(target, false);
        }
        var allocation = accounts.allocateFirstSaveOrder(accountId);
        var saved = adjustments.saveAndFlush(new BalanceAdjustmentEntity(userId, accountId, delta, time, now,
                allocation.firstSaveOrder(), remark.isEmpty() ? null : remark));
        idempotency.saveAndFlush(new IdempotencyRecord(userId, CREATE_SCOPE, key, hash, "CREATED", saved.getId(), now));
        return new CreateOutcome(201, createdBody(target, before, saved), false);
    }

    private CreateOutcome outcome(long accountId, IdempotencyRecord record, byte[] hash, BigDecimal target) {
        if (!record.sameRequest(hash)) {
            throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT", "同一个 Idempotency-Key 已用于不同的创建请求");
        }
        if ("NO_CHANGE".equals(record.getResultCode())) return noChange(target, true);
        var saved = record.getResourceId() == null ? null
                : adjustments.findByIdAndAccountIdAndUserId(record.getResourceId(), accountId, context.userId()).orElse(null);
        if (saved == null) {
            throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在").header("Idempotent-Replay", "true");
        }
        var before = target.subtract(saved.getAdjustmentAmount());
        return new CreateOutcome(200, createdBody(target, before, saved), true);
    }

    public AdjustmentView get(long accountId, long adjustmentId) {
        return view(load(accountId, adjustmentId));
    }

    public void delete(long accountId, long adjustmentId) {
        transaction.executeWithoutResult(status -> {
            accounts.requireWritable(accountId);
            var current = load(accountId, adjustmentId);
            adjustments.delete(current);
            adjustments.flush();
        });
    }

    private BalanceAdjustmentEntity load(long accountId, long adjustmentId) {
        return adjustments.findByIdAndAccountIdAndUserId(adjustmentId, accountId, context.userId())
                .orElseThrow(() -> new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在"));
    }

    private CreateOutcome noChange(BigDecimal target, boolean replay) {
        String amount = Money.format(target);
        return new CreateOutcome(200, new CreateBody(false, "NO_ADJUSTMENT_NEEDED", null, amount, null, null, amount), replay);
    }

    private CreateBody createdBody(BigDecimal target, BigDecimal before, BalanceAdjustmentEntity saved) {
        return new CreateBody(true, null, Money.format(before), Money.format(target), view(saved), Money.format(target), null);
    }

    private AdjustmentView view(BalanceAdjustmentEntity saved) {
        return new AdjustmentView(saved.getId(), Money.format(saved.getAdjustmentAmount()),
                TIME.format(saved.getBusinessTime().atZone(BEIJING)), saved.getRemark());
    }

    private static String operationKey(String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.trim().length() > 64) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "Idempotency-Key 不能为空且最长 64 个字符");
        }
        return raw.trim();
    }

    private static String text(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private static LocalDateTime parseBusinessTime(String raw) {
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
}
