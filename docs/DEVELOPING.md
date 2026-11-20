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
| `pro` | APERIT Pro (`com.fidenit.kite.pro`) | Nothing to enter. Each turn runs detached on our server's Claude Code behind an HTTPS gateway (below); the phone enrolls itself on first launch |

```sh
cd android
./gradlew assembleFreeDebug                                    # app/build/outputs/apk/free/debug/app-free-debug.apk
./gradlew -PaperitGatewayUrl=https://<gateway> assembleProDebug # without it: http://10.0.2.2:8787, this PC seen from the emulator
android/scripts/check-apk-secrets.sh <apk>                     # exit 1 when a key, device token, private key or bearer token is in the apk; lists every URL host
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

- **A turn on a server runs detached.** The app uploads the turn text to `~/.kite/runs/<id>.in` on the server and starts `claude` there with `setsid nohup`; its output goes to `<id>.out`, its exit code to `<id>.exit`, the wrapper's process number to `<id>.pid` (`<id>` is made by the app: `[A-Za-z0-9-]+`, checked before it reaches a shell). The app then follows the `.out` file over SSH from the first line it has not received yet (the server prefixes each output line with `>`, and the app counts only whole lines). When the connection drops, or nothing arrives for 40 seconds, the app reconnects after 1, 2, 4, 8, 15, 30 s (then 30 s) and continues after the lines it already has; the status line says so. The end of a run is the exit line only, never the first `result` (one `claude` process can print two). Stop sends no signal from the app's command: it leaves a stop request file, `<id>.stop`, prints one line saying so (from that line on the app counts the Stop as delivered, even when the run needs longer to end), and waits about 3 s for the exit code. The wrapper keeps the process number of the `claude` it started in a shell variable only and looks for that file every 0.2 s for as long as that child lives (whether it lives is asked from bash itself; `ps`, or `/proc` where `ps` gives no answer, only adds the check that the child is still its own and not a zombie, so a `ps` that fails or lacks the options never ends the watch); it sends a signal only on a pass on which it has just confirmed that the child is alive and its own, and only while the stop request file is still there: TERM to that child and the child's descendants (children first) on the first such pass, and about a second after that (five such passes later) KILL to that tree, again on every such pass until the child is gone; on a pass where it cannot confirm (neither `ps` nor `/proc` gave an answer) it sends nothing and keeps watching. When the child is gone it ends what is left in its own session (only where `setsid` exists), and writes the exit code. Every number it signals is its own child's or one a process listing just gave it, checked to be digits only, above 1 and not itself; the saved number in `.pid` is only read to see whether the wrapper is still there (by its command line, without a signal), so a stale or wrong pid file can never cause a signal to another process. Following reports a run as `lost` only after three such checks in a row, 0.3 s apart, found no wrapper and no exit code. If the wrapper itself is killed from outside while `claude` lives, nothing watches the stop request any more: that `claude` runs to its own end and Stop cannot end it. A start that arrives after the stop request launches nothing and records `stopped`; cleaning a run removes the turn text `.in` first (so a late start finds nothing to launch) and keeps, or writes, the stop request while the run may still be running: when the `.pid` file is there and the end value in `.exit` is missing, empty or `stopped`. Otherwise (a real exit code in `.exit`, or no `.pid` file) it removes the stop request. A `.stop` file that was kept stays beside `.exit` until the seven-day cleanup. Once the server has answered that a run is over (an exit code, `stopped`, `lost`, `gone`), the app hands that outcome on once and does not reconnect or start the run again. If the server cannot be told within about 10 s (also while the app is connecting or reconnecting: closing the connection never waits for a connect in progress) the turn ends locally, the error line says that Stop did not reach the server, and the app stops and cleans that run with a connection of its own as soon as the server answers (retried after 1, 2, 4, 8, 15, 30 s, at once when the app starts or comes back to the front), not only at the next turn. After a stopped turn nothing starts by itself: queued words are dropped and card taps stay queued for the next message. The server files of a finished run (`.in .out .err .pid`) are deleted only after the saved board and conversation are on disk, then the record's delete is on disk (if a write fails or takes more than 5 s the record and the server files stay, and the next start replays the turn); the files of a run whose stop was not delivered are never removed before it is stopped; the `.exit` file stays (seven days, removed when a turn is uploaded) so that reattaching to a run that already ended answers `gone` and the turn is never run twice. Run ids that could not be stopped or cleaned are kept in `toclean.json`, merged and never overwritten, and an id leaves the list only after a confirmed stop and clean.
- **What survives a restart.** Before the first network call of an SSH turn the app writes `inflight.json` (the turn text, what the user saw, the board before the turn, the run id, the server as user@host:port, whether the start was confirmed); the record with the run id is on disk before the upload. A record is followed only on the server it was started on: with another server set (or an old record that does not say) nothing is followed or started there, the saved board is shown, one line says so, and the record is dropped. If the server has no such run, it is started again only when the record says it was never started. A turn that was stopped before the app was killed is not picked up again. When the app starts and finds it, it puts the pre-turn board back, takes up the card taps made since, and follows the run again from line 0, so the result of the turn lands on the board (speech is not played while it catches up). The record is deleted after the turn has been handled and saved. For the API engine (and an SSH turn that never got a run id) the record only makes the app say, once, that the last message was cut off and show it so it can be sent again. Queued words and card-tap notices (`queue.json`), the runs still to clean up, the current topic and the last spoken line are restored too. The board is saved after every card tap by a single writer thread, strictly in the order the writes and deletes were queued (a write that failed, for example with the storage full, is tried again by the writer each time the app waits for the disk, so saving works again without a restart once the disk does), and flushed (for one second at most; the main thread waits for the writer nowhere else) when the app goes to the background and when the screen closes, so a tap is on disk before the app can be stopped normally; talking by voice is switched off then. If the app is killed after the turn was saved but before its record was deleted, the replay takes that turn's exchange out by its sequence number (stored in the record and with each exchange in `recent.json`), so no earlier exchange is lost at the 40-exchange cap (an old record without the number: nothing is cut); card taps made during the interruption and during the replay are applied after the replay, in order. The connection test in Settings uses a connection of its own and leaves a running turn alone.
- Not there yet for this: a foreground service or notification (the app must be opened to receive a result), resuming an API-engine turn, a check on a real phone (the engine is tested against a real `sshd` and `claude` on a server, the app part by JVM tests and reading).
- The instructions are the desktop's `src/prompt.md`, copied at build time, with additions for the phone (`PHONE_NOTE`, `NOTE`).
- Core memory is `vault/기억/핵심.md`; with no separate keeper model, the model writes to it with `note_append`.
- Only debug builds may connect without TLS to `10.0.2.2` and `localhost`.
- **The decision model (Jev).** With a TypeSafe Jev key in Settings (kept like the other keys, in the encrypted store; empty means none) a turn gets two small decisions, both from one call to `https://api.typesafe.ai/v1/systemone` that must finish within 2.5 s or is dropped (no key, no answer, a status other than 200 or an odd body all mean "no decision": the turn goes on as without Jev and nothing is shown for it).
  - Before the turn (not for a turn that is picked up again after a restart, and only when something was typed or said): the kind of request is put in the status line, the topic Jev picks is shown at once, and for a request that asks for work, a screen change or a memory search a short acknowledgement is given first ("네, 바로 해볼게요." and so on; it is read aloud when talking by voice, otherwise shown as the line under the cards) and the turn text gets `<already_said>` so the model does not repeat it. Effort and kind need a confidence of at least 0.5, the topic 0.6.
  - After the turn (in the background): for the cards the model touched (at most 20) Jev says whether each is clear from the screen alone and how important it is. A card that is clear with a probability below 0.4 is named once in `<review>` in the next turn text; the importance (score / 2, three levels) puts the more important cards first, after the cards that wait for an answer.
  - Every call is logged to `jev.log` in the app's files (time, app version, the first 40 characters of the input, the values chosen, the time taken; never the key).
