# Use KITE with your own agent or model

KITE is a renderer of the [KITE Protocol](../protocol/README.md): it keeps a board and draws it. Who writes the board is up to you. This page is the how-to for the KITE desktop app; the rules of the language are in [protocol/SPEC.md](../protocol/SPEC.md).

| You have | Use | KITE's own memory, workers, tools |
|---|---|---|
| An agent that speaks MCP (Claude Code, Cursor, your own) | [MCP](#mcp-your-agent-comes-to-kite) | not used; the agent brings its own |
| An agent built on an AG-UI framework (LangGraph, CrewAI, Pydantic AI, ...) | [AG-UI](#ag-ui-kite-goes-to-your-agent) | not used |
| A script or a program | [The protocol socket](#the-protocol-socket-a-script-writes-the-board) | not used |
| Another model (OpenAI, a local model, OpenRouter) | [Another model](#another-model-kites-own-loop) | kept |
| Claude | The Claude API key or Claude Code engine ([README](../README.md#pick-an-engine)) | kept |

All of these are chosen in the app under **Keys** (bottom left), or with the files and variables in the [settings table](#settings).

## MCP: your agent comes to KITE

1. In KITE, open **Keys**, then **More options**, choose **Your own agent**, and save. KITE stops running a model of its own and opens an MCP door at `http://127.0.0.1:7711/mcp`.
2. Open **Keys** again and press **Copy the Claude Code command** (or **Copy the config (JSON)** for other clients). For Claude Code the command is:

   ```sh
   claude mcp add --transport http kite http://127.0.0.1:7711/mcp --header "Authorization: Bearer <token>"
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

What you type in KITE while no agent is connected is kept (the last 2000 events) and delivered when one asks.

You can also open the MCP door next to another engine, so that an agent outside can add to a board Claude is working on: set `KITE_MCP_PORT`, or write a port number in `mcp-port.txt`.

## AG-UI: KITE goes to your agent

1. Run your agent as an AG-UI server (an HTTP endpoint that takes a `RunAgentInput` and streams events).
2. In KITE, open **Keys**, choose **Your own agent**, type the endpoint under **AG-UI** (for example `http://localhost:8000/agent`), and save.

On every turn KITE POSTs the board, what you did and said, the guide for agents (as `context`) and one frontend tool, `screen`. It applies what comes back from any of:

- the agent's text, read as ops line by line while it streams;
- `screen` tool calls (the verdict returns to the agent as a tool message in a following run);
- `CUSTOM` events named `kite.ops` whose value is a string of ops or an array of op strings.

The exact shape is in [SPEC.md, section 10.5](../protocol/SPEC.md#105-ag-ui). An agent whose model follows the guide needs no KITE-specific code. An agent that answers in prose still shows up: its text becomes the caption and a note card.

Headers (an `Authorization` header, for example) go on lines after the address in `agui.txt`:

```
https://agent.example.com/run
Authorization: Bearer <your token>
```

## The protocol socket: a script writes the board

Start KITE with `KITE_PROTOCOL_PORT` set and it listens on `127.0.0.1` for lines. It works with any engine, the demo included.

```python
import socket
s = socket.create_connection(("127.0.0.1", 7878))   # KITE_PROTOCOL_PORT=7878
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

## Another model: KITE's own loop

Choose **Another model** under **Keys** and give the base address, the model and, if needed, a key. KITE keeps its own loop (memory, workers, tools, voice) and only changes the model, over the `chat/completions` format.

| Provider | Base address |
|---|---|
| OpenAI | `https://api.openai.com/v1` |
| Ollama | `http://localhost:11434/v1` |
| LM Studio | `http://localhost:1234/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |

What to expect: the instructions KITE gives its model were written and tuned for Claude. A smaller model may write worse boards or wrong ops; wrong lines are rejected and it is told why. Web search and web fetch are tools of Anthropic's API and are not available here.

## Settings

Everything can be set in the **Keys** window. The same choices as files next to the program, or as environment variables (which win):

| File | Variable | Meaning |
|---|---|---|
| `engine.txt` | `KITE_ENGINE` | `api`, `claude-code`, `openai`, `external` (an agent over MCP or the socket), `ag-ui` |
| `key.txt` | `ANTHROPIC_API_KEY` | Claude API key |
| `openai.txt` | `KITE_OPENAI_URL`, `KITE_OPENAI_MODEL`, `OPENAI_API_KEY` | another model: base address, model, key (one per line in the file) |
| `agui.txt` | `KITE_AGUI_URL` | the AG-UI endpoint; header lines may follow in the file |
| `mcp-port.txt` | `KITE_MCP_PORT` | open the MCP door on this port (always open, on 7711, with engine `external`) |
| `data/mcp-token.txt` | `KITE_MCP_TOKEN` | the MCP token; made on first use |
| | `KITE_PROTOCOL_PORT`, `KITE_PROTOCOL_TOKEN` | the protocol socket, and an optional token (`auth <token>` as the first line) |
| `permission.txt` | `KITE_PERMISSION` | what KITE's own engines may do without asking: `ask` (the default), `edits`, `all` |
| `claude-permission.txt` | | the same for the Claude Code engine: `default`, `acceptEdits`, `bypassPermissions` |
| `data/lang.txt` | `KITE_LANG` | `en` or `ko`: the language of the screen and of what the model writes; also the switch in the corner |
| `prompt-lang.txt` | `KITE_PROMPT` | `ko` uses the Korean instructions for the model; without it they are English (about 20% fewer tokens, same answers) |
| `outside-media.txt` | `KITE_OUTSIDE_MEDIA` | `yes` lets an agent outside KITE create web, image and file cards |

Variables named `LYBOARD_...` from earlier versions are still read.

## Without a model: demos and logs

- **Seed the demo.** With no key, or `KITE_DEMO=1`, set `KITE_DEMO_SEED=<file.ops>`: the board is filled before the window opens.
- **Script the turns.** `KITE_DEMO_FILE=<file>` holds scenes separated by a line of `===`; each message you send plays the next. See [protocol/examples](../protocol/examples).
- **The log.** `data/ops.log` is the board: every accepted op in order, in the [log format](../protocol/SPEC.md#102-log). KITE rebuilds the board from it at start. You can write one with KITE closed.

## Security

- The MCP door and the socket listen on `127.0.0.1` only. The MCP door always needs its token and refuses requests that carry an `Origin` header, so a web page in your browser cannot use it. The socket closes a connection that sends an HTTP request line; give it a token (`KITE_PROTOCOL_TOKEN`) if other users or programs you do not trust share the computer.
- Whoever can write ops can create `web` and `image` cards, which make KITE load an address or a local file. Open these doors only for agents you would let do that.
- With an outside agent, what you type and pick in KITE goes to that agent.
