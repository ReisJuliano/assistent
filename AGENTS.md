# AGENTS.md

Instructions for coding agents (Codex, Claude, etc.) working in this repository.

1. Read **[CLAUDE.md](CLAUDE.md)** first: architecture, conventions, build/test commands and known gotchas.
2. The prioritized plan of what to build next, with implementation details per feature, is in
   **[docs/ROADMAP.md](docs/ROADMAP.md)**.
3. Local REST API reference: **[docs/API.md](docs/API.md)**.
4. Speaking while the answer is generated and being interrupted while speaking: **[docs/STREAMING_E_INTERRUPCAO.md](docs/STREAMING_E_INTERRUPCAO.md)**
   (current state, known bugs, target design, acceptance criteria).

Always finish a change with `./gradlew assembleDebug testDebugUnitTest` (JDK 17) and, for anything visual, verify it on
the emulator through the local API plus a screenshot (see "Testing the running app through its API" in CLAUDE.md).
