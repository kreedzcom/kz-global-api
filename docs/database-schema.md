# Database Schema

PostgreSQL 17 in production. Flyway manages all migrations from `src/main/resources/db/migration/`.  
H2 (PostgreSQL compatibility mode) is used in tests — see `TestDatabase` in `src/test`.

---

## Entity-relationship overview

```
game_server ──┬──< map_record >── player
              │         │
plugin_version┘         ├── best_nub_record  (player × map)
                        ├── best_pro_record  (player × map)
                        └── world_record     (map × category)

map ──< map_minimum_time
map ──< map_record (map_name FK)
map ──< world_record (map_name FK)

game_server ──< event_log
game_server ──< replay_upload_session
```

---

## Tables

### `game_server` (V1)

Represents a registered CS 1.6 server.

| Column | Type | Notes |
|--------|------|-------|
| `id` | `SERIAL` PK | Auto-increment integer |
| `name` | `VARCHAR(255)` UNIQUE NOT NULL | Human-readable label |
| `access_key` | `BYTEA` UNIQUE NOT NULL | 16-byte random key used for WS authentication |
| `active` | `BOOLEAN` NOT NULL | `false` = deactivated (soft-delete); open WS sessions are also disconnected |
| `last_connected_at` | `TIMESTAMPTZ` | Updated on each successful WS auth |
| `created_at` | `TIMESTAMPTZ` NOT NULL | Set by DB default |

---

### `plugin_version` (V1)

Tracks published plugin builds.

| Column | Type | Notes |
|--------|------|-------|
| `id` | `SERIAL` PK | |
| `semver` | `VARCHAR(50)` NOT NULL | e.g. `"1.2.3"` |
| `checksum_linux` | `BYTEA` NOT NULL | Expected binary checksum for the Linux `.so` |
| `checksum_windows` | `BYTEA` NOT NULL | Expected binary checksum for the Windows `.dll` |
| `is_cutoff` | `BOOLEAN` NOT NULL | `true` = the API rejects connections from this version |
| `created_at` | `TIMESTAMPTZ` NOT NULL | |

The `HELLO` handler validates `plugin_version` + `plugin_checksum` (Linux) against this table.

---

### `player` (V1)

A Steam player seen on at least one server.

| Column | Type | Notes |
|--------|------|-------|
| `steamid` | `VARCHAR(32)` PK | Steam2 ID format, e.g. `STEAM_0:0:12345` |
| `last_nickname` | `VARCHAR(64)` NOT NULL | Updated on every `PLAYER_JOIN` |
| `ip_address` | `VARCHAR(45)` | Optional; last known IP |
| `first_seen_at` | `TIMESTAMPTZ` NOT NULL | |
| `last_seen_at` | `TIMESTAMPTZ` NOT NULL | |

`steamid` is a natural key (VARCHAR PK, not an auto-increment). Use `upsert` — not `insert` — when writing from Exposed lambdas (Kotlin column name shadows outer variables; always capture to a local `val` first).

---

### `map` (V1)

A map seen by the system.

| Column | Type | Notes |
|--------|------|-------|
| `name` | `VARCHAR(255)` PK | e.g. `kz_canyon` |
| `checksum` | `VARCHAR(64)` | Optional map file checksum |
| `type` | `VARCHAR(32)` | e.g. `kz`, `bhop` |
| `length` | `REAL` | Estimated length in units |
| `difficulty` | `INTEGER` | Difficulty tier |

All columns except `name` are nullable for forward compatibility. Maps are created lazily via `INSERT IGNORE` when a record is submitted.

---

### `map_minimum_time` (V1)

Anti-cheat minimum run time per map.

| Column | Type | Notes |
|--------|------|-------|
| `map_name` | `VARCHAR(255)` PK → `map(name)` | |
| `min_time_ms` | `BIGINT` NOT NULL | Submissions below this are `Rejected` |
| `updated_by` | `VARCHAR(255)` NOT NULL | Admin identifier |
| `updated_at` | `TIMESTAMPTZ` NOT NULL | |

---

### `event_log` (V1)

Append-only audit trail.

