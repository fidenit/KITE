# APERIT: notes for whoever works here next

APERIT is an AI workspace with no chat window: the user talks, the AI builds and edits a board of cards. It was called KITE until version 0.3, and the local folder may still be named `KITE`. The language the AI writes is PATET (https://github.com/fidenit/patet), a separate repository. The owner reads Korean; write to them in Korean. Text in the repository and defaults in the app are English; the app has a language setting.

## Layout

| | |
|---|---|
| `src/` | The desktop app (Rust, egui): `main.rs` the app, `ui.rs` drawing, `chat.rs` the left panel and the Settings window, `agent.rs` / `openai.rs` / `cc.rs` / `agui.rs` / `mcp.rs` the engines, `prompt.md` / `worker.md` the instructions for the model (English; `*.ko.md` are older Korean ones) |
| `protocol/` | **A copy of the PATET repository. Do not edit it here.** Change PATET, then run `python tools/vendor_patet.py` with the PATET repository beside this one |
| `android/` | The Android app (Kotlin, Compose). `core/Board.kt` and `core/Lang.kt` are the Kotlin implementation of PATET, and are copied into PATET's `kotlin/` |
| `docs/` | `DRIVING.md` (engines, settings), `DEVELOPING.md`, `COSTS.md` (tokens), `NEXT.md` (history and what is next, in Korean) |

## Build and check

Windows: Rust `stable-x86_64-pc-windows-gnu` and WinLibs MinGW on the PATH. `.\run.ps1` builds and starts; `.\run.ps1 test`; `.\run.ps1 pack` makes `portable\APERIT` (and moves an old `portable\KITE` there whole). macOS: `./run.sh`, same words. Never run `pack` just to test: it replaces the owner's installed copy.

`.\run.ps1 dist` makes `dist\APERIT-Setup-<version>.exe` (Inno Setup 6, per-user, no admin rights; `installer\aperit.iss`) and `dist\APERIT-<version>-windows-x64.zip`. `build.rs` puts the icon and version details into the exe (windres) and links WebView2Loader.dll to load late (dlltool, gendef, delayimp from MinGW); `src/loader.rs` writes the DLL it carries to `data\` when none is beside the exe, so `aperit.exe` alone runs. Engines live in `src/llm.rs`; Claude Code on a server is `src/ssh.rs` + the remote branch of `src/cc.rs`. The Settings window is `key_window` in `src/chat.rs`; spending, prices and the daily limit are `src/usage.rs`; retries on 429/529/5xx are `agent::with_retries` (used by `openai.rs` too).

```sh
cargo test --workspace
node protocol/js/test.mjs
cd android && ./gradlew testFreeDebugUnitTest      # needs JAVA_HOME (Android Studio's jbr) and ANDROID_HOME
```

Not in the repository, and needed to run with a real model: a key pasted in the Settings window, or "Sign in with Claude" there. Keys are never in a plain file: `src/secret.rs` keeps them encrypted (Windows DPAPI in `data/keys.bin`, the keychain on macOS). For a test copy in another folder on this PC, copy `data/keys.bin` along: it opens for the same user on the same computer. `data/` (the board) and `vault/` (memory notes) are local too.

## Testing a window without touching the owner's desktop

Never send keys or the real mouse. Start a copy off screen and let it play a script:

```sh
APERIT_AUTOPLAY="plan a workshop|!set k2 chosen o2" APERIT_POS="-2800,-2400" APERIT_SIZE="1500,950" \
APERIT_SHOT=shot.bmp APERIT_SHOT_AT=9000 APERIT_MODEL=claude-haiku-5-5 ./aperit
```

`!op` is a user gesture, `+!op` a gesture during a turn (`+@wait N` holds the next `+` steps N seconds, e.g. until a permission card has come: `+@wait 40|+!set ask1 chosen yes`), `@click x y` and `@type text` drive the built-in pointer; `data/ops.log`, `data/rejects.log` and `data/timing.jsonl` say what happened. Settings are `APERIT_<NAME>` (the old `KITE_<NAME>` still works). Android without a key: on a debug build, `adb shell run-as com.fidenit.kite cp /data/local/tmp/board.txt files/board.txt` puts a board on screen.

## Kept on purpose under the old name

The Android package `com.fidenit.kite` and its stored settings (changing them makes it a different app), vault tags `kite/...`, worktree branches `kite/...`, the promo video's file name, the private server repository `kite-cloud`.

## Where things stand (2026-11-19)

- The protocol copy in `protocol/` is PATET 0.5 (adds `by`, who wrote a card). Workers: `on` (where), `after` (order), compaction for long jobs; see `docs/DRIVING.md` "Where workers run".
- 0.4.0 is not released: no tag, nothing uploaded; `.\run.ps1 dist` builds the installer and the zip locally. The owner's installed copy is still the old `portable\KITE`; `.\run.ps1 pack` moves it, and only the owner should run that.
- Checked with real runs: the Claude API engine, the Claude Code engine, Claude Code as an outside agent over MCP, the OpenAI-compatible engine (against Anthropic's compatible address only), AG-UI with a Pydantic AI agent, the new cards on an Android emulator.
- Not checked: any model that is not Claude; macOS; a real Android device; signing in when Claude Code is not yet signed in.
- The full list of what is next is at the top of `docs/NEXT.md` (Korean), including what it takes to make the Android apps installable (no APK is published; APERIT Pro needs its relay server, subscription check and a build with that address). First item: when lines of a turn are rejected, run one more turn at once (PATET's SPEC 10.1 recommends it; this app only reports them on the next turn). Then the release.
- Open decisions for the owner: the app's license (the protocol stays Apache-2.0), the 0.4.0 release.
