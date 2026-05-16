# Architecture

## Overview

`kz-global-api` is the backend for the **KZ Global Record System** for Counter-Strike 1.6.  
It provides a centralised, server-authoritative store for runs, leaderboards, and replay files for servers running the `cs16kz` plugin.

The service is written in **Kotlin / Ktor (Netty)** and exposes two distinct surfaces:

- **WebSocket endpoint** (`/ws/game`) — persistent connections from game servers; all real-time game events flow here.
- **Admin REST API** (`/admin/*`) — management of servers, plugin versions, map minimum times, and records.

---

## High-level component diagram

```
                        ┌─────────────────────────────────────────────────┐
                        │                  kz-global-api                   │
                        │                                                   │
  CS 1.6 game server    │  ┌─────────────────┐   ┌─────────────────────┐  │
  running cs16kz  ──WS──►  │ GameServerWsRoute│   │   Admin REST routes │◄──── HTTP (admin)
                        │  │  /ws/game        │   │   /admin/*          │  │
                        │  └────────┬─────────┘   └──────────┬──────────┘  │
                        │           │                         │             │
                        │  ┌────────▼─────────────────────────▼──────────┐ │
                        │  │               Domain layer                   │ │
                        │  │  RecordService · ReplayService · Broadcast   │ │
                        │  └────────────────────┬─────────────────────────┘ │
                        │                       │                            │
                        │  ┌────────────────────▼─────────────────────────┐ │
                        │  │  KzEventBus (SharedFlow)  ·  AuditLogger      │ │
                        │  └────────────────────┬─────────────────────────┘ │
                        │                       │                            │
                        │  ┌────────────────────▼─────────────────────────┐ │
                        │  │  PostgreSQL (Exposed ORM + Flyway)            │ │
                        │  └──────────────────────────────────────────────┘ │
                        │                                                   │
                        │  ┌───────────────────────────────────────────┐   │
                        │  │  Cloudflare R2 (AWS S3-compatible)         │   │
                        │  │  Replay files (.krpz, ZSTD-compressed)    │   │
                        │  └───────────────────────────────────────────┘   │
                        └─────────────────────────────────────────────────┘
```

---

## Technology stack

| Concern                 | Choice                                                                          |
|-------------------------|---------------------------------------------------------------------------------|
| Language                | Kotlin                                                                          |
| HTTP / WebSocket server | Ktor (Netty engine)                                                             |
| Serialization           | kotlinx.serialization (JSON)                                                    |
| ORM                     | Exposed (typed SQL DSL)                                                         |
| Migrations              | Flyway (`src/main/resources/db/migration/`)                                     |
| Connection pool         | HikariCP                                                                        |
| Database                | PostgreSQL in production; H2 in tests (PostgreSQL compatibility mode)           |
| Dependency injection    | Koin                                                                            |
| Object storage          | AWS SDK for Kotlin (S3-compatible client) against Cloudflare R2                 |
| Metrics                 | Micrometer with Prometheus text scrape endpoint                                 |
| Logging                 | Logback; optional Logstash JSON encoder for structured logs                     |
| Build                   | Gradle (Kotlin DSL; dependency coordinates live in `gradle/libs.versions.toml`) |
| JVM                     | Toolchain-managed Java release (see `build.gradle.kts`)                         |

---

## Request flows

### Game server connection

```
plugin              kz-global-api             PostgreSQL
  |                       |                       |
  |── WS upgrade ────────►|                       |
  |                       |── verify Bearer hex ─►|
  |                       |   (game_server.access_key)
  |── HELLO msg ─────────►|                       |
  |                       |── validate semver, checksum, cutoff
  |◄─ HELLO_ACK ──────────|   (map info if WRs exist)
  |                       |                       |
  |── PLAYER_JOIN ────────►|── upsert player ─────►|
  |◄─ (ack with is_banned)|                       |
  |                       |                       |
  |── ADD_RECORD ─────────►|── check min time ────►|
  |                       |── insert record ──────►|
  |                       |── update leaderboards ►|
  |◄─ RECORD_ACK ─────────|                       |
  |                       |── emit KzEvent ───────►BroadcastService
  |                       |                       |── push WR to
  |                       |                       |   all servers on map
  |── binary replay ──────►|── assemble chunks    |
  |                       |── store to R2          |
  |◄─ FILE_ACK ───────────|── update replay_r2_key►|
```

