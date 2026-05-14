# WebSocket Protocol

## Connection

Game servers connect to `ws(s)://<host>/ws/game` with an HTTP `Authorization` header:

```
Authorization: Bearer <32-char hex access key>
```

The key is the one returned when the server was registered via `POST /admin/servers`.  
A missing or invalid key closes the WebSocket with Ktor close code **`1008` (`VIOLATED_POLICY`)** and a short reason string (for example `Invalid or inactive token`).

---

## Envelope

Every **text** frame (both inbound and outbound) is a JSON object with this shape:

```json
{
  "msg_type": <int>,
  "msg_id":   <int64, optional, default 0>,
  "data":     { ... }
}
```

`msg_id` is an opaque value the plugin may set to correlate responses; the API echoes it back.  
Binary frames are used exclusively for **replay chunk uploads** (see below).

---

## Message types

### Inbound (plugin → API)

| `msg_type` | Name | Description |
|------------|------|-------------|
| `1` | `HELLO` | First message after connection — declares plugin version, checksum, current map |
| `2` | `MAP_CHANGE` | Server changed map |
| `3` | `PLAYER_JOIN` | Player connected to the server |
| `4` | `PLAYER_LEAVE` | Player disconnected |
| `5` | `WANT_MAP_INFO` | Request current WR data for a map |
| `6` | `WANT_COURSE_TOP` | Request leaderboard for a map / category |
| `7` | `WANT_PLAYER_RECORDS` | Request a player's personal records on a map |
| `8` | `ADD_RECORD` | Submit a completed run |

### Outbound (API → plugin)

| `msg_type` | Name | Description |
|------------|------|-------------|
| `101` | `HELLO_ACK` | Connection accepted; includes heartbeat interval and optional map info |
| `102` | `MAP_INFO` | WR data for a map; also pushed to all servers on the same map when a world record changes (`msg_id` is `0`) |
| `103` | `COURSE_TOP` | Leaderboard entries |
| `104` | `PLAYER_RECORDS` | A player's personal times |
| `105` | `RECORD_ACK` | Confirmation of a submitted run |
| `106` | `FILE_ACK` | Confirmation of a replay upload |
| `199` | `ERROR` | Error response |

---

## Payload schemas

### `HELLO` (1)

```json
{
  "plugin_version": "<semver>",
  "plugin_checksum": "aabbccdd...",
  "map_name": "kz_canyon"
}
```

- `plugin_version` — semver string; must match an active row in `plugin_version`.
- `plugin_checksum` — hex-encoded MD5/SHA of the Linux plugin binary; must match `checksum_linux` for that version.
- If the plugin version is marked `is_cutoff = true` the connection is rejected.

### `HELLO_ACK` (101)

```json
{
  "heartbeat_interval": 30,
  "map_info": { ... }
}
```

`map_info` is included whenever the map exists in the API database (see `MAP_INFO` below). WR-related fields may be `null` if no world record exists yet for that category.

---

### `MAP_CHANGE` (2)

```json
{ "map_name": "kz_bhop_mix" }
```

### `MAP_INFO` / `WANT_MAP_INFO` (102 / 5)

Request:
```json
{ "map_name": "kz_canyon" }
```

Response:
```json
{
  "map_name": "kz_canyon",
  "wr_nub_steamid":  "STEAM_0:0:12345",
  "wr_nub_time_ms":  35420,
  "wr_pro_steamid":  "STEAM_0:0:67890",
  "wr_pro_time_ms":  28100
}
```

Fields are `null` when no world record exists for that category.

---

### `PLAYER_JOIN` (3)

```json
{
  "steamid":    "STEAM_0:0:12345",
  "nickname":   "xX_speedrunner_Xx",
  "ip_address": "198.51.100.10"
}
```

`ip_address` is optional. The API upserts the player row and responds with:

```json
{ "steamid": "STEAM_0:0:12345", "is_banned": false }
```

(`is_banned` is always `false` in the current implementation — ban logic is planned.)

### `PLAYER_LEAVE` (4)

```json
{ "steamid": "STEAM_0:0:12345" }
```

No response is sent.

---

### `WANT_COURSE_TOP` (6)

```json
{
  "map_name": "kz_canyon",
  "category": "pro",
  "limit":    10,
  "offset":   0
}
```

- `category`: `"nub"` (teleports allowed) or `"pro"` (no teleports). Default `"nub"`.
- `limit`: 1–100. `offset`: pagination start.

