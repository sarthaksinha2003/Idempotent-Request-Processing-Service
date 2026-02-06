# Idempotent Request Processing Service

A distributed **idempotent request processing service** built with Spring Boot to guarantee
**exactly-once execution semantics** for REST APIs in the presence of retries, concurrency,
and partial failures.

This project focuses on solving real-world distributed systems problems commonly seen
in banking and payment systems.

---

## 🚀 Key Features

- Exactly-once request execution using **Idempotency Keys**
- Protection against duplicate requests caused by network retries
- Safe handling of **concurrent duplicate requests**
- Persistent request state tracking with **MySQL**
- **Pessimistic database locking** to prevent race conditions
- **Redis-based caching** for fast idempotency lookups (O(1))
- Deterministic response replay for duplicate requests
- Designed for **distributed, multi-instance deployments**

---

## 🛠 Tech Stack

- **Java 17**
- **Spring Boot 4**
- Spring Web (REST APIs)
- Spring Data JPA (Hibernate)
- MySQL
- Redis
- Spring Cache

---

## 🧠 High-Level Design

1. Client sends a request with an `Idempotency-Key`
2. Service checks Redis cache for an existing response
3. On cache miss, the database is queried using **pessimistic locking**
4. First request is marked `IN_PROGRESS`
5. Business logic executes **exactly once**
6. Response is persisted and cached
7. Duplicate requests replay the stored response

This approach guarantees **zero duplicate side effects**, even under high concurrency.

---

## 📦 Project Structure

```text
idempotent-request-service/
├── src/
│   ├── main/
│   │   ├── java/
│   │   └── resources/
│   └── test/
├── pom.xml
└── .gitignore
