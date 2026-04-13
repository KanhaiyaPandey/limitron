# Limitron — Distributed Rate Limiter (Redis + Spring Cloud Gateway)

Limitron is a production-style, **distributed rate limiting** service built on **Redis** and integrated at the edge using **Spring Cloud Gateway**. It supports:

- **Token Bucket** (atomic Lua script)
- **Sliding Window** (Redis ZSET + Lua)
- **Per-user + per-endpoint** limits (`rate_limiter:{userId}:{endpoint}`)
- **Gateway filter enforcement** via `X-USER-ID` and route filters

---

## Problem Statement

In a distributed system (multiple app instances, multiple pods), in-memory counters are not sufficient for rate limiting because:

- state is not shared across instances
- concurrency can cause race conditions
- bursts must be controlled consistently at the gateway

Limitron solves this by centralizing rate-limit state in **Redis** and enforcing it using **atomic operations** (Lua scripts) at the boundary (API Gateway).

---

## Architecture Overview

**Request path**

1. Client → **Spring Cloud Gateway**
2. Gateway extracts `X-USER-ID` and endpoint path
3. Gateway calls `RateLimiterService`
4. `RateLimiterService` executes **Redis Lua** (atomic)
5. Allowed → request is forwarded to backend
6. Blocked → gateway returns **HTTP 429**

**Key components**

- `RateLimiterGatewayFilterFactory` — gateway filter that enforces rate limits
- `RateLimiterService` — implements Token Bucket + Sliding Window strategies
- Redis — source of truth for distributed rate-limit state

---

## Core Features

- **Distributed correctness**: Redis is the shared state; no per-instance counters.
- **Atomic enforcement**: Lua scripts ensure correct behavior under concurrency.
- **Per-endpoint policies**: configure different limits for `/login`, `/search`, etc.
- **Gateway-first design**: enforce rate limiting before traffic hits backend services.
- **Fail-closed on Redis errors**: if Redis is unavailable, requests are treated as blocked (configurable future enhancement).

---

## Advanced Features

- **Strategy selection** via config:
  - `TOKEN_BUCKET` (default)
  - `SLIDING_WINDOW`
- **Per-endpoint limit map** with a default fallback
- **Key design for isolation**
  - per-user isolation: `{userId}`
  - per-endpoint isolation: `{endpoint}`

---

## Tech Stack

- Java 17+
- Spring Boot (reactive runtime)
- Spring Cloud Gateway (WebFlux)
- Redis
- Maven
- JUnit 5 + Mockito
- Testcontainers (Redis integration tests)

---

## Rate Limiting Strategies

### 1) Token Bucket (Lua, atomic)

**What it gives**

- smooth refill over time
- supports bursts up to bucket size

**How it works**

- A Redis key stores `tokens:lastRefillTime`
- Lua script refills tokens based on elapsed time and decrements exactly once per request
- Key format (per user + endpoint):
  - `rate_limiter:{userId}:{endpoint}`

### 2) Sliding Window (ZSET + Lua, atomic)

**What it gives**

- precise “N requests per window” behavior (e.g., 5 req/min)

**How it works**

- A ZSET stores request events by timestamp
- Lua script:
  - removes old entries (`ZREMRANGEBYSCORE`)
  - counts (`ZCARD`)
  - conditionally adds a new entry (`ZADD`)
- Key format:
  - `rate_limiter_sw:{userId}:{endpoint}`

---

## Configuration

### Gateway route + filter

Configured in `src/main/resources/application.yml`:

- Route: `/api/** → backend`
- Filter: `RateLimiter` (custom filter factory)

Backend URI is configurable:

```properties
limitron.gateway.backend-uri=http://localhost:8081
```

### Endpoint limits

Example:

```yaml
limitron:
  rate-limiter:
    default-limit: 10
    endpoint-limits:
      /login: 5
      /search: 50
```

### Strategy switch

```properties
limitron.rate-limiter.strategy=TOKEN_BUCKET
# or
limitron.rate-limiter.strategy=SLIDING_WINDOW
```

---

## How to Start

### Prerequisites

- Java 17+
- Redis (local) OR Docker (for Redis via container)

### Start Redis (example)

If you have Redis installed locally, run it on `localhost:6379`.

Or with Docker:

```bash
docker run --rm -p 6379:6379 redis:7.2-alpine
```

### Run the Gateway app

```bash
./mvnw spring-boot:run
```

### Send a request through Gateway

The gateway expects a user id header:

```bash
curl -i -H "X-USER-ID: user-1" http://localhost:8080/api/login
```

When blocked, you’ll get:

- `429 Too Many Requests`

---

## Testing

### Run all tests

```bash
./mvnw test
```

### What tests exist

- **Unit tests**
  - service behavior, key selection, endpoint limit resolution, fail-closed behavior
  - gateway filter behavior (allow/deny/missing header)
  - controller behavior (allowed/blocked/invalid input)
- **Integration tests (Redis + Testcontainers)**
  - real Redis enforcement (token bucket + sliding window)
  - concurrency/atomicity tests (Lua)

### Note about Testcontainers

Integration tests use Testcontainers and will be **skipped automatically** if Docker isn’t available on the machine.

To run them, ensure Docker is running and re-run:

```bash
./mvnw test
```

---

## Performance Notes

### Token Bucket

- One atomic Lua execution per request
- O(1) read/update per key
- Efficient under high concurrency due to Redis-side atomicity

### Sliding Window

- One atomic Lua execution per request
- ZSET operations per request:
  - cleanup: `ZREMRANGEBYSCORE`
  - count: `ZCARD`
  - insert: `ZADD` (only if allowed)
- Designed for correctness; for extremely high throughput consider:
  - approximate algorithms
  - batching
  - per-route sampling

---

## Future Improvements

- **Configurable “fail-open vs fail-closed”** behavior when Redis is unavailable
- **Per-endpoint window sizing** (not just per-endpoint limit)
- **Per-plan / multi-tenant quotas** (e.g., free vs paid tiers)
- **Better key compaction** (path templating, e.g. `/users/{id}` → `/users/*`)
- **Observability**
  - metrics (allowed/blocked/latency)
  - structured logs
  - traces across gateway → redis → backend
- **Hot-reload policies** (dynamic config + caching)

---

## Key Learnings

- **Correct distributed rate limiting** requires a shared state store and atomic updates.
- Redis Lua scripts are a clean way to prevent race conditions without application-level locks.
- Gateway-first enforcement reduces backend load and improves system resilience.
- Per-endpoint policies matter: `/login` and `/search` have very different risk profiles.

