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
                        concurrency-safe overdraft protection. \
                        Source code: https://github.com/Kmsasaleh/payment-ledger

                        ### Try it in 6 steps
                        For each step, open the endpoint below, click **Try it out**, paste the body, and click **Execute**.

                        1. **POST /accounts** with `{"name":"Funding","type":"SYSTEM","currency":"CAD"}`. Copy the `id`.
                        2. **POST /accounts** with `{"name":"Alice","type":"USER","currency":"CAD"}`. Copy the `id`.
                        3. **POST /transfers** with Idempotency-Key `demo-1` and \
                        `{"fromAccountId":"<funding id>","toAccountId":"<alice id>","amountCents":10000,"description":"Funding"}`. \
                        Expect two entries: -10000 and +10000.
                        4. **GET /accounts/{id}** with Alice's id. Expect `balanceCents: 10000`.
                        5. Repeat step 3 with the **same key**. Same `transactionId` comes back, and Alice is not charged twice.
                        6. Send Alice 1000000 cents with a **new key**. Rejected with `422 Insufficient funds`.

                        Use a fresh Idempotency-Key for each new transfer."""));
    }
}