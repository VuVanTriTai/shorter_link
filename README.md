# 🔗 Smart Link Shortener

> A production-grade URL shortener built with **Spring Boot 3**, **React**, **Redis**, and **PostgreSQL**.
> Features a high-throughput analytics pipeline, JWT authentication, rate limiting, and URL safety validation.

[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen?logo=springboot)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18-blue?logo=react)](https://reactjs.org/)
[![Redis](https://img.shields.io/badge/Redis-7-red?logo=redis)](https://redis.io/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue?logo=postgresql)](https://www.postgresql.org/)

---

## 📋 Table of Contents

- [Features](#-features)
- [Tech Stack](#-tech-stack)
- [Project Structure](#-project-structure)
- [Architecture Overview](#-architecture-overview)
- [Click Event Processing Flow](#-click-event-processing-flow)
- [Performance Benchmarks (k6)](#-performance-benchmarks-k6)
- [Authentication Flow](#-authentication-flow)
- [Quick Start](#-quick-start)
- [Environment Variables](#-environment-variables)

---

## ✨ Features

- **URL Shortening** - custom short codes, expiry dates, click limits
- **QR Code Generation** - on-demand PNG QR codes per link
- **High-Throughput Analytics** - Redis Stream + batch pipeline, ~2,300 RPS
- **Click Tracking** - IP, user-agent, referrer, deduplication via Redis INCR
- **JWT Authentication** - access token (stateless) + refresh token (Redis, atomic rotation)
- **Google OAuth2** - one-click social login
- **Rate Limiting** - per-IP sliding window via Redis
- **URL Safety Validation** - SSRF guard, reachability check, Google Safe Browsing integration
- **Admin Dashboard** - user management, link moderation, global stats
- **Graceful Redis Degradation** - falls back to direct DB write if Redis is unavailable
- **Docker Compose** - one-command local setup

---

## 🛠 Tech Stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot 3, Spring Security, Spring Data JPA |
| Frontend | React 18, Vite, Axios |
| Cache / Queue | Redis 7 (String, Hash, Stream, Consumer Group) |
| Database | PostgreSQL 15 |
| Auth | JWT (jjwt), Google OAuth2 |
| QR Code | ZXing |
| Load Testing | k6 |
| Containerization | Docker, Docker Compose |

---

## 📁 Project Structure

```
smart-link-shortener/
├── frontend/                        # React 18 + Vite SPA
│   └── src/
│       ├── pages/
│       └── components/
├── src/main/java/com/vvttai/smart_link_shortener/
│   ├── config/                      # Security, Redis, JWT filter
│   ├── common/
│   │   ├── ratelimit/               # IP-based Redis rate limiter
│   │   ├── exception/               # Global exception handler
│   │   └── util/                    # JwtTokenProvider
│   └── modules/
│       ├── auth/                    # Login, Register, Refresh Token, Google OAuth
│       ├── link/                    # CRUD, cache, URL validation, QR code
│       │   └── service/
│       │       ├── LinkCacheService.java
│       │       ├── UrlValidationService.java
│       │       ├── SsrfGuard.java
│       │       ├── ReachabilityChecker.java
│       │       ├── SafeBrowsingClient.java
│       │       └── CustomCodeValidator.java
│       ├── analytics/               # Click tracking pipeline
│       │   └── service/
│       │       ├── AnalyticsService.java
│       │       ├── RedisClickCounterService.java
│       │       ├── ClickNormalizer.java
│       │       └── AnalyticsBatchProcessor.java
│       ├── redirect/
│       ├── admin/
│       └── user/
├── k6-10k-clicks.js
├── k6-test-click.js
├── docker-compose.yml
└── src/main/resources/
    ├── application.properties
    └── db/migration/
```

---

## 🏗 Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                       Client (React)                    │
└──────────────────────────┬──────────────────────────────┘
                           │ HTTP / REST
┌──────────────────────────▼──────────────────────────────┐
│                  Spring Boot Backend                    │
│                                                         │
│  AuthController · LinkController · RedirectController  │
│                         │                               │
│              Service Layer (Business Logic)             │
└──────────────┬──────────────────────────────────────────┘
               │
    ┌──────────▼──────────┐      ┌─────────────────────┐
    │       Redis 7        │      │    PostgreSQL 15     │
    │                     │      │                     │
    │ • Link cache (Hash) │      │ • links             │
    │ • Click counter     │─────►│ • click_analytics   │
    │ • Click Stream      │ sync │ • click_stats       │
    │ • Refresh tokens    │      │ • users             │
    │ • Rate limiter      │      │                     │
    └─────────────────────┘      └─────────────────────┘
```

---

## ⚡ Click Event Processing Flow

When a user visits /r/{code}, two things happen:

### Phase 1 — Redirect (< 5ms)

```
GET /r/{code}
    │
    ▼
LinkCacheService.getLink(code)
    ├── HIT  → return Redis cached link (TTL 1h)
    └── MISS → query PostgreSQL → populate cache → return
    │
    ▼
HTTP 302 → originalUrl
```

### Phase 2 — Async Click Recording (non-blocking)

```
@Async (does NOT block the redirect response)
AnalyticsService.recordClick(shortCode, request)
    │
    ├─► ClickNormalizer  → extract IP, User-Agent, Referrer
    │
    ├─► RedisClickCounterService
    │       INCR  analytics:count:{code}
    │       SETEX analytics:uniq:{code}:{ip}   (dedup, TTL 24h)
    │
    ├─► XADD analytics:click-stream * {payload}
    │       (Redis Stream — ordered, persistent log)
    │
    └─► [Redis unavailable] → ClickAnalyticsRepository.save()
            Graceful fallback: direct write to PostgreSQL
```

### Phase 3 — Batch Flush to PostgreSQL (every 5s)

```
@Scheduled(fixedDelay = 5000)
AnalyticsBatchProcessor.processBatch()
    │
    ├─► XREADGROUP GROUP analytics-group consumer-1
    │       COUNT 500  (reads pending events from stream)
    │
    ├─► batchInsertWithIdempotency()
    │       INSERT INTO click_analytics ... ON CONFLICT DO NOTHING
    │
    ├─► upsertStat(shortCode, date)
    │       UPDATE click_stats SET total_clicks = total_clicks + N
    │
    └─► XACK analytics:click-stream analytics-group {ids}
```

### Fault Tolerance

| Failure Scenario | Protection |
|---|---|
| Redis crash before XREADGROUP | Message stays in Stream → re-read on restart |
| Redis crash after XREADGROUP, before XACK | Message in **PEL** → recovered via ReadOffset.from("0-0") |
| Redis crash after XACK | Already written to PostgreSQL ✅ |
| Redis completely unavailable | Fallback to direct DB write — **0% data loss** |
| Duplicate event replay | ON CONFLICT DO NOTHING — idempotent insert |

---

## 📊 Performance Benchmarks (k6)

**Setup:** 10,000 requests · 20 Virtual Users · short-link redirect + click tracking

### With Redis (Normal Operation)

```
.\k6.exe run -e SHORT_CODE=mytest513 k6-10k-clicks.js
```

| Metric | Result |
|---|---|
| Throughput | **~2,312 req/s** |
| Avg latency | 6 ms |
| P95 latency | **17.58 ms** ✅ (threshold < 50ms) |
| Error rate | **0.00%** ✅ |
| Checks passed | 100% (20,000 / 20,000) |

### Without Redis (DB Fallback)

| Metric | Result |
|---|---|
| Throughput | **~555 req/s** |
| Avg latency | 7 ms |
| P95 latency | **101.43 ms** ⚠️ (exceeded threshold) |
| Error rate | **0.00%** ✅ |
| Checks passed | 100% (20,000 / 20,000) |

### Summary

| | Redis ON | Redis OFF |
|---|---|---|
| Throughput | ~2,312 RPS | ~555 RPS |
| P95 Latency | 17.58 ms | 101.43 ms |
| Error Rate | 0% | 0% |
| Slowdown | — | ~4.2× |

> Even when Redis is completely down, the system serves 100% of requests with 0% errors.
> The fallback degrades throughput (~4×) but ensures **zero data loss**.

---

## 🔐 Authentication Flow

```
Login
  ├─► Validate credentials
  ├─► Issue JWT Access Token   (stateless · 15min TTL)
  └─► Issue Refresh Token      (Redis · 7d TTL)
        Key:   refreshtoken:{token}
        Value: username
        Op:    GETDEL (atomic read + delete = rotation)

Token Refresh
  ├─► GETDEL refreshtoken:{oldToken} → username
  ├─► Issue new Access Token
  └─► Store new Refresh Token in Redis

Logout
  └─► DEL refreshtoken:{token}
```

---

## 🚀 Quick Start

### Prerequisites

- Docker + Docker Compose
- Java 17+
- Node.js 18+

### Run with Docker Compose

```bash
git clone https://github.com/VuVanTriTai/shorter_link.git
cd smart-link-shortener
cp .env.example .env
# Edit .env with your credentials
docker compose up -d
```

App: http://localhost:8080 · Frontend dev: http://localhost:5173

### Run locally

```bash
# Backend
./mvnw spring-boot:run

# Frontend
cd frontend && npm install && npm run dev
```

---

## 🔧 Environment Variables

See [.env.example](.env.example) for all required variables.

| Variable | Description |
|---|---|
| DB_URL | PostgreSQL JDBC URL |
| DB_USERNAME / DB_PASSWORD | Database credentials |
| REDIS_HOST / REDIS_PORT | Redis connection |
| JWT_SECRET | JWT signing secret (min 256-bit) |
| GOOGLE_CLIENT_ID | Google OAuth2 client ID |
| SAFE_BROWSING_API_KEY | Google Safe Browsing API key |
| APP_BASE_URL | Base URL for generating short links |

---

## 📄 License

MIT License
