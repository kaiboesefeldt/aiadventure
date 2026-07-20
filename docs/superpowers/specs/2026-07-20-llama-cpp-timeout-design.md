# llama.cpp timeout design

## Goal

Allow slow local llama.cpp inference to run for up to 300 seconds without one gateway path timing out earlier than another.

## Design

`LlamaCppGateway` owns one `Duration` constant for llama.cpp HTTP timeouts. The default `HttpClient` connection timeout and every request timeout in the gateway use that exact value.

This covers model and slot discovery, slot erasure, template application, non-streaming prefill/warmup/scoring completions, and streaming turn requests. Test-only coroutine timeouts and Gradle download settings are outside this boundary.

## Verification

Add a regression test proving the gateway's default timeout is 300 seconds, run the focused gateway tests, then run `./gradlew check`.
