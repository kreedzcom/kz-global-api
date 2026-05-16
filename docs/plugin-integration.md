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

## 3. Handle `get_replay` (new message type 9) — **not implemented in the API yet**

> **Note:** The server does not expose handlers or `MsgType` entries for these messages today. Treat this section as the **intended** plugin-side contract for a future API release.

When the plugin wants the WR replay for a map (e.g. on map load if no local `.krpz` found):

**Send:**
```json
{ "msg_type": 9, "msg_id": 5, "data": { "map_name": "kz_longjumps2" } }
```

**Receive (msg_type 108):**
```json
{ "msg_type": 108, "msg_id": 5, "data": { "url": "https://r2.../replays/...krpz" } }
```

Download the file directly from the URL using the existing HTTP client, save as the local `.krpz` path, then load it for bot playback.

## 4. Handle `del_record` (new message type 10) — **not implemented in the API yet**

> Same status as §3: no server handler exists yet.

When an admin deletes or invalidates a record, the API broadcasts:

```json
{ "msg_type": 109, "msg_id": 0, "data": { "record_id": "...", "map_name": "kz_longjumps2" } }
```

The plugin should delete `maps/kz_longjumps2.krpz` from disk and reset the replay bot if it was currently playing that replay.

## 5. Replay download on map load — **blocked until §3–4 are implemented**

After receiving `HelloAck` or `MapChange` response:
1. Check if a local `.krpz` exists for the current map.
2. If not, send `get_replay` to the API.
3. On receiving the URL, download and save the file.

## New message type constants

| Constant            | Value | Direction      |
|---------------------|-------|----------------|
| `GET_REPLAY`        | 9     | plugin → API   |
| `DEL_RECORD`        | 10    | plugin → API   |
| `GET_REPLAY_ACK`    | 108   | API → plugin   |
| `DEL_RECORD_NOTIFY` | 109   | API → plugin   |
