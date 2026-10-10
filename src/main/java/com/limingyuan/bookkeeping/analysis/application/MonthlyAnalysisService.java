package com.limingyuan.bookkeeping.analysis.application;

import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisView.Category;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisView.Facts;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisView.Generation;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisView.KeyExpense;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisView.Snapshot;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisView.Uncategorized;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.CategoryFact;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.KeyExpenseFact;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Get and generate monthly analysis. The model call sits between the store's own short transactions. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MonthlyAnalysisService {
    private final MonthlyAnalysisFactsService facts;
    private final AnalysisGenerationStore store;
    private final MonthlyAnalysisReportGeneration reports;
    private final TrustedUserContext users;
    private final Clock clock;

    public MonthlyAnalysisService(MonthlyAnalysisFactsService facts, AnalysisGenerationStore store,
            MonthlyAnalysisReportGeneration reports, TrustedUserContext users, Clock clock) {
        this.facts = facts;
        this.store = store;
        this.reports = reports;
        this.users = users;
        this.clock = clock;
    }

    public MonthlyAnalysisView get(long accountId, String month) {
        var context = facts.build(accountId, month);
        return assemble(context, users.userId(), store.findLatest(users.userId(), context.yearMonth()).orElse(null));
    }

    public MonthlyAnalysisResult generate(long accountId, String month, String expectedFingerprint) {
        var context = facts.build(accountId, month);
        String fingerprint = context.fingerprint();
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "expectedFingerprint 不能为空");
        }
        if (!fingerprint.equals(expectedFingerprint.strip())) {
            throw new ApiFailure(409, "REFRESH_REQUIRED", "月份数据已变化，请刷新后再分析");
        }
        if (context.incomeTransactionCount() == 0 && context.expenseTransactionCount() == 0) {
            throw new ApiFailure(409, "EMPTY_MONTH", "当前月份没有可分析收支记录");
        }
        long userId = users.userId();
        var existing = store.findSuccessful(userId, context.yearMonth(), fingerprint);
        if (existing.isPresent()) return new MonthlyAnalysisResult("REUSED", assemble(context, userId, existing.get()));
        return switch (store.acquire(userId, context.yearMonth(), fingerprint)) {
            case PermitAcquisition.Reused reused -> new MonthlyAnalysisResult("REUSED", assemble(context, userId, reused.snapshot()));
            case PermitAcquisition.Busy ignored -> throw new ApiFailure(409, "IN_PROGRESS", "这个月的分析正在生成");
            case PermitAcquisition.QuotaExceeded ignored -> throw new ApiFailure(409, "QUOTA_EXCEEDED", "今日成功分析次数已用完");
            case PermitAcquisition.Granted granted -> finish(context, granted.permit());
        };
    }

    private MonthlyAnalysisResult finish(MonthlyAnalysisContext context, GenerationPermit permit) {
        GeneratedMonthlyAnalysis generated;
        try {
            generated = reports.generate(context);
        } catch (AnalysisGenerationFailed ex) {
            store.completeFailure(permit);
            throw providerFailure(ex);
        } catch (RuntimeException ex) {
            store.completeFailure(permit);
            if (ex instanceof InvalidModelOutput) throw new ApiFailure(502, "GENERATION_FAILED", "分析生成失败，请稍后重试");
            throw ex;
        }
        SuccessSettlement settlement;
        try {
            settlement = store.completeSuccess(permit, generated.report(), provenance(generated.generation()));
        } catch (RuntimeException ex) {
            store.completeFailure(permit);
            throw ex;
        }
        if (!(settlement instanceof SuccessSettlement.Applied applied)) {
            throw new ApiFailure(502, "GENERATION_FAILED", "分析生成失败，请稍后重试");
        }
        return new MonthlyAnalysisResult("SUCCESS", assemble(context, permit.userId(), applied.snapshot()));
    }

    private MonthlyAnalysisView assemble(MonthlyAnalysisContext context, long userId, AnalysisSnapshot snapshot) {
        String fingerprint = context.fingerprint();
        var coordination = store.findCoordination(userId, context.yearMonth(), fingerprint).orElse(null);
        boolean empty = context.incomeTransactionCount() == 0 && context.expenseTransactionCount() == 0;
        boolean active = coordination != null && coordination.state() == GenerationState.IN_PROGRESS
                && coordination.leaseExpiresAt().isAfter(clock.instant());
        String viewState;
        if (active) viewState = "GENERATION_IN_PROGRESS";
        else if (snapshot != null) viewState = snapshot.contextFingerprint().equals(fingerprint) ? "CURRENT" : "STALE";
        else if (empty) viewState = "EMPTY_MONTH";
        else if (coordination != null && coordination.state() == GenerationState.FAILED) viewState = "GENERATION_FAILED";
        else viewState = "NO_ANALYSIS";
        return new MonthlyAnalysisView(context.yearMonth(), fingerprint, empty, viewState, facts(context),
                snapshot(snapshot, fingerprint), generation(coordination, active));
    }

    private static Facts facts(MonthlyAnalysisContext context) {
        return new Facts(context.timeZone(), context.currency(), context.netIncome(), context.netExpense(), context.surplus(),
                context.incomeTransactionCount(), context.expenseTransactionCount(), categories(context.incomeCategories()),
                categories(context.expenseCategories()), uncategorized(context.uncategorizedIncome()),
                uncategorized(context.uncategorizedExpense()), keyExpenses(context.keyExpenses()));
    }

    private static List<Category> categories(List<CategoryFact> facts) {
        var views = new ArrayList<Category>();
        for (var fact : facts) views.add(new Category(fact.name(), fact.transactionCount(), fact.netAmount(), categories(fact.children())));
        return List.copyOf(views);
    }

    private static Uncategorized uncategorized(MonthlyAnalysisContext.UncategorizedFact fact) {
        return new Uncategorized(fact.transactionCount(), fact.netAmount());
    }

    private static List<KeyExpense> keyExpenses(List<KeyExpenseFact> facts) {
        var views = new ArrayList<KeyExpense>();
        for (var fact : facts) {
            views.add(new KeyExpense(fact.transactionType(), fact.categoryPath(), fact.transactionName(),
                    fact.signedAmount(), fact.businessTime()));
        }
        return List.copyOf(views);
    }

    private static Snapshot snapshot(AnalysisSnapshot snapshot, String fingerprint) {
        if (snapshot == null) return null;
        var report = snapshot.report();
        var provenance = snapshot.provenance();
        return new Snapshot(snapshot.id(), snapshot.contextFingerprint(), !snapshot.contextFingerprint().equals(fingerprint),
                report.monthlyOverview(), report.expenseStructure(), report.surplusReasons(), report.pointsToWatch(),
                provenance.provider(), provenance.model(), provenance.promptVersion(), snapshot.generatedAt());
    }

    private static Generation generation(GenerationStatus status, boolean active) {
        return status == null ? null : new Generation(status.state().name(), active);
    }

    private static AnalysisProvenance provenance(MonthlyAnalysisGeneration generation) {
        return new AnalysisProvenance(generation.provider(), generation.model(), MonthlyAnalysisPrompt.VERSION,
                generation.promptTokens(), generation.completionTokens(), generation.totalTokens(),
                generation.latencyMillis(), generation.providerRequestId());
    }

    private static ApiFailure providerFailure(AnalysisGenerationFailed failure) {
        int status = failure.kind() == AnalysisGenerationFailed.Kind.TIMEOUT ? 504 : 502;
        return new ApiFailure(status, "GENERATION_FAILED", "分析生成失败，请稍后重试");
    }
}
