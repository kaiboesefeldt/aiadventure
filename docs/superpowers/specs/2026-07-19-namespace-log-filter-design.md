# Namespace Log Filter Design

## Goal

At levels below WARN, emit only logs whose SLF4J logger name starts with
`de.aidadventure`. Logs from all other namespaces must remain suppressed even
when the server is started with `--log-level=TRACE`, `DEBUG`, or `INFO`.

## Configuration

Server startup configures SLF4J Simple before Ktor and its dependencies create
loggers. It sets the global default level to `warn` and sets the
`de.aidadventure` namespace level to the validated value from `--log-level`.

Consequently, both application and dependency logs at WARN or ERROR remain
visible. At TRACE, DEBUG, or INFO, only the application namespace is allowed
to emit records. The existing command-line syntax, case-insensitive validation,
and default of `info` remain unchanged.

## Implementation Boundary

The early SLF4J configuration is extracted into a small internal unit so its
two system properties can be verified without starting Ktor. `main` invokes
that unit immediately after parsing `ServerRuntimeConfig` and before creating
the embedded server.

No logger call sites, browser/server APIs, prompt construction, or llama.cpp
request flow change.

## Verification

Unit tests assert the configuration always sets the global default to `warn`
and maps the application namespace to each accepted requested level, including
the implicit `info` default. The repository gate remains `./gradlew check`.

## Project-Local Kotlin/JS Verification

The root build config pins the Kotlin/JS Node environment to `22.0.0` through
the current `NodeJsEnvSpec` API in `allprojects`. This avoids npm 11's
incompatibility with Kotlin's generated workspace lock while remaining entirely
project-local.

The Karma plugin hook prefers the original root-hoisted
`kotlin-web-helpers` path and falls back to the generated test-workspace path.
This supports npm's valid nested install layout without changing browser tests.
