# kz-global-api

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

## Development

```bash
./gradlew test        # run tests
./gradlew build       # compile + test
./gradlew buildFatJar # build deployable jar
```

See [AGENTS.md](AGENTS.md) for architecture, conventions, and how to add features.

## License

[AGPL-3.0](LICENSE)
