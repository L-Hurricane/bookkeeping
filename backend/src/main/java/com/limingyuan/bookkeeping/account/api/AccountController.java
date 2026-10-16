package com.limingyuan.bookkeeping.account.api;

import com.limingyuan.bookkeeping.account.application.AccountService;
import com.limingyuan.bookkeeping.common.api.StrictStringDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.annotation.JsonDeserialize;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountService accounts;
    public AccountController(AccountService accounts) { this.accounts = accounts; }
    public record InitializeRequest(@NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String openingBalance) { }
    public record AccountList(List<AccountService.AccountView> items) { }
    @GetMapping
    public AccountList list() { return new AccountList(accounts.list()); }
    @PostMapping("/initialize")
    public ResponseEntity<AccountService.AccountView> initialize(@Valid @RequestBody InitializeRequest request) {
        return ResponseEntity.status(201).body(accounts.initialize(request.openingBalance()));
    }
}
