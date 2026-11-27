# Steward and models (developer notes)

How Steward talks to a model, how it stays small enough for small local models, and how
to test models. User-facing docs: `server/wiki/Steward.md`.

## The prompt: skills, not every tool every time

Before 0.50, every request carried all 29 actions as typed tools plus a system prompt
with the time and the family in it: about 4,500 tokens before anyone said anything, and
nothing a model server could cache (the time changed every minute). On a Raspberry Pi 5
that's over a minute of reading per request.

Now (`server/steward_skills.go`), following Hermes Agent's skills:

- **System prompt** (`stewardCore`): short rules, the family's names and a one-line index
  of skills. It only changes when someone joins or the family is renamed, so llama.cpp,
  Ollama and cloud providers keep it cached between requests.
- **Two tools**: `open_skill(name)` returns a skill's rules and its actions as short
  signatures; `act(action, args)` runs one. The action name is an enum, so a model server
  that constrains output to the schema can't invent one.
- **The note**: the time, who's talking and the page they're on go in square brackets on
  the newest message. Earlier messages stay as they were, so the cached prefix grows
  instead of being thrown away.
- **Skills opened up front**: `guessSkills` opens the page's skill (Upkeep → chores) and
  up to one more matched from the words in the message, which saves a round trip.
- **Compact results** (`compactResult`): no empty fields, colours or bookkeeping, and
  times as words ("Fri 27 Nov 2026 14:05") instead of milliseconds.
- **Forgiving calls** (`runCall`): an action called by name (skipping `act`), arguments
  next to `action` instead of inside `args`, and Hermes-style `<tool_call>` text in the
  reply all work. A missing argument comes back with the action's usage.

Measured: about 750 tokens sent every time (cacheable), plus 200 to 500 per skill opened.
"Full" mode (every action typed, as before) is still a setting for big cloud models.

`TestStewardPromptBudget` fails if the fixed part grows past about 900 tokens or a skill
past about 550. Trim it, or move it into a skill.

### Adding an action

1. Add it to `stewardTools` (steward.go) with a short description and parameters.
2. Put it in a skill in `stewardSkills`. The test checks every action is in one.
3. Give it a key scope in `actionScope` (mcp.go), so outside agents can be allowed to use it.
4. Add a case to the test set if it's something families will say.

## Fallback

`stewardAnswer` tries the main model. If it errors, times out (`Timeout`, 90 s by
default) or calls `hand_off` (offered when `HandOff` is on), and nothing has been written
yet, the fallback model answers the same conversation. If the main model already changed
something, the error comes back instead, so nothing is done twice.

## The built-in model

`server/brain.go`: the spec check (`/proc/cpuinfo`, `/proc/meminfo`, cgroup memory limit,
free disk, `/dev/nvidia0` or `/dev/dri`), the catalogue (`brainModels`: Hugging Face
repo, file, size, memory, licence), rough speed estimates, downloading (resumable,
checked against Hugging Face's SHA-256), and running `/brain/llama-server` on
127.0.0.1:18090 under a supervisor that restarts it with backoff.

The image builds llama.cpp's `llama-server` (Dockerfile, `brain` stage, pinned with
`LLAMA_VERSION`) with every CPU variant chosen at run time, like whisper.cpp. If that
build fails, the image is made without it and the console says the engine is missing.

The estimates: writing speed is memory bandwidth divided by the model's size; reading
speed is per-core throughput times cores divided by parameters. The constants come from
published llama.cpp numbers (Pi 5, N100/N150, desktops). They're rough on purpose; the
test measures real numbers.

## The test set

`server/steward_eval.go`: 21 cases (quick, more, safety), each run against a fresh made-up
family on a throwaway server (the Bakers: Alex and Jo, parents; Sam, older child; Pip,
younger child) and checked by what actually changed (a list item exists, a chore was
ticked by the right person, an event is at the right time), not by which action was
called. Safety cases check a younger child can't tick off a sibling's chore or give
themselves stars.

Run it from the command line:

    famlogic -steward-eval -url http://localhost:8081/v1 -model qwen3.5-4b [-mode skills|full] [-levels quick,more,safety] [-out report.json]

or from the console (Settings → AI → Test it), which runs it in the background on the
model Steward uses now.

## Benchmarking models: scripts/steward-bench.sh

Runs llama.cpp's official container for each model in the catalogue and the test set
against it, then prints a summary (right answers, median time, prompt tokens).

    scripts/steward-bench.sh                          # every model, on an NVIDIA card (CUDA)
    scripts/steward-bench.sh --models "qwen3.5-4b qwen3.5-9b" --levels quick
    scripts/steward-bench.sh --cpu --cpus 4 --memory 8g   # like an N100 mini PC
    scripts/steward-bench.sh --cpu --cpus 4 --memory 4g --models "qwen3.5-0.8b lfm3-1.7b qwen3.5-2b"   # like a Pi 5
    scripts/steward-bench.sh --url http://localhost:11434/v1 --model qwen3.5:4b   # an Ollama you already run

Reports go to `steward-bench/<model>.json`. `--famlogic ./server/famlogic` uses a binary
you built instead of the published image (for testing prompt changes before release).

## MCP and Hermes

`server/mcp.go`: `POST /mcp` speaks MCP's streamable HTTP transport (JSON answers), with
an API key as the bearer token. It offers `list_skills`, `open_skill` and `act`, the same
layer Steward uses, limited to the key's scopes. `GET /api/v1/agent-skill` is a SKILL.md
(agentskills.io format) that tells Hermes how to connect.
