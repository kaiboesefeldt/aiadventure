# Post-turn Runtime Fragment Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans task-by-task.

**Goal:** Defer runtime-fragment selection to completed turns while retaining
the result for the next prompt.

1. Add failing application tests proving prefill/current-turn paths do not
   call the resolver, and the completed history is queried once.
2. Remove synchronous route resolution; reserve and complete only the existing
   post-turn rerank.
3. Store the accepted/rejected rerank result in `SessionPrefillCoordinator`;
   apply it to queued prefill and waiting/direct turn handoffs.
4. Add coordinator tests for retained fragments and waiting turns, then run
   focused server tests and `./gradlew check`.
