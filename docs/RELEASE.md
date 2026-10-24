# Release process

This document defines the process to cut a release of the Storage Analyzer
library. It is intentionally boring: every step is checkable.

> **Current status:** no version has been tagged or published yet. The
> pipeline below is configured and dry-run locally; the first tag `v0.1.0`
> creates the first Maven Central release. Until then, consume the library
> through a composite build or `publishToMavenLocal` (see
> [COMPATIBILITY.md](COMPATIBILITY.md#release-status)).

## Versioning

- SemVer: `MAJOR.MINOR.PATCH` (starting at `0.1.0` for the first public
  snapshot).
- Release versions are **derived from the git tag**: a tag `v1.2.3` publishes
  version `1.2.3` (the CI strips the `v` prefix and passes it to the build as
  `-PpublishVersion`). Local development without a property uses `0.1.0`.
- The version is set once for all library modules in the root
  `build.gradle.kts` (`allprojects { version = … }`).

## Maven Central vs local development

| | Coordinates | Where it comes from |
|---|---|---|
| Published release | `io.github.mohamed-dev-ui:storageanalyzer-<module>:<version>` | Maven Central, via tag push |
| Composite build (dev) | same group/id via `dependencySubstitution` | this repo, checked out |
| Local smoke test | `./gradlew publishToMavenLocal` | your `~/.m2` (unsigned, no credentials needed) |

Artifact map: `:core` → `storageanalyzer-core`,
`:database-api` → `storageanalyzer-database-api`, `:sqlite` →
`storageanalyzer-sqlite`, `:filesystem` → `storageanalyzer-filesystem`,
`:history` → `storageanalyzer-history`, `:reactive` →
`storageanalyzer-reactive`, `:room` → `storageanalyzer-room`, `:testing` →
`storageanalyzer-testing`. `:app` and `:example` are never published.
Java/Kotlin package names stay `com.mohamed.storageanalyzer.*` regardless of
the Maven group.

---

## One-time setup (repo owner; do once, ever)

1. **Central Portal account** — sign in at <https://central.sonatype.com>
   **with your GitHub account** (`Mohamed-Dev-UI`). With GitHub sign-up the
   portal normally **auto-verifies your namespace** `io.github.mohamed-dev-ui`
   — check it under *Account → View Namespaces*; it must show **Verified**.
   If it is missing: send Central Support an email as described in the
   [namespace docs](https://central.sonatype.org/register/namespace/).
   (Manual fallback without GitHub sign-up: *Add Namespace* →
   `io.github.mohamed-dev-ui` → *Verify* → create a public GitHub repository
   named after the verification key, wait for Verified, delete the repo.)
2. **User (publishing) token** — *Account → Generate User Token* on the
   portal. The shown **username** and **password** are the publishing
   credentials (NOT your login). Needed for secret #1/#2 below.
3. **GPG signing key** — Maven Central rejects unsigned artifacts.
   On any machine with `gpg` (Git Bash, `gpg4win`, `gnupg`):

   ```bash
   gpg --full-generate-key            # RSA 4096, makes validators happy; set a passphrase you remember (or none)
   gpg --list-keys --keyid-format LONG        # copy the 40-hex fingerprint
   gpg --export-secret-keys --armor <LAST 8 HEX CHARS OR FINGERPRINT>   # prints "-----BEGIN PGP PRIVATE KEY BLOCK-----"
   # distribute the public key so validators can check signatures:
   gpg --keyserver keys.openpgp.org --send-keys <FINGERPRINT>
   gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>
   ```

   Save the entire armored private key block for secret #3 below.

## GitHub actions secrets (Settings → Secrets and variables → Actions → New repository secret)

| Secret name               | Value                                                                                                                                                                                              |
|---------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `MAVEN_CENTRAL_USERNAME`  | Portal user-token **username** (step 2 above)                                                                                                                                                        |
| `MAVEN_CENTRAL_PASSWORD`  | Portal user-token **password** (step 2 above)                                                                                                                                                        |
| `GPG_PRIVATE_KEY`         | The full ASCII-armored private key from step 3 (multi-line is fine; GitHub secrets support newlines)                                                                                                  |
| `GPG_KEY_ID`              | (optional) the last 8 hex chars of the key fingerprint — leave empty if the private key contains only one key                                                                                         |
| `GPG_PASSPHRASE`          | (optional) the key's passphrase — leave empty if your key has none                                                                                                                                    |

They are consumed by the workflow as Gradle properties via the
`ORG_GRADLE_PROJECT_*` environment mapping (see
`.github/workflows/publish.yml`); nothing is checked into the repository.

---

## Cutting a release

1. **Pre-release checklist** below must pass.
2. Test the release **locally without credentials** (unsigned):

   ```bash
   ./gradlew publishToMavenLocal      # all eight library artifacts into ~/.m2, with POM + sources + javadoc jars
   ```

3. Commit everything, move the `[Unreleased]` entries in `CHANGELOG.md` into
   a versioned section with today's date (Keep a Changelog format), then tag
   and push:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

4. GitHub Actions (`Publish to Maven Central`) runs on the tag and:
   - builds all eight library artifacts (jar + `-sources` + `-javadoc` via Dokka),
   - signs everything with the GPG key,
   - uploads to the Central Portal,
   - **waits for deployment validation and releases automatically**.
5. Publication to Maven Central search indexes completes 10–30 minutes after
   validation. Check <https://central.sonatype.com/search?q=storageanalyzer>.
   Manual re-runs / extra uploads: the workflow also has a
   **workflow_dispatch** trigger with an explicit `version` input.

## Pre-release checklist (all phases)

1. **Tests green, all modules**
   ```bash
   ./gradlew build
   ```
   - Expect: 134 JVM tests, 0 failures (39 core, 15 sqlite, 15 filesystem,
     14 history, 8 reactive, 9 room, 15 example executed, 19 app unit) plus
     3 property-gated benchmark probes skipped by default.
2. **Android showcase green** (the demo app is part of the repository and
   must keep compiling against the library):
   ```bash
   ./gradlew :app:assembleDebug
   # with an emulator/device attached:
   ./gradlew :app:connectedDebugAndroidTest
   ```
   Expect: APK builds and the instrumented smoke test passes.
3. **Benchmark probe re-run** (release owner, on their documented hardware):
   ```bash
   ./gradlew :example:test -PwithBenchmarks=true --rerun-tasks
   ```
   Paste fresh numbers into docs/PERFORMANCE.md (keep the old table as a
   historical entry if hardware changed).
4. **Docs in sync**:
   - README module map matches `settings.gradle.kts`.
   - README/USAGE consumption snippets use the coordinates of the version
     being released.
   - `docs/README.md` and `docs/API.md` links resolve (no anchors to moved
     internal records).
   - `docs/MEASUREMENT_ACCURACY.md` reflects any new `MeasurementMethod` /
     accuracy decisions.
   - `docs/ACCOUNTING.md` policy table covers all shipped variants.
   - `docs/COMPATIBILITY.md` engine matrix lists the shipped drivers.
   - `docs/LIMITATIONS.md` deferred-module table matches reality (no stub
     modules in the build).
   - `CHANGELOG.md` has an entry for the version being released.
5. **Public API review**: every public symbol added since the last tag is
   documented (KDoc) and its stability is declared (frozen / experimental).
6. **Format stability**: any change to `SnapshotFormat` requires a new
   versioned header line; old readers must be kept reading old sections
   (strict decode, loud on unknown fields — versioned evolution plan in
   `serialization` module roadmap).
7. **Non-destructive spot check**: grep for `executeUpdate`, `INSERT.*INTO`,
   `DELETE`, `DROP`, `PRAGMA .*=` writes in providers — providers must
   contain no state-changing statements; the test suite embeds
   non-destructive assertions.

## Changelog

`CHANGELOG.md` at the repo root follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Move `[Unreleased]`
entries into a dated version section when tagging:

```markdown
## [x.y.z] — YYYY-MM-DD
### Added / Changed / Fixed / Known limitations
- ...
```

## Post-release

- Watch the workflow run; on `FAILED` validation, the plugin prints the
  exact rule violations (fix, delete nothing on Central — versions are
  immutable — and re-tag with a new patch version).
- Verify the artifacts on <https://central.sonatype.com> and their POMs
  (name, description, MIT license, developer, SCM links).
- Update `docs/PERFORMANCE.md` environment table with the hardware used.
- Record the test/benchmark summary in the release tracker (issue tracker).

## Rollback policy

A broken release (test regression, benchmark regression > 30% on same
hardware, or an accuracy-model violation discovered post-tag) is fixed by
patch release. Published versions are immutable on Maven Central: when a
version must be pulled, use the Central Portal "Drop/Retract" support
request for the deployment and go straight to a fixed patch version; tags
are never re-used or force-moved.
