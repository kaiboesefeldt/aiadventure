# World State

## Goal

A browser session has a small, persisted and versioned world state. It gives the primary model a compact, understandable starting point before each player action. After the visible response, a separate secondary model evaluates the previous state, action and response and proposes a JSON patch.

## Contract

- The canonical state consists of an entity registry and current facts. Entities can arise during play; IDs are internal, while names and short descriptions appear in the prompt.
- The secondary model generates only a bounded patch. JSON Schema/GBNF enforces its shape; Kotlin checks revision, references, bounds and conflicting changes before commit.
- New entities require an explicit create operation. Ambiguous references do not change state.
- State resolution runs after the primary stream completes. In the first experiment the next warmup/prefill waits for it; duration, patch result and snapshot size are measured without logging game text.
- The primary prompt always contains instruction, scenario, canonical history, deterministic state snapshot and new action. Warmup is exactly the prefix of the same turn.
- IndexedDB owns the state. The server remains stateless.

## First delivery scope

Entity registry, location, possessions, status, short open facts and bounded entity creation. No combat rules, clocks, memory retrieval or automatic repair of visible prose.

## Runtime

`server.sh` starts primary and secondary `llama-server` processes with separate model paths, ports and thread budgets. The secondary port is passed as a server option. Both children terminate when the launcher ends.