### Admin REST request

```
Admin client          kz-global-api             PostgreSQL
  |                       |                       |
  |── POST /admin/servers ►|                       |
  |   Authorization: Bearer <key>                  |
  |                       |── verify bearer token  |
  |                       |── generate 16-byte key |
  |                       |── INSERT game_server ─►|
  |◄─ 201 { id, name, accessKey }                  |
```

---

## Package structure

```
kz/global/api/
├── Application.kt              Entry point — wires Ktor plugins, Koin, routes, shutdown hook
├── config/
│   └── AppConfig.kt            HOCON → typed config data classes (DB, R2, admin key)
├── auth/
│   ├── AdminAuth.kt            Ktor bearer auth provider for admin routes
│   └── GameServerAuth.kt       Resolves game server from hex Bearer token
├── db/
│   ├── DatabaseFactory.kt      HikariCP + Flyway + Exposed connect
│   └── tables/                 Exposed table objects mirroring the Flyway schema
├── di/
│   ├── AppModule.kt            Koin singleton definitions
│   └── HandlersModule.kt       Koin definitions for WS message handlers
├── api/                        Admin REST route handlers
│   ├── ServersRoute.kt
│   ├── PluginVersionsRoute.kt
│   ├── RecordsRoute.kt
│   └── MapTimesRoute.kt
├── domain/
│   ├── records/RecordService.kt    Core run-submission logic
│   ├── replays/ReplayService.kt    Chunk reassembly, ZSTD validation, R2 upload
│   └── broadcast/BroadcastService.kt  Fan-out WR updates to connected servers
├── events/
│   ├── KzEventBus.kt           Internal SharedFlow event bus
│   └── AuditLogger.kt          Writes structured events to event_log
├── metrics/
│   └── KzMetrics.kt            Micrometer counters, timers, gauges
├── storage/
│   └── R2Client.kt             AWS SDK wrapper for Cloudflare R2
├── util/
│   ├── Hex.kt                  ByteArray ↔ hex string helpers
│   └── UuidV7.kt               Time-ordered UUID v7 generator
└── ws/
    ├── GameServerWsRoute.kt    WS upgrade, frame dispatch, session lifecycle
    ├── WsMessage.kt            Message type constants, envelope, all payload types
    ├── GameServerSession.kt    Per-server mutable state (map, connected players)
    ├── ConnectedServersRegistry.kt  Thread-safe registry of active sessions
    └── handlers/               One handler class per inbound message type
```

---

## Authentication

Two independent schemes coexist.

### Game server (WebSocket)

- The admin creates a server via `POST /admin/servers`. The API generates a cryptographically random 16-byte key stored as `BYTEA` in `game_server.access_key` and returns it hex-encoded (32 chars).
- The plugin includes this key as an HTTP `Authorization: Bearer <hex>` header on the WS upgrade request.
- `GameServerAuth.resolveGameServerToken` decodes the hex, queries for a matching active server, updates `last_connected_at`, and returns the server id.

### Admin API

- A single static bearer key is set via the `ADMIN_BEARER_KEY` environment variable.
- Ktor's built-in bearer auth provider validates every request to `/admin/*`.

---

## Internal event bus

`KzEventBus` wraps a `MutableSharedFlow` with no replay buffer (default `extraBufferCapacity = 64`).

Events emitted today:

| Event | Emitted by | Consumed by |
|-------|-----------|-------------|
| `KzEvent.NewRecord` | `RecordService` after a successful insert | — (available for future subscribers) |
| `KzEvent.NewWorldRecord` | `RecordService` when WR is beaten | `BroadcastService` → pushes `MAP_INFO` to all connected servers on that map |

Events are emitted **after** the database transaction commits to keep them consistent.

---

## Record submission pipeline

`RecordService.submit` runs inside a single `suspendTransaction`:

