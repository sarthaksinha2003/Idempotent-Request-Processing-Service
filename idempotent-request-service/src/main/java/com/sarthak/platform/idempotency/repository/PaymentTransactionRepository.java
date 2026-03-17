package com.sarthak.platform.idempotency.repository;

import com.sarthak.platform.idempotency.entity.PaymentTransaction;
import com.sarthak.platform.idempotency.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByTransactionId(String transactionId);

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    List<PaymentTransaction> findBySenderAccountIdOrderByCreatedAtDesc(String senderAccountId);

    List<PaymentTransaction> findByStatus(PaymentStatus status);
}