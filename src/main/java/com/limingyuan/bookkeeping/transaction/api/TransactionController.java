package com.limingyuan.bookkeeping.transaction.api;

import com.limingyuan.bookkeeping.common.api.StrictStringDeserializer;
import com.limingyuan.bookkeeping.transaction.application.TransactionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.annotation.JsonDeserialize;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/transactions")
public class TransactionController {
    private final TransactionService transactions;
    public TransactionController(TransactionService transactions) { this.transactions = transactions; }

    public record CreateRequest(
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String type,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String amount,
            @JsonDeserialize(using = StrictStringDeserializer.class) String name,
            Long categoryId,
            List<Long> tagIds,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String businessTime,
            @JsonDeserialize(using = StrictStringDeserializer.class) String remark) { }

    public record EditRequest(
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String amount,
            @JsonDeserialize(using = StrictStringDeserializer.class) String name,
            Long categoryId,
            List<Long> tagIds,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String businessTime,
            @JsonDeserialize(using = StrictStringDeserializer.class) String remark) { }

    @PostMapping
    public ResponseEntity<TransactionService.TransactionView> create(@PathVariable long accountId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody CreateRequest request) {
        var outcome = transactions.create(accountId, idempotencyKey, request.type(), draft(request.amount(),
                request.name(), request.categoryId(), request.tagIds(), request.businessTime(), request.remark()));
        var response = ResponseEntity.status(outcome.status()).body(outcome.body());
        return outcome.replay() ? ResponseEntity.status(outcome.status()).header("Idempotent-Replay", "true").body(outcome.body()) : response;
    }

    @GetMapping("/{transactionId}")
    public TransactionService.TransactionView get(@PathVariable long accountId, @PathVariable long transactionId) {
        return transactions.get(accountId, transactionId);
    }

    @PutMapping("/{transactionId}")
    public TransactionService.TransactionView edit(@PathVariable long accountId, @PathVariable long transactionId,
            @Valid @RequestBody EditRequest request) {
        return transactions.edit(accountId, transactionId, draft(request.amount(), request.name(), request.categoryId(),
                request.tagIds(), request.businessTime(), request.remark()));
    }

    @DeleteMapping("/{transactionId}")
    public ResponseEntity<Void> delete(@PathVariable long accountId, @PathVariable long transactionId) {
        transactions.delete(accountId, transactionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public TransactionService.TransactionList list(@PathVariable long accountId, @RequestParam String month,
            @RequestParam(required = false) String type, @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Boolean uncategorizedOnly, @RequestParam(required = false) Long tagId) {
        return transactions.list(accountId, month, type, categoryId, uncategorizedOnly, tagId);
    }

    private static TransactionService.Draft draft(String amount, String name, Long categoryId, List<Long> tagIds,
            String businessTime, String remark) {
        return new TransactionService.Draft(amount, name, categoryId, tagIds, businessTime, remark);
    }
}
