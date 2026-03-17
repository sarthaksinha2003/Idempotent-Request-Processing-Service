package com.sarthak.platform.idempotency.exception;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String key) {
        super("Idempotency key '" + key + "' was already used with a different request payload");
    }
}