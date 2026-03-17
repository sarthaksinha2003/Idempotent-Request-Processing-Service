package com.sarthak.platform.idempotency.exception;

public class RequestInProgressException extends RuntimeException {

    public RequestInProgressException(String key) {
        super("Request with idempotency key '" + key + "' is currently being processed. Retry after a short delay.");
    }
}