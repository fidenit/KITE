# APERIT changes

APERIT was called KITE up to version 0.3; the entries below 0.4.0 use the name of their time.

The apps. Changes to the protocol itself are in [PATET's CHANGELOG](https://github.com/fidenit/patet/blob/main/CHANGELOG.md).

## 0.4.0 (not released yet)

**The app has a new name, APERIT, works with agents and models other than Claude, and the language it speaks is a specification of its own, PATET.**

### New names

- The app is **APERIT** (Latin: it opens, it shows). The program is `aperit.exe` / `APERIT.app`; settings are read as `APERIT_<NAME>`.
- Nothing you have is lost: settings given as `KITE_<NAME>` still work, boards saved under the old header are read, and `run.ps1 pack` / `run.sh pack` move an existing `portable\KITE` folder to `portable\APERIT` whole (key, board, memory, browser logins).
- The protocol is **[PATET](https://github.com/fidenit/patet)** (Latin: it lies open), version 0.5, in a repository of its own. `protocol/` here is a copy of it. On the wire the names are `patet 0.4`, `<patet-board>`, `patet.ops`; the old ones (`kite-protocol`, `kite.ops`) are still understood.
- The MCP server an outside agent adds is named `aperit` (it was `kite`): run the copied command again.
- On Android the app is shown as APERIT and APERIT Pro. Its package name is unchanged, so it updates in place.
- Say "Aperit" (or, as before, "Kite") to call it back while it waits.

### Claude Code on a server

- A new engine: **Settings** → **A server (SSH)** hands each turn to the Claude Code on a server, with the server's files and commands. APERIT's memory, route maps and workers stay on your computer and reach it through the SSH connection. A key, the ssh agent or a password (kept encrypted) signs in; **Test the connection** checks it first.

### The Settings window on one screen

- **Keys** is now **Settings**, at the bottom left.
- Settings can be asked for in the conversation. The language, light or dark, the model and a lower daily limit change at once; the engine, what may be done without asking, and a higher or no limit open **Settings** with the change made, for you to save. A key pasted into the conversation is not sent anywhere: **Settings** opens with it filled in. "Connect to my server" (or an address, `root@1.2.3.4`) opens **Settings** on a new server with what was said filled in: the fields, the connection test and the password box are there, not in a card.
- On a server, **Save** first tests the connection with what is typed and saves only when it works; if not, the reason shows under the test button and the button becomes **Save anyway**. Refused with neither a password nor a key file, it says that a password (or key) is what is missing.
- With the Claude Code engines (this computer, or a server), what the permission setting does not allow is no longer refused: Claude Code asks APERIT (its permission prompt, over MCP), and the same card as the other engines comes to "my turn" (Allow once / Allow all like this / Don't). This also lets a command run on a server where Claude Code runs as root, one allowed command at a time.
- Changing "What it may do without asking" retries at once what the setting had blocked, or says in one line that the change was seen.
- On a server signed in as root, Claude Code refuses "Everything" (a safety rule of its own); Settings warns when that is chosen, and the error says so instead of "check that it is logged in".
- A question the AI asks just now comes first in "my turn", before older questions still waiting (they stay, one **Next** away).
- One wide window with everything in view: the ways in on the left, what the chosen one needs in the middle, spending and the optional keys on the right. Nothing to unfold, nothing to scroll, and the window keeps its size when you switch.
- **Model**: Opus 5.5 (the default), Sonnet 5.5 or Haiku 5.5 for the Claude API engine, each with what a turn roughly costs. Saved in `model.txt`; `APERIT_MODEL` still wins.
- **Spending**: what your Claude API key has spent today and this month, estimated from the tokens each response reports (`data/usage.json`). An optional **daily limit** (`spend-limit.txt`, `APERIT_DAILY_LIMIT`): at the limit no new turn, worker or background request starts that day, and the app says why.
- When Claude is overloaded (529), rate-limited (429) or briefly unreachable, the request is sent again after a wait (what the service asks, or 2 to 16 seconds; four more tries), with a line saying so. The OpenAI-compatible engine does the same.

### Who wrote a card

- PATET 0.5 gives every card a `by`: who wrote it. When the cards of a board name more than one writer, APERIT shows it small at the top right of each card (never as a field of a record). A worker's progress card carries the place it runs (`gpu`, `claude-code`), so workers on different machines are told apart.

### Work in order, with a second look

- A job can wait for others (`after`): it starts by itself when they are done, with their reports in its order, and does not start if one fails. A change and its review, or the steps of a pipeline, are handed over in one go.
- The AI is asked to add a review job after a change that matters, on another model or machine when there is one: it checks and reports, and fixes nothing.
- Workers can read the board and the other jobs' reports while they work.

### Several servers, and workers wherever their files are

- **Settings** → **A server (SSH)** keeps several servers, each with a name (chips above the fields; the green dot marks the one turns go to). `ssh.txt` holds them under `[name]` lines; the one-server file of before still reads. Passwords are kept per server.
- A worker can be sent to any of them, or to the Claude Code on this computer, or to the Claude API: the AI sees where it can send work (`<places>`) and names one with `on`. Workers in different places run at the same time.

### Long jobs keep going

- A long turn or job on the Claude API engine no longer runs out of room: past 100,000 input tokens (`APERIT_COMPACT_AT`) the API summarizes the older part and the work continues from the summary. On the OpenAI-compatible engine, old tool results are cleared instead. Each step then stops resending everything, so long jobs also cost less.

### A cleaner window

- Nothing is left cut off. The conversation shows the AI's sentence whole, even past the protocol's 80 characters for the caption. When text on a card is cut at its limit, a short turn rewrites it right away to fit (it is not part of the conversation). The instructions ask for a short answer (about 40 characters) and the details on cards.

- The line at the bottom left no longer shows numbers meant for development (model, time, tokens, card and character counts, the zoom hint). Set `APERIT_DEBUG` to see them.
- APERIT has an icon of its own, in the window, the taskbar and Explorer, and `aperit.exe` says what it is (name, version, maker) in its properties.
- `aperit.exe` runs on its own: WebView2Loader.dll, which web cards need, is carried inside it.

### Keys stay inside the app

- Keys are no longer written to plain files. On Windows they are encrypted for your user account on this computer (`data/keys.bin`); on macOS they go to the login keychain. Key files from earlier versions are moved in on the first start and removed.
- The ElevenLabs key can be entered in the **Settings** window; before, it needed a file.

### The protocol stands apart

- The board language is the **KITE Protocol**, version 0.3, in [protocol/](protocol): a specification that does not depend on the app, a guide to give your model, a comparison with A2UI, json-render, AG-UI and MCP Apps, a schema, examples, conformance cases, and implementations with no dependencies in Rust and JavaScript.
- **`<kite-board>`**, a renderer for the web as one custom element, for developers who want a board in their own product ([protocol/web](protocol/web)).
- Rejections carry a stable code and an English message (`err unknown-verb unknown verb 'foo'`). Before, they were Korean sentences.
- The desktop app uses the reference implementation. The Android app's Kotlin implementation was brought to 0.3 and runs the same conformance cases.
- New in the language with 0.3: a renderer says what it supports; a `record` card with fields the agent names; `doc` cards that point at long content; kinds a renderer adds (`x-...`); decisions with several answers or marked as not undoable; required boxes, time and choice in forms. See [PATET's CHANGELOG](https://github.com/fidenit/patet/blob/main/CHANGELOG.md).

### Use your own agent

- **MCP.** KITE can be an MCP server for an agent outside it (Claude Code, Cursor, your own): tools `screen`, `board_read`, `events`, `guide`. Choose **Your own agent** under **Settings**; the window gives you the command to connect.
- **AG-UI.** KITE can be the client of an AG-UI agent (LangGraph, CrewAI, Pydantic AI and others): give the agent's address under **Settings**.
- **The protocol socket answers back.** `read` returns the board, `watch` pushes what the user does and types. Verdicts are `ok`, `ok clipped ...`, `err <code> <message>`.
- With an outside agent, what you type and pick goes to it as events, and KITE runs no model.
- An outside agent cannot create web, image and file cards unless you allow it under **Settings**: those cards load what the agent chose.

### Use another model

- **Settings** is two things now: **Sign in with Claude** (one press when the Claude Code on this computer is signed in; otherwise it opens the sign-in), or **paste a key**. KITE tells whose key it is (Claude, OpenAI, Gemini, OpenRouter, Groq, xAI), sets the address, and picks the newest general model from the service's own list. Nothing else to fill in.
- A local model or another address is under **Settings** → **Local or other model**: any OpenAI-compatible endpoint (Ollama, LM Studio, ...). KITE's memory, workers and tools stay; web search is with Claude only.

### You decide what runs

- KITE is the interface; what an engine may do on your computer is a setting, not a trait of the app. With the Claude API engine and with another model, KITE now **asks first**: before a command runs or a file is created or changed, a card comes to "my turn" (Allow once / Allow all like this / Don't). Under **Settings** you can allow file edits, or everything. To keep the behaviour of 0.3.0, choose **Everything**.
- Reading and searching are not asked about. The Claude Code engine keeps following your Claude Code settings.

### English first

- The app starts in English. Switch to Korean in the corner, or set `KITE_LANG=ko`. An existing choice is kept.
- Settings from the environment are named `KITE_...`; the older `LYBOARD_...` names still work.
- Documentation for developers is in English: [docs/DEVELOPING.md](docs/DEVELOPING.md), [docs/DRIVING.md](docs/DRIVING.md).

### Since 0.3.0, also

- The protocol socket (`KITE_PROTOCOL_PORT`), the version header of the log, and the Android apps KITE and KITE Pro, which were added after the 0.3.0 release.

### Lighter turns

- The instructions KITE gives its model, and the tool descriptions, are in English: about 20% fewer input tokens per request, with the same boards in the user's language. The Korean instructions can be chosen with `prompt-lang.txt`.
- The instructions and tools are cached for an hour, not five minutes, so coming back to the board after a pause does not pay for everything again.
- The status line counts every request of a turn that uses tools. What was measured is in [docs/COSTS.md](docs/COSTS.md).

### Good to know

- Checked with real runs on a real window: the Claude API engine (Haiku and Opus), the Claude Code engine, the question card before a command, Claude Code connected as an outside agent over MCP, the OpenAI-compatible engine against a real server, and saving from the Settings window.
- AG-UI was checked with an agent built on Pydantic AI and a real model: the guide reached it as context, and its text drew the board. It answered in text; it did not call the `screen` tool in that run.
- Not tried: a model that is not Claude (so recognising a key and picking a model for the other services is untested against them), macOS, and the new cards on an Android device.
- Text sent back to a model about a rejected line changed from Korean to the coded English form. A board saved by 0.3.0 loads unchanged.

## 0.3.0 (2026-11-16)

Renamed to KITE. Stepped zoom, picking cards, the unseen browser, smoother resizing, Claude Code as a second engine. See the [release](https://github.com/fidenit/KITE/releases/tag/v0.3.0).
