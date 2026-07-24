# Weltzustand Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist a compact authoritative world state and prepare each next Primary prompt from an accepted Secondary-model patch.

**Architecture:** Browser sessions carry a serializable state. Server code receives it with each request, asks the secondary llama-server for a constrained JSON patch after a finished stream, validates/reduces it, and returns the accepted state to the browser before warmup. PromptFactory projects that state deterministically.

**Tech Stack:** Kotlin/JVM, Kotlin/JS, kotlinx.serialization, Ktor, llama.cpp GBNF, IndexedDB.

## Global Constraints

- Preserve exact warmup-to-turn cache-prefix equality.
- Never log scenario, history, action, response, or state content in operational logs.
- Keep state snapshots bounded and browser-owned.

---

### Task 1: World model and reducer

**Files:** Create server world-state model/reducer and JVM tests; modify shared request/session models.

- [ ] Write reducer tests for create, move, transfer, revision rejection, and ambiguous/no-op patch.
- [ ] Implement serializable entities, facts, bounded patch operations, and deterministic reducer.
- [ ] Run focused JVM tests.

### Task 2: Secondary gateway and post-turn contract

**Files:** Modify llama gateway, Application, API models, and tests.

- [ ] Write a failing test proving an accepted patch is returned after a completed stream and unavailable secondary work preserves the prior state.
- [ ] Implement constrained secondary completion and server-side JSON/schema/reducer validation.
- [ ] Run focused server tests.

### Task 3: Browser persistence and cache-safe prompt projection

**Files:** Modify web models/mappers/submission flow, PromptFactory, and JS/JVM tests.

- [ ] Write failing tests for persisted state, deterministic snapshot, and warmup-prefix equality.
- [ ] Pass the last accepted state with turn/prefill/warmup requests; store the returned state before preparing the next prompt.
- [ ] Run focused browser and JVM tests.

### Task 4: Secondary launcher and documentation

**Files:** Modify `server.sh`, `.env.example`, `README.md`, launcher tests if present.

- [ ] Write/update launcher validation tests or shell-checkable assertions.
- [ ] Start and reap a configured secondary `llama-server`; expose the port to the JVM server.
- [ ] Run `./gradlew check` and a launcher configuration smoke check.
