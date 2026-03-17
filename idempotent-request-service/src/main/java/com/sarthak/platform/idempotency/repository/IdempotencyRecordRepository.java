package com.sarthak.platform.idempotency.repository;

import com.sarthak.platform.idempotency.entity.IdempotencyRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM IdempotencyRecord r WHERE r.idempotencyKey = :key")
    Optional<IdempotencyRecord> findByIdempotencyKeyWithLock(@Param("key") String key);

    @Query("SELECT r FROM IdempotencyRecord r WHERE r.status = 'IN_PROGRESS' AND r.createdAt < :threshold")
    List<IdempotencyRecord> findStaleInProgressRecords(@Param("threshold") LocalDateTime threshold);

    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :now")
    int deleteExpiredRecords(@Param("now") LocalDateTime now);
}