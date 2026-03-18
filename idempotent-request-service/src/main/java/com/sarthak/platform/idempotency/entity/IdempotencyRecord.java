package com.sarthak.platform.idempotency.entity;

import com.sarthak.platform.idempotency.enums.IdempotencyStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(nullable = false, length = 255)
    private String requestPath;

    @Column(nullable = false, length = 10)
    private String requestMethod;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column
    private Integer responseStatusCode;

    @Column(length = 100)
    private String resourceId;

    @Column(length = 100)
    private String processingNode;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public boolean isInProgress() {
        return IdempotencyStatus.IN_PROGRESS.equals(this.status);
    }

    public boolean isTerminal() {
        return IdempotencyStatus.COMPLETED.equals(this.status)
                || IdempotencyStatus.FAILED.equals(this.status);
    }
}
