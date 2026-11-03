# Gameplay Architecture: Fast Turn Start and a Consistent World

> Historical roadmap: this document describes a proposed two-model architecture and is not a specification of the current implementation.

## Purpose and guiding decision

This paper describes an incremental expansion of the game for two local, CPU-based LLMs. The primary LLM visibly narrates; a smaller secondary LLM performs preparatory and structuring tasks in parallel. The goal is better continuity, memories and narrative direction without increasing the wait between **submission** and the first visible token.

Structured world state is the authoritative source for verifiable game facts. Narration remains free to shape tone, descriptions, dialogue and small improvised details. This guides the model without prescribing a script or railroading play.

## Starting point

A turn has three periods relevant to the design:

1. **Phase 1 – visible generation:** The primary LLM streams the game master's response. About 5–6 tokens/s is enough as long as it does not interrupt reading.
2. **Phase 2 – user typing:** This time is free to the user. Background tasks may run and the exact typed action text is prefetched for the primary LLM. Its duration cannot be planned.
3. **Phase 3 – user has submitted:** Until the first output token, every additional wait is visible and therefore critical.

After the visible stream, a runtime fragment is already retrieved and evaluated and the canonical prompt prefix for the next turn is warmed. While the user types, a debounced prefill adds the action text. This cache chain remains the central performance invariant.

An initial CPU snapshot indicates enough parallelism to explore a two-model architecture. Performance depends on the selected models and hardware and must be measured for each deployment.

## Non-negotiable latency and safety rules

- The primary LLM has absolute priority in phase 3, except during rare scheduled primary context compression. No new secondary work starts on submission. Already running secondary work may finish cooperatively, but must not block a service queue slot or the start of the primary turn.
- No secondary result lies on the critical turn path. If it has not already been canonically accepted at submission, it changes neither the current turn nor its primary prompt. It may remain as a versioned analysis candidate for a later safe commit.
- The canonical primary prompt stays deterministic: instruction, original scenario, canonical history, new action. Any inserted context must be fixed before the corresponding warmup.
- Prefill and turn continue to use the same `sessionId` and retained primary slot. Cache reuse is checked using reuse and evaluation counters confirmed for the llama.cpp version in use; in current versions these are `timings.cache_n` and `timings.prompt_n`.
- LLM output is untrusted input. The secondary LLM may propose only a narrow schema-validated result; deterministic logic decides acceptance, limits and conflicts.
- Scenario, history, actions and LLM text do not belong in operational logs. Measurements use request ID, duration, cache metrics and classifications.

## Target design

### Component roles

| Component | Responsibility | Must not |
| --- | --- | --- |
| Primary LLM | Visible game master response, primary prompt cache, streaming | Wait for phase-3 side work |
| Secondary LLM | Short structured proposals, reranking, state patches, memory extraction, direction options | Output narration directly or block the turn |
| World-state reducer | Validate schemas, apply allowed transitions, detect conflicts | Generate creative prose |
| Memory index | Retrieve accepted episodic memories | Define authoritative facts |
| Orchestrator | Deadlines, priority, admission limits, commit timing and metrics | Make game-specific decisions |

### World state: small, explicit and authoritative

The state contains only facts that can be determined or verified:

- current location, time and known travel times;
- present NPCs, states and important relationships;
- inventory, doors, resources and other verifiable objects;
- secrets with revelation levels instead of free-form secret text;
- open event clocks with clear trigger conditions;
- confirmed consequences of past actions.

It expressly excludes the complete plot, linguistic mood and every descriptive detail. For each new turn, the primary LLM receives a short canonical snapshot. Its response may add color to the world but should not overwrite hard facts.

A detected contradiction is not corrected in the middle of the stream. Post-processing rejects the invalid patch and creates a concise repair note suitable for the next turn. This prevents the error from spreading without disrupting the visible turn or its pace.

### Concrete minimal state model

The first state is not a general role-playing ruleset. It consists of stable IDs and states the reducer can validate without language understanding. Each session has a monotonically increasing `revision`; each patch identifies its base revision. A late patch or one belonging to a different history is not accepted.

