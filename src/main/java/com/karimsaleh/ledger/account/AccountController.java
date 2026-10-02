package com.karimsaleh.ledger.account;

import com.karimsaleh.ledger.transaction.EntryRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountRepository accountRepository;
    private final EntryRepository entryRepository;

    public AccountController(AccountRepository accountRepository, EntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountRepository.save(
                new Account(request.name(), request.type(), request.currency().toUpperCase()));
        return AccountResponse.from(account, 0);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found: " + id));
        return AccountResponse.from(account, entryRepository.balanceOf(id));
    }
}