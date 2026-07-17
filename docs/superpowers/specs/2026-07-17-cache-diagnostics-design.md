# Cache Diagnostics Design

## Goal

Expose enough content-safe server evidence to distinguish a released llama.cpp
slot from an incompatible prompt cache across prefill and turn requests.

## Design

Diagnostics are server DEBUG logs only. `LlamaCppGateway` logs a SHA-256
fingerprint and character count after `/apply-template`, plus the native cache
metrics when a completion finishes. `SessionPrefillCoordinator` logs its lease
state transitions with an opaque coordinator fingerprint and slot ID.

Logs must never contain scenario markdown, history, player actions, model text,
model IDs, raw session IDs, or raw prompts. They allow correlation of the
coordinator, retained slot, rendered prompt identity, and llama.cpp's aggregate
cache/evaluated token metrics.

## Verification

Tests cover deterministic, non-revealing prompt fingerprints and coordinator
lease transitions. Live evidence uses only request IDs, opaque fingerprints,
slot IDs, and aggregate token metrics.
