# Architecture

## Overview

`kz-global-api` is the backend for the **KZ Global Record System** for Counter-Strike 1.6.  
It provides a centralised, server-authoritative store for runs, leaderboards, and replay files for servers running the `cs16kz` plugin.

The service is written in **Kotlin / Ktor (Netty)** and exposes two distinct surfaces:

- **WebSocket endpoint** (`/ws/game`) — persistent connections from game servers; all real-time game events flow here.
- **Admin REST API** (`/admin/*`) — management of servers, plugin versions, map minimum times, records, and player bans.

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
  |── ADD_RECORD ─────────►|── validate payload ──►|
  |                       |── check min time ────►|
  |                       |── insert record ──────►|
  |                       |── update leaderboards ►|  (or defer if replay-required)
  |◄─ RECORD_ACK ─────────|                       |
  |                       |── emit KzEvent ───────►BroadcastService
  |                       |                       |── push WR to
  |                       |                       |   all servers on map
  |── binary replay ──────►|── rate-limit bytes   |
  |                       |── assemble chunks    |
  |                       |── store to R2          |
  |◄─ FILE_ACK ───────────|── update replay_r2_key►|
  |                       |── finalize leaderboard ►|  (if was pending)
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
│   ├── AppConfig.kt            HOCON → typed config data classes (DB, R2, admin, security)
│   └── SecurityConfig.kt       Rate limits, replay caps, metrics auth, replay-required mode
├── auth/
│   ├── AdminAuth.kt            Ktor bearer auth provider for admin routes
│   └── GameServerAuth.kt       Resolves game server from hex Bearer token + IP allowlist
├── security/
│   ├── WsPayloadValidator.kt   Steam ID, map name, time, local_uid validation
│   ├── FixedWindowRateLimiter.kt  Per-key request and byte budgets
│   ├── WsRateLimiters.kt       WS upgrade, ADD_RECORD, reads, replay throughput
│   └── IpAllowlist.kt          Optional per-server source IP restriction
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
│   ├── MapTimesRoute.kt
│   └── PlayersRoute.kt         Ban / unban players
├── domain/
│   ├── records/RecordService.kt    Core run-submission logic
│   ├── replays/ReplayService.kt    Chunk reassembly, ZSTD validation, R2 upload
│   ├── players/PlayerBanService.kt Player ban lookups and updates
│   └── broadcast/BroadcastService.kt  Fan-out WR updates to connected servers
├── jobs/
│   └── EventLogRetentionJob.kt Purges old audit log rows on a schedule
├── events/
│   ├── KzEventBus.kt           Internal SharedFlow event bus
│   └── AuditLogger.kt          Writes structured events to event_log
├── metrics/
│   └── KzMetrics.kt            Micrometer counters, timers, gauges
├── storage/
│   └── R2Client.kt             AWS SDK wrapper for Cloudflare R2
├── util/
│   ├── Hex.kt                  ByteArray ↔ hex string helpers
│   ├── UuidV7.kt               Time-ordered UUID v7 generator
│   └── ClientIp.kt             Client IP from X-Forwarded-For or socket
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
- `GameServerAuth.resolveGameServerToken` decodes the hex, queries for a matching active server, checks optional `game_server.allowed_ips` against the client IP (from `X-Forwarded-For` when behind Traefik), updates `last_connected_at`, and returns the server id.
- WS upgrades are rate-limited per client IP before token validation.

### Admin API

- A single static bearer key is set via the `ADMIN_BEARER_KEY` environment variable.
- Ktor's bearer auth provider validates every request to `/admin/*` using constant-time token comparison.

---

## Security

Application-layer abuse controls (all tunable under `security.*` in `application.conf`):

