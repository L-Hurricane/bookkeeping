package com.limingyuan.bookkeeping.transfer.api;

import com.limingyuan.bookkeeping.common.api.StrictStringDeserializer;
import com.limingyuan.bookkeeping.transfer.application.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.annotation.JsonDeserialize;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {
    private final TransferService transfers;
    public TransferController(TransferService transfers) { this.transfers = transfers; }

    public record TransferRequest(
            @NotNull Long fromAccountId,
            @NotNull Long toAccountId,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String amount,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String fromBusinessTime,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String toBusinessTime) { }

    @PostMapping
    public ResponseEntity<TransferService.TransferView> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        var outcome = transfers.create(idempotencyKey, draft(request));
        var response = ResponseEntity.status(outcome.status()).body(outcome.body());
        return outcome.replay()
                ? ResponseEntity.status(outcome.status()).header("Idempotent-Replay", "true").body(outcome.body())
                : response;
    }

    @GetMapping("/{transferId}")
    public TransferService.TransferView get(@PathVariable long transferId) {
        return transfers.get(transferId);
    }

    @PutMapping("/{transferId}")
    public TransferService.TransferView update(@PathVariable long transferId, @Valid @RequestBody TransferRequest request) {
        return transfers.update(transferId, draft(request));
    }

    @DeleteMapping("/{transferId}")
    public ResponseEntity<Void> delete(@PathVariable long transferId) {
        transfers.delete(transferId);
        return ResponseEntity.noContent().build();
    }

    private static TransferService.Draft draft(TransferRequest request) {
        return new TransferService.Draft(request.fromAccountId(), request.toAccountId(), request.amount(),
                request.fromBusinessTime(), request.toBusinessTime());
    }
}