`COURSE_TOP` (103) response:

```json
{
  "map_name": "kz_canyon",
  "category": "pro",
  "entries": [
    { "rank": 1, "steamid": "STEAM_0:0:1", "nickname": "player1", "time_ms": 28100, "teleports": 0 },
    { "rank": 2, "steamid": "STEAM_0:0:2", "nickname": "player2", "time_ms": 30450, "teleports": 0 }
  ]
}
```

---

### `WANT_PLAYER_RECORDS` (7)

```json
{ "steamid": "STEAM_0:0:12345", "map_name": "kz_canyon" }
```

`PLAYER_RECORDS` (104) response:

```json
{
  "steamid": "STEAM_0:0:12345",
  "records": [
    { "map_name": "kz_canyon", "time_ms": 29500, "teleports": 0, "record_id": "<uuid>" },
    { "map_name": "kz_canyon", "time_ms": 31000, "teleports": 3, "record_id": "<uuid>" }
  ]
}
```

Only non-flagged records are returned.

---

### `ADD_RECORD` (8)

```json
{
  "steamid":   "STEAM_0:0:12345",
  "map_name":  "kz_canyon",
  "time_ms":   28950,
  "teleports": 0,
  "local_uid": "server-side-unique-string"
}
```

- `local_uid` is a server-generated string used for idempotency. Submitting the same `local_uid` twice returns the same `RECORD_ACK` both times.
- `teleports == 0` → the run is counted as both **nub** and **pro**.
- `teleports > 0` → the run is counted as **nub** only.

`RECORD_ACK` (105) response:

```json
{
  "id":        "01970000-0000-7000-0000-000000000000",
  "local_uid": "server-side-unique-string",
  "is_pb":     true
}
```

`id` is the global UUIDv7 assigned by the API.

---

### World record push (uses `MAP_INFO` / 102)

When a world record changes, the API pushes **`MAP_INFO` (`msg_type` 102)** with `msg_id` `0` to every connected game server currently on that map (same JSON shape as the `WANT_MAP_INFO` response). There is no separate `WR_BROADCAST` message type in the current implementation.

### `ERROR` (199)

```json
{ "message": "human-readable description" }
```

---

## Replay chunk upload (binary frames)

After a run is accepted with `RECORD_ACK`, the plugin may upload the replay as a sequence of binary frames. Each frame has this layout:

```
┌──────────── 92-byte ws_uchunk_header (packed, little-endian) ─────────────┐
│ local_uid[64] │ id u64 │ crc32 i32 │ chunk_index u64 │ chunk_total u64 │
└────────────────────────────────────────────────────────────────────────────┘
│  data (variable length)                                                      │
└────────────────────────────────────────────────────────────────────────────┘
```

- `local_uid` — null-terminated UTF-8, same value used in `ADD_RECORD`.
- `id` — legacy uint64 field from the plugin; ignored by the API.
- `crc32` — CRC32 checksum of the `data` portion only.
- `chunk_index` — 0-based chunk index (uint64 on the wire; must fit in a 32-bit range for the API).
- `chunk_total` — total number of chunks in this upload (uint64 on the wire; bounded server-side).

Once all chunks are received and CRC-validated, the API assembles them, verifies the ZSTD magic header (`0x28 0xB5 0x2F 0xFD`), and stores the result in R2.

`FILE_ACK` (106) response:

```json
{ "local_uid": "server-side-unique-string", "status": true }
```

`status: false` indicates the upload was rejected (bad CRC, missing ZSTD magic, or unknown `local_uid`).

---

## Session lifecycle

```
WS connect
  └─ auth check (Bearer token) → resolves game server id
  └─ register in ConnectedServersRegistry (session bound to that id)
  └─ HELLO expected as first application message
       └─ plugin version / checksum validation
       └─ HELLO_ACK (binds plugin_version_id for later ADD_RECORD)
  └─ normal message exchange
  └─ server disconnect / error
       └─ unregister from ConnectedServersRegistry
       └─ graceful close on API shutdown via ConnectedServersRegistry.closeAll()
```

The API does **not** enforce a HELLO timeout; if the plugin never sends `HELLO`, the TCP/WebSocket session stays open and the game server id from the token remains registered, but **`ADD_RECORD` is rejected** until a valid `HELLO` has been processed (plugin version id is unset).
