# Security policy

## Supported versions

Storage Analyzer `0.1.0` is the first published release. Security fixes target
the latest `0.x` minor line.

| Version | Supported |
|---------|-----------|
| `0.1.x` | Yes       |
| Older   | None published yet |

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub Security Advisories:

<https://github.com/Mohamed-Dev-UI/StorageAnalyzer/security/advisories/new>

Do **not** open a public issue for a suspected vulnerability. Include:

- affected version or commit,
- a minimal reproduction (database, schema bundle or snapshot if relevant),
- impact and the environment (OS, JDK, provider module), and
- whether the issue is exploitable with attacker-controlled input.

We aim to acknowledge reports within seven days and will coordinate a fix and
disclosure timeline with you. Credit is given in the advisory unless you ask
otherwise.

## Scope and design

Storage Analyzer is a local, read-only analytics library:

- It performs **no network access** and collects **no telemetry**.
- Built-in providers open storage **read-only**; SQLite connections additionally
  set `PRAGMA query_only`. The library never writes to analyzed databases.
- Snapshot files and Room schema bundles are **untrusted input**: decoding is
  strict and fails loudly rather than silently skipping records. Treat a
  parse failure as a data-integrity problem, not as an instruction to retry
  with a permissive parser.
- Database paths and schema identifiers are data, not code. SQLite identifiers
  are percent-encoded for stable identities; the library does not interpolate
  schema names into privileged statements without the provider's own escaping.
- The SQLite provider depends on `org.xerial:sqlite-jdbc`, which ships native
  binaries. Keep the driver current and review its advisories independently.

The library is not a sandbox. Do not point it at untrusted storage with
elevated privileges, and keep dependencies patched in your application.