- ElevenLabs: if reading aloud with the ElevenLabs voice fails before any sound of the sentence was played, the speaker is stopped and the sentence is read with the phone's own voice; if the stream drops in the middle of a sentence, the speaker is stopped and the sentence is not read again. One line says, in everyday words, what was wrong (key refused, usage limit, connection, the phone could not play the sound, anything else: `Eleven.Why`), what was done with the sentence and what to do (`voice/Speaking.kt`); it is shown once until a sentence is read without a failure, also when the phone's own voice cannot be used; transcription stops at once on HTTP 401, 403 or 429; a failed transcription shows one line of the same kind (`Speaking.heardNotice`: the cause, that the words were not sent and the app keeps listening, what to do), once until a transcription works again (if talking was switched off meanwhile the line says so instead of "keeps listening"); the phone's own voice is given up after a time limit (10 s for its engine to start, 5 s + 1 s per character for a sentence: `Speaking.phone`) and that sentence counts as not read; a microphone that cannot be used switches talking off and shows one such line (`Speaking.micNotice`: no permission, could not be opened, stopped while listening); the microphone permission is not asked while the ElevenLabs key is empty (the key error is shown instead).
- Not there yet (Jev): reading memory notes in advance (the notes of the SSH engine live on the server), the tidy turn that merges overlapping topics (the phone has no idle turn), recall of old conversations (`<recall>`; the phone keeps no turn history), the check whether a heard sentence is meant for the assistant while it waits (that is Claude Haiku, not Jev), and a per-turn effort from Jev (the effort in Settings is used).
- Not there yet (voice): audio focus (a call or music at the same time), whether the phone listens to its own speaker, the screen freezing for up to 0.5 s when the microphone is stopped. These only show on a real device.
- Not tested: the Jev and ElevenLabs paths were run against fake servers only (never api.typesafe.ai or api.elevenlabs.io), and no real phone or emulator was used for any of this; the ViewModel is run on the JVM with Robolectric.
- Not there yet: routes, workers, browser tools, messages and calls, attached pictures, the Play subscription for Pro, and the bindings for outside agents (MCP, AG-UI, the socket).

