# What a turn costs

Measured on 2026-11-18. Numbers are tokens. The methods are at the end, so they can be run again.

## In short

- **A board answer is not longer than a chat answer.** For the same four requests, the ops a model wrote were 3,641 characters in total and the chat answers 5,048: about 28% less visible output. Output is the small side of the bill either way.
- **The cost of a turn is its input**, and most of that is the same on every turn: instructions and tool definitions. With APERIT's Claude API engine a turn sends about 36,500 tokens, of which about 90% is read from the cache in a session of consecutive turns.
- **That is the same size as a Claude Code turn.** On the same machine, one empty Claude Code turn (`claude -p`, with its own instructions, tools, skills and MCP servers) sent 38,175 tokens.
- **Two things were wasteful, and both are fixed.** A cache that lasted five minutes, so a turn after a pause paid for everything again (now one hour). Instructions written in Korean, which cost about half again as many tokens as the same text in English (now English: a request went from about 32,800 tokens to about 26,300 on an empty board).

## What you see in the app

The **Settings** window shows what your Claude API key has spent today and this month, and takes a daily limit. The amounts are estimates: each response reports its tokens (new input, cache read, cache written, output), and APERIT multiplies them by the published prices of that model (`src/usage.rs`), counting the main turns, the workers and the small background requests alike. Claude Code bills on its own and is not counted; nor are other services, whose prices APERIT does not know. At the limit no new request starts that day; one already running finishes its step.

The model buttons show what an ordinary turn roughly costs: 4,000 new input tokens, 26,000 read from the cache, 1,000 out. With the prices of 2026-11 that is about 4¢ on Opus 5.5, 2¢ on Sonnet 5.5 and under 1¢ on Haiku 5.5. A turn that misses the cache, or uses many tools, costs several times that (below). Opus stays the default; choosing a cheaper model is the user's call.

## Long jobs

A turn or a worker that uses tools sends the whole conversation again on every step, so a long job used to cost more with every step and could end by running out of room. Now, past 100,000 input tokens, the API writes a summary of the older part and later steps send the summary instead (about 2,000 characters in a test). The summary is a step of its own and is billed; APERIT counts it in the spending. Measured on 2026-11-19 with Haiku 5.5 and the threshold at its minimum (50,000): a turn that read four large source files compacted twice, finished with the right answer, and cost about 2.5¢ in all.

## Where the input goes (Claude API engine)

From 20 real turns of daily use with Opus (2026-11-16): input per request 34,088 to 44,612, median 36,466; output median 514; cache hit 90%.

| Part | Tokens | Same every turn |
|---|---|---|
| Instructions (`src/prompt.md`, Korean) | 19,965 (measured) | yes |
| Tool definitions (30 tools, Korean descriptions) | about 4,000 to 5,000 (estimated from their size) | yes |
| The turn: board, core memory, recent conversation, memory index, environment, what the user said | the rest, about 11,000 | no |
| **Total** | **about 36,500** | about two thirds |

A turn that uses tools sends all of it once per step. Until this version the status line showed only the last step's input; it now shows the sum.

Checked with real turns after the change: the one-hour cache was accepted, and a second turn read 34,014 of its 34,661 input tokens from the cache (98%).

## What a cached and an uncached turn cost

Prices relative to one ordinary input token (Anthropic's published multipliers; a cache read is 0.05 for Opus 5.5):

| | Token-equivalents for a 36,500-token request |
|---|---|
| Everything read from the cache except the new part (about 3,500 new) | about 5,200 |
| Nothing cached, five-minute cache written (x1.25) | about 45,600 |
| Nothing cached, one-hour cache written for instructions and tools (x2 on 25,000) | about 64,000, once an hour |

A turn that misses the cache costs about nine times a turn that hits it. A chat session has turns seconds apart; a board is something you come back to. With the five-minute cache, every return after a pause was a miss. The instructions and tools are now cached for an hour (`"ttl": "1h"`); if the API refuses that, APERIT falls back to the default by itself. It pays off when at least one more turn follows within the hour.

## Korean and English instructions

The same four principles from the instructions, in Korean and in a faithful English translation: 371 tokens against 248. English is 67% of Korean.

The instructions and the tool descriptions were then translated (`src/prompt.md`; the Korean ones remain as `src/prompt.ko.md`, chosen with `APERIT_PROMPT=ko` or `prompt-lang.txt`). The same Korean request on an empty board, real turns with Haiku:

| Instructions | Input tokens per request | Board |
|---|---|---|
| Korean | 32,773 | Korean; a metric, an input card asking for the three candidates |
| English | about 26,300 (52,698 over two requests) | Korean; the same cards, plus named values |

About 6,400 fewer tokens on every request, 20%. With Opus and the English instructions a Korean request gave a Korean board of four cards with nothing rejected, for 25,870 input tokens. The instructions are in English; the model writes in the language the user chose (`<lang>`).

For comparison, the protocol's own guide for agents ([protocol/GUIDE.md](../protocol/GUIDE.md), English) is 5,042 tokens: what an outside agent needs to write boards. The rest of APERIT's instructions is about working on the computer, memory, workers and voice.

## The Claude Code engine and outside agents

- **Claude Code engine:** each APERIT turn is one Claude Code run, so it carries Claude Code's own instructions and tools as well as APERIT's. APERIT limits the tools it asks for; an earlier measurement put a simple turn at about 40,000 tokens. Claude Code caches on its own terms.
- **An agent of your own over MCP:** APERIT adds four small tool definitions to your agent, and the guide (about 5,000 tokens) once, when the agent calls `guide`. Reading the board costs what the board weighs.
- **AG-UI:** APERIT sends the guide as `context` with every run. Whether that is cached is up to your agent.

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

An agent that knew nothing of APERIT was given only the `protocol/` folder and built a web app from it: `<patet-board>`, a small server, and Haiku through the Claude Code CLI as the model, with the guide as the system prompt. Twelve model calls: 103 lines applied, 2 rejected, no prose. Each call read about 5,400 tokens from the cache and wrote 2,500 to 3,000 new ones; the whole run cost about one cent. The guide plus the renderer's description was 15 KB, about 5,000 tokens: that is what the protocol itself costs a developer per turn before caching, against about 20,000 for APERIT's own instructions.

What that run got wrong went into the protocol the same day: a model that answered its own questions (now rejected with `user-only`), and turns started by a gesture that lost track of the request (the turn layout in SPEC.md 10.1 now carries the earlier requests). Rerun with the corrected guide as it is: 22 lines, none rejected, and wrong values in a form were asked for again and not guessed.

## Not measured

- A comparison with other UI formats (A2UI, json-render) for the same screen.
- The share of rejected lines for models other than Haiku, and over long conversations.
- Tokens of the tool definitions exactly (the figure above is estimated from their size).
- Board quality with English instructions over many days of real use: a handful of turns looked the same as with the Korean ones.

## How it was measured

- Real turns: `data/timing.jsonl` of an installation in daily use (tokens per turn; no text was read).
- Instructions and the Korean-English sample: `claude -p --model haiku --output-format json --tools "" --strict-mcp-config --system-prompt-file <file>` with the user turn `hi`, minus the same call with a one-character system prompt (2,407 tokens of overhead).
- A Claude Code turn: `claude -p --model haiku --output-format json` with nothing else, in an empty folder.
- Board and chat answers: the same call with the guide, or with "You are a helpful assistant", as the system prompt.
