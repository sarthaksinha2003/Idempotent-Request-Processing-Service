package com.sarthak.platform.idempotency.exception;

public class MissingIdempotencyKeyException extends RuntimeException {

    public MissingIdempotencyKeyException() {
        super("Idempotency-Key header is required for this operation");
    }
}