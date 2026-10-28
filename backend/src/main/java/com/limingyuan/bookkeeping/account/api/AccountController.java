package com.limingyuan.bookkeeping.account.api;

import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionService;
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
    private final AccountPermanentDeletionService permanentDeletion;

    public AccountController(AccountService accounts, AccountPermanentDeletionService permanentDeletion) {
        this.accounts = accounts;
        this.permanentDeletion = permanentDeletion;
    }
    public record InitializeRequest(@NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String openingBalance) { }
    public record CreateRequest(
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String name,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String kind,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String openingBalance) { }
    public record RenameRequest(@NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String name) { }
    public record PermanentDeletionRequest(
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String previewToken) { }
    public record AccountList(List<AccountService.AccountView> items) { }
    @GetMapping
    public AccountList list(@RequestParam(required = false) String lifecycle) {
        return new AccountList(accounts.list(lifecycle));
    }
    @PostMapping("/{accountId}/archive")
    public AccountService.AccountView archive(@PathVariable long accountId) { return accounts.archive(accountId); }
    @PostMapping("/{accountId}/restore")
    public AccountService.AccountView restore(@PathVariable long accountId) { return accounts.restore(accountId); }

    @PostMapping("/{accountId}/permanent-deletion/preview")
    public AccountPermanentDeletionService.PreviewView permanentDeletionPreview(@PathVariable long accountId) {
        return permanentDeletion.preview(accountId);
    }

    @PostMapping("/{accountId}/permanent-deletion")
    public ResponseEntity<Void> permanentDeletion(@PathVariable long accountId,
            @Valid @RequestBody PermanentDeletionRequest request) {
        permanentDeletion.execute(accountId, request.previewToken());
        return ResponseEntity.noContent().build();
    }
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
