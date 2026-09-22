# Model wrapper scripts

Approved in conversation on 2026-09-22.

`PRIMARY_MODEL_SCRIPT` and `SECONDARY_MODEL_SCRIPT` optionally select a readable,
executable wrapper file. A configured wrapper takes precedence over that model's
`MODEL_PATH` and `THREADS`, which are neither required nor forwarded in this mode.
An empty script setting preserves the existing GGUF launch behavior.

Invoke the wrapper directly with `llama-server` as its first argument, followed
by `-rea off`, the configured `--port`, and, for the primary, the temporary
`--slot-save-path`. Wrappers forward these arguments after their own settings,
run the server in the foreground, and must not detach their children.
The existing process-group cleanup must also terminate wrapper children.

Validate both configurations before starting processes. Keep prompt construction,
cache contracts and the application API unchanged. Document both launch modes.
Use isolated launcher fixtures to verify argument boundaries, precedence, mixed
configurations, invalid configuration, exit propagation and process cleanup.
Final verification is `./gradlew check` plus the launcher regression tests.
