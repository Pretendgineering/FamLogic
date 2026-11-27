#!/usr/bin/env bash
# Steward's model bench: runs Steward's test set (about 20 real family requests,
# each checked by what actually changed) against several small open models, and
# prints how many each got right and how quick it was.
#
# Needs Docker. For a graphics card (NVIDIA), the NVIDIA Container Toolkit too.
#
#   scripts/steward-bench.sh                      # every model, on the graphics card
#   scripts/steward-bench.sh --cpu                # on the CPU only
#   scripts/steward-bench.sh --cpu --cpus 4 --memory 8g   # like a small home server
#   scripts/steward-bench.sh --models "qwen3.5-2b qwen3.5-4b" --levels quick
#   scripts/steward-bench.sh --url http://localhost:11434/v1 --model qwen3.5:4b   # a server you already run (Ollama...)
#   scripts/steward-bench.sh --famlogic ./server/famlogic   # use a FamLogic you built, not the image
#
# Reports go to ./steward-bench/<model>.json (and a summary at the end).

set -euo pipefail

MODELS="qwen3.5-0.8b lfm3-1.7b qwen3.5-2b phi-4-mini qwen3.5-4b gemma-4-e4b qwen3.5-9b"
GPU=1 CPUS="" MEMORY="" LEVELS="" MODE="skills" URL="" MODEL="" PORT=8081
FAMLOGIC_IMAGE="ghcr.io/pretendgineering/famlogic:latest" FAMLOGIC_BIN=""
LLAMA_TAG="server" OUT="steward-bench"

while [ $# -gt 0 ]; do
  case "$1" in
    --cpu) GPU=0 ;;
    --cpus) CPUS="$2"; shift ;;
    --memory) MEMORY="$2"; shift ;;
    --models) MODELS="$2"; shift ;;
    --levels) LEVELS="$2"; shift ;;
    --mode) MODE="$2"; shift ;;
    --url) URL="$2"; shift ;;
    --model) MODEL="$2"; shift ;;
    --port) PORT="$2"; shift ;;
    --famlogic) FAMLOGIC_BIN="$2"; shift ;;
    --image) FAMLOGIC_IMAGE="$2"; shift ;;
    --out) OUT="$2"; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option $1 (see --help)"; exit 2 ;;
  esac
  shift
done

mkdir -p "$OUT"

# The catalogue (the same as the console's): id, Hugging Face repo:quant.
repo_for() {
  case "$1" in
    qwen3.5-0.8b) echo "unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M" ;;
    lfm3-1.7b)    echo "LiquidAI/LFM3-1.7B-GGUF:Q4_K_M" ;;
    qwen3.5-2b)   echo "unsloth/Qwen3.5-2B-GGUF:Q4_K_M" ;;
    phi-4-mini)   echo "unsloth/Phi-4-mini-instruct-GGUF:Q4_K_M" ;;
    qwen3.5-4b)   echo "unsloth/Qwen3.5-4B-GGUF:Q4_K_M" ;;
    gemma-4-e4b)  echo "unsloth/gemma-4-E4B-it-GGUF:Q4_K_M" ;;
    qwen3.5-9b)   echo "unsloth/Qwen3.5-9B-GGUF:Q4_K_M" ;;
    *) echo "" ;;
  esac
}

# Run Steward's test set against a model server.
run_eval() { # url model report
  local args=(-steward-eval -url "$1" -model "$2" -mode "$MODE" -source server -out "/out/$(basename "$3")")
  [ -n "$LEVELS" ] && args+=(-levels "$LEVELS")
  if [ -n "$FAMLOGIC_BIN" ]; then
    "$FAMLOGIC_BIN" "${args[@]/\/out\//$OUT/}"
  else
    docker run --rm --network host -v "$(pwd)/$OUT:/out" "$FAMLOGIC_IMAGE" "${args[@]}"
  fi
}

if [ -n "$URL" ]; then
  [ -n "$MODEL" ] || { echo "--url needs --model too"; exit 2; }
  run_eval "$URL" "$MODEL" "$OUT/$(echo "$MODEL" | tr ':/' '__').json"
  exit 0
fi

if [ "$GPU" = 1 ]; then
  LLAMA_TAG="server-cuda"
  GPU_ARGS=(--gpus all)
  NGL=99
else
  GPU_ARGS=()
  NGL=0
fi
LIMITS=()
[ -n "$CPUS" ] && LIMITS+=(--cpus "$CPUS")
[ -n "$MEMORY" ] && LIMITS+=(--memory "$MEMORY")
THREADS="${CPUS:-$(nproc)}"
THREADS="${THREADS%.*}"

cleanup() { docker rm -f fl-bench-llm >/dev/null 2>&1 || true; }
trap cleanup EXIT

for m in $MODELS; do
  repo=$(repo_for "$m")
  [ -n "$repo" ] || { echo "skipping unknown model $m"; continue; }
  echo
  echo "=== $m ($repo) on $([ "$GPU" = 1 ] && echo "the graphics card" || echo "the CPU${CPUS:+, $CPUS cores}${MEMORY:+, $MEMORY memory}")"
  cleanup
  docker run -d --name fl-bench-llm ${GPU_ARGS[@]+"${GPU_ARGS[@]}"} ${LIMITS[@]+"${LIMITS[@]}"} -p "$PORT:8080" \
    -v fl-bench-models:/root/.cache/llama.cpp \
    "ghcr.io/ggml-org/llama.cpp:$LLAMA_TAG" \
    -hf "$repo" --jinja -c 12288 -np 1 -ngl "$NGL" -t "$THREADS" --host 0.0.0.0 --port 8080 --alias "$m" >/dev/null
  # Wait for it to download (the first time) and load the model.
  for i in $(seq 1 900); do
    if curl -fs "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then break; fi
    if ! docker ps -q -f name=fl-bench-llm | grep -q .; then echo "the model server stopped:"; docker logs --tail 20 fl-bench-llm; continue 2; fi
    [ $((i % 30)) = 0 ] && echo "  still loading ($i s)..."
    sleep 1
  done
  run_eval "http://127.0.0.1:$PORT/v1" "$m" "$OUT/$m.json" || echo "  the test set didn't finish for $m"
done

echo
echo "=== Summary ($OUT/)"
if command -v python3 >/dev/null; then
  python3 - "$OUT" <<'PY'
import json, sys, glob, os
rows = []
for p in sorted(glob.glob(os.path.join(sys.argv[1], "*.json"))):
    r = json.load(open(p))
    rows.append((r["model"], r["passed"], r["total"], r["medianMs"] / 1000, r["avgPromptTokens"]))
print(f'{"model":<16} {"right":>7} {"median":>8} {"prompt tokens":>14}')
for m, p, t, s, tok in sorted(rows, key=lambda x: (-x[1], x[3])):
    print(f"{m:<16} {p:>3}/{t:<3} {s:>7.1f}s {tok:>14}")
PY
else
  grep -h '"model"\|"passed"\|"medianMs"' "$OUT"/*.json
fi
