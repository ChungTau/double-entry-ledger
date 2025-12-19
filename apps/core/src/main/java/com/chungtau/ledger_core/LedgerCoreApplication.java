package com.chungtau.ledger_core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.chungtau.ledger_core.repository")
public class LedgerCoreApplication {

	public static void main(String[] args) {
		SpringApplication.run(LedgerCoreApplication.class, args);
	}

}
