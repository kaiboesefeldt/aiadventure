# Public Portfolio Polish Implementation Plan

> **For agentic workers:** Use the repository's subagent workflow. Execute the tasks in order, with narrowly scoped implementation subagents; Luna Medium is the intended model. Track the checkboxes and report evidence and unresolved issues rather than inventing results.

**Goal:** Prepare the repository as an understandable, reproducible English-language public example of the owner's engineering work.

**Architecture:** Preserve application behavior and existing interfaces. Improve public documentation, translate repository-owned German text, add automated verification, and produce a reviewable publication audit. Keep historical records identifiable and preserve scenario semantics.

**Tech Stack:** Kotlin/JVM, Ktor, Kotlin/JS, React, Gradle, llama.cpp, Markdown, shell.

**Spec:** This plan records the user's request and the preceding public-readiness assessment; no separate specification exists.

**Execution record:** The portfolio entry point, English documentation and
examples, cache/health corrections, Woodpecker configuration and local
publication review have been prepared. See `docs/publication-readiness.md` for
items that require a real model, hosted CI or the owner's publication review.
The checkboxes below preserve the original acceptance checklist; final test and
integration results are recorded in the task handoff.

## Global Constraints

- Follow the current `AGENTS.md`: code changes in a dedicated branch and worktree, implemented by subagents; merge into `main`, remove the worktree and delete the branch at the end of a code-change task.
- Do not publish, push, upload, send, or make the repository public. Publication requires a separate explicit instruction.
- Do not change gameplay, prompt construction, cache behavior, API contracts, or scenario identifiers merely to improve presentation. Investigate defects separately and report them if a behavioral fix is needed.
- Translate repository-owned German prose into English, including documentation, examples, visible application text and relevant comments. Preserve machine-readable keys, IDs, paths, commands, code identifiers, and external license text; do not translate vendored third-party material. Distinguish archival documents from current guidance, without deleting history or silently changing its meaning.
- Do not fabricate model compatibility, hardware requirements, performance, authorship, test results, screenshots, or security assurances. Record actual environment and evidence, or mark an item unverified.
- Keep `.env`, local models, personal data, credentials and generated artifacts out of commits and reports. Do not expose secret values in command output.
- Serialize Gradle operations. Final verification is `./gradlew check` without excluded dependency-install tasks. Stop processes started for testing.
- Preserve unrelated changes. Stage only task files. No force pushes, history rewriting, or destructive cleanup without explicit authorization.

## Review Focus

- Scenario translation: all references to location, flag and signal identifiers still match, and imports and existing behavior remain valid (Task 3).
- Documentation accuracy: health checks and cache metrics do not overclaim connectivity or cache reuse (Task 4).
- A clean checkout without an installed local model can execute CI tests (Task 5).
- Internal links still resolve after document renames and heading translations (Tasks 2–4).
- Publication audit covers tracked files and history without disclosing possible secrets or claiming exhaustive legal/security clearance (Task 6).

## Task 1: Establish scope and baseline

**Files:** Inspect `AGENTS.md`, `.gitignore`, tracked files, existing build and test configuration.

- [ ] Record branch, status, HEAD and existing worktrees; preserve unrelated changes. Set up a dedicated task branch and worktree as required by `AGENTS.md` before implementation.
- [ ] Inventory tracked repository-owned German text with `git grep -n -I -E '[äöüÄÖÜß]|\b(und|oder|nicht|Spiel|werden)\b'`, supplemented by inspection: this search is a lead, not proof of completeness. Classify public docs, examples, application strings/comments, historical plans/specs and third-party material. Include non-Markdown files when present.
- [ ] Identify current and historical documents, existing CI platform/remotes, test browser requirements, and any tracked binaries. Record proposed translations and exclusions in the task report; do not print sensitive content.
- [ ] Run `./gradlew check` once as baseline if the environment permits; record failures and prerequisites without masking them. Run `bash scripts/test-server-launcher.sh` separately.

## Task 2: Portfolio entry point and operating guide

**Files:** Modify `README.md`; create focused documentation under `docs/` as needed.

