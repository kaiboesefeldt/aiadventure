# Single-prompt World State Update

After each game-master turn, resolve location and narrative focus together in one structured completion on the retained llama.cpp slot. Keep location selection constrained to the canonical catalog and preserve each previous field independently when its returned value is missing or invalid. The follow-up prompt must extend the canonical turn prompt with the actual game-master response and one analysis question, keeping the longest reusable prompt prefix intact. Continue the existing canonical warmup after analysis.

Acceptance: one structured completion per World State update; same session and slot; cache enabled; combined schema; independent field fallback; existing new/resumed/following-turn cache-contract tests remain intact.