```yaml
revision: 17
time: { day: 2, minute-of-day: 1335 }
player:
  location: quay
  inventory: [brass-compass, warehouse-key]
actors:
  mira: { location: quay, status: wary }
location-state:
  quay: { signal-fire: unlit }
items:
  warehouse-key: { holder: player, state: intact }
facts: [mira-promised-a-meeting]
player-knowledge: [mira-knows-the-smuggling-route]
clocks:
  patrol-arrives: { progress: 2, threshold: 4 }
```

The primary prompt receives not the entire state but a deterministically built local snapshot: current location, allowed exits, visible actors and objects, immediately usable inventory, relevant known facts and due clocks.

```text
AUTHORITATIVE STATE — do not contradict:
Day 2, 22:15; player at quay.
Here: Mira (wary), unlit signal fire.
Exits: market, lighthouse path. Inventory: brass compass, warehouse key.
Known: Mira knows a smuggling route. Pressure: patrol arrives in 2 steps.
```

IDs and allowed transitions come from frontmatter; descriptive scenario text remains freely written.

```yaml
world-state:
  locations:
    quay: { exits: { market: 5, lighthouse-path: 10 } }
  actors:
    mira: { home: quay, initial-status: wary }
  items: [brass-compass, warehouse-key]
  facts: [mira-promised-a-meeting]
  clocks:
    patrol-arrives: { threshold: 4 }
```

The first stage contains only location, time, present actors, inventory and confirmed flags. Relationships, knowledge lists, object states and event clocks follow only once the core works reliably.

### Origin of IDs and facts

`brass-compass` is an **authorized entity ID** from the scenario definition, specifically `world-state.items`. The same applies to locations, NPCs and event clocks. The secondary LLM may, for example, change the compass's holder, but in the first stage it cannot create a new entity with an invented name.

`mira-promised-a-meeting` is an **authorized fact key** from `world-state.facts`. Visible narration may satisfy its condition; the state patch may then only set or clear the previously known key. This lets the reducer verify which facts may form part of the game mechanics.

Details not declared in advance initially remain narration or episodic memory, not hard world state. If dynamic entities or open promises later become important, they require a separate extension: a bounded, schema-defined creation mechanism that does not simply accept arbitrary model IDs. An optional startup planning phase may automatically generate authorized IDs from a campaign bible before play; it does not silently expand the allowed set afterward.

### State patches and deterministic acceptance

After the visible response, the secondary LLM creates a small structured **proposal**, not the new truth:

```yaml
base-revision: 17
advance-minutes: 5
operations:
  - { type: move-player, to: market }
  - { type: move-actor, actor: mira, to: warehouse }
  - { type: set-flag, flag: mira-promised-a-meeting }
```

The reducer accepts each operation only when IDs exist, an allowed route exists, ownership and object state match, the clock is valid and the base revision remains current. Unknown IDs, impossible routes and conflicting operations are rejected.

A game rule also applies: a patch records only consequences that the visible response clearly describes as having occurred. “The player wants to go to the market” moves nobody; only “After walking to the market ...” can establish movement. When in doubt, state remains unchanged: a missing change is less harmful than a false fact.

### Mechanics with visible benefits

| Mechanic | Authoritative rule | Effect for the player |
| --- | --- | --- |
| Spatial continuity | Player and actors have exactly one location; movement requires an exit or allowed exception. | Nobody appears in the wrong place without explanation; travel and pursuit have weight. |
| Time and pressure | Travel and longer actions advance time; clocks trigger declared events at thresholds. | Hesitation has understandable consequences rather than merely decorative time references. |
| Items | An item has a location or holder and optionally a state. | Finding, losing, using and promises involving items remain meaningful. |
| NPC continuity | An NPC's location and status are authoritative. | Tone, availability and reactions build on previous events. |
| Knowledge boundaries | World facts and `player-knowledge` are separate. | The game master does not reveal secrets solely due to background knowledge. |
| Chains of consequences | Confirmed flags and clocks are later triggers. | Actions are revisited rather than disappearing after one response. |