## The gateway (APERIT Pro)

APERIT Pro talks to a gateway kept in a separate private repository. It runs each turn as a detached Claude Code run on the server, exactly like the SSH engine, and keeps the board on the server. The address is given at build time (`-PaperitGatewayUrl=https://…`, `BuildConfig.GATEWAY_URL`) and is not in this repository. What the app relies on:

- **Identity.** On first launch the app makes a device id (UUID) and a device secret (`apd_` + 32 random bytes, base64url) and keeps both only in the encrypted store; if that store cannot be opened or read (restored onto another phone, damaged) it is wiped and the phone enrolls again as a new device, never falling back to plain storage. `POST /v1/enroll` sends the id and the SHA-256 of the secret; the answer is `approved`, `pending` with a 6-digit code (approved on the server by the owner) or `revoked`. A build may carry a single-use enrollment ticket (`-PaperitEnrollTicket=…`, `BuildConfig.ENROLL_TICKET`, never in the repository): it is sent until the server has judged it, and a used or expired ticket leaves the phone pending with its code. A pending device expires on the server after 30 minutes; `/v1/me` then answers 401 and the app enrolls again and shows the new code. The status is checked when the app starts, when it comes to the front and before each message.
- **Turns.** `engine/Link.kt` is the seam under `ClaudeCode`: `SshLink` sends today's commands over SSH, `GatewayLink` (OkHttp) uses `PUT /v1/runs/{id}/input`, `POST …/start`, `GET …/follow?n=` (server-sent events: each `data:` is one line of the SSH follow loop), `POST …/stop`, `DELETE /v1/runs/{id}`, `GET …/err`, `PUT /v1/system`, `GET /v1/core`, `GET /v1/info`. A dropped call gives the same "reconnect from line n" outcome as a dropped SSH command; HTTP 401/403 end the turn with a line naming the reason; `APERITFAIL busy` (the server already runs its limit of turns or is short of memory) has its own line. Every request carries `Authorization: Bearer <device secret>` and `X-Aperit-App: <version>-pro`.
- **State.** After each local save the board, the last 40 exchanges and the current topic go to `PUT /v1/state` (debounced, retried, never blocking the screen; `If-Match` = the last revision the phone has). On start the app takes the server's document when the phone has no saved board or an older revision; on 412 it takes the server's document and says so in one line. The replaced local copy is kept in `state-backup.json`.
- `pro.log` in the app's files records status changes and state revisions and sizes (never the secret, the ticket or the content).
- **Board and the "My turn" dock (0.4.0).** The board fills rows with cards in board order: the packer is `core/BoardLayout.kt` (minimum width per kind = padding + fixed dp + c syllables of the width of "가"; text never shrinks), drawing is `ui/BoardGrid.kt` (equal heights in a row, scroll to and flash the pointed cards). One waiting question (decision or input with `chosen`/`sent` empty, any topic) sits in the "My turn" dock right above the input row (`ui/TodoDock.kt`, the dock slot of `BottomBar`). Which one is `TodoPicker` in `core/Todo.kt`: an AI `focus` replaces it unless the user is typing in it, a new question never replaces a waiting docked one, after an answer the next one docks at once (same topic, then the first new one, then the oldest), and with nothing else waiting the answered card stays as "Applying" while the AI runs. When a card docks the board switches to its topic and points at the cards the AI changed in that question's turn. Typed values live in the view model (`draftOf`, `marksOf`). The dock's height limit is `Dim.DOCK_MAX`. `pro.log` gets one line per dock change (card id, reason, count, no content).
- **Screen (0.3.2).** Every size of the phone screen is in `ui/Dim.kt` (text in sp, spacing and shapes in dp); the composables in `ui/` read them from there. Text boxes in form cards and Settings share `FieldBox` (ui/Screen.kt). A tap outside text boxes and buttons, a send, and Done clear the focus and hide the keyboard. Pro sends `X-Aperit-App: <versionName>-pro_w<W>h<H>d<D>f<F>` (screen width and height in dp, density and font scale with two decimals, built by `proAppHeader` in AppHeader.kt); if that does not fit the gateway's `^[A-Za-z0-9._-]{1,40}$` it sends `<versionName>-pro`.
- **Voice (0.3.0).** `voice/Eleven.kt` calls a `Target` (base URL + auth headers): the free app calls ElevenLabs as before (`https://api.elevenlabs.io/v1/…`, `xi-api-key`), Pro calls `<gateway>/v1/voice/text-to-speech/{voice}/stream?output_format=…` and `<gateway>/v1/voice/speech-to-text` with the device bearer and `X-Aperit-App`, no key header, no redirects, the same request bodies. `/v1/me` says `"voice": true|false`; the app keeps the last value (refreshed with the status) and shows the Talk and microphone buttons and the voice section of Settings (conversation language, microphone sensitivity) only when it is true. Gateway error bodies (`voice_off`, `voice_key`, `voice_limit`, `voice_refused`, `voice_unreachable`, `rate_limited`, `too_large`, `bad_request`, and 401/403 for the device) become `Eleven.Gate`; a 5xx whose body is not the gateway's JSON counts as not reaching the server. The lines are in `voice/ProVoice.kt`: they name no key, no ElevenLabs and none of the app's settings (only the phone's own text-to-speech setting is named, when the phone's voice cannot be used either). After a server-voice failure the rest of that answer is read with the phone's own voice; the next answer tries the server again. A sentence cut off after part of it was played is not read again. When the server switches voice off during a conversation, the conversation ends with one line. A 401/403 starts the normal status check; right after an approval that came from enrolling, the app asks `/v1/me` once more so the Talk button appears without a restart. `pro.log` gets one line per voice call with numbers and words only (characters, bytes, milliseconds, reason).
- Jev is off in Pro for now; Settings shows the connection status and the short device id (first 8 characters), plus the voice section when the server's voice is on.
- The pro app is excluded from cloud backup and device-to-device transfer (`allowBackup="false"`, and `dataExtractionRules` in `src/pro/`).

## Releasing

1. Bump `version` in `Cargo.toml` (the app) and, when the protocol changed, `VERSION` in `protocol/rust/src/lang.rs`, the crate version, and `protocol/CHANGELOG.md`.
2. Add the release to [CHANGELOG.md](../CHANGELOG.md). Release notes are written in English.
3. `.\run.ps1 test`, then `.\run.ps1 pack`; zip `portable\APERIT` without `data\` and `vault\` (the keys are in `data\`).
4. Tag `v<version>` and attach the zip to a GitHub release.
