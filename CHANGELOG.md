# KITE changes

The apps. Changes to the protocol itself are in [protocol/CHANGELOG.md](protocol/CHANGELOG.md).

## 0.4.0 (not released yet)

**KITE now works with agents and models other than Claude, and the language it speaks is a specification of its own.**

### The protocol stands apart

- The board language is the **KITE Protocol**, version 0.2, in [protocol/](protocol): a specification that does not depend on the app, a guide to give your model, a schema, examples, conformance cases, and a reference implementation in Rust with no dependencies.
- Rejections carry a stable code and an English message (`err unknown-verb unknown verb 'foo'`). Before, they were Korean sentences.
- The desktop app uses the reference implementation. The Android app's Kotlin implementation was brought to 0.2 and runs the same conformance cases.

### Use your own agent

- **MCP.** KITE can be an MCP server for an agent outside it (Claude Code, Cursor, your own): tools `screen`, `board_read`, `events`, `guide`. Choose **Your own agent** under **Keys**; the window gives you the command to connect.
- **AG-UI.** KITE can be the client of an AG-UI agent (LangGraph, CrewAI, Pydantic AI and others): give the agent's address under **Keys**.
- **The protocol socket answers back.** `read` returns the board, `watch` pushes what the user does and types. Verdicts are `ok`, `ok clipped ...`, `err <code> <message>`.
- With an outside agent, what you type and pick goes to it as events, and KITE runs no model.

### Use another model

- **Another model** under **Keys**: any OpenAI-compatible endpoint (OpenAI, Ollama, LM Studio, OpenRouter). KITE's memory, workers and tools stay; web search is with Claude only.

### English first

- The app starts in English. Switch to Korean in the corner, or set `KITE_LANG=ko`. An existing choice is kept.
- Settings from the environment are named `KITE_...`; the older `LYBOARD_...` names still work.
- Documentation for developers is in English: [docs/DEVELOPING.md](docs/DEVELOPING.md), [docs/DRIVING.md](docs/DRIVING.md).

### Since 0.3.0, also

- The protocol socket (`KITE_PROTOCOL_PORT`), the version header of the log, and the Android apps KITE and KITE Pro, which were added after the 0.3.0 release.

### Good to know

- The instructions KITE gives Claude are still written in Korean. Cards and answers follow the language you choose.
- The engines for outside agents and other models were tested with test servers and scripted clients on a real window, not yet with a real model or an agent built on an AG-UI framework.
- Text sent back to a model about a rejected line changed from Korean to the coded English form. A board saved by 0.3.0 loads unchanged.

## 0.3.0 (2026-11-16)

Renamed to KITE. Stepped zoom, picking cards, the unseen browser, smoother resizing, Claude Code as a second engine. See the [release](https://github.com/fidenit/KITE/releases/tag/v0.3.0).
