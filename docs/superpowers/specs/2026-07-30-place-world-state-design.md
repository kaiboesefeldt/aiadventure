# Location-Based WorldState

The persisted WorldState consists solely of `location: String` and is empty until a location has been established canonically. The old generic entity and facts registry is removed entirely. Scenarios initialize it with `world-state.location`.

After a completed visible turn, the secondary model first uses fixed examples to ask whether the player character actually left the scene location. It may return only a final `FINAL: move` or `FINAL: stay`. For `stay`, a missing result or an ambiguous result, the state remains unchanged.

For `move`, a second prompt asks for the destination. The server accepts only the last `FINAL: <name>` if the normalized name occurs as a complete text element in the game master's response. This permits new locations without model IDs; known and new names receive equal treatment. The old location is not presented to either LLM query.

The fixed prompt instruction and examples form the cached prefix. The reasoning is never logged or persisted. The server remains stateless; the browser persists the `location` state as before.
