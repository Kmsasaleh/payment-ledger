package com.karimsaleh.ledger;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentLedgerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Payment Ledger API")
                .version("1.0")
                .description("""
                        Double-entry payment ledger with idempotent transfers and \
                        concurrency-safe overdraft protection.

                        **Try it:** create a SYSTEM account (the funding source) and two USER accounts, \
                        fund one user from the SYSTEM account, then transfer between users. \
                        Every transfer needs a unique Idempotency-Key; reusing one returns the original \
                        result instead of charging twice.

                        Source code: https://github.com/Kmsasaleh/payment-ledger"""));
    }
}