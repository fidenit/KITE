# What a turn costs

Measured on 2026-11-18. Numbers are tokens. The methods are at the end, so they can be run again.

## In short

- **A board answer is not longer than a chat answer.** For the same four requests, the ops a model wrote were 3,641 characters in total and the chat answers 5,048: about 28% less visible output. Output is the small side of the bill either way.
- **The cost of a turn is its input**, and most of that is the same on every turn: instructions and tool definitions. With KITE's Claude API engine a turn sends about 36,500 tokens, of which about 90% is read from the cache in a session of consecutive turns.
- **That is the same size as a Claude Code turn.** On the same machine, one empty Claude Code turn (`claude -p`, with its own instructions, tools, skills and MCP servers) sent 38,175 tokens.
- **Two things were wasteful and one still is.** A cache that lasted five minutes, so a turn after a pause paid for everything again (fixed: one hour). Instructions written in Korean, which cost about half again as many tokens as the same text in English (not fixed yet).

## Where the input goes (Claude API engine)

From 20 real turns of daily use with Opus (2026-11-16): input per request 34,088 to 44,612, median 36,466; output median 514; cache hit 90%.

| Part | Tokens | Same every turn |
|---|---|---|
| Instructions (`src/prompt.md`, Korean) | 19,965 (measured) | yes |
| Tool definitions (30 tools, Korean descriptions) | about 4,000 to 5,000 (estimated from their size) | yes |
| The turn: board, core memory, recent conversation, memory index, environment, what the user said | the rest, about 11,000 | no |
| **Total** | **about 36,500** | about two thirds |

A turn that uses tools sends all of it once per step. Until this version the status line showed only the last step's input; it now shows the sum.

## What a cached and an uncached turn cost

Prices relative to one ordinary input token (Anthropic's published multipliers; a cache read is 0.05 for Opus 5.5):

| | Token-equivalents for a 36,500-token request |
|---|---|
| Everything read from the cache except the new part (about 3,500 new) | about 5,200 |
| Nothing cached, five-minute cache written (x1.25) | about 45,600 |
| Nothing cached, one-hour cache written for instructions and tools (x2 on 25,000) | about 64,000, once an hour |

A turn that misses the cache costs about nine times a turn that hits it. A chat session has turns seconds apart; a board is something you come back to. With the five-minute cache, every return after a pause was a miss. The instructions and tools are now cached for an hour (`"ttl": "1h"`); if the API refuses that, KITE falls back to the default by itself. It pays off when at least one more turn follows within the hour.

## Korean and English instructions

The same four principles from the instructions, in Korean and in a faithful English translation: 371 tokens against 248. English is 67% of Korean.

Applied to the whole of `src/prompt.md`, an English version would be about 13,400 tokens instead of 19,965: about 6,600 fewer on every request, or 18% of a turn. This is an estimate from one sample, and translating the instructions changes how the model behaves, so it has to be done with real turns to compare. It is the largest saving left.

For comparison, the protocol's own guide for agents ([protocol/GUIDE.md](../protocol/GUIDE.md), English) is 5,042 tokens: what an outside agent needs to write boards. The rest of KITE's instructions is about working on the computer, memory, workers and voice.

## The Claude Code engine and outside agents

- **Claude Code engine:** each KITE turn is one Claude Code run, so it carries Claude Code's own instructions and tools as well as KITE's. KITE limits the tools it asks for; an earlier measurement put a simple turn at about 40,000 tokens. Claude Code caches on its own terms.
- **An agent of your own over MCP:** KITE adds four small tool definitions to your agent, and the guide (about 5,000 tokens) once, when the agent calls `guide`. Reading the board costs what the board weighs.
- **AG-UI:** KITE sends the guide as `context` with every run. Whether that is cached is up to your agent.

## Output: board ops and chat answers

Haiku, the same request, once with the guide as instructions ("answer by writing board ops only") and once as a plain assistant:

| Request | Board ops (characters, lines) | Chat answer (characters) |
|---|---|---|
| Plan a 3-day trip with a budget | 1,522, 34 lines | 1,714 |
| Compare three laptops | 732, 17 lines | 779 |
| What to do before moving house | 764, 19 lines | 1,633 |
| Pick a workshop venue | 623, 13 lines | 922 |
| **Total** | **3,641** | **5,048** |

Every one of the 83 op lines was valid: none was rejected when applied (checked with `protocol/js`). The model had only the guide to go by.

Output tokens as billed, which include the model's thinking: 7,988 for the boards, 9,852 for the chat answers. Four requests and one small model are a small sample; the direction (a board is somewhat shorter, never much longer) is what to take from it.

## A newcomer's app, for scale

An agent that knew nothing of KITE was given only the `protocol/` folder and built a web app from it: `<kite-board>`, a small server, and Haiku through the Claude Code CLI as the model, with the guide as the system prompt. Twelve model calls: 103 lines applied, 2 rejected, no prose. Each call read about 5,400 tokens from the cache and wrote 2,500 to 3,000 new ones; the whole run cost about one cent. The guide plus the renderer's description was 15 KB, about 5,000 tokens: that is what the protocol itself costs a developer per turn before caching, against about 20,000 for KITE's own instructions.

What that run got wrong went into the protocol the same day: a model that answered its own questions (now rejected with `user-only`), and turns started by a gesture that lost track of the request (the turn layout in SPEC.md 10.1 now carries the earlier requests). Rerun with the corrected guide as it is: 22 lines, none rejected, and wrong values in a form were asked for again and not guessed.

## Not measured

- A comparison with other UI formats (A2UI, json-render) for the same screen.
- The share of rejected lines for models other than Haiku, and over long conversations.
- Tokens of the tool definitions exactly (the figure above is estimated from their size).
- Whether the one-hour cache is accepted in practice: it was added from the documentation and has a fallback, but no real turn was run.

## How it was measured

- Real turns: `data/timing.jsonl` of an installation in daily use (tokens per turn; no text was read).
- Instructions and the Korean-English sample: `claude -p --model haiku --output-format json --tools "" --strict-mcp-config --system-prompt-file <file>` with the user turn `hi`, minus the same call with a one-character system prompt (2,407 tokens of overhead).
- A Claude Code turn: `claude -p --model haiku --output-format json` with nothing else, in an empty folder.
- Board and chat answers: the same call with the guide, or with "You are a helpful assistant", as the system prompt.
