# Contributing

## Getting started

1. Fork the repo and clone it locally
2. Copy `.env.example` to `.env` and fill in values (a local Postgres instance or Docker works)
3. Run `./gradlew build` to verify everything compiles and tests pass

## Making changes

- Keep PRs focused — one thing per PR
- Follow the code style already in the codebase (see [AGENTS.md](AGENTS.md) for conventions)
- Every change must include a test — integration test preferred, unit test where that's not practical
- Run `./gradlew test` before opening a PR
- Do not commit a failing build

## Database changes

All schema changes go through Flyway. Add a new migration file under `src/main/resources/db/migration/V{n}__{description}.sql`. Never modify an existing migration that has already been applied.

## Opening a PR

- Write a short description of what the change does and why
- Reference any related issue if applicable
- PRs without tests will not be merged

## License

By contributing, you agree that your contributions will be licensed under [AGPL-3.0](LICENSE).
