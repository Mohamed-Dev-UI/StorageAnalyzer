# Contributing

Thanks for helping improve Storage Analyzer. This project values honest
measurement over convenient guesses; contributions are reviewed against that
principle first.

## Before you start

- Read [docs/README.md](docs/README.md) for the documentation map and
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the module boundaries.
- The library is pre-1.0: breaking changes are allowed, but they must be
  deliberate, documented in [CHANGELOG.md](CHANGELOG.md), and reflected in the
  docs and tests.
- For a large change, open an issue describing the problem and intended API
  before writing code.

## Development setup

Requirements:

- **JDK 17** (Gradle toolchain; the build uses Java 17 bytecode).
- **Android SDK platform 37** only if you build `:app`. Pure-JVM modules do
  not need it.

```bash
./gradlew build                     # all JVM modules, tests and the Android app
./gradlew :core:test :filesystem:test :sqlite:test :history:test :reactive:test :room:test :app:testDebugUnitTest
./gradlew :example:run              # runnable JVM demo
./gradlew :app:assembleDebug        # Android showcase APK
```

Android checks (`:app:connectedDebugAndroidTest`) need a running emulator or
device. Benchmarks are opt-in:

```bash
./gradlew :example:test -PwithBenchmarks=true --rerun-tasks
```

## What makes a change acceptable

1. **Honesty invariants hold.** Every requested metric is answered as
   `MEASURED`, `UNAVAILABLE` (with a reason) or `NOT_APPLICABLE` — never a
   silent zero. Budget-limited runs must stay `PARTIAL` and explain why.
2. **Providers stay read-only.** Do not add writes, schema changes or
   transaction-state mutations to analysis paths.
3. **Storage is counted once.** New resources that can alias existing ones
   must declare `alsoDiscoverableBy`/`decomposes` so the global inventory does
   not double count.
4. **Tests cover the semantics**, not just the happy path: cancellation,
   budgets, unreadable files, corrupt inputs and hostile identifiers are
   expected test cases. Use `:testing` (`RecordingContext`, `TempDir`,
   `testIdentity`) and follow `FilesystemProviderTest` as the template.
5. **Dependencies stay boring.** `:core` remains dependency-free; `:room`
   must not acquire androidx dependencies; new dependencies need a clear
   justification in the pull request.
6. **Docs and examples stay executable.** Every scenario in
   [docs/USAGE.md](docs/USAGE.md) has a function in
   `example/src/main/kotlin/com/mohamed/storageanalyzer/example/UsageExamples.kt`
   asserted by `UsageExamplesTest`. Update both together; documentation rot is
   a test failure.

## Pull requests

- Keep changes focused; separate refactors from behavior changes.
- Add or update tests and the relevant docs.
- Add a [CHANGELOG.md](CHANGELOG.md) entry under `Unreleased`.
- Run `./gradlew build` before opening the PR and describe the commands you
  ran. Include emulator details when touching `:app`.
- Public KDoc is part of the API: document new public types and members,
  including their accuracy and failure semantics.
- Use clear imperative commit messages that explain **why** the change is
  needed.

## Code style

- Official Kotlin style, 4-space indentation, no wildcard imports.
- Prefer explicit, small APIs over abstractions that exist only for
  hypothetical consumers.
- Validation belongs at API boundaries (constructors, parsers); internal code
  can trust its own invariants.
- Do not add comments that restate the code; explain non-obvious constraints
  and honesty decisions.

## Security

Do not report security issues in public issues or pull requests. See
[SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contributions are licensed under the
project's [MIT License](LICENSE).
