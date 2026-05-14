# AGENTS.md — AI Agent Guide

This file tells AI coding agents how to work in this repository.

## What this project is

`kz-global-api` is the global backend for CS 1.6 KZ (Kreedz) game servers.
Game servers running the `cs16kz` plugin connect via WebSocket, submit player runs,
and receive real-time world record broadcasts. Replays are stored on Cloudflare R2.

Companion repo: [cs16kz](https://github.com/GameChaos/cs16kz) — the C++ AMX Mod X plugin.

## Tech stack

- **Kotlin** + **Ktor** (Netty engine)
- **Exposed** (typed SQL DSL, not DAO pattern)
- **Flyway** for migrations (SQL files in `src/main/resources/db/migration/`)
- **Koin** for DI
- **AWS SDK for Kotlin** for Cloudflare R2 (S3-compatible)
- **PostgreSQL** via HikariCP
- **Logback** for logging

If you add or replace a dependency, update this section.

## How to build and run

```bash
./gradlew build          # compile + test
./gradlew run            # run locally (needs env vars set)
./gradlew buildFatJar    # produce deployable jar
./gradlew test           # run tests only
```

Docker (full stack including postgres):
```bash
cp .env.example .env     # fill in values
docker compose up --build
```

## Code conventions

- Follow existing file and package structure under `kz/global/api/`
- One responsibility per file — keep files small and focused
- Use Kotlin `data class` for message payloads and DTOs
- Use `sealed class` for discriminated unions (e.g. incoming WS message types)
- Prefer `suspend fun` for any DB or I/O work
- All DB access goes through Exposed DSL inside a `transaction { }` block — never raw JDBC
- Config is read from `application.conf` (HOCON) via `environment.config` — never hardcode values
- No `println` or `System.out` — use SLF4J: `private val log = LoggerFactory.getLogger(ClassName::class.java)`
- New fields on DB tables must be nullable for backward compatibility (Flyway migration required)
- Do not add business logic directly in route handlers — delegate to domain services
- Do not use `GlobalScope` — use the `Application`-scoped coroutine scope

## Security and performance

Every feature or change must consider both:

**Security**
- Never trust input from game servers — validate all fields before processing
- Never expose internal error details in API responses
- Any new endpoint must have authentication — there are no public write endpoints
- New DB queries must use parameterized statements (Exposed DSL does this automatically — never interpolate strings into raw SQL)
- If a change touches authentication, token handling, or server whitelisting, flag it explicitly in the PR

**Performance**
- DB queries in WebSocket handlers must be fast — a slow query blocks that server's message processing
- Add indexes for any new column used in a `WHERE` or `ORDER BY`
- Avoid N+1 queries — batch or join instead
- Long-running work (replay upload, GC, broadcasting) must run in a background coroutine, not inline in a handler

## Testing

**Always write tests.** Every new feature or bug fix must include a test.

Every test must follow the **AAA pattern**: Arrange, Act, Assert — with a blank line separating each section. Do not add `// Arrange`, `// Act`, `// Assert` comments; the blank lines are enough.

```kotlin
@Test
fun `description of what is verified`() {
    val payload = AddRecordPayload(...)

    val result = service.submit(serverId, pluginVersionId, payload)

    assertIs<RecordResult.Accepted>(result)
}
```

- Prefer **integration tests** using H2 in-memory DB (PostgreSQL compatibility mode) over mocks for anything touching the DB — see `TestDatabase` in `src/test/kotlin/.../support/`
- Use `testApplication { }` for Ktor route tests — see `TestApplicationSetup`
- Use `mockk` only when a real implementation is not practical (e.g. R2 client)
- Run `./gradlew test` before committing — a build with failing tests is not acceptable
- **Kotlin lambda receiver shadowing**: inside `Table.insert {}` / `Table.upsert {}` lambdas, bare identifiers resolve to the Table's columns first. If your outer scope has a field with the same name as a column, capture it in a local variable before the lambda to avoid silent column self-references

## Documentation

The `docs/` folder contains the reference documentation for this project. **Keep it up to date** whenever you make a meaningful change to architecture, protocols, or schema.

| File                         | What it cover s                                                                                                      |
|------------------------------|----------------------------------------------------------------------------------------------------------------------|
| `docs/architecture.md`       | System overview, component diagram, request flows, record/replay pipelines, observability, configuration, deployment |
| `docs/websocket-protocol.md` | Every WS message type with full JSON schemas, binary replay frame layout, session lifecycle                          |
| `docs/database-schema.md`    | All tables, columns, constraints, indexes, FK relationships, design decisions                                        |
| `docs/plugin-integration.md` | Changes required in the `cs16kz` C++ plugin to integrate with this API                                               |

When to update:
- **New WS message type** → `websocket-protocol.md`
- **New or changed DB table/column** → `database-schema.md`
- **New service, package, or major flow change** → `architecture.md`
- **Plugin-facing protocol change** → `plugin-integration.md`

## Adding a new WebSocket message type

1. Add a new `data class` to `WsMessage.kt` inside the `Incoming` or `Outgoing` sealed class
2. Create a handler file in `ws/handlers/`
3. Register it in the `when` dispatch in `GameServerWsRoute.kt`
4. Add a Flyway migration if new DB columns are needed
5. Write a test

## Adding a new REST endpoint

1. Create a route function in `api/`
2. Register it in `Application.kt`'s routing block
3. Write a test

## What NOT to do

- Do not bypass Flyway — never alter the DB schema by hand
- Do not store secrets in code or `application.conf` — use env vars
- Do not commit a failing test
