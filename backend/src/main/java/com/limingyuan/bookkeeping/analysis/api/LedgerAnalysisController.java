package com.limingyuan.bookkeeping.analysis.api;

import com.limingyuan.bookkeeping.analysis.application.LedgerAnalysisService;
import com.limingyuan.bookkeeping.analysis.application.LedgerAnalysisService.LedgerAnalysisResult;
import com.limingyuan.bookkeeping.analysis.application.LedgerAnalysisView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Whole-ledger month and year analysis. The client does not choose accounts. */
@RestController
@RequestMapping("/api/v1/ledger-analysis")
public class LedgerAnalysisController {
    private final LedgerAnalysisService analysis;

    public LedgerAnalysisController(LedgerAnalysisService analysis) { this.analysis = analysis; }

    @GetMapping
    public LedgerAnalysisView get(@RequestParam String periodKind, @RequestParam String period) {
        return analysis.get(periodKind, period);
    }

    @PostMapping
    public LedgerAnalysisResult generate(@RequestBody GenerateLedgerAnalysisRequest request) {
        return analysis.generate(request.periodKind(), request.period(), request.expectedFingerprint());
    }

    public record GenerateLedgerAnalysisRequest(String periodKind, String period, String expectedFingerprint) { }
}
