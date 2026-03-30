# Multi-stage build for a minimal production image

# Stage 1: Build
FROM golang:1.22-alpine AS builder

WORKDIR /build

# Download dependencies first (layer caching)
COPY go.mod go.sum ./
RUN go mod download

# Copy source and build
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -ldflags="-w -s" -o /bin/asphalt-server ./cmd/server

# Stage 2: Runtime
FROM alpine:3.19

# ca-certificates for TLS connections to external services
RUN apk add --no-cache ca-certificates tzdata

COPY --from=builder /bin/asphalt-server /bin/asphalt-server

# Non-root user for production security
RUN addgroup -S asphalt && adduser -S asphalt -G asphalt
USER asphalt

EXPOSE 8080

ENTRYPOINT ["/bin/asphalt-server"]
