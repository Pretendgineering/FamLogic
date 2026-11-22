# Build a static binary...
# (Cross-compiles on the build machine, so ARM images such as Raspberry Pi build fast.)
FROM --platform=$BUILDPLATFORM golang:1.24-alpine AS build
WORKDIR /src
COPY . .
ARG VERSION=dev
ARG TARGETOS TARGETARCH
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /famlogic . \
 && mkdir -p /data

# ...and ship it in a minimal image with no shell, running as a non-root user.
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /famlogic /famlogic
COPY --from=build --chown=65532:65532 /data /data
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD ["/famlogic", "-healthcheck"]
ENTRYPOINT ["/famlogic"]
