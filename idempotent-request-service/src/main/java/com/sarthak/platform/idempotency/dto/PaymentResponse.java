package com.sarthak.platform.idempotency.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sarthak.platform.idempotency.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResponse {

    private String transactionId;
    private String idempotencyKey;
    private String senderAccountId;
    private String receiverAccountId;
    private BigDecimal amount;
    private String currency;
    private PaymentStatus status;
    private String gatewayReference;
    private String message;
    private String failureReason;
    private LocalDateTime processedAt;
    private boolean replayed;
}