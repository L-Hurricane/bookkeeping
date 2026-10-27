package com.limingyuan.bookkeeping.ledger.api;

import com.limingyuan.bookkeeping.ledger.application.LedgerFeedService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerFeedController {
    private final LedgerFeedService feed;

    public LedgerFeedController(LedgerFeedService feed) { this.feed = feed; }

    @GetMapping
    public LedgerFeedService.Page query(@RequestParam(required = false) String month,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String name, @RequestParam(defaultValue = "ACTIVE") String accountScope,
            @RequestParam(required = false) String accountIds, @RequestParam(required = false) String cursor) {
        return feed.query(month, from, to, name, accountScope, accountIds, cursor);
    }
}