The state does not answer “may the player do this?”; it provides reliable starting conditions: where the character is, who can be reached, what is available, what they know and what consequences are already underway. The primary LLM can still describe creative detours and surprising solutions; the reducer later accepts them only through an allowed transition or marks them for gentle repair in subsequent context.

### Example of a gameplay consequence

The patrol clock is at `2/4`, Mira is at the quay and the player has the warehouse key. The player may freely negotiate, scout the patrol, follow Mira or walk to the warehouse. Each clear change of location or longer action advances time. At `4/4` the patrol appears; this is not a scripted sequence but a new situation to which the player can again respond freely. State only ensures that the patrol is not simultaneously far away, Mira is not speaking at both the quay and warehouse, and the key does not suddenly lie on the ground again.

The quality gain is a world that remembers consequences and exerts pressure without replacing the primary LLM's creative response or the player's freedom of choice.

### Memory and direction

In addition to state there are two non-authoritative layers:

- **Episodic memories:** Very short retrieved entries such as a promise, a lie or an observed weakness. Only accepted, deduplicated entries enter the vector store.
- **Direction cues:** Candidates for tension, consequences and the next meaningful scene. They remain optional and must never impose facts or restrict player freedom.

The current runtime fragment is an early example of a direction cue. Its semantic search and insertion remain compatible with the new model: state provides hard boundaries, memory supplies relevant past events, and a fragment adds creative direction when useful.

## Secondary LLM work by phase

| Phase | Useful work | Handling the result |
| --- | --- | --- |
| 1: primary streams | Check active event clocks, prepare possible follow-up cues from current state | Purely speculative; may finish after phase 3 |
| Transition from 1 to 2 | Turn last action and complete response into a `statePatch`, memories and conflict notices | Validate against schema before canonical acceptance |
| 2: user typing | Evaluate runtime fragment/memory candidates, roughly estimate action intent, prepare direction options | Usable only before commit time |
| 3: submitted | Start no new side work | Already running secondary work may finish; primary prefill and turn start immediately unless scheduled primary compression is still running and must finish first |

Action intent is deliberately coarse, for example `move`, `inspect`, `talk`, `use-item` or `attack`. It can prepare retrieval but never replaces prefill of the text actually submitted.

## Frontmatter configuration

Frontmatter should control gameplay without becoming a second prompt script. New fields are introduced only when the relevant roadmap phase actually needs them. Suitable later options include:

```yaml
world-state:
  locations: strict
  time: enabled
  inventory: enabled
memory:
  mode: episodic
  max-active: 3
events:
  clocks: enabled
gameplay:
  hint-density: medium
  continuity: strict
planning:
  enabled: true
```

`strict` means hard state transitions are checked deterministically. It does not mean the model cannot introduce new ideas or places; those require an explicitly permitted transition.

## Optional planning phase before play

For larger scenarios, a one-time, somewhat slower start may be acceptable. The planning phase produces a compact campaign bible rather than a written plot:

- location graph with travel times;
- goals, knowledge and relationships of important NPCs;
- secrets with conditions for revelation;
- event clocks and consequences of inaction;
- desired tone, pacing and limits on randomness.

The campaign bible is converted into the same structured state and thus made available to the deterministic reducer. It frames free choices rather than prescribing a solution.

## Planned context compression

In long games the complete raw history becomes too large for an efficient prompt. A sliding window would be simple but would produce a different prefix on each turn and continually invalidate the primary cache. Instead the game uses rare, explicit **context epochs**.

### Checkpoint plus raw tail

A checkpoint turn is determined by token budget rather than a fixed turn number. In a typical example, after turn 10 the primary LLM creates a recap of turns 1–10. This recap initially remains a derived artifact. Through turn 13 play continues using unchanged raw history and the existing cache. On switching, this becomes:

```text
Instruction + scenario + authoritative state
+ primary recap of turns 1–10
+ raw turns 11–13
+ new action
```

The switch breaks the cache exactly once. The new prefix is then warmed while the user types and can again be prefetched normally. Complete raw history remains unchanged in IndexedDB; the recap is only a versioned prompt representation and can be regenerated from the original material when needed.

