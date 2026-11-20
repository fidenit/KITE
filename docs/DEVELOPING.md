# Developing APERIT

Building, keys, the layout of the code, and how to add a kind of card. What the program is: [README](../README.md). 한국어: [DEVELOPING.ko.md](DEVELOPING.ko.md) (older; this page is the current one).

## What is in the repository

```
protocol/        the PATET: specification, guide for agents, comparison, schema, examples,
                 conformance cases, the reference implementation (protocol/rust), the JavaScript
                 implementation (protocol/js) and the web renderer <patet-board> (protocol/web)
src/             APERIT for Windows and macOS (Rust, egui)
android/         APERIT and APERIT Pro for Android (Kotlin, Compose)
docs/            how to use and develop the apps
tools/promo/     how the intro video is recorded
```

The protocol does not depend on the apps. The apps depend on the protocol: the desktop app uses the crate in `protocol/rust`, and the Android app has its own implementation in Kotlin that is held to the same [conformance cases](../protocol/conformance). `node protocol/js/test.mjs` runs them against the JavaScript implementation; `protocol/web/index.html?selftest=1`, served by any static server, uses the web renderer as a person would and prints what happened.

## Build and run the desktop app

Windows (Rust `stable-x86_64-pc-windows-gnu` and MinGW from [WinLibs](https://winlibs.com)):

```powershell
.\run.ps1          # build and run
.\run.ps1 test     # tests of the app and of the protocol crate
.\run.ps1 pack     # make a folder you can carry: portable\APERIT
```

macOS (Rust from [rustup](https://rustup.rs), Xcode command line tools):

```sh
./run.sh            # build and run
./run.sh test
./run.sh pack       # portable/APERIT/APERIT.app
```

On Windows the program needs `WebView2Loader.dll` next to the executable (`run.ps1` copies it).

Run from the repository, the program keeps its files (keys, `data/`, `vault/`) in the repository folder; they are in `.gitignore`. A packed copy keeps them in its own folder.

## Keys and settings

Text files next to the program. The **Settings** window writes the first ones for you.

| File | Holds | Without it |
|---|---|---|
| `engine.txt` | `api`, `claude-code`, `openai`, `external` or `ag-ui` | `api` |
| `openai.txt` | another model: base address and model, one per line (its key is kept with the other keys) | |
| `agui.txt` | the address of an AG-UI agent | |
| `permission.txt` | `ask`, `edits` or `all`: what APERIT's own engines may do without asking | `ask` |
| `voice.txt` | ElevenLabs voice id | the default voice |

Keys are kept encrypted inside the app: on Windows for this user account on this computer only (`data/keys.bin`), on macOS in the login keychain. No key is written to a plain file. A key file left by an earlier version (`key.txt`, `jev.txt`, `eleven.txt`, the key line of `openai.txt`, the header lines of `agui.txt`) is moved in on the next start and removed. See `src/secret.rs`.

Every setting read from the environment is named `APERIT_<NAME>` (the older `LYBOARD_<NAME>` is still read). Those meant for users are listed in [DRIVING.md](DRIVING.md#settings). The rest are for checking and recording: `APERIT_DEMO`, `APERIT_AUTOPLAY` (types the given inputs by itself, in a window with another title), `APERIT_POS`, `APERIT_SIZE`, `APERIT_SHOT` (saves a picture of the window), `APERIT_KEY_WINDOW`, `APERIT_DEBUG` and others; search for `sys::var(` in `src/`.

## Checking a change without touching the desktop

A test window must not take the keyboard or the mouse, and must not be mistaken for the real one. Start a copy of the executable from an empty folder, off screen, with scripted input:

```sh
APERIT_AUTOPLAY="plan the workshop|!set k2 chosen o2" APERIT_POS="-2600,-2400" APERIT_SHOT=shot.bmp APERIT_SHOT_AT=9000 ./aperit
```

`APERIT_AUTOPLAY` entries are typed one after another; an entry starting with `!` is applied as a gesture of the user instead. To drive the board from outside in the same run, add `APERIT_PROTOCOL_PORT` or `APERIT_MCP_PORT` and use the socket or MCP ([DRIVING.md](DRIVING.md)).

## Layout of the code

| File | What it does |
|---|---|
| `protocol/rust/src/core.rs` | The board (groups, topics, cards), the card types, and `Board::apply`, the one check every op passes |
| `protocol/rust/src/lang.rs` | The line language: parsing, serializing, cutting a stream into lines |
| `src/main.rs` | The app: state, the turn, `apply_op_line` (the one door for every line, whoever wrote it), the event loop |
| `src/llm.rs` | Runs a turn on a thread and picks the engine; the demo scenes |
| `src/agent.rs` | The Claude API engine: the loop of model call and tool use; `use_tool`, shared with `openai.rs` |
| `src/cc.rs` | The Claude Code engine |
| `src/openai.rs` | The engine for OpenAI-compatible APIs |
| `src/agui.rs` | The AG-UI engine (APERIT as an AG-UI client) |
| `src/mcp.rs` | APERIT as an MCP server: the inside door for the Claude Code engine, the outside door for other agents |
| `src/feed.rs` | The protocol socket (line session) |
| `src/hub.rs` | Numbered events for those who follow the board from outside |
| `src/prompt.md`, `src/worker.md` | Instructions for the model and for workers (`{{PARTS}}` is filled from the card type table). `prompt.ko.md` and `worker.ko.md` are the older Korean ones, used with `APERIT_PROMPT=ko`; they are no longer kept in step |
| `src/tools.rs` | Tools: commands, files, memory notes, browser, web |
| `src/permit.rs` | What APERIT's own engines may do without asking, and the question card when they may not |
| `src/jobs.rs` | Workers: delegated jobs that run beside the conversation |
| `src/decide.rs`, `src/jev.rs` | Small decisions before and after the main model (Jev) |
| `src/memory.rs`, `src/vault.rs` | Past turns, core memory, the Obsidian vault |
| `src/ui.rs` | Drawing the board: layout, zoom, transitions, cards, direct manipulation |
| `src/chat.rs` | The left panel: "my turn", the conversation, the input, the keys window |
| `src/route.rs`, `src/page.rs`, `src/phone.rs`, `src/pics.rs` | Routes, the unseen browser, messages and calls (macOS), pictures |
| `src/tts.rs`, `src/eleven.rs`, `src/audio.rs` | Speaking, transcribing, speaker, microphone |
| `src/sys.rs` | The program folder, settings from the environment, the language, the clock, the title bar |

How a line reaches the board: every writer (the model's stream, the `screen` tool, a gesture of the user, the socket, MCP, an AG-UI event) ends in `App::apply_op_line`, which parses, applies, logs to `data/ops.log`, and publishes an event to `hub`. Nothing changes the board another way.

## Adding a kind of card

A new card type is a change to the protocol, so it starts there. (A kind that only one renderer draws needs none of this: name it `x-...` and draw it; every other renderer shows it as a record.)

1. `protocol/rust/src/core.rs`: add the kind to `Kind` and register it in `PARTS` (name, default size, fields with limits, item shape, and the description a model reads when choosing). If its items or fields need a check, add it in `put_item` or in `Op::Set`. If it waits for the user, add it to `Kind::asks`.
2. `protocol/SPEC.md` section 6 and the table in `protocol/GUIDE.md` (a test fails until the guide's table matches `PARTS`). Add a case under `protocol/conformance`.
3. `src/ui.rs`: draw it in `draw_body` (the compiler points at the missing kind).
4. `android/.../core/Board.kt`: the same entry in `PARTS` (a test compares it with `core.rs`), and its drawing in `ui/Cards.kt`.
5. `protocol/js/patet.js`: the same entry in `PARTS`, and its drawing in `protocol/web/patet-board.js`.

The instructions for the model need no edit: the card type table in them is built from `PARTS`.

## The Android apps (`android/`)

One codebase, two apps (`productFlavors` in `android/app/build.gradle.kts`).

| Flavor | App | Engine |
|---|---|---|
| `free` | APERIT (`com.fidenit.kite`) | The phone calls the Claude API with the key the user enters. As an advanced choice, the Claude Code on a server of the user's (SSH) |
| `pro` | APERIT Pro (`com.fidenit.kite.pro`) | Nothing to enter. The same requests go to a relay server, which holds the key and meters usage |

```sh
cd android
./gradlew assembleFreeDebug                                    # app/build/outputs/apk/free/debug/app-free-debug.apk
./gradlew -PkiteRelayUrl=https://<relay> assembleProDebug      # without it: http://10.0.2.2:8787, this PC seen from the emulator
./gradlew testFreeDebugUnitTest                                # board, language, conformance cases, API engine and Jev/ElevenLabs clients against fake servers, KiteViewModel start-up and restore (Robolectric, on the JVM)
```

JDK 17 or newer (the `jbr` of Android Studio) and the Android SDK (`ANDROID_HOME`, or `sdk.dir=` in `android/local.properties`).

| File (`app/src/main/java/com/fidenit/kite/`) | What it does |
|---|---|
| `core/Board.kt`, `core/Lang.kt` | The protocol in Kotlin, ported from `protocol/rust` |
| `engine/Api.kt` | The Claude API engine (official Java SDK): streaming, the tool loop, a one-hour prompt cache, usage and an estimated cost per request |
| `engine/PhoneTools.kt` | Tools that run on the phone: `screen`, the memory vault (`note_*`). Web search and fetch are Anthropic's server tools |
| `engine/Jev.kt`, `engine/Decide.kt` | The decision model: a client for TypeSafe's Jev (`src/jev.rs`) and the small decisions before and after a turn (`src/decide.rs`), with the same question texts and thresholds |
| `engine/Ssh.kt`, `engine/ClaudeCode.kt`, `engine/Detached.kt` | The Claude Code engine on a server (SSH, `claude -p`): each turn runs detached on the server and the app follows it (below) |
| `Journal.kt` | Files that survive the app being killed: the running turn, queued words and card-tap notices, runs still to clean up on the server, the current topic |
| `voice/` | Microphone and speaker, ElevenLabs transcription and speech, stop and wait words |
| `KiteViewModel.kt` | The turn, the order of speech and cards, conversation by voice, choosing the engine |
| `ui/` | The screen: topics, cards, settings |

- **A turn on a server runs detached.** The app uploads the turn text to `~/.kite/runs/<id>.in` on the server and starts `claude` there with `setsid nohup`; its output goes to `<id>.out`, its exit code to `<id>.exit`, the wrapper's process number to `<id>.pid` (`<id>` is made by the app: `[A-Za-z0-9-]+`, checked before it reaches a shell). The app then follows the `.out` file over SSH from the first line it has not received yet (the server prefixes each output line with `>`, and the app counts only whole lines). When the connection drops, or nothing arrives for 40 seconds, the app reconnects after 1, 2, 4, 8, 15, 30 s (then 30 s) and continues after the lines it already has; the status line says so. The end of a run is the exit line only, never the first `result` (one `claude` process can print two). Stop sends TERM to the run's process group (and KILL as a fallback) after checking that the saved process number is still this run's wrapper; if the server cannot be told within about 10 s (also while the app is connecting or reconnecting: closing the connection never waits for a connect in progress) the turn ends locally, the error line says that Stop did not reach the server, and the app stops and cleans that run with a connection of its own as soon as the server answers (retried after 1, 2, 4, 8, 15, 30 s, at once when the app starts or comes back to the front), not only at the next turn. After a stopped turn nothing starts by itself: queued words are dropped and card taps stay queued for the next message. The server files of a finished run (`.in .out .err .pid`) are deleted only after the saved board and conversation are on disk, then the record's delete is on disk (if a write fails or takes more than 5 s the record and the server files stay, and the next start replays the turn); the files of a run whose stop was not delivered are never removed before it is stopped; the `.exit` file stays (seven days, removed when a turn is uploaded) so that reattaching to a run that already ended answers `gone` and the turn is never run twice. Run ids that could not be stopped or cleaned are kept in `toclean.json`, merged and never overwritten, and an id leaves the list only after a confirmed stop and clean.
- **What survives a restart.** Before the first network call of an SSH turn the app writes `inflight.json` (the turn text, what the user saw, the board before the turn, the run id, the server as user@host:port, whether the start was confirmed); the record with the run id is on disk before the upload. A record is followed only on the server it was started on: with another server set (or an old record that does not say) nothing is followed or started there, the saved board is shown, one line says so, and the record is dropped. If the server has no such run, it is started again only when the record says it was never started. A turn that was stopped before the app was killed is not picked up again. When the app starts and finds it, it puts the pre-turn board back, takes up the card taps made since, and follows the run again from line 0, so the result of the turn lands on the board (speech is not played while it catches up). The record is deleted after the turn has been handled and saved. For the API engine (and an SSH turn that never got a run id) the record only makes the app say, once, that the last message was cut off and show it so it can be sent again. Queued words and card-tap notices (`queue.json`), the runs still to clean up, the current topic and the last spoken line are restored too. The board is saved after every card tap by a single writer thread, strictly in the order the writes and deletes were queued, and flushed (for one second at most; the main thread waits for the writer nowhere else) when the app goes to the background and when the screen closes, so a tap is on disk before the app can be stopped normally; talking by voice is switched off then. If the app is killed after the turn was saved but before its record was deleted, the replay takes that turn's exchange out by its sequence number (stored in the record and with each exchange in `recent.json`), so no earlier exchange is lost at the 40-exchange cap (an old record without the number: nothing is cut); card taps made during the interruption and during the replay are applied after the replay, in order. The connection test in Settings uses a connection of its own and leaves a running turn alone.
- Not there yet for this: a foreground service or notification (the app must be opened to receive a result), resuming an API-engine turn, a check on a real phone (the engine is tested against a real `sshd` and `claude` on a server, the app part by JVM tests and reading).
- The instructions are the desktop's `src/prompt.md`, copied at build time, with additions for the phone (`PHONE_NOTE`, `NOTE`).
- Core memory is `vault/기억/핵심.md`; with no separate keeper model, the model writes to it with `note_append`.
- Only debug builds may connect without TLS to `10.0.2.2` and `localhost`.
- **The decision model (Jev).** With a TypeSafe Jev key in Settings (kept like the other keys, in the encrypted store; empty means none) a turn gets two small decisions, both from one call to `https://api.typesafe.ai/v1/systemone` that must finish within 2.5 s or is dropped (no key, no answer, a status other than 200 or an odd body all mean "no decision": the turn goes on as without Jev and nothing is shown for it).
  - Before the turn (not for a turn that is picked up again after a restart, and only when something was typed or said): the kind of request is put in the status line, the topic Jev picks is shown at once, and for a request that asks for work, a screen change or a memory search a short acknowledgement is given first ("네, 바로 해볼게요." and so on; it is read aloud when talking by voice, otherwise shown as the line under the cards) and the turn text gets `<already_said>` so the model does not repeat it. Effort and kind need a confidence of at least 0.5, the topic 0.6.
  - After the turn (in the background): for the cards the model touched (at most 20) Jev says whether each is clear from the screen alone and how important it is. A card that is clear with a probability below 0.4 is named once in `<review>` in the next turn text; the importance (score / 2, three levels) puts the more important cards first, after the cards that wait for an answer.
  - Every call is logged to `jev.log` in the app's files (time, app version, the first 40 characters of the input, the values chosen, the time taken; never the key).
- ElevenLabs: if reading aloud with the ElevenLabs voice fails (wrong key, limit, no connection) the sentence is read with the phone's own voice and the error is shown once; transcription stops at once on HTTP 401, 403 or 429 and shows the first error text; the microphone permission is not asked while the ElevenLabs key is empty (the key error is shown instead).
- Not there yet (Jev): reading memory notes in advance (the notes of the SSH engine live on the server), the tidy turn that merges overlapping topics (the phone has no idle turn), recall of old conversations (`<recall>`; the phone keeps no turn history), the check whether a heard sentence is meant for the assistant while it waits (that is Claude Haiku, not Jev), and a per-turn effort from Jev (the effort in Settings is used).
- Not there yet (voice): audio focus (a call or music at the same time), whether the phone listens to its own speaker, the screen freezing for up to 0.5 s when the microphone is stopped. These only show on a real device.
- Not tested: the Jev and ElevenLabs paths were run against fake servers only (never api.typesafe.ai or api.elevenlabs.io), and no real phone or emulator was used for any of this; the ViewModel is run on the JVM with Robolectric.
- Not there yet: routes, workers, browser tools, messages and calls, attached pictures, the Play subscription for Pro, and the bindings for outside agents (MCP, AG-UI, the socket).

## The relay

The server APERIT Pro talks to (it holds the key, meters and limits each user, checks the subscription) is kept in a separate private repository. What the app relies on:

- The `baseUrl` of the Anthropic Java SDK is the relay's address and the `apiKey` is the subscriber's token. The address is given at build time (`-PkiteRelayUrl=https://…`) and is not in this repository.
- Over the month's limit the relay answers HTTP 402, which the app shows as "this month's usage is used up".
- `GET /v1/usage` (header `x-api-key: <token>`) returns `used_usd`, `budget_usd` and `plan`.

## Releasing

1. Bump `version` in `Cargo.toml` (the app) and, when the protocol changed, `VERSION` in `protocol/rust/src/lang.rs`, the crate version, and `protocol/CHANGELOG.md`.
2. Add the release to [CHANGELOG.md](../CHANGELOG.md). Release notes are written in English.
3. `.\run.ps1 test`, then `.\run.ps1 pack`; zip `portable\APERIT` without `data\` and `vault\` (the keys are in `data\`).
4. Tag `v<version>` and attach the zip to a GitHub release.
