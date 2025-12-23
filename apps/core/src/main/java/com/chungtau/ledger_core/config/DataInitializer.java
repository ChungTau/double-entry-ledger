package com.chungtau.ledger_core.config;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.chungtau.ledger_core.entity.Account;
import com.chungtau.ledger_core.repository.AccountRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Initializes system accounts required for double-entry bookkeeping.
 * Creates Equity accounts for each supported currency to serve as the source
 * for opening balance transactions (Genesis transactions).
 */
@Configuration
@Slf4j
public class DataInitializer {

    public static final String SYSTEM_USER_ID = "SYSTEM";
    private static final List<String> SUPPORTED_CURRENCIES = List.of("HKD", "USD", "EUR");

    @Bean
    ApplicationRunner initializeSystemAccounts(AccountRepository accountRepository) {
        return args -> {
            for (String currency : SUPPORTED_CURRENCIES) {
                accountRepository.findByUserIdAndCurrency(SYSTEM_USER_ID, currency)
                    .ifPresentOrElse(
                        account -> log.debug("System Equity account for {} already exists: {}",
                            currency, account.getId()),
                        () -> {
                            Account equityAccount = Account.builder()
                                .userId(SYSTEM_USER_ID)
                                .currency(currency)
                                .balance(BigDecimal.ZERO)
                                .build();
                            Account saved = accountRepository.save(equityAccount);
                            log.info("Created System Equity account for {}: {}",
                                currency, saved.getId());
                        }
                    );
            }
        };
    }
}
