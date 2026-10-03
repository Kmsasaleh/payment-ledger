package com.karimsaleh.ledger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, Object> home() {
        return Map.of(
                "service", "Payment Ledger API",
                "description", "Double-entry payment ledger with idempotent transfers and concurrency-safe overdraft protection",
                "source", "https://github.com/Kmsasaleh/payment-ledger",
                "endpoints", List.of(
                        "POST /accounts",
                        "GET /accounts/{id}",
                        "POST /transfers (requires Idempotency-Key header)",
                        "GET /actuator/health"));
    }
}