| Control | Purpose |
|---------|---------|
| **WS payload validation** | Steam ID format, map name charset/length, positive bounded `time_ms`, `local_uid` format |
| **Rate limits** | Per-IP WS upgrades; per-server `ADD_RECORD`/min; per-server read-query QPS; replay bytes/sec |
| **WebSocket `maxFrameSize`** | Caps size of a single binary/text frame (default 2 MiB) |
| **Replay assembly caps** | Max assembled replay bytes (default 100 MiB), max concurrent in-flight uploads per server, TTL eviction of stale partial uploads |
| **Replay binding** | In-memory upload state and R2 store require matching `server_id` + `local_uid` |
| **Player bans** | `player.is_banned` enforced on `PLAYER_JOIN` and `ADD_RECORD`; admin `PATCH /admin/players/{steamid}/ban` |
| **Replay-required leaderboards** | When `REQUIRE_REPLAY_FOR_LEADERBOARD=true`, PB/WR updates and WR broadcasts wait until replay is stored (`leaderboard_pending` on `map_record`) |
| **WR improvement ratio** | Optional `MAX_WR_IMPROVEMENT_RATIO` rejects runs that beat the WR by more than a configured fraction |
| **Metrics auth** | `GET /metrics` requires `METRICS_BEARER_KEY` when set (Prometheus scrapes with bearer token in production) |
| **Event log retention** | Background job deletes `event_log` rows older than configured days |

Edge rate limiting and TLS are handled by Traefik / Cloudflare in production (`kz-global-infra`).

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

1. **Ban check** — reject if `player.is_banned`.
2. **WR improvement ratio** — if `MAX_WR_IMPROVEMENT_RATIO` is set and a WR exists, reject impossibly large improvements vs the current WR time.
3. **Idempotency check** — if `local_uid` already exists, return `RecordResult.Duplicate` with the existing record id.
4. **Anti-cheat** — if `map_minimum_time` exists for the map and the submitted time is below it, return `RecordResult.Rejected`.
5. **Insert** — create `map_record` row with a UUIDv7 id; set `leaderboard_pending` when replay-required mode is enabled.
6. **Leaderboard update** — unless deferred: if **`gochecks == 0`**, upsert `best_pro_record` and possibly `world_record` (`pro`); otherwise upsert `best_nub_record` and possibly `world_record` (`nub`). A run never updates both categories.
7. After the transaction (or after replay store when pending): emit `KzEvent.NewRecord` and optionally `KzEvent.NewWorldRecord`; write audit log.

`RecordService.finalizePendingLeaderboard(localUid)` runs the deferred leaderboard step after a successful R2 upload.

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

`ReplayService` validates each chunk's CRC32, assembles them in memory (with per-server concurrency, TTL, and max-byte caps), verifies the ZSTD magic bytes (`0x28 0xB5 0x2F 0xFD`) of the concatenated payload, then uploads the result to R2 under the key `replays/{record_uuid}.krpz` and records the key in `map_record.replay_r2_key`. Each in-flight upload is bound to the `server_id` that started it.

---

## Observability

### Metrics (`/metrics`)

Prometheus scrape endpoint. When `METRICS_BEARER_KEY` is set, requests must include `Authorization: Bearer <key>`. In production, only Prometheus on the internal Docker network should reach this path.

Key metrics:

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

Every significant mutation (server created, record deleted, plugin cutoff, etc.) is written to the `event_log` table by `AuditLogger` with `event_type` and a JSON payload string, allowing full traceability without an external system. `EventLogRetentionJob` purges rows older than `security.eventLogRetentionDays` (default 90).

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
| `METRICS_BEARER_KEY`   |          | Bearer token for `GET /metrics` (recommended in prod) |
| `REQUIRE_REPLAY_FOR_LEADERBOARD` | | Set `true` to defer PB/WR until replay is stored |
| `MAX_WR_IMPROVEMENT_RATIO` |      | Optional `0.0`–`1.0`; max fractional WR improvement per run |
| `PORT`                 |          | HTTP port (default 8080)                         |
| `LOG_FORMAT`           |          | Set to `json` for structured logging             |

Additional tunables (`security.maxReplayBytes`, rate limits, etc.) have defaults in `application.conf` and are documented in `.env.example` comments where exposed as env vars.

---

## Deployment

The application is packaged as a fat JAR (`./gradlew buildFatJar`) and shipped in a Docker image; the base image tag is defined in the repository `Dockerfile`.

`docker-compose.yml` runs the API alongside PostgreSQL for local development; database image and tags are pinned there, not duplicated here.

For production, place a reverse proxy such as **Traefik** in front (see `kz-global-infra`) to handle TLS termination and routing.
