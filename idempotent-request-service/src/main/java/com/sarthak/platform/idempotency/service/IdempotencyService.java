package com.sarthak.platform.idempotency.service;

import com.sarthak.platform.idempotency.dto.PaymentResponse;
import com.sarthak.platform.idempotency.entity.IdempotencyRecord;
import com.sarthak.platform.idempotency.enums.IdempotencyStatus;
import com.sarthak.platform.idempotency.exception.IdempotencyKeyConflictException;
import com.sarthak.platform.idempotency.exception.RequestInProgressException;
import com.sarthak.platform.idempotency.repository.IdempotencyRecordRepository;
import com.sarthak.platform.idempotency.util.RequestHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private static final String CACHE_PREFIX = "idempotency:";

    private final IdempotencyRecordRepository recordRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final RequestHashUtil hashUtil;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord checkAndReserve(
            String idempotencyKey,
            String requestPath,
            String requestMethod,
            Object payload) {

        log.debug("Checking idempotency key: {}", idempotencyKey);

        // Step 1: Check Redis cache first (fast path)
        String cachedResponse = redisTemplate.opsForValue().get(cacheKey(idempotencyKey));

        if (cachedResponse != null) {
            log.debug("Cache HIT for key: {}", idempotencyKey);

            // Check payload hash even on cache hit
            String requestHash = hashUtil.computeHash(payload);
            String storedHash = redisTemplate.opsForValue().get(hashCacheKey(idempotencyKey));

            if (storedHash != null && !storedHash.equals(requestHash)) {
                log.warn("Payload mismatch on cache hit for key: {}", idempotencyKey);
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }

            IdempotencyRecord cached = new IdempotencyRecord();
            cached.setIdempotencyKey(idempotencyKey);
            cached.setStatus(IdempotencyStatus.COMPLETED);
            cached.setResponseBody(cachedResponse);
            cached.setResponseStatusCode(200);
            return cached;
        }

        // Step 2: Cache miss - check DB with pessimistic lock
        String requestHash = hashUtil.computeHash(payload);
        Optional<IdempotencyRecord> existing =
                recordRepository.findByIdempotencyKeyWithLock(idempotencyKey);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();

            // Check payload mismatch
            if (!record.getRequestHash().equals(requestHash)) {
                log.warn("Payload mismatch for key: {}", idempotencyKey);
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }

            // Handle based on the current status
            if (record.isInProgress()) {
                log.info("Key {} is IN_PROGRESS", idempotencyKey);
                throw new RequestInProgressException(idempotencyKey);
            }

            // COMPLETED or FAILED - return existing record for replay
            log.debug("Replaying response for key: {}", idempotencyKey);
            return record;
        }

        // Step 3: New request - create IN_PROGRESS record
        IdempotencyRecord newRecord = IdempotencyRecord.builder()
                .idempotencyKey(idempotencyKey)
                .requestPath(requestPath)
                .requestMethod(requestMethod)
                .requestHash(requestHash)
                .status(IdempotencyStatus.IN_PROGRESS)
                .processingNode(getInstanceId())
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();

        recordRepository.save(newRecord);
        log.info("New idempotency record created for key: {}", idempotencyKey);
        return newRecord;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(String idempotencyKey,
                              Object responseObject,
                              int httpStatusCode,
                              String resourceId) {
        try {
            String responseJson = objectMapper.writeValueAsString(responseObject);

            IdempotencyRecord record = recordRepository
                    .findByIdempotencyKeyWithLock(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "No record found for key: " + idempotencyKey));

            record.setStatus(IdempotencyStatus.COMPLETED);
            record.setResponseBody(responseJson);
            record.setResponseStatusCode(httpStatusCode);
            record.setResourceId(resourceId);
            recordRepository.save(record);

            // Populate Redis cache with response
            redisTemplate.opsForValue().set(
                    cacheKey(idempotencyKey),
                    responseJson,
                    Duration.ofHours(24));

            // Store request hash in Redis for mismatch detection on cache hit
            redisTemplate.opsForValue().set(
                    hashCacheKey(idempotencyKey),
                    record.getRequestHash(),
                    Duration.ofHours(24));

            log.info("Marked COMPLETED for key: {}", idempotencyKey);

        } catch (Exception e) {
            log.error("Failed to mark COMPLETED for key: {}", idempotencyKey, e);
            throw new RuntimeException("Failed to finalize idempotency record", e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String idempotencyKey,
                           Object errorResponse,
                           int httpStatusCode) {
        try {
            String responseJson = objectMapper.writeValueAsString(errorResponse);

            recordRepository.findByIdempotencyKeyWithLock(idempotencyKey)
                    .ifPresent(record -> {
                        record.setStatus(IdempotencyStatus.FAILED);
                        record.setResponseBody(responseJson);
                        record.setResponseStatusCode(httpStatusCode);
                        recordRepository.save(record);

                        redisTemplate.opsForValue().set(
                                cacheKey(idempotencyKey),
                                responseJson,
                                Duration.ofHours(1));

                        log.info("Marked FAILED for key: {}", idempotencyKey);
                    });

        } catch (Exception e) {
            log.error("Failed to mark FAILED for key: {}", idempotencyKey, e);
        }
    }

    public PaymentResponse replayResponse(IdempotencyRecord record) {
        try {
            PaymentResponse response = objectMapper.readValue(
                    record.getResponseBody(), PaymentResponse.class);
            response.setReplayed(true);
            return response;
        } catch (Exception e) {
            log.error("Failed to deserialize stored response for key: {}",
                    record.getIdempotencyKey(), e);
            throw new IllegalStateException(
                    "Corrupted idempotency record", e);
        }
    }

    private String cacheKey(String idempotencyKey) {
        return CACHE_PREFIX + idempotencyKey;
    }

    private String getInstanceId() {
        return System.getenv().getOrDefault("HOSTNAME", "local-instance");
    }

    private String hashCacheKey(String idempotencyKey) {
        return CACHE_PREFIX + idempotencyKey + ":hash";
    }
}