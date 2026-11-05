# Multi-stage build for a minimal production image

# Stage 1: Build
FROM golang:1.22-alpine AS builder

WORKDIR /build

# Download dependencies first (layer caching)
COPY go.mod go.sum ./
RUN go mod download

# Copy source and build
COPY . .
ARG TARGETARCH=amd64
RUN CGO_ENABLED=0 GOOS=linux GOARCH=$TARGETARCH \
    go build -ldflags="-w -s" -o /bin/asphalt-server ./cmd/server

# Stage 2: Runtime
FROM alpine:3.19

# ca-certificates for TLS connections to external services
RUN apk add --no-cache ca-certificates tzdata

COPY --from=builder /bin/asphalt-server /bin/asphalt-server

# Non-root user for production security
RUN addgroup -S asphalt && adduser -S asphalt -G asphalt \
    && mkdir /data && chown asphalt:asphalt /data
USER asphalt

# SQLite by default: no database to set up. Mount /data to keep it, or set
# DATABASE_URL=postgres://... to use PostgreSQL instead.
ENV DATABASE_URL=sqlite:/data/asphalt.db
VOLUME /data

EXPOSE 8080

ENTRYPOINT ["/bin/asphalt-server"]
