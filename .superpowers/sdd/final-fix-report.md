# Final Review Fix Report

## Corrected findings

- The Kotlin/JS development server is fixed to `http://localhost:8088`; the
  server CORS allow-list uses exactly that origin. A live server on port 8090
  returned `Access-Control-Allow-Origin: http://localhost:8088` for that
  origin and rejected `http://localhost:9999` with `403`.
- The server now binds `127.0.0.1` by default. `ADVENTURE_EXPOSE_LAN=true` is
  the explicit opt-in for `0.0.0.0`; invalid values fail fast. README and
  AGENTS document this contract.
- API calls receive `X-Request-ID`; gateway/model failures log only the
  request ID, operation, safe result code, and exception class. The server
  includes an SLF4J provider and never logs request/LLM content.
- The IndexedDB test opens a real browser database, saves and lists a scenario
  and session, closes it, reopens it, and reads the persisted data again.
- `OLLAMA_KEEP_ALIVE` accepts positive whole-number `h`, `m`, `s`, or bare
  seconds. Invalid, non-positive, and overflowing inputs fail with clear
  validation errors.

## Verification

- `./gradlew :server:test --tests 'de.aidadventure.server.ServerRuntimeConfigTest' --tests 'de.aidadventure.server.ollama.KeepAliveDurationTest' --tests 'de.aidadventure.server.ApplicationTest'` passed.
- `./gradlew -Pkotlin.daemon.jvmargs=-Xmx3g :web:test` passed, including the
  real browser IndexedDB integration test. The same heap setting is committed
  in `gradle.properties` for normal builds.
- `curl http://localhost:8088/` returned `200` while
  `browserDevelopmentRun` was active.
- A live server started with `PORT=8090` reported
  `Responding at http://127.0.0.1:8090`; CORS requests were verified as above.
