# Steward: the family's AI helper

Steward is the round button in the bottom-right corner of every screen, in the app and in
the web console. Tell it what to add, change or tick off ("Sam has the dentist next
Tuesday at 3", "add milk to Shopping", "I unloaded the dishwasher"), or ramble off
everything you need to do today, and it sorts each thing into chores, the calendar and the
Docket. It acts as whoever is talking, so a child can't do anything through Steward that
they couldn't do with a tap. After an answer, **Undo** puts back whatever it changed.

Steward is optional. With it switched off, or not set up, there's no button anywhere.

**Children:** switch **Children too** on in Settings → AI, then each child's own switch (console → Family → open the child): older children start with it on, younger ones off. A child can only do what they could with a tap; when they ask for more ("can I have a friend over?"), Steward sends their parents the request.

## Where Steward thinks

Set it up in the web console: **Settings → AI**. There are three choices.

**On this server (built in).** Nothing else to set up. FamLogic looks at the computer it
runs on (its processor, memory and free disk), lists the models that fit, and guesses how
long each would take to answer. Choose one and press **Download**, then **Use**. The model
stays on your server and nothing leaves your home.

| Model | Download | Memory | Good for |
|---|---|---|---|
| Qwen3.5 0.8B | 0.5 GB | 1.2 GB | Simple one-step requests; fine for children |
| Liquid LFM3 1.7B | 1.1 GB | 2 GB | Everyday requests; made for tool use |
| Qwen3.5 2B | 1.3 GB | 2.2 GB | Everyday requests |
| Phi-4 mini | 2.5 GB | 4 GB | Most of what Steward does |
| Qwen3.5 4B | 2.7 GB | 4.2 GB | Most of what Steward does: the best all-rounder |
| Gemma 4 E4B | 5 GB | 6.5 GB | Most of what Steward does |
| Qwen3.5 9B | 5.7 GB | 8 GB | Close to cloud quality; best with a graphics card |

On a small home server (a Raspberry Pi 5, an Intel N100 mini PC), expect anything from a
few seconds to half a minute an answer, depending on the model. The first answer after
starting takes longer.

**On a computer at home.** A model server on a stronger computer, like a gaming PC's
graphics card: llama.cpp, Ollama or LM Studio. The console shows the command to start one
and finds its models for you. On a 12 GB graphics card, even the 9B models answer in a
second or two.

**In the cloud.** A provider's model (OpenRouter, OpenAI, Anthropic, or any
OpenAI-compatible address) with your own key. Quickest and cleverest, but what the family
asks, and what Steward reads to answer it, goes to the provider.

## If it can't answer: a second model

Under **If it can't answer**, add a second model (usually a cloud one). Steward hands over
to it when the first is down or too slow, or (if you allow it) when the first says a
request is too much for it. It only hands over before anything has been changed, so
nothing happens twice. A good setup: a small model on your server for everyday things,
and a cloud model for the hard ones.

## Test it

**Settings → AI → Test it** runs real family requests on a made-up family, with the
model Steward uses now, and checks that each one actually worked. Your family's things
aren't touched. It shows how many it got right and how long each took. **Recent answers**
lists Steward's latest answers: which model, how long, and how much it read and wrote.

## Outside agents (Hermes, MCP)

An outside AI agent can look after the family's things too, with a key from **Settings →
AI → Outside agents**. Each key can only do what you switch on for it (chores, calendar,
lists...), and every use is listed. Agents that speak MCP (Hermes, Claude Desktop) connect
to the MCP address shown there. For Hermes, download **FamLogic's skill** into
`~/.hermes/skills/famlogic/`: it teaches Hermes FamLogic once, instead of with every
request.
