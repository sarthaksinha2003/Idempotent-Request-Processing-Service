# Idempotent Request Processing Service

A distributed idempotent request processing service built with Spring Boot that guarantees **exactly-once execution semantics** for REST APIs in the presence of retries, network failures, and concurrent duplicate requests.

This project solves a core distributed systems problem commonly seen in banking and payment systems — ensuring that a payment is never processed twice, even if the client retries the request multiple times.

---

## Table of Contents

- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [How Idempotency Works](#how-idempotency-works)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [API Reference](#api-reference)
- [Error Responses](#error-responses)
- [Idempotency Behaviour Matrix](#idempotency-behaviour-matrix)
- [Key Design Decisions](#key-design-decisions)
- [Deployment](#deployment)

---

## Architecture
```
Client
  │
  │  POST /api/v1/payments
  │  Idempotency-Key: <uuid>
  ▼
PaymentController
  │
  ▼
PaymentService
  │
  ├──► IdempotencyService.checkAndReserve()
  │         │
  │         ├── Redis HIT  ──────────────────► replay stored response (O(1))
  │         │
  │         └── Redis MISS
  │                  │
  │                  └── MySQL + PESSIMISTIC WRITE lock
  │                           │
  │              ┌────────────┼──────────────────┐
  │            NEW        IN_PROGRESS     COMPLETED/FAILED
  │              │            │                  │
  │         insert record   throw 409        replay response
  │         (IN_PROGRESS)
  │
  ├──► executePayment()  [runs exactly once]
  │
  └──► IdempotencyService.markCompleted()
            │
            ├── persist response to MySQL
            └── populate Redis cache
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.0.3 |
| REST | Spring Web MVC |
| ORM | Spring Data JPA (Hibernate 7) |
| Database | MySQL 8 |
| Cache | Redis (Lettuce client) |
| Build | Maven |
| Utilities | Lombok |

---

## How Idempotency Works

1. Client generates a unique `Idempotency-Key` (UUID recommended) per logical operation
2. Client sends the key as an HTTP header with every request
3. On first request — service creates an `IN_PROGRESS` record with a pessimistic DB lock
4. Business logic executes exactly once
5. Response is persisted to MySQL and cached in Redis
6. On duplicate request — stored response is replayed instantly from Redis (O(1))
7. If duplicate arrives while still processing — 409 Conflict is returned
8. If duplicate arrives with different payload — 422 Unprocessable Entity is returned

---

## Project Structure
```
src/main/java/com/sarthak/platform/idempotency/
├── config/
│   └── RedisConfig.java                  Redis template and cache manager
├── controller/
│   └── PaymentController.java            REST endpoints
├── dto/
│   ├── ApiErrorResponse.java             Standard error response shape
│   ├── PaymentRequest.java               Validated incoming payment payload
│   └── PaymentResponse.java             Outgoing payment response
├── entity/
│   ├── IdempotencyRecord.java            Core idempotency table
│   └── PaymentTransaction.java           Payment domain table
├── enums/
│   ├── IdempotencyStatus.java            IN_PROGRESS / COMPLETED / FAILED
│   └── PaymentStatus.java               PENDING / SUCCESS / FAILED / REVERSED
├── exception/
│   ├── GlobalExceptionHandler.java       Centralized exception → HTTP mapping
│   ├── IdempotencyKeyConflictException   Payload mismatch → 422
│   ├── MissingIdempotencyKeyException    Missing header → 400
│   └── RequestInProgressException        Concurrent duplicate → 409
├── repository/
│   ├── IdempotencyRecordRepository       Includes @Lock(PESSIMISTIC_WRITE)
│   └── PaymentTransactionRepository
├── service/
│   ├── IdempotencyService.java           Core idempotency engine
│   └── PaymentService.java              Payment business logic
└── util/
    └── RequestHashUtil.java              SHA-256 payload hashing
```

---

## Prerequisites

- Java 21+
- Maven 3.8+
- MySQL 8 running on `localhost:3306`
- Redis running on `localhost:6379`
- Docker (optional, for running Redis)

---

## Getting Started

### 1. Clone the repository
```bash
git clone <your-repo-url>
cd idempotent-request-service
```

### 2. Create MySQL database
```sql
CREATE DATABASE idempotent_db;
```

### 3. Start Redis (using Docker)
```bash
docker run -d -p 6379:6379 redis
```

### 4. Configure credentials

Update `src/main/resources/application.properties`:
```properties
spring.datasource.username=your_mysql_username
spring.datasource.password=your_mysql_password
```

### 5. Run the application
```bash
mvn spring-boot:run
```

Service starts on `http://localhost:8080`.

Hibernate will auto-create the database tables on first run.

---

## Configuration

| Property | Default | Description |
|---|---|---|
| `server.port` | `8080` | Server port |
| `spring.datasource.url` | `localhost:3306/idempotent_db` | MySQL URL |
| `spring.jpa.hibernate.ddl-auto` | `update` | Schema strategy |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |

---

## API Reference

### POST /api/v1/payments

Creates a payment. Requires `Idempotency-Key` header.

**Request Headers:**

| Header | Required | Description |
|---|---|---|
| `Content-Type` | Yes | `application/json` |
| `Idempotency-Key` | Yes | Unique UUID per payment operation |

**Request Body:**
```json
{
  "senderAccountId": "ACC-001",
  "receiverAccountId": "ACC-002",
  "amount": 150.00,
  "currency": "USD",
  "description": "Invoice #1042 payment"
}
```

| Field | Type | Required | Validation |
|---|---|---|---|
| `senderAccountId` | String | Yes | Not blank |
| `receiverAccountId` | String | Yes | Not blank |
| `amount` | Decimal | Yes | Greater than 0.01 |
| `currency` | String | Yes | Exactly 3 characters (ISO code) |
| `description` | String | No | Max 500 characters |

**First call response — 201 Created:**
```json
{
  "transactionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000",
  "senderAccountId": "ACC-001",
  "receiverAccountId": "ACC-002",
  "amount": 150.00,
  "currency": "USD",
  "status": "SUCCESS",
  "gatewayReference": "GW-F47AC10B58CC",
  "message": "Payment processed successfully",
  "processedAt": "2026-03-19T02:36:01",
  "replayed": false
}
```

**Duplicate call response — 200 OK:**
```json
{
  "transactionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  ...same fields as above...
  "replayed": true
}
```

**Response Headers:**

| Header | Description |
|---|---|
| `Idempotency-Key` | Echoes back the key |
| `Idempotent-Replayed` | `true` if this is a duplicate response |

---

### GET /api/v1/payments/{transactionId}

Retrieve a payment by transaction ID.

**Response — 200 OK:**
```json
{
  "transactionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "senderAccountId": "ACC-001",
  "receiverAccountId": "ACC-002",
  "amount": 150.00,
  "currency": "USD",
  "status": "SUCCESS",
  "gatewayReference": "GW-F47AC10B58CC",
  "processedAt": "2026-03-19T02:36:01",
  "replayed": false
}
```

**Not found — 404:**
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Transaction not found: f47ac10b-...",
  "path": "/api/v1/payments/f47ac10b-...",
  "timestamp": "2026-03-19T02:36:01"
}
```

---

### GET /api/v1/payments/health

Health check endpoint.

**Response — 200 OK:**
```
Idempotent Payment Service is running
```

---

### GET /actuator/health

Spring Boot Actuator health endpoint.

---

## Error Responses

All errors follow this consistent format:
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Human readable explanation",
  "path": "/api/v1/payments",
  "timestamp": "2026-03-19T02:36:01",
  "fieldErrors": ["field: validation message"]
}
```

`fieldErrors` is only present for validation failures.

---

## Idempotency Behaviour Matrix

| Scenario | HTTP Status | Description |
|---|---|---|
| First request | 201 Created | Payment executed and persisted |
| Duplicate (COMPLETED) | 200 OK | Stored response replayed from Redis |
| Duplicate (FAILED) | 422 | Stored error response replayed |
| Duplicate while IN_PROGRESS | 409 Conflict | Another thread is processing this key |
| Same key, different payload | 422 | Protocol violation — rejected |
| Missing Idempotency-Key header | 400 | Header is required |
| Invalid request body | 400 | Validation failed with field errors |
| Transaction not found | 404 | No transaction with given ID |

---

## Key Design Decisions

### Pessimistic Locking
`SELECT ... FOR UPDATE` on `idempotency_records` prevents race conditions when two identical requests arrive simultaneously. The second request blocks on the DB lock until the first commits.

### Two-Level Storage
Redis provides O(1) fast-path for all repeat requests after first completion. MySQL is the authoritative source handling the first request and concurrent duplicates.

### Separated Tables
`idempotency_records` tracks request lifecycle. `payment_transactions` stores business domain data. They are linked by `idempotencyKey` string — not a foreign key — keeping the idempotency engine domain-agnostic and reusable.

### SHA-256 Payload Hashing
Request body is hashed and stored on first request. Duplicates with different payloads are detected and rejected with 422.

### REQUIRES_NEW Transaction Propagation
Idempotency operations run in their own transactions so the pessimistic lock is released immediately after the check phase, not held for the entire payment duration.

---

## Deployment

> This section will be updated when the service is deployed.

- [ ] Docker containerization
- [ ] Environment-specific configuration
- [ ] Production database setup
- [ ] Redis cluster configuration
- [ ] CI/CD pipeline

---

## Author

Sarthak Sinha