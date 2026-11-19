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

Text files next to the program. The **Keys** window writes the first ones for you.

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
./gradlew testFreeDebugUnitTest                                # board, language, conformance cases, API engine against a mock server
```

JDK 17 or newer (the `jbr` of Android Studio) and the Android SDK (`ANDROID_HOME`, or `sdk.dir=` in `android/local.properties`).

| File (`app/src/main/java/com/fidenit/kite/`) | What it does |
|---|---|
| `core/Board.kt`, `core/Lang.kt` | The protocol in Kotlin, ported from `protocol/rust` |
| `engine/Api.kt` | The Claude API engine (official Java SDK): streaming, the tool loop, a one-hour prompt cache, usage and an estimated cost per request |
| `engine/PhoneTools.kt` | Tools that run on the phone: `screen`, the memory vault (`note_*`). Web search and fetch are Anthropic's server tools |
| `engine/Ssh.kt`, `engine/ClaudeCode.kt` | The Claude Code engine on a server (SSH, `claude -p`) |
| `voice/` | Microphone and speaker, ElevenLabs transcription and speech, stop and wait words |
| `KiteViewModel.kt` | The turn, the order of speech and cards, conversation by voice, choosing the engine |
| `ui/` | The screen: topics, cards, settings |

- The instructions are the desktop's `src/prompt.md`, copied at build time, with additions for the phone (`PHONE_NOTE`, `NOTE`).
- Core memory is `vault/기억/핵심.md`; with no separate keeper model, the model writes to it with `note_append`.
- Only debug builds may connect without TLS to `10.0.2.2` and `localhost`.
- Not there yet: the decision model (Jev), routes, workers, browser tools, messages and calls, recall of old conversations, attached pictures, the Play subscription for Pro, and the bindings for outside agents (MCP, AG-UI, the socket).

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
