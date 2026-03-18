package com.sarthak.platform.idempotency.controller;

import com.sarthak.platform.idempotency.dto.PaymentRequest;
import com.sarthak.platform.idempotency.dto.PaymentResponse;
import com.sarthak.platform.idempotency.exception.MissingIdempotencyKeyException;
import com.sarthak.platform.idempotency.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        validateIdempotencyKey(idempotencyKey);

        log.info("POST /api/v1/payments | key={}", idempotencyKey);

        PaymentResponse response = paymentService
                .processPayment(idempotencyKey, request);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("Idempotent-Replayed", String.valueOf(response.isReplayed()));

        HttpStatus status = response.isReplayed()
                ? HttpStatus.OK
                : HttpStatus.CREATED;

        return ResponseEntity.status(status)
                .headers(headers)
                .body(response);
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<PaymentResponse> getPayment(
            @PathVariable String transactionId) {

        log.info("GET /api/v1/payments/{}", transactionId);
        PaymentResponse response = paymentService.getTransaction(transactionId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Idempotent Payment Service is running");
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must not exceed 255 characters");
        }
    }
}