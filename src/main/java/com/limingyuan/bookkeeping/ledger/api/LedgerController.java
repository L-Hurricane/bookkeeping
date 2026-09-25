package com.limingyuan.bookkeeping.ledger.api;

import com.limingyuan.bookkeeping.ledger.application.LedgerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/ledger")
public class LedgerController {
    private final LedgerService ledger;
    public LedgerController(LedgerService ledger) { this.ledger = ledger; }

    @GetMapping
    public LedgerService.Ledger query(@PathVariable long accountId, @RequestParam String month) {
        return ledger.query(accountId, month);
    }
}
