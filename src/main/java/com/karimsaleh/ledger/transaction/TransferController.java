package com.karimsaleh.ledger.transaction;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<?> transfer(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @Valid @RequestBody TransferRequest request) {
        TransferResult result = transferService.transfer(
                idempotencyKey,
                request.fromAccountId(),
                request.toAccountId(),
                request.amountCents(),
                request.description());

        // Never return reason flags: that would tell fraudsters which signal tripped.
        return switch (result.outcome()) {
            case APPROVED, SKIPPED -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(TransferResponse.from(result.transaction()));
            case HELD -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new TransferDecisionResponse(result.decisionId(), "PENDING_REVIEW"));
            case BLOCKED -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new TransferDecisionResponse(result.decisionId(), "BLOCKED"));
        };
    }
}
