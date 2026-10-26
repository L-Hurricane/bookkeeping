package com.limingyuan.bookkeeping.statistics.api;

import com.limingyuan.bookkeeping.statistics.application.LedgerStatisticsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/statistics")
public class LedgerStatisticsController {
    private final LedgerStatisticsService statistics;

    public LedgerStatisticsController(LedgerStatisticsService statistics) { this.statistics = statistics; }

    @GetMapping
    public LedgerStatisticsService.Report report(@RequestParam String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(defaultValue = "ACTIVE") String accountScope,
            @RequestParam(required = false) String accountIds) {
        return statistics.report(preset, from, to, accountScope, accountIds);
    }

    @GetMapping("/transactions")
    public TransactionList transactions(@RequestParam String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(defaultValue = "ACTIVE") String accountScope,
            @RequestParam(required = false) String accountIds, @RequestParam String type,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Boolean uncategorized,
            @RequestParam(required = false) Long tagId, @RequestParam(required = false) String tagIds,
            @RequestParam(required = false) String tagMode, @RequestParam(required = false) Boolean untagged) {
        return new TransactionList(statistics.transactions(preset, from, to, accountScope, accountIds, type,
                categoryId, uncategorized, tagId, tagIds, tagMode, untagged));
    }

    public record TransactionList(List<LedgerStatisticsService.TransactionItem> items) { }
}
