# KITE

**Kinetic Interactive Task Environment** · [한국어](README.ko.md)

> A screen full of text is the worst way to use AI.

Humanity built an extraordinary engine and bolted it to a wooden cart. The engine is AI. The cart is a chat window full of text.

Every answer comes back as a scroll of paragraphs. Most of it goes unread. The chat window, a tool made for people typing to each other, became the place where we work with a machine that can do far more than type.

People have already built many excellent project and collaboration tools: Jira, monday.com, Asana, Notion, Trello. Yet in front of AI, all of us went back to plain text. And those tools are too complex, with features spread too wide, to be the place where you talk to an AI.

What we want is one place. Every conversation and every topic lives there, shown as the information you actually need, shaped so it reads at a glance, in an interface that adapts as you speak. No tsunami of text.

KITE is that place. You say what you need, and the AI arranges the screen for you: a schedule as a timeline, a trip as a route, a choice as a card you tap.

The name says it twice. KITE is a **Kinetic Interactive Task Environment**: a workspace that moves as you talk. Read it again and it is the rule the screen follows: **Keep Information To Essentials**. Only what you need stays in front of you.

Nothing like it existed, so we built it. We hope it makes working with AI less tiring and more productive for everyone.

![KITE by FIDENIT: a wall of text gives way to a live board. One request plans a workshop; the route, schedule, budget and checklist appear, and the one decision that needs you comes to the left.](docs/images/hero.gif)

## What is different

**The screen is the answer.** The AI does not write to you. It edits a board of cards, one line at a time, and you watch the board change. Ask about the same thing again and the same card updates. The board shows where things stand now. It is not a transcript.

![Asking for a to-do list, then reporting progress: the same cards update](docs/images/todo.gif)

**Work comes to you.** When the AI needs a decision or a value from you, that one card moves to the "my turn" slot beside the conversation, and the board slides to the part of the work it belongs to. Answer it and the next one arrives. You do not go looking for what is waiting on you.

![The AI compares options, then brings the question it needs answered to the "my turn" slot](docs/images/myturn.gif)

Tap any card and it comes to the same slot, with only the cards on the same topic left beside it. Close it and the whole board comes back.

![Dragging the board to another topic, tapping a card: it moves to the left and the board keeps only its topic](docs/images/focus.gif)

**One board for everything.** No chat rooms and no threads. Topics sit on a single canvas, grouped into areas the AI creates, renames and merges as your work grows. One scroll step takes you up a level, from cards to topics to groups; one step back takes you into the group or topic you point at. Drag to move around. Finished work is shelved and comes back when you mention it.

![One scroll step at a time: from a topic's cards out to its group, out to the whole board, then into another group and one of its topics](docs/images/canvas.gif)

**The right card for each piece.** Big numbers, timelines, checklists, progress, tables, charts, forms to fill in, routes, drawings, pictures and web pages. When a topic has more than fits at a readable size, the less important cards fold under "+N more" instead of the text shrinking.

![One request, many kinds of cards: big numbers, a timeline, a bar chart, a checklist, and a form that comes to you](docs/images/components.gif)

**It draws.** Routes with real distances and travel times, line, bar and donut charts, flows and layouts, pictures. The AI picks the form that reads fastest.

![Asking for a drive: the AI finds the places, reads the travel time and draws the route](docs/images/route.gif)

![A trend line, a budget split and a release flow drawn on request](docs/images/charts.gif)

**It remembers.** Facts worth keeping are picked out after every turn. Old conversations related to what you just said are found and brought along. Everything is stored as plain Markdown in a folder you can open in Obsidian.

**It does the work.** It runs commands, reads and edits files, searches the web and drives a browser on the board. It also has a browser of its own that runs out of sight: it opens sites, fills in forms and clicks through, and shows you the page only when a person is needed, such as a login or a captcha. The last button, like confirming a booking or paying, is left for you to press. Longer jobs are written up as a work order and handed to workers that run in parallel while you keep talking.

**You can talk to it.** Speak and listen instead of typing. Interrupt it while it is speaking.

## Try it

You can look around without anything: with no key, KITE opens in a demo that plays prepared scenes and says so on screen. To really use it you need a [Claude API key](https://console.anthropic.com/settings/keys). Usage is billed to your own account. The key can also come from the `ANTHROPIC_API_KEY` environment variable.

**Or use Claude Code as the engine.** If [Claude Code](https://claude.com/claude-code) is installed, choose it in the same window and KITE hands each turn to it instead of calling the API. Your Claude Code login, `CLAUDE.md`, skills, hooks, MCP servers and permission settings all apply, and no API key is needed. Files, commands and the web go through Claude Code's tools, while KITE's own tools (route maps, memory notes, reading the board, workers) are offered to it through a small local MCP server inside KITE. Workers and memory keeping also run through Claude Code. A turn costs more tokens than with the API key, because Claude Code brings its own instructions. By default it does only what your Claude Code settings allow; the same window lets you allow file edits, or everything. Extra options for Claude Code, such as `--model sonnet`, go in `claude-args.txt` in the program folder.

A second key is optional: a [TypeSafe AI](https://typesafe.ai) Jev key. Jev is a small, fast model that only makes decisions. With it, KITE picks the topic before answering, puts important cards first, tidies overlapping topics, and recalls only the past conversations that matter. Without it, KITE works the same. Both keys can be entered or changed any time from **Keys** at the bottom left (or `TYPESAFE_API_KEY`).

**Windows.** Download the zip from [Releases](https://github.com/fidenit/KITE/releases), unpack it and run `kite.exe`. There is no installer. On first start a window asks for your key; you can skip it and stay in the demo.

**Mac.** Build it yourself for now: `./run.sh pack` creates `portable/KITE/KITE.app`. See [DEVELOPING](docs/DEVELOPING.md).

Keys, the board and the memory vault live in the program's own folder. Move the folder and everything moves with it.

## Know before you run

- **The AI acts without asking.** It runs commands and changes files on your computer on its own. KITE was built to be used that way. Run it knowing this, on a machine where that is acceptable.
- **It costs money as you use it.** By default it uses the most capable Claude model.
- **It is early.** One person uses it daily. Korean and English, switched in the corner. See [what is next](docs/NEXT.md).
- Distances and times on route cards are read from Kakao Map, so routes work in South Korea.

## For developers

Rust, [egui](https://github.com/emilk/egui), and the Claude API called directly over HTTP. No web front end. The model's output is a small line-based language for editing the board, applied as each line arrives; the board, written back in the same language, is what the model sees on the next turn.

Build steps, the layout of the code and how to add a new kind of card are in [docs/DEVELOPING.md](docs/DEVELOPING.md) (Korean).

Most of the code was written together with AI (Claude). The idea and the design decisions are human.

## License

[Apache License 2.0](LICENSE). Keep the [NOTICE](NOTICE) file when you redistribute. The name "KITE" may not be used as the name of a derived product.

---

**FIDENIT** · for the world.
