package com.sarthak.platform.idempotency.service;

import com.sarthak.platform.idempotency.dto.PaymentRequest;
import com.sarthak.platform.idempotency.dto.PaymentResponse;
import com.sarthak.platform.idempotency.entity.IdempotencyRecord;
import com.sarthak.platform.idempotency.entity.PaymentTransaction;
import com.sarthak.platform.idempotency.enums.IdempotencyStatus;
import com.sarthak.platform.idempotency.enums.PaymentStatus;
import com.sarthak.platform.idempotency.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final IdempotencyService idempotencyService;
    private final PaymentTransactionRepository transactionRepository;

    @Transactional
    public PaymentResponse processPayment(String idempotencyKey,
                                          PaymentRequest request) {

        log.info("Processing payment | key={} | from={} to={} | amount={} {}",
                idempotencyKey,
                request.getSenderAccountId(),
                request.getReceiverAccountId(),
                request.getAmount(),
                request.getCurrency());

        // Step 1: Check idempotency
        IdempotencyRecord record = idempotencyService.checkAndReserve(
                idempotencyKey,
                "/api/v1/payments",
                "POST",
                request
        );

        // Step 2: If duplicate - replay stored response
        if (record.isTerminal()) {
            log.info("Replaying stored response for key: {}", idempotencyKey);
            return idempotencyService.replayResponse(record);
        }

        // Step 3: New request - execute payment
        PaymentResponse response;
        try {
            response = executePayment(idempotencyKey, request);
            idempotencyService.markCompleted(
                    idempotencyKey,
                    response,
                    HttpStatus.CREATED.value(),
                    response.getTransactionId()
            );
        } catch (Exception e) {
            log.error("Payment failed for key: {}", idempotencyKey, e);
            PaymentResponse errorResponse = buildFailureResponse(
                    idempotencyKey, request, e.getMessage());
            idempotencyService.markFailed(
                    idempotencyKey,
                    errorResponse,
                    HttpStatus.UNPROCESSABLE_ENTITY.value()
            );
            return errorResponse;
        }

        log.info("Payment successful | key={} | txId={}",
                idempotencyKey, response.getTransactionId());
        return response;
    }

    public PaymentResponse getTransaction(String transactionId) {
        PaymentTransaction tx = transactionRepository
                .findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction not found: " + transactionId));
        return mapToResponse(tx);
    }

    private PaymentResponse executePayment(String idempotencyKey,
                                           PaymentRequest request) {

        String transactionId = UUID.randomUUID().toString();
        String gatewayRef = "GW-" + UUID.randomUUID()
                .toString().substring(0, 12).toUpperCase();

        PaymentTransaction transaction = PaymentTransaction.builder()
                .transactionId(transactionId)
                .idempotencyKey(idempotencyKey)
                .senderAccountId(request.getSenderAccountId())
                .receiverAccountId(request.getReceiverAccountId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .description(request.getDescription())
                .status(PaymentStatus.SUCCESS)
                .gatewayReference(gatewayRef)
                .processedAt(LocalDateTime.now())
                .build();

        transactionRepository.save(transaction);

        return PaymentResponse.builder()
                .transactionId(transactionId)
                .idempotencyKey(idempotencyKey)
                .senderAccountId(request.getSenderAccountId())
                .receiverAccountId(request.getReceiverAccountId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(PaymentStatus.SUCCESS)
                .gatewayReference(gatewayRef)
                .message("Payment processed successfully")
                .processedAt(LocalDateTime.now())
                .replayed(false)
                .build();
    }

    private PaymentResponse buildFailureResponse(String idempotencyKey,
                                                 PaymentRequest request,
                                                 String reason) {
        return PaymentResponse.builder()
                .idempotencyKey(idempotencyKey)
                .senderAccountId(request.getSenderAccountId())
                .receiverAccountId(request.getReceiverAccountId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(PaymentStatus.FAILED)
                .failureReason(reason)
                .message("Payment processing failed")
                .processedAt(LocalDateTime.now())
                .replayed(false)
                .build();
    }

    private PaymentResponse mapToResponse(PaymentTransaction tx) {
        return PaymentResponse.builder()
                .transactionId(tx.getTransactionId())
                .idempotencyKey(tx.getIdempotencyKey())
                .senderAccountId(tx.getSenderAccountId())
                .receiverAccountId(tx.getReceiverAccountId())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .status(tx.getStatus())
                .gatewayReference(tx.getGatewayReference())
                .failureReason(tx.getFailureReason())
                .processedAt(tx.getProcessedAt())
                .replayed(false)
                .build();
    }
}