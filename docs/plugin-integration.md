# cs16kz Plugin Integration Guide

This guide describes the `cs16kz` wire contract and how to register a release with `kz-global-api`.

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

- `plugin_version` is the exact embedded `MODULE_VERSION` string.
- `plugin_checksum` is the embedded `MODULE_CHECKSUM`, a 32-hex-character fingerprint of the native module source files.
- For packaged releases, copy `plugin_version`, `checksum_linux`, and `checksum_windows` from `kz-hello.txt` into the corresponding fields when registering the build.

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

On `FILE_ACK` with `status: true`, retire the upload. If `playable` is `false`, discard the local upload copy without adding it to the replay bot cache. Upgrade the API and module together to distinguish retained evidence from playable replays.

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
    "record_id": "01970000-0000-7000-8000-000000000000",
    "map_name": "kz_longjumps2"
  }
}
```

Download the file over **HTTPS only** from the presigned URL (1-hour expiry), verify zstd magic bytes, save as `kz_global/replays/{map_name}/{local_uid}_{record_id}.krpz`, then load for bot playback.

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

The plugin deletes `kz_global/replays/{map_name}/{local_uid}_{record_id}.krpz`, invalidates pending downloads for the deleted record, and resets the replay bot if it was playing that file. Pass the UUID to `kz_api_del_record` to delete a downloaded replay through Pawn.

## 5. Replay download on map load

After receiving `HelloAck` or `MapChange` response (`MAP_INFO`):
1. Check if a local `.krpz` exists for the current map (`kz_pb_find_fastest`). When the map has a pro WR, a nub-only local file is not sufficient — the plugin re-requests until a pro replay is cached or the API reports none available.
2. If not, send `GET_REPLAY` (9) to the API (deduped — one in-flight request per map).
3. On receiving `GET_REPLAY_ACK`, download, validate, save, and parse the replay.

The API also supports queries 6 (`WANT_COURSE_TOP`) and 7 (`WANT_PLAYER_RECORDS`). The current Pawn interface exposes map details and replay requests.

## Message type constants

| Constant            | Value | Direction      |
|---------------------|-------|----------------|
| `GET_REPLAY`        | 9     | plugin → API   |
| `GET_REPLAY_ACK`    | 108   | API → plugin   |
| `DEL_RECORD_NOTIFY` | 109   | API → plugin   |

## Natives

- `kz_api_get_replay(mapname[])` — manually trigger a WR replay fetch for a map.
- `kz_api_del_record(mapname[], local_uid[])` — delete local replay files for a record (same as `DEL_RECORD_NOTIFY` handler).
