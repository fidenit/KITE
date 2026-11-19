# Use APERIT with your own agent or model

APERIT is a renderer of the [PATET](../protocol/README.md): it keeps a board and draws it. Who writes the board is up to you. This page is the how-to for the APERIT desktop app; the rules of the language are in [protocol/SPEC.md](../protocol/SPEC.md).

| You have | Use | APERIT's own memory, workers, tools |
|---|---|---|
| An agent that speaks MCP (Claude Code, Cursor, your own) | [MCP](#mcp-your-agent-comes-to-aperit) | not used; the agent brings its own |
| An agent built on an AG-UI framework (LangGraph, CrewAI, Pydantic AI, ...) | [AG-UI](#ag-ui-aperit-goes-to-your-agent) | not used |
| A script or a program | [The protocol socket](#the-protocol-socket-a-script-writes-the-board) | not used |
| Another model (OpenAI, a local model, OpenRouter) | [Another model](#another-model-aperits-own-loop) | kept |
| Claude | The Claude API key or Claude Code engine ([README](../README.md#pick-an-engine)) | kept |
| Claude Code on a server | [A server over SSH](#a-server-claude-code-over-ssh) | kept, on this computer |

All of these are chosen in the app under **Keys** (bottom left), or with the files and variables in the [settings table](#settings).

## MCP: your agent comes to APERIT

1. In APERIT, open **Keys**, choose **Your own agent**, and save. APERIT stops running a model of its own and opens an MCP door at `http://127.0.0.1:7711/mcp`.
2. Open **Keys** again and press **Copy the Claude Code command** (or **Copy the config (JSON)** for other clients). For Claude Code the command is:

   ```sh
   claude mcp add --transport http aperit http://127.0.0.1:7711/mcp --header "Authorization: Bearer <token>"
   ```

   The same config is in `data/mcp.json` in the program folder.
3. Tell your agent to use the board. It gets four tools:

   | Tool | What it does |
   |---|---|
   | `guide` | Returns the [guide for agents](../protocol/GUIDE.md): ops, card types, how to compose a board. Read once |
   | `board_read` | The board as ops; with `topic`, one topic in full |
   | `screen` | Applies `ops` (one per line); answers `applied` or the rejected lines with a code each |
   | `events` | What you typed (`input <text>`) and did on the board (`user <op>`) since the last call; waits up to `wait` seconds |

A loop that serves the user looks like this:

```
guide()                                  once
events(wait=50)                          -> input plan a team workshop for next week
screen("say Two venues compared. ...")   the answer is the board
events(wait=50)                          -> user set k2 chosen o2
screen("say Gapyeong it is.\ndel k2\n...")
```

What you type in APERIT while no agent is connected is kept (the last 2000 events) and delivered when one asks.

You can also open the MCP door next to another engine, so that an agent outside can add to a board Claude is working on: set `APERIT_MCP_PORT`, or write a port number in `mcp-port.txt`.

## AG-UI: APERIT goes to your agent

1. Run your agent as an AG-UI server (an HTTP endpoint that takes a `RunAgentInput` and streams events).
2. In APERIT, open **Keys**, choose **Your own agent**, type the endpoint under **AG-UI** (for example `http://localhost:8000/agent`), and save.

On every turn APERIT POSTs the board, what you did and said, the guide for agents (as `context`) and one frontend tool, `screen`. It applies what comes back from any of:

- the agent's text, read as ops line by line while it streams;
- `screen` tool calls (the verdict returns to the agent as a tool message in a following run);
- `CUSTOM` events named `patet.ops` whose value is a string of ops or an array of op strings.

The exact shape is in [SPEC.md, section 10.5](../protocol/SPEC.md#105-ag-ui). An agent whose model follows the guide needs no APERIT-specific code. An agent that answers in prose still shows up: its text becomes the caption and a note card.

Headers (an `Authorization` header, for example) carry tokens, so they are kept with the keys, encrypted. To add them, write them on lines after the address in `agui.txt`; on its next start APERIT moves them into its store and leaves only the address in the file:

```
https://agent.example.com/run
Authorization: Bearer <your token>
```

## The protocol socket: a script writes the board

Start APERIT with `APERIT_PROTOCOL_PORT` set and it listens on `127.0.0.1` for lines. It works with any engine, the demo included.

```python
import socket
s = socket.create_connection(("127.0.0.1", 7878))   # APERIT_PROTOCOL_PORT=7878
f = s.makefile("rw", encoding="utf-8", newline="\n")
def send(line):
    f.write(line + "\n"); f.flush()
    return f.readline().strip()

print(send("topic demo Demo"))                  # ok
print(send("card k1 metric demo 2x2 Users"))    # ok
print(send("set k1 value 42"))                  # ok
print(send("card k1 metric demo 2x2 Again"))    # err exists card 'k1' already exists; ...
```

Besides ops, a session takes `read [<topic>]` (the board comes back as lines starting with `| `, then `ok`), `watch` (the user's `event user <op>` and `event input <text>` lines are pushed to you), `watch all` and `unwatch`. See [SPEC.md, section 10.3](../protocol/SPEC.md#103-line-session).

Limits: 4096 bytes a line, 50 lines a second per connection (bursts of 100), 4 connections. Everything is logged to `data/feed.log`.

## A server: Claude Code over SSH

APERIT can hand each turn to the Claude Code on a server instead of the one on this computer. Under **Keys** → **A server (SSH)**, give the address, the user, and either a key file or a password (or nothing, to use the ssh agent and your usual keys), and the folder to work in. **Test the connection** signs in and asks the server's Claude Code for its version.

- Files and commands are the server's. Claude Code's login, `CLAUDE.md`, skills, hooks and permissions there apply.
- APERIT's own tools (memory notes, route maps, workers, reading the board) stay on this computer. The server's Claude Code reaches them through a reverse tunnel on the same SSH connection, to `127.0.0.1` on both ends, with a token.
- It uses the `ssh` that Windows 10/11 and macOS already have. The first connection accepts the server's host key and remembers it (`~/.ssh/known_hosts`); a changed key is refused.
- A password is kept with the other keys, encrypted, and handed to ssh by APERIT itself (as its `SSH_ASKPASS`): it is never on a command line or in a file.
- On the server, APERIT keeps the instructions it sends in `~/.aperit/`, and pictures you attach while it runs.

Several servers can be kept. Each has a name; the Keys window shows them as chips above the fields, with a green dot on the one turns go to, and **+ New server** for another. Saving makes the one shown the one turns go to.

The settings, other than the passwords, are in `ssh.txt` next to the program: a `use=<name>` line, then each server under a `[name]` line with one `name=value` per line: `host`, `port`, `user`, `key`, `dir`, and `claude` (how to call Claude Code there, when it is not on the PATH). A file from an earlier version (the lines without a `[name]`) is read as one server named after its host. Each server's password is kept apart, encrypted.

## Where workers run

The AI you talk to hands long work to workers, which run at the same time while it goes on talking with you. A worker runs where the AI runs, unless it is sent elsewhere: when there is more than one place, each turn lists them (`<places>`: the Claude API, the OpenAI-compatible service, the Claude Code on this computer, each saved server) and `job_start` takes `on`, the name of one. So a job on a server's repository can go to that server while another runs here. On a server, a worker's folder is a folder there (the server's `dir` unless the job names one); `worktree` is for workers on this computer only. The progress card says where a worker runs when it is not the default.

Jobs can come in order. `job_start` takes `after`, the ids of jobs that must be done first: the job waits (its progress card says for what), starts by itself when they are done with their reports added to its order (`<earlier_jobs>`), and does not start if one of them fails. So the AI hands over a whole sequence at once, a change and its review, or the steps of a pipeline, and goes on talking. The instructions ask for a review job after a change that matters, sent to another model or machine when there is one, that checks and reports without fixing.

While they work, workers can read the board (`board_read`) and the other jobs' state and reports (`job_list`, `job_result`), so one can build on what another put up. They cannot start or steer other jobs; that stays with the AI you talk to.

## Another model: APERIT's own loop

Choose **Another model** under **Keys** and give the base address, the model and, if needed, a key. APERIT keeps its own loop (memory, workers, tools, voice) and only changes the model, over the `chat/completions` format.

| Provider | Base address |
|---|---|
| OpenAI | `https://api.openai.com/v1` |
| Ollama | `http://localhost:11434/v1` |
| LM Studio | `http://localhost:1234/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |

What to expect: the instructions APERIT gives its model were written and tuned for Claude. A smaller model may write worse boards or wrong ops; wrong lines are rejected and it is told why. Web search and web fetch are tools of Anthropic's API and are not available here.

## Settings

Keys are kept encrypted inside the app: on Windows for this user account on this computer only (`data/keys.bin`), on macOS in the login keychain. No key is written to a plain file. A key file left by an earlier version (`key.txt`, `jev.txt`, `eleven.txt`, the key line of `openai.txt`, the header lines of `agui.txt`) is moved in on the next start and removed.

Everything can be set in the **Keys** window. The same choices as files next to the program, or as environment variables (which win):

| File | Variable | Meaning |
|---|---|---|
| `engine.txt` | `APERIT_ENGINE` | `api`, `claude-code`, `ssh` (Claude Code on a server), `openai`, `external` (an agent over MCP or the socket), `ag-ui` |
| `ssh.txt` | | the servers: `use=<name>`, then for each a `[name]` line and `host=`, `port=`, `user=`, `key=`, `dir=`, `claude=` lines |
| (**Keys** window) | `ANTHROPIC_API_KEY` | Claude API key |
| `openai.txt` | `APERIT_OPENAI_URL`, `APERIT_OPENAI_MODEL`, `OPENAI_API_KEY` | another model: base address and model (one per line in the file); the key from the **Keys** window |
| `agui.txt` | `APERIT_AGUI_URL` | the AG-UI endpoint |
| `mcp-port.txt` | `APERIT_MCP_PORT` | open the MCP door on this port (always open, on 7711, with engine `external`) |
| `data/mcp-token.txt` | `APERIT_MCP_TOKEN` | the MCP token; made on first use |
| | `APERIT_PROTOCOL_PORT`, `APERIT_PROTOCOL_TOKEN` | the protocol socket, and an optional token (`auth <token>` as the first line) |
| `model.txt` | `APERIT_MODEL` | the Claude model of the Claude API engine: `claude-opus-5-5` (the default; no file), `claude-sonnet-5-5`, `claude-haiku-5-5`, or any Claude model id |
| `spend-limit.txt` | `APERIT_DAILY_LIMIT` | a daily limit in dollars for the Claude API key; at the limit no new turn, worker or background request starts that day. What was spent is kept per day in `data/usage.json` |
| `permission.txt` | `APERIT_PERMISSION` | what APERIT's own engines may do without asking: `ask` (the default), `edits`, `all` |
| `claude-permission.txt` | | the same for the Claude Code engine: `default`, `acceptEdits`, `bypassPermissions` |
| `data/lang.txt` | `APERIT_LANG` | `en` or `ko`: the language of the screen and of what the model writes; also the switch in the corner |
| `prompt-lang.txt` | `APERIT_PROMPT` | `ko` uses the Korean instructions for the model; without it they are English (about 20% fewer tokens, same answers) |
| `outside-media.txt` | `APERIT_OUTSIDE_MEDIA` | `yes` lets an agent outside APERIT create web, image and file cards |

Variables named `LYBOARD_...` from earlier versions are still read.

When the service is busy (HTTP 429, 529 or another 5xx) or cannot be reached, APERIT waits and sends the request again, up to four more times: as long as the service asks (`retry-after`), or 2, 4, 8 and 16 seconds. The line under the conversation says so while it waits, and **Stop** ends the wait. Only a request that got no answer is sent again, so nothing on the board is written twice.

A long turn or a long job does not run out of room. With the Claude API engine, once a request passes 100,000 input tokens (`APERIT_COMPACT_AT`, at least 50,000) the API summarizes the older part of the conversation and the work goes on from the summary (server-side compaction, the `compact-2026-01-12` beta); the line under the conversation says "Summarizing the work so far". With the OpenAI-compatible engine, past the same size the results of all but the last eight tool calls are cleared (the calls stay, so the model knows what it did). The Claude Code engine and its workers compact on their own. The main turn never grows this way across turns: each turn is rebuilt from the board, the last ten exchanges, older ones that bear on what was said, and memory.

## Without a model: demos and logs

- **Seed the demo.** With no key, or `APERIT_DEMO=1`, set `APERIT_DEMO_SEED=<file.ops>`: the board is filled before the window opens.
- **Script the turns.** `APERIT_DEMO_FILE=<file>` holds scenes separated by a line of `===`; each message you send plays the next. See [protocol/examples](../protocol/examples).
- **The log.** `data/ops.log` is the board: every accepted op in order, in the [log format](../protocol/SPEC.md#102-log). APERIT rebuilds the board from it at start. You can write one with APERIT closed.

## Security

- The MCP door and the socket listen on `127.0.0.1` only. The MCP door always needs its token and refuses requests that carry an `Origin` header, so a web page in your browser cannot use it. The socket closes a connection that sends an HTTP request line; give it a token (`APERIT_PROTOCOL_TOKEN`) if other users or programs you do not trust share the computer.
- Whoever can write ops can create `web` and `image` cards, which make APERIT load an address or a local file. Open these doors only for agents you would let do that.
- With an outside agent, what you type and pick in APERIT goes to that agent.
