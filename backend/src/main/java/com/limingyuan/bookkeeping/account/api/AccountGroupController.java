package com.limingyuan.bookkeeping.account.api;

import com.limingyuan.bookkeeping.account.application.AccountGroupService;
import com.limingyuan.bookkeeping.common.api.StrictStringDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.annotation.JsonDeserialize;

@RestController
@RequestMapping("/api/v1/account-groups")
public class AccountGroupController {
    private final AccountGroupService groups;

    public AccountGroupController(AccountGroupService groups) { this.groups = groups; }

    public record NameRequest(@NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String name) { }
    public record OrderRequest(@NotNull List<Long> ids) { }

    @GetMapping
    public AccountGroupService.GroupList list() { return groups.list(); }

    @PostMapping
    public ResponseEntity<AccountGroupService.GroupView> create(@Valid @RequestBody NameRequest request) {
        return ResponseEntity.status(201).body(groups.create(request.name()));
    }

    @PatchMapping("/{groupId}")
    public AccountGroupService.GroupView rename(@PathVariable long groupId, @Valid @RequestBody NameRequest request) {
        return groups.rename(groupId, request.name());
    }

    @DeleteMapping("/{groupId}")
    public ResponseEntity<Void> delete(@PathVariable long groupId) {
        groups.delete(groupId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/order")
    public AccountGroupService.GroupList reorder(@Valid @RequestBody OrderRequest request) {
        return groups.reorder(request.ids());
    }
}
