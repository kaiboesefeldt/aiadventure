# Prompt cache contract design

## Goal

Prompt caching is critical to the latency that makes the game fluidly
playable. It is therefore a release-blocking performance contract. Future
changes must preserve both cache-compatible prompt growth and the warm
llama.cpp slot that owns that cache.

## Cache-compatible prompt contract

`PromptFactory` produces the logical message sequence that is subsequently
rendered by llama.cpp. For a cache hit, the earlier sequence must be an exact
prefix of the later sequence: existing messages keep their role, content, and
order, and only new suffix messages are appended.

Fast local tests must cover these three flows without contacting llama.cpp:

1. A new game's action-free warmup is a prefix of its first action turn.
2. A resumed game's warmup containing stored history is a prefix of its first
   resumed turn.
3. A completed turn, including the persisted game-master reply, is a prefix of
   the following turn after the next player action is appended.

The tests compare typed logical messages rather than logging or snapshotting
real prompts. Inserting dynamic data, rewriting history or scenario text,
changing a role, reordering messages, or adding a synthetic empty action
before the later action must fail the contract.

## Warm-slot contract

Prompt equality cannot help if the server loses the slot containing the KV
cache. `SessionPrefillCoordinator` must keep one lease and `LlamaCppSlot` for
one live session across cache-reusing operations. It may release the slot only
on explicit close, TTL expiry, session removal, gateway failure, or restart.

An in-process `PrefillGateway` test double records acquisition, warmup,
prefill, turn, and release calls. Fast local lifecycle tests must prove:

1. New-game warmup and first turn use one acquired slot and release only at
   cleanup.
2. Resumed-game warmup and first turn use one acquired slot and release only
   at cleanup.
3. Completed turn, next prefill, and following turn use one acquired slot and
   release only at cleanup.

## Evidence boundary

These tests protect application-side causes of cache loss and run in ordinary
development without a model server. They do not claim a native cache hit.
Only llama.cpp's `tokens_cached` and `tokens_evaluated` metrics prove actual
backend cache reuse in a live deployment.
