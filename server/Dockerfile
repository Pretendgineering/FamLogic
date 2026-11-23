# FamLogic: the server, plus voice (speech to text and reading aloud) built in.
#
#   build    the Go server, cross-compiled on the build machine (fast for ARM too)
#   whisper  whisper.cpp's command-line tool, compiled for the target; it picks the
#            best instructions for the CPU it finds itself on (any x86-64 or ARM64)
#   voice    Piper (reading aloud) and the models, downloaded once
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
RUN apt-get update && apt-get install -y --no-install-recommends build-essential cmake git ca-certificates \
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

FROM --platform=$BUILDPLATFORM debian:bookworm-slim AS voice
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*
ARG TARGETARCH
ARG PIPER_VERSION=2023.11.14-2
# Piper and its libraries (espeak-ng's data comes with it).
RUN arch=$([ "$TARGETARCH" = arm64 ] && echo aarch64 || echo x86_64) \
 && mkdir -p /voice/models \
 && curl -fsSL "https://github.com/rhasspy/piper/releases/download/${PIPER_VERSION}/piper_linux_${arch}.tar.gz" | tar xz -C /voice
# Models: Whisper small (English, quantised: ~190 MB) and the LJSpeech voice (public domain).
RUN curl -fsSL -o /voice/models/whisper.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en-q5_1.bin \
 && curl -fsSL -o /voice/models/voice.onnx https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ljspeech/medium/en_US-ljspeech-medium.onnx \
 && curl -fsSL -o /voice/models/voice.onnx.json https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ljspeech/medium/en_US-ljspeech-medium.onnx.json

FROM gcr.io/distroless/cc-debian12
COPY --from=voice /voice /voice
COPY --from=whisper /out /voice/whisper
COPY --from=build /famlogic /famlogic
COPY --from=build --chown=65532:65532 /data /data
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD ["/famlogic", "-healthcheck"]
ENTRYPOINT ["/famlogic"]