| Column | Type | Notes |
|--------|------|-------|
| `id` | `BIGSERIAL` PK | |
| `server_id` | `INTEGER` → `game_server(id)` | Nullable — admin actions have no server |
| `event_type` | `VARCHAR(64)` NOT NULL | e.g. `record_submitted`, `server_deactivated` |
| `payload` | `TEXT` NOT NULL | JSON string with event-specific detail |
| `created_at` | `TIMESTAMPTZ` NOT NULL | |

Indexed on `event_type` and `created_at`.

---

### `map_record` (V2)

A single completed run.

| Column | Type | Notes |
|--------|------|-------|
| `id` | `UUID` PK | UUIDv7 — time-ordered, assigned by the API |
| `server_id` | `INTEGER` NOT NULL → `game_server(id)` | Which server submitted this run |
| `player_steamid` | `VARCHAR(32)` NOT NULL → `player(steamid)` | |
| `map_name` | `VARCHAR(255)` NOT NULL → `map(name)` | |
| `time_ms` | `BIGINT` NOT NULL | Run time in milliseconds |
| `teleports` | `INTEGER` NOT NULL DEFAULT 0 | `0` = pro-eligible; `> 0` = nub only |
| `local_uid` | `VARCHAR(64)` UNIQUE NOT NULL | Server-generated idempotency key |
| `replay_r2_key` | `VARCHAR(255)` | R2 object key once replay is uploaded (`replays/{uuid}.krpz`) |
| `flagged` | `BOOLEAN` NOT NULL DEFAULT `false` | Anti-cheat review flag |
| `reviewed` | `BOOLEAN` NOT NULL DEFAULT `false` | Set when admin has reviewed the record |
| `plugin_version_id` | `INTEGER` NOT NULL → `plugin_version(id)` | Which plugin build submitted it |
| `created_at` | `TIMESTAMPTZ` NOT NULL | |

Indexes: `(player_steamid, map_name)`, `map_name`, `time_ms`, `flagged`.

---

### `best_nub_record` (V2)

Denormalised per-player best time in the **nub** category (any number of teleports).

| Column | Type | Notes |
|--------|------|-------|
| `player_steamid` | `VARCHAR(32)` → `player` | Composite PK |
| `map_name` | `VARCHAR(255)` → `map` | Composite PK |
| `record_id` | `UUID` → `map_record(id)` ON DELETE CASCADE | |

Upserted by `RecordService` whenever a player beats their own nub best.

---

### `best_pro_record` (V2)

Same structure as `best_nub_record` but for the **pro** category (zero teleports only).

---

### `world_record` (V2)

The single fastest run globally per map + category.

| Column | Type | Notes |
|--------|------|-------|
| `map_name` | `VARCHAR(255)` → `map` | Composite PK |
| `category` | `VARCHAR(3)` CHECK `IN ('nub','pro')` | Composite PK |
| `record_id` | `UUID` → `map_record(id)` ON DELETE CASCADE | |

Updated by `RecordService` when `best_pro_record` / `best_nub_record` is lower than the current WR.  
A `KzEvent.NewWorldRecord` is emitted after the transaction, which `BroadcastService` uses to push updated `MAP_INFO` to all live servers on that map.

---

### `replay_upload_session` (V3)

Tracks in-flight multi-chunk replay uploads (schema only — current state is held in memory by `ReplayService`).

| Column | Type | Notes |
|--------|------|-------|
| `local_uid` | `VARCHAR(64)` PK | Matches `map_record.local_uid` |
| `server_id` | `INTEGER` NOT NULL → `game_server(id)` | |
| `received_chunks` | `INTEGER` NOT NULL DEFAULT 0 | |
| `total_chunks` | `INTEGER` NOT NULL | |
| `started_at` | `TIMESTAMPTZ` NOT NULL | |

---

## Key design decisions

**UUIDv7 primary keys** — `map_record.id` uses time-ordered UUIDs (v7). This keeps B-tree inserts sequential (no random page splits), embeds creation time, and avoids auto-increment integer overflow at scale.

**Denormalised leaderboard tables** — `best_nub_record`, `best_pro_record`, and `world_record` are write-through caches updated inside the same transaction as `map_record` inserts. This makes leaderboard queries O(1) lookups instead of full aggregations.

**Soft deletes for servers** — `game_server.active = false` instead of `DELETE`. This preserves `map_record` foreign-key integrity and the audit trail.

**Nullable map metadata** — all columns in `map` except `name` are nullable so maps can be created lazily on first record submission without requiring any prior admin setup.