The recap contains no authoritative world facts, which remain in the separate state snapshot. It compresses only narrative continuity: resolved events, open threads, relationships, player intentions and tone. It carries at least `coversThroughTurn`, `stateRevision` and a source fingerprint so it cannot be applied to another session or history version.

### Timing and consciously accepted cost

Option 2 is the chosen strategy: the primary recap starts as scheduled after its checkpoint turn in phase 2 and uses the still-compatible primary cache. It is not cancelled. If the user submits unusually quickly while the recap is still generating, the following turn waits for that maintenance to finish. The normal turn can then reuse the shared prefix through the checkpoint.

This is a deliberate exception to the usual phase-3 priority. It must occur rarely and remain measurable: recap duration, submission-to-first-token during maintenance, cache metrics before and after the epoch switch, and the number of affected fast submissions. The benefit is a high-quality recap from the complete original context; a secondary recap remains possible as a comparison or fallback, but is not the preferred source.

The soft limit must be early enough to allow at least one complete turn before the hard context limit. If a recap is not ready in time, the old epoch remains active; the next planned maintenance starts earlier. Only near the hard limit may the scheduler force an earlier epoch switch.

## Roadmap by quality gain and total effort

Each stage can be delivered independently. Assessment has three distinct dimensions: **gameplay quality gain**, **implementation effort**, and **performance cost** in primary latency, throughput or cache reuse. A measurement or infrastructure stage is not artificially rated as a high quality gain. The order favors high visible quality at low total effort; technical prerequisites come first only when they directly limit phase-3 risk.

### 0. Measurement and priority foundation

**Quality gain: none directly. Implementation effort: medium. Performance cost: very low.** This stage does not yet improve visible gameplay, but prevents a later improvement from merely feeling fast or silently degrading phase 3.

- Capture primary metrics per turn: submission to first token, prompt and generation rates, and backend-reported reuse and evaluation counters.
- Capture secondary metrics separately: task, start/end status, duration, on-time/fallback and CPU allocation.
- Introduce an explicit priority contract: no new secondary work starts in phase 3; already running jobs may finish, but no background job may block the turn queue.
- Compare at least primary alone, 4+2 cores and 4+4 cores using reproducible scenarios. Success means no statistically relevant degradation in primary time to first token and no deterioration of the cache metric.

This stage changes neither prompt nor visible gameplay.

### 1. Non-blocking commit contract for optional post-processing

**Quality gain: indirectly high. Implementation effort: medium. Performance cost: low.** This stage itself adds no gameplay mechanic, but makes state, memories and direction cues usable without making a quickly submitted turn wait for their completion.

- Immediately after a visible turn, secure a canonical **base warmup** that needs no still-undecided side analysis.
- Each optional secondary finding carries a history and state version. It may change the current primary prompt only if complete and validated before its binding commit.
- If it arrives later, retain it as a candidate for a later safe commit; it holds neither slot nor turn and is not written retroactively into the already warmed prefix.
- A regression test covers the boundary case: the user submits immediately after the stream. The turn starts on the base prefix without waiting for optional post-processing.

Align the current fragment-based rerank with this contract before considering a change of model provider.

### Experimental track: secondary LLM as shadow reranker

**Quality gain: unproven. Implementation effort: low to medium. Performance cost: low, to be measured.** Reranking is a good bounded experiment for the small model, but not a major gameplay benefit by itself: the existing primary rerank already provides a decision. Therefore it runs alongside stage 1, but neither blocks the roadmap nor becomes an architectural foundation.

- The secondary model receives a short fixed rerank request and returns only a score in a narrow schema.
- During an observation period, current rules continue to decide; disagreements, latencies and accepted candidates are counted with minimal data collection.
- Only if selection quality is at least equivalent and the non-blocking commit contract is preserved may secondary rerank replace primary rerank.

### 2. Minimal deterministic world state

**Quality gain: high. Implementation effort: medium to high. Performance cost: low to medium.** This provides the most visible continuity improvement and is the foundation for memories and events. Additional secondary post-processing and the short state snapshot are a deliberately accepted performance cost to be measured.

