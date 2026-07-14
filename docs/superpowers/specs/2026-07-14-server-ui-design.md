# Server-Delivered UI Design

## Goal

Start the complete local application with `./gradlew :server:run`.  Ktor serves
both the existing JSON/SSE API and the compiled browser application from
`127.0.0.1:8080`; no browser development server is part of the normal workflow.

## Approach

The `web` module continues to compile its Kotlin/JS React application with the
existing production Webpack task.  The `server` module makes that task a build
dependency, copies its generated distribution into the server's processed
resources, and therefore includes the browser files in the server runtime
classpath.  This keeps the runnable server artifact self-contained and avoids a
runtime dependency on a sibling build directory or a second process.

Ktor serves those classpath resources at `/`.  The existing `/api/health`,
`/api/models`, and `/api/turn` routes remain unchanged and are registered before
the static UI route.  The UI entry document is returned for `/`; browser assets
are returned from the same resource bundle.  Missing static assets remain 404
responses and never fall through to an API handler.

## User Workflow

1. Start Ollama and make a model available as today.
2. Run `./gradlew :server:run`.
3. Open `http://127.0.0.1:8080/`.
4. Stop the application with Ctrl-C in that same Gradle process.

`browserDevelopmentRun` is not required or documented as a startup step.
The web module's development task may remain available as a Gradle capability,
but is outside the supported user workflow.

## Security and Runtime Boundaries

The server remains loopback-only by default and preserves the existing explicit
`ADVENTURE_EXPOSE_LAN=true` opt-in.  Same-origin browser API calls no longer
need a development CORS origin, so the fixed `localhost:8088` CORS rule is
removed.  No scenario, session, player action, history, or LLM text is stored
on the server; IndexedDB remains the browser-side owner.  API prompt
construction, SSE framing, and operational logging remain untouched.

## Verification

Server tests prove that the packaged UI entry document is returned from `/` and
that API endpoints still return their existing responses.  The previous tests
for the fixed development CORS origin are removed because that origin is no
longer part of the runtime architecture.  `./gradlew check` verifies both
modules and causes the production browser bundle to be available before server
resource processing.  A manual smoke test starts only `:server:run`, loads `/`,
checks `/api/health`, and confirms a selected local Ollama model can stream a
turn in the UI.
