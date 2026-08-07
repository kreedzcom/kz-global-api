# cs16kz Plugin Integration Guide

Changes required in the `cs16kz` C++ plugin to work with `kz-global-api`.

## 1. Add `plugin_version` and `plugin_checksum` to `hello`

The API validates the plugin build on connect. Add two fields to the `hello` payload:

```json
{
  "msg_type": 1,
  "msg_id": 1,
  "data": {
    "plugin_version": "<semver>",
    "plugin_checksum": "deadbeefdeadbeefdeadbeefdeadbeef",
    "map_name": "kz_longjumps2"
  }
}
```

- `plugin_version` — read from `MODULE_VERSION`
- `plugin_checksum` — MD5 of the `.so`/`.dll` binary itself (compute at module load using the path from `dlinfo` / `GetModuleFileName`)

If the version/checksum is unknown or cutoff, the API closes the connection with a human-readable reason.

## 2. Add `gochecks` and `checkpoints` to `add_record`

Every finished run must include **`gochecks`** (go-check count) and **`checkpoints`** (aggregate checkpoint touches; no per-segment split times). If **`gochecks > 0`**, **`checkpoints` must be greater than zero**.

```json
{
  "msg_type": 8,
  "msg_id": 42,
  "data": {
    "steamid": "STEAM_0:0:12345",
    "map_name": "kz_longjumps2",
    "time_ms": 12345,
    "local_uid": "abc123...",
    "checkpoints": 12,
    "gochecks": 2
  }
}
```

- **`gochecks == 0`** — **pro** only ( **`checkpoints` may be zero** on linear maps with no intermediate checkpoints).
- **`gochecks > 0`** — **nub** only; **`checkpoints` must be positive**.

## 3. Handle `get_replay` (message type 9)

When the plugin wants the WR replay for a map (e.g. on map load if no local `.krpz` found):

**Send:**
```json
{ "msg_type": 9, "msg_id": 5, "data": { "map_name": "kz_longjumps2" } }
```

**Receive (msg_type 108):**
```json
{
  "msg_type": 108,
  "msg_id": 5,
  "data": {
    "url": "https://r2.../replays/...krpz",
    "local_uid": "0_00012345_steam_abc",
    "map_name": "kz_longjumps2"
  }
}
```

Download the file over **HTTPS only** from the presigned URL (1-hour expiry), verify zstd magic bytes, save as `kz_global/replays/{map_name}/{local_uid}.krpz`, then load for bot playback.

## 4. Handle `del_record_notify` (message type 109)

When an admin deletes or flags a record, the API broadcasts:

```json
{
  "msg_type": 109,
  "msg_id": 0,
  "data": {
    "record_id": "...",
    "map_name": "kz_longjumps2",
    "local_uid": "0_00012345_steam_abc"
  }
}
```

The plugin deletes `kz_global/replays/{map_name}/{local_uid}.krpz` (and `.krpr` if present) and resets the replay bot if it was playing that file.

## 5. Replay download on map load

After receiving `HelloAck` or `MapChange` response (`MAP_INFO`):
1. Check if a local `.krpz` exists for the current map (`kz_pb_find_fastest`). When the map has a pro WR, a nub-only local file is not sufficient — the plugin re-requests until a pro replay is cached or the API reports none available.
2. If not, send `GET_REPLAY` (9) to the API (deduped — one in-flight request per map).
3. On receiving `GET_REPLAY_ACK`, download, validate, save, and parse the replay.

## Message type constants

| Constant            | Value | Direction      |
|---------------------|-------|----------------|
| `GET_REPLAY`        | 9     | plugin → API   |
| `GET_REPLAY_ACK`    | 108   | API → plugin   |
| `DEL_RECORD_NOTIFY` | 109   | API → plugin   |

## Natives

- `kz_api_get_replay(mapname[])` — manually trigger a WR replay fetch for a map.
- `kz_api_del_record(mapname[], local_uid[])` — delete local replay files for a record (same as `DEL_RECORD_NOTIFY` handler).