- [ ] Restructure the README so a reader can understand the product, project status, key features, architecture, prerequisites, first run, tests, limitations and deeper documentation. Use evidence from code and configuration for every technical claim. Make a compact text or Mermaid architecture diagram.
- [ ] Give one concrete tested setup and troubleshooting steps only when actually verified; otherwise state what remains unverified. Include JVM version, llama.cpp/model requirements and setup dependencies supported by evidence. Make clear what the health endpoint actually checks.
- [ ] Provide a screenshot or short recording only if one can be captured from the real application without private content and an actual model is available. Otherwise leave a clearly reported follow-up, not a fabricated asset.
- [ ] Move detailed scenario syntax and operational internals into linked guides where appropriate, retaining all useful information. Do not invent a personal contribution or AI-use statement; prepare suggested wording for owner review if evidence is insufficient.
- [ ] Check every changed relative link and anchor against the resulting files. Review `git diff --check` and the complete README diff.

## Task 3: Translate documentation and examples

**Files:** `docs/Spiel-Erstellen.md`, `docs/gameplay-architecture-roadmap.md`, `docs/llama-cpp-structured-output.md`, `examples/`, historical `docs/superpowers/` material, and other repository-owned text identified in Task 1.

- [ ] Translate public-facing scenario authoring guidance to English, preserving all syntax variants, examples, caveats and links. If renaming the guide, update every inbound path and heading anchor across tracked files.
- [ ] Translate German narrative and explanatory text in examples, including manual testing instructions. Preserve YAML keys and identifiers and every cross-reference; check tests for literal expectations before changing example strings. Update assertions only when their intended behavior remains the same.
- [ ] Translate German prose in remaining repository-owned documentation and relevant application strings/comments in manageable batches. Keep historical plans/specs clearly historical and preserve their original dates, decisions and technical meaning; do not interpret historical content as current guidance. Review non-text documents and embedded images individually and document any that cannot be translated safely.
- [ ] Inspect all translation diffs for omissions and changes to keys, links or meaning. Rerun the German-text inventory and explain intentional remaining matches, such as names, historical identifiers, quoted external material and license text. Run scenario import/smoke tests and affected browser tests; run the full check at final verification.

## Task 4: Resolve documented inconsistencies

**Files:** `README.md`, `AGENTS.md`, `examples/signal-test-game-testing.md`, and related current guides.

- [ ] Trace health endpoint behavior and startup instructions to implementation and correct any unsupported claims.
- [ ] Compare cache metric descriptions against the installed/tested llama.cpp behavior and gateway code. Align current instructions with version-specific evidence; when counters cannot establish reuse, say it is unknown. Preserve cache contract tests and distinguish their proof from live measurement.
- [ ] Compare the authoring guide's current behavior claims with parser/runtime code and tests, especially selected fragment effects after initial eligibility. Correct confirmed discrepancies and report unresolved ones.
- [ ] Recheck changed links, commands and documentation examples. Use focused existing tests when executable examples or expectations changed.

## Task 5: Continuous integration

**Files:** CI configuration appropriate to the existing hosting platform; `README.md` verification section.

- [ ] Inspect the remote and existing configuration before choosing the CI provider; do not create a remote or enable an external service. If the platform cannot be established, document the candidate configuration and leave provider-specific setup pending.
- [ ] Configure a clean environment with the required JDK and browser to run `./gradlew check` and `bash scripts/test-server-launcher.sh`, without a local model or secrets. Respect the Gradle wrapper and project-local dependency setup; do not exclude installation tasks.
- [ ] Validate the configuration locally where possible. Report separately whether the workflow was syntax-checked, run locally, or actually executed by a hosted CI service. Add a badge only after a real public workflow URL and successful run exist.

## Task 6: Publication audit and final handoff

**Files:** Inspect tracked files and Git history; optionally create a concise local audit report without sensitive values.

- [ ] Inspect tracked file names, sizes, binaries, authorship and history for accidental personal content, credentials, model files and generated output. Use secret scanning that suppresses values; report paths and remediation needs privately without reproducing secrets. Do not rewrite history or publish.
- [ ] Review project and bundled third-party licensing and attribution, including `.agents/skills`, dependencies, examples and media. Record provenance questions and any required owner decisions; do not claim legal clearance.
- [ ] Re-run `./gradlew check` without exclusions and `bash scripts/test-server-launcher.sh`; check changed links, `git diff --check`, final staged scope and status. If a check cannot run, report the exact blocker and do not claim success.
- [ ] Stop every process started during this work. Review the final diff and complete the branch/worktree workflow required by `AGENTS.md`, preserving unrelated work. Report commits, verification results, translation exceptions, publication risks and any remaining owner decisions. Do not push or publish.