1. **Idempotency check** — if `local_uid` already exists, return `RecordResult.Duplicate` with the existing record id.
2. **Anti-cheat** — if `map_minimum_time` exists for the map and the submitted time is below it, return `RecordResult.Rejected`.
3. **Insert** — create `map_record` row with a UUIDv7 id.
4. **Leaderboard update** — if **`gochecks == 0`**: upsert `best_pro_record` and possibly `world_record` (`pro`); otherwise upsert `best_nub_record` and possibly `world_record` (`nub`). A run never updates both categories.
6. After the transaction: emit `KzEvent.NewRecord` and optionally `KzEvent.NewWorldRecord`; write audit log.

---

## Replay pipeline

Replays are uploaded as a sequence of binary WebSocket frames, each containing a 92-byte `ws_uchunk_header` (packed, matching the cs16kz plugin) followed by a data payload.

**Header layout** (little-endian):

| Offset | Size | Field |
|--------|------|-------|
| 0 | 64 B | `local_uid` (null-terminated UTF-8) |
| 64 | 8 B | unused |
| 72 | 4 B | CRC32 of data payload |
| 76 | 4 B | chunk index (0-based) |
| 80 | 4 B | total chunk count |
| 84 | 4 B | padding |

`ReplayService` validates each chunk's CRC32, assembles them in memory, verifies the ZSTD magic bytes (`0x28 0xB5 0x2F 0xFD`) of the concatenated payload, then uploads the result to R2 under the key `replays/{record_uuid}.krpz` and records the key in `map_record.replay_r2_key`.

---

## Observability

### Metrics (`/metrics`)

Prometheus scrape endpoint. Key metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `kz_records_submitted_total` | Counter | All accepted run submissions |
| `kz_world_records_total` | Counter | World record updates (by category) |
| `kz_replay_uploads_total` | Counter | Successful replay uploads |
| `kz_replay_upload_failures_total` | Counter | Failed replay uploads |
| `kz_ws_auth_failures_total` | Counter | Rejected WS connection attempts |
| `kz_flagged_records_total` | Counter | Records flagged for review |
| `kz_record_persist_duration_seconds` | Timer | Time spent in `RecordService.submit` |
| `kz_connected_servers` | Gauge | Live count of connected game servers |

### Structured logging

Set `LOG_FORMAT=json` to switch Logback to Logstash JSON output, ready for log aggregation pipelines (e.g. Loki). Default is human-readable pattern layout for local development.

### Audit log

Every significant mutation (server created, record deleted, plugin cutoff, etc.) is written to the `event_log` table by `AuditLogger` with `event_type` and a JSON payload string, allowing full traceability without an external system.

---

## Configuration

All configuration is injected through environment variables consumed by `application.conf` (HOCON).

| Variable               | Required | Description                                      |
|------------------------|----------|--------------------------------------------------|
| `DATABASE_URL`         | ✓        | JDBC URL (`jdbc:postgresql://host/db`)           |
| `POSTGRES_USER`        |          | DB user for Hikari (set with Docker Compose)     |
| `POSTGRES_PASSWORD`    |          | DB password for Hikari (set with Docker Compose) |
| `DATABASE_POOL_SIZE`   |          | HikariCP pool size (default 10)                  |
| `R2_ENDPOINT`          | ✓        | Cloudflare R2 S3-compatible endpoint URL         |
| `R2_ACCESS_KEY_ID`     | ✓        | R2 access key                                    |
| `R2_SECRET_ACCESS_KEY` | ✓        | R2 secret key                                    |
| `R2_BUCKET`            | ✓        | Bucket name for replay storage                   |
| `ADMIN_BEARER_KEY`     | ✓        | Static bearer token for admin API                |
| `PORT`                 |          | HTTP port (default 8080)                         |
| `LOG_FORMAT`           |          | Set to `json` for structured logging             |

---

## Deployment

The application is packaged as a fat JAR (`./gradlew buildFatJar`) and shipped in a Docker image; the base image tag is defined in the repository `Dockerfile`.

`docker-compose.yml` runs the API alongside PostgreSQL for local development; database image and tags are pinned there, not duplicated here.

For production, place a reverse proxy such as **Traefik** in front (see `kz-global-infra`) to handle TLS termination and routing.
