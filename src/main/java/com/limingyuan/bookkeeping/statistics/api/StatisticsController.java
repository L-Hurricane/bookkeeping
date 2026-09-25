package com.limingyuan.bookkeeping.statistics.api;

import com.limingyuan.bookkeeping.statistics.application.StatisticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/statistics")
public class StatisticsController {
    private final StatisticsService statistics;
    public StatisticsController(StatisticsService statistics) { this.statistics = statistics; }

    @GetMapping("/monthly")
    public StatisticsService.MonthlySummary monthly(@PathVariable long accountId, @RequestParam String month) {
        return statistics.monthly(accountId, month);
    }

    @GetMapping("/categories")
    public StatisticsService.CategoryStatistics categories(@PathVariable long accountId, @RequestParam String month,
            @RequestParam String type) {
        return statistics.categories(accountId, month, type);
    }

    @GetMapping("/tags")
    public StatisticsService.TagStatistics tags(@PathVariable long accountId, @RequestParam String month, @RequestParam String type) {
        return statistics.tags(accountId, month, type);
    }
}
