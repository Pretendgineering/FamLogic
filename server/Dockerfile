# FamLogic: the server, plus voice (speech to text and reading aloud) built in.
#
#   build    the Go server, cross-compiled on the build machine (fast for ARM too)
#   whisper  whisper.cpp's command-line tool, compiled for the target; it picks the
#            best instructions for the CPU it finds itself on (any x86-64 or ARM64);
#            and famlogic-tts, a small helper that keeps the Kokoro voice loaded
#            (sherpa-onnx's libraries, from its PyPI wheel for the target)
#   voice    the models (Whisper; Kokoro and its voices), downloaded once
#   final    a minimal image with glibc and no shell. It starts as root only to hand
#            its folders to PUID:PGID (65532 by default), then runs as that user.

FROM --platform=$BUILDPLATFORM golang:1.26-alpine AS build
WORKDIR /src
COPY . .
ARG VERSION=dev
ARG TARGETOS TARGETARCH
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /famlogic . \
 && mkdir -p /data

FROM debian:bookworm-slim AS whisper
RUN apt-get update && apt-get install -y --no-install-recommends build-essential cmake git ca-certificates curl unzip \
 && rm -rf /var/lib/apt/lists/*
ARG WHISPER_VERSION=v1.8.2
RUN git clone --depth 1 --branch ${WHISPER_VERSION} https://github.com/ggml-org/whisper.cpp /w
# x86-64: every CPU variant, picked at run time (old NAS Celerons to new Xeons).
# ARM64: the plain ARMv8 baseline, which every Raspberry Pi 4/5 and ARM server runs.
ARG TARGETARCH
RUN if [ "$TARGETARCH" = amd64 ]; then cpu="-DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON"; else cpu=""; fi \
 && cmake -S /w -B /b -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=ON $cpu -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
      -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF -DWHISPER_BUILD_EXAMPLES=ON \
      -DCMAKE_BUILD_WITH_INSTALL_RPATH=ON -DCMAKE_INSTALL_RPATH='$ORIGIN' \
 && cmake --build /b -j"$(nproc)" --target whisper-cli \
 && mkdir -p /out \
 && cp /b/bin/whisper-cli /out/ \
 && find /b -name '*.so*' -exec cp -P {} /out/ \; \
 && cp -L /usr/lib/*-linux-gnu/libstdc++.so.6 /usr/lib/*-linux-gnu/libgomp.so.1 /out/ 2>/dev/null; ls -la /out
# Reading aloud: sherpa-onnx (1.13.x: it falls back to espeak-ng for names and other
# words its dictionary doesn't know; 2.x skips them).
ARG SHERPA_VERSION=1.13.8
COPY tts/famlogic-tts.c /tts/
RUN curl -fsSL -o /tmp/core.whl "https://files.pythonhosted.org/packages/py3/s/sherpa_onnx_core/sherpa_onnx_core-${SHERPA_VERSION}-py3-none-manylinux2014_$(uname -m).whl" \
 && unzip -q /tmp/core.whl -d /sherpa && mkdir -p /out-tts \
 && gcc -O2 -Wno-deprecated-declarations -o /out-tts/famlogic-tts /tts/famlogic-tts.c \
      -I/sherpa/sherpa_onnx/include -L/sherpa/sherpa_onnx/lib -lsherpa-onnx-c-api -Wl,-rpath,'$ORIGIN' \
 && cp /sherpa/sherpa_onnx/lib/libsherpa-onnx-c-api.so /sherpa/sherpa_onnx/lib/libonnxruntime.so /out-tts/ \
 && cp -L /usr/lib/*-linux-gnu/libstdc++.so.6 /usr/lib/*-linux-gnu/libgcc_s.so.1 /out-tts/ && ls -la /out-tts

FROM --platform=$BUILDPLATFORM debian:bookworm-slim AS voice
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates jq && rm -rf /var/lib/apt/lists/*
# Whisper small (English, quantised: ~190 MB).
RUN mkdir -p /voice/models/kokoro \
 && curl -fsSL -o /voice/models/whisper.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en-q5_1.bin
# Kokoro v1.0 (Apache-2.0), quantised (~115 MB), its voices, English dictionary and
# espeak-ng's pronunciation data, as packaged for sherpa-onnx.
ARG KOKORO=https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0/resolve/main
RUN cd /voice/models/kokoro \
 && curl -fsSL -o model.onnx "$KOKORO/model.int8.onnx" \
 && for f in voices.bin tokens.txt lexicon-us-en.txt LICENSE; do curl -fsSL -o "$f" "$KOKORO/$f"; done \
 && curl -fsSL "https://huggingface.co/api/models/csukuangfj/kokoro-int8-multi-lang-v1_0/tree/main/espeak-ng-data?recursive=true" \
      | jq -r '.[] | select(.type == "file") | .path' > /tmp/espeak.txt \
 && test "$(wc -l < /tmp/espeak.txt)" -gt 100 \
 && while read -r f; do mkdir -p "$(dirname "$f")" && curl -fsSL -o "$f" "$KOKORO/$f"; done < /tmp/espeak.txt

FROM gcr.io/distroless/cc-debian12
COPY --from=voice /voice /voice
COPY --from=whisper /out /voice/whisper
COPY --from=whisper /out-tts /voice/tts
COPY --from=build /famlogic /famlogic
COPY --from=build --chown=65532:65532 /data /data
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD ["/famlogic", "-healthcheck"]
ENTRYPOINT ["/famlogic"]
