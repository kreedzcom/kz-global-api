# kz-global-api

[![CI](https://github.com/kreedzcom/kz-global-api/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/kreedzcom/kz-global-api/actions/workflows/ci.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=kreedzcom_kz-global-api&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=kreedzcom_kz-global-api)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=kreedzcom_kz-global-api&metric=coverage)](https://sonarcloud.io/summary/new_code?id=kreedzcom_kz-global-api)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=kreedzcom_kz-global-api&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=kreedzcom_kz-global-api)

Global record backend for CS 1.6 KZ (Kreedz). Game servers running the [cs16kz](https://github.com/nquinquenel/cs16kz) plugin connect via WebSocket to submit runs, fetch map records, and receive world record broadcasts.

## Getting started

```bash
cp .env.example .env
# fill in .env

docker compose up --build
```

Or run locally:

```bash
./gradlew run
```

API available at `http://localhost:8080`. Health check: `GET /health`.

Prometheus metrics: `GET /metrics` (requires `Authorization: Bearer <METRICS_BEARER_KEY>` when set — see `.env.example`).

## Security

Game servers authenticate with a per-server hex key on WebSocket upgrade; admins use `ADMIN_BEARER_KEY` on `/admin/*`. Optional controls include rate limits, replay size caps, player bans, per-server IP allowlists, and replay-required leaderboards — see [docs/architecture.md](docs/architecture.md#security) and `.env.example`.

## Development

```bash
./gradlew test        # run tests
./gradlew build       # compile + test
./gradlew buildFatJar # build deployable jar
```

See [AGENTS.md](AGENTS.md) for architecture, conventions, and how to add features.

## License

[AGPL-3.0](LICENSE)
