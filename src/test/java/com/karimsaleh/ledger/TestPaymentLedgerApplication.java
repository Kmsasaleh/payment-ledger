package com.karimsaleh.ledger;

import org.springframework.boot.SpringApplication;

public class TestPaymentLedgerApplication {

	public static void main(String[] args) {
		SpringApplication.from(PaymentLedgerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
