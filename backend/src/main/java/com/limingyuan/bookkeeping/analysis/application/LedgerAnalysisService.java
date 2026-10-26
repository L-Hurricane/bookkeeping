package com.limingyuan.bookkeeping.analysis.application;

import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Active whole-ledger month or year analysis. Reading a saved report does not call the model. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class LedgerAnalysisService {
    private final LedgerAnalysisFactsService facts;
    private final AnalysisGenerationStore store;
    private final MonthlyAnalysisGenerator generator;
    private final AnalysisGenerationAdmission admission;
    private final TrustedUserContext users;
    private final Clock clock;

    public LedgerAnalysisService(LedgerAnalysisFactsService facts, AnalysisGenerationStore store,
            MonthlyAnalysisGenerator generator, AnalysisGenerationAdmission admission, TrustedUserContext users,
            Clock clock) {
        this.facts = facts;
        this.store = store;
        this.generator = generator;
        this.admission = admission;
        this.users = users;
        this.clock = clock;
    }

    public LedgerAnalysisView get(String periodKind, String period) {
        var context = facts.build(periodKind, period);
        long userId = users.userId();
        var snapshot = store.findSuccessfulLedger(userId, context.periodKind(), context.period(), context.fingerprint())
                .or(() -> store.findLatestLedger(userId, context.periodKind(), context.period()));
        return assemble(context, userId, snapshot.orElse(null));
    }

    public LedgerAnalysisResult generate(String periodKind, String period, String expectedFingerprint) {
        var context = facts.build(periodKind, period);
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "expectedFingerprint 不能为空");
        }
        if (!context.fingerprint().equals(expectedFingerprint.strip())) {
            throw new ApiFailure(409, "REFRESH_REQUIRED", "期间数据已变化，请刷新后再分析");
        }
        if (context.emptyPeriod()) {
            throw new ApiFailure(409, "EMPTY_PERIOD", "当前期间没有可分析收支记录");
        }
        long userId = users.userId();
        var existing = store.findSuccessfulLedger(userId, context.periodKind(), context.period(), context.fingerprint());
        if (existing.isPresent()) return new LedgerAnalysisResult("REUSED", assemble(context, userId, existing.get()));
        admission.admit();
        return switch (store.acquireLedger(userId, context.periodKind(), context.period(), context.fingerprint())) {
            case LedgerPermitAcquisition.Reused reused ->
                    new LedgerAnalysisResult("REUSED", assemble(context, userId, reused.snapshot()));
            case LedgerPermitAcquisition.Busy ignored -> throw new ApiFailure(409, "IN_PROGRESS", "这个期间的分析正在生成");
            case LedgerPermitAcquisition.QuotaExceeded ignored ->
                    throw new ApiFailure(409, "QUOTA_EXCEEDED", "今日成功分析次数已用完");
            case LedgerPermitAcquisition.Granted granted -> finish(context, granted.permit());
        };
    }

    private LedgerAnalysisResult finish(LedgerAnalysisFacts context, LedgerGenerationPermit permit) {
        GeneratedMonthlyAnalysis generated;
        try {
            var generation = generator.generateJson(context.json(), LedgerAnalysisPrompt.text());
            var interpretation = ModelOutputValidator.parse(generation.content());
            ModelOutputValidator.requireConsistentAmounts(interpretation, context.incomeNet(), context.expenseNet(),
                    context.change());
            var report = new MonthlyAnalysisReport(context.overview(), context.structure(), context.comparison(),
                    interpretation.pointsToWatch());
            generated = new GeneratedMonthlyAnalysis(report, generation);
        } catch (AnalysisGenerationFailed ex) {
            store.completeLedgerFailure(permit);
            throw providerFailure(ex);
        } catch (RuntimeException ex) {
            store.completeLedgerFailure(permit);
            if (ex instanceof InvalidModelOutput) throw new ApiFailure(502, "GENERATION_FAILED", "分析生成失败，请稍后重试");
            throw ex;
        }
        SuccessSettlement settlement;
        try {
            settlement = store.completeLedgerSuccess(permit, generated.report(), provenance(generated.generation()));
        } catch (RuntimeException ex) {
            store.completeLedgerFailure(permit);
            throw ex;
        }
        if (!(settlement instanceof SuccessSettlement.Applied applied)) {
            throw new ApiFailure(502, "GENERATION_FAILED", "分析生成失败，请稍后重试");
        }
        return new LedgerAnalysisResult("SUCCESS", assemble(context, permit.userId(), applied.snapshot()));
    }

    private LedgerAnalysisView assemble(LedgerAnalysisFacts context, long userId, AnalysisSnapshot snapshot) {
        var coordination = store.findLedgerCoordination(userId, context.periodKind(), context.period(), context.fingerprint())
                .orElse(null);
        boolean active = coordination != null && coordination.state() == GenerationState.IN_PROGRESS
                && coordination.leaseExpiresAt().isAfter(clock.instant());
        String viewState;
        if (active) viewState = "GENERATION_IN_PROGRESS";
        else if (snapshot != null) viewState = snapshot.contextFingerprint().equals(context.fingerprint()) ? "CURRENT" : "STALE";
        else if (context.emptyPeriod()) viewState = "EMPTY_MONTH";
        else if (coordination != null && coordination.state() == GenerationState.FAILED) viewState = "GENERATION_FAILED";
        else viewState = "NO_ANALYSIS";
        return new LedgerAnalysisView(context.periodKind(), context.period(), context.fingerprint(), context.emptyPeriod(),
                viewState, context.opening(), context.closing(), context.change(), context.incomeNet(), context.expenseNet(),
                context.incomeCount(), context.expenseCount(), context.priorPeriod(), context.priorChange(),
                context.priorIncomeNet(), context.priorExpenseNet(), snapshotView(snapshot, context.fingerprint()),
                coordination == null ? null : new LedgerAnalysisView.Generation(coordination.state().name(), active));
    }

    private static LedgerAnalysisView.Snapshot snapshotView(AnalysisSnapshot snapshot, String fingerprint) {
        if (snapshot == null) return null;
        var report = snapshot.report();
        var provenance = snapshot.provenance();
        return new LedgerAnalysisView.Snapshot(snapshot.id(), snapshot.contextFingerprint(),
                !snapshot.contextFingerprint().equals(fingerprint), report.monthlyOverview(), report.expenseStructure(),
                report.surplusReasons(), report.pointsToWatch(), provenance.provider(), provenance.model(),
                provenance.promptVersion(), snapshot.generatedAt());
    }

    private static AnalysisProvenance provenance(MonthlyAnalysisGeneration generation) {
        return new AnalysisProvenance(generation.provider(), generation.model(), LedgerAnalysisPrompt.VERSION,
                generation.promptTokens(), generation.completionTokens(), generation.totalTokens(),
                generation.latencyMillis(), generation.providerRequestId());
    }

    private static ApiFailure providerFailure(AnalysisGenerationFailed failure) {
        int status = failure.kind() == AnalysisGenerationFailed.Kind.TIMEOUT ? 504 : 502;
        return new ApiFailure(status, "GENERATION_FAILED", "分析生成失败，请稍后重试");
    }

    public record LedgerAnalysisResult(String outcome, LedgerAnalysisView analysis) { }
}