- Introduce a small versioned state schema: location, time, NPC presence, inventory and confirmed flags.
- After a completed visible turn, secondary post-processing produces a bounded patch; the reducer validates schema, references and allowed transitions.
- Initially persist only accepted patches and reconstruct them on resume; the browser remains owner of session and state.
- First evaluate state patches in shadow mode against real turn histories. Only afterward insert a very short, already accepted snapshot as canonical context before the next primary warmup. If the new patch is not ready at commit, the last accepted snapshot remains valid.
- Measure conflicts, rejected patches and snapshot size without logging game content.

The first scope remains deliberately small. Combat statistics, complex quest logic and procedural world simulation are outside this stage.

### 3. Episodic memories with retrieval

**Quality gain: medium to high. Implementation effort: medium. Performance cost: low to medium.** State prevents hard contradictions; this step makes the world appear to remember over the long term. Its prompt and retrieval budget must remain strictly limited.

- The secondary model proposes at most a few short memories from the confirmed turn.
- A deterministic filter removes duplicates, uncertainties, contradictory statements and entries without later gameplay value.
- Add these accepted memories to the existing vector store.
- During retrieval, memories and runtime fragments do not compete for uncontrolled prompt space: a budget selects a few relevant entries.

Memory is not a fact store. Hard facts remain exclusively in world state.

### 4. Scheduled primary context compression

**Quality gain: high in long games. Implementation effort: medium. Performance cost: rare but potentially high for an individual turn.** This stage preserves long-term continuity and cache efficiency while deliberately accepting occasional phase-3 waiting for a high-quality primary recap.

- Introduce token budget and soft and hard limits based on the actual prompt budget.
- Create a versioned primary recap as a checkpoint artifact and retain the complete raw history in the browser.
- After the specified number of raw turns, perform the atomic epoch switch to `State + Recap + Tail` and warm the new prefix.
- Measure maintenance wait and cache reuse separately.

### 5. Event clocks and direction cues

**Quality gain: medium. Implementation effort: medium. Performance cost: low.** Once state and memory are reliable, gameplay consequences can make the world more active.

- Frontmatter defines simple event clocks and their triggers.
- The reducer advances them only on clear state transitions, not free model interpretation.
- The secondary model formulates optional short direction cues from them.
- Retrieval and reranking select at most one suitable cue per turn; without a suitable cue, the turn remains unchanged and free.

### 6. Startup planning and extended frontmatter

**Quality gain: high for curated scenarios. Implementation effort: high. Performance cost: one-time medium to high.** This stage benefits large curated scenarios, though not necessarily every import. Its one-time startup cost is explicitly part of the tradeoff.

- An optional planning phase generates the campaign bible and validates it against the same state schema.
- Frontmatter controls only the policies and budgets needed for it.
- The user sees clear progress and can skip startup planning; the game remains fully functional without a plan.

## Acceptance criteria per stage

Every change must supply three proofs:

1. **Latency:** Turn measurements show primary time to first token with and without secondary work; phase 3 must not worsen outside explicitly marked primary compression maintenance. This rare maintenance is measured separately.
2. **Cache:** For new, resumed and subsequent turns, reuse and evaluation counters confirmed for the backend version in use continue to establish prefix reuse on a retained slot.
3. **Gameplay:** Test cases demonstrate at least one valid state transition, one rejected contradiction and, from stage 3 onward, a memory that is relevant again later.

## Explicit non-goals

- No secondary text appears directly in the visible transcript.
- No secondary or other optional background job may delay a turn merely because the user submits quickly. The documented exception is rare scheduled primary context compression.
- The complete chat history is not copied to the secondary model for every side task; compact state and the latest relevant turn data are the normal case.
- No automatic correction of prose already streamed in the UI.
- No frontmatter language intended to model every possible mechanic.

## Next decision

The most sensible first implementation is stage 0, followed by stage 1's non-blocking commit contract. Minimal world state is then the first visible quality improvement. For long games, episodic memories and planned primary context compression follow; the secondary shadow reranker remains a parallel experimental track, not the next mandatory product component.
