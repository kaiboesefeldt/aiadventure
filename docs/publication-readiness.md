# Publication readiness

This record describes a local review, not a guarantee that the repository or its
history contains no confidential information or licensing issues.

- The project declares Apache-2.0 in `LICENSE`. Repo-local Superpowers skills
  identify their upstream release and include its MIT license in `.agents/skills/`.
  Dependency and model licenses still need to be checked for the versions and
  distribution choices used in a release.
- The tracked files include the Gradle wrapper JAR, `docs/timing.odt` and an
  exported GIF. The ODT contains authoring metadata and a relative template
  reference. Review these artifacts and their provenance before publication.
- A filename review of all reachable history found no `.env`, GGUF model,
  private-key or credential filename. A limited scan of added historical lines
  for common private-key headers and selected token formats found no matches.
  These checks cannot detect every secret or private detail; review history and
  any findings with a dedicated secret scanner before making it public.
- The current commit history identifies its author. Confirm that the displayed
  identity is the one intended for publication.
- The Woodpecker commands passed in a fresh Debian trixie container with JDK 21
  and Chromium. Hosting requires separate Codeberg onboarding and activation;
  no hosted CI result is implied.
- An end-to-end model session, reproducible hardware/model setup, screenshot,
  and review of narrative quality in the translated scenarios remain to be
  documented. A local launch attempt with the existing model wrapper failed
  because its configured Vulkan device was unavailable in the execution
  environment. Model output and event classification depend on the chosen model.
