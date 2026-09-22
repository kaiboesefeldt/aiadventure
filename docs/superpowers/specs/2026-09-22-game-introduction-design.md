# LLM introduction for new games

Approved in conversation on 2026-09-22.

New sessions automatically stream a short, scenario-grounded introduction describing
the starting situation and inviting the player's first decision. The instruction
must not let the model choose actions for the player or reveal scenario secrets.

Use the existing warmup and turn API with the session's stable ID. Persist the
start instruction as an ordinary player-role history message, hidden only in the
transcript, so subsequent prompts retain the complete canonical history. Persist
the introduction and world state through the existing turn completion flow.

New sessions carry an introductionPending flag. Older serialized sessions default
to false. Clear the flag only when the complete introduction is saved. On failure,
discard partial display text and offer retry; resuming an unfinished new session
also retries. Completed introductions are never regenerated on resume. Disable
player input until introduction completion, including secondary world updates.

Verify automatic streaming, hidden instruction persistence, failed attempts and
retry, resume behavior, mapper history, serialization compatibility, and the
existing exact-prefix and retained-slot cache tests with ./gradlew check.
