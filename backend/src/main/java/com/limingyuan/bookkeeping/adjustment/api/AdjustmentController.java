package com.limingyuan.bookkeeping.adjustment.api;

import com.limingyuan.bookkeeping.adjustment.application.AdjustmentService;
import com.limingyuan.bookkeeping.common.api.StrictStringDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.annotation.JsonDeserialize;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/balance-adjustments")
public class AdjustmentController {
    private final AdjustmentService adjustments;
    public AdjustmentController(AdjustmentService adjustments) { this.adjustments = adjustments; }

    public record CreateRequest(
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String targetBalance,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String businessTime,
            @JsonDeserialize(using = StrictStringDeserializer.class) String remark) { }

    @PostMapping
    public ResponseEntity<AdjustmentService.CreateBody> create(@PathVariable long accountId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody CreateRequest request) {
        var outcome = adjustments.create(accountId, idempotencyKey,
                new AdjustmentService.Draft(request.targetBalance(), request.businessTime(), request.remark()));
        if (!outcome.replay()) return ResponseEntity.status(outcome.status()).body(outcome.body());
        return ResponseEntity.status(outcome.status()).header("Idempotent-Replay", "true").body(outcome.body());
    }

    @GetMapping("/{adjustmentId}")
    public AdjustmentService.AdjustmentView get(@PathVariable long accountId, @PathVariable long adjustmentId) {
        return adjustments.get(accountId, adjustmentId);
    }

    @DeleteMapping("/{adjustmentId}")
    public ResponseEntity<Void> delete(@PathVariable long accountId, @PathVariable long adjustmentId) {
        adjustments.delete(accountId, adjustmentId);
        return ResponseEntity.noContent().build();
    }
}
