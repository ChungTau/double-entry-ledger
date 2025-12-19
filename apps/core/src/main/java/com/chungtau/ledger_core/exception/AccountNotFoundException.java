package com.chungtau.ledger_core.exception;

/**
 * Exception thrown when an account cannot be found in the system.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}
