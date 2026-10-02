package com.karimsaleh.ledger.transaction;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse transfer(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @Valid @RequestBody TransferRequest request) {
        LedgerTransaction transaction = transferService.transfer(
                idempotencyKey,
                request.fromAccountId(),
                request.toAccountId(),
                request.amountCents(),
                request.description());
        return TransferResponse.from(transaction);
    }
}