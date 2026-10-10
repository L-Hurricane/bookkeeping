package com.limingyuan.bookkeeping.analysis.api;

import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisResult;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisService;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/monthly-analysis")
public class MonthlyAnalysisController {
    private final MonthlyAnalysisService analysis;

    public MonthlyAnalysisController(MonthlyAnalysisService analysis) { this.analysis = analysis; }

    @GetMapping
    public MonthlyAnalysisView get(@PathVariable long accountId, @RequestParam String month) {
        return analysis.get(accountId, month);
    }

    @PostMapping
    public MonthlyAnalysisResult generate(@PathVariable long accountId, @RequestBody GenerateMonthlyAnalysisRequest request) {
        return analysis.generate(accountId, request.month(), request.expectedFingerprint());
    }

    public record GenerateMonthlyAnalysisRequest(String month, String expectedFingerprint) { }
}
