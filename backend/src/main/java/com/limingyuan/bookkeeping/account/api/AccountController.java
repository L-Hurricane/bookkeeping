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
    public record CreateRequest(
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String name,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String kind,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String openingBalance) { }
    public record RenameRequest(@NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String name) { }
    public record AccountList(List<AccountService.AccountView> items) { }
    @GetMapping
    public AccountList list() { return new AccountList(accounts.list()); }
    @PostMapping("/initialize")
    public ResponseEntity<AccountService.AccountView> initialize(@Valid @RequestBody InitializeRequest request) {
        return ResponseEntity.status(201).body(accounts.initialize(request.openingBalance()));
    }
    @PostMapping
    public ResponseEntity<AccountService.AccountView> create(@Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(201).body(accounts.create(request.name(), request.kind(), request.openingBalance()));
    }
    @PatchMapping("/{accountId}")
    public AccountService.AccountView rename(@PathVariable long accountId, @Valid @RequestBody RenameRequest request) {
        return accounts.rename(accountId, request.name());
    }

    @PutMapping("/{accountId}/name")
    public AccountService.AccountView renameByPut(@PathVariable long accountId, @Valid @RequestBody RenameRequest request) {
        return accounts.rename(accountId, request.name());
    }
}
