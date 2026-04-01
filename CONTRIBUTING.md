# Contributing to Asphalt

Thank you for your interest in contributing. Asphalt is a privacy-first road
anomaly detection system designed for real deployment conditions in India and
similar markets. Good contributions improve detection quality, reduce false
positives, and help make road safety data available to communities that need it.

---

## Table of Contents

1. [Code of Conduct](#code-of-conduct)
2. [What we welcome](#what-we-welcome)
3. [Getting started](#getting-started)
4. [Project structure](#project-structure)
5. [Running tests](#running-tests)
6. [Code style](#code-style)
7. [Submitting changes](#submitting-changes)
8. [Issue reporting](#issue-reporting)
9. [Areas where help is most needed](#areas-where-help-is-most-needed)

---

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).
By participating, you agree to uphold it. Report unacceptable behaviour to the
maintainers via the contact address in that document.

---

## What we welcome

- **Bug fixes** — especially misclassifications (false positives/negatives on real roads)
- **Vehicle type additions** — e.g. electric three-wheelers, heavy trucks, tractors
- **Backend improvements** — clustering quality, confidence scoring, query performance
- **Documentation** — corrections, translations, clearer explanations
- **Test coverage** — unit tests for untested logic, integration test infrastructure
- **Platform ports** — iOS SDK, Flutter plugin

We do not accept contributions that:
- Collect additional personal data or device identifiers
- Weaken the privacy guarantees described in the docs
- Break backwards compatibility in the event schema without a migration path

---

## Getting started

### Prerequisites

| Tool | Version | Used for |
|------|---------|----------|
| Go | 1.22+ | Backend server |
| JDK | 17 | Android SDK and demo app |
| Android SDK | API 34 | SDK compilation (not needed for unit tests) |
| Git | any | Version control |

You do **not** need an Android device or emulator to run the unit tests.
The detection logic (`AnomalyDetector`, `ThreeWheelerFilter`, etc.) runs on the JVM.

### Fork and clone

```bash
git clone https://github.com/<your-username>/asphalt.git
cd asphalt
```

### Backend setup

```bash
cd backend
go mod download
go build ./...
go test ./...
```

### Android SDK unit tests

```bash
cd sdk/android
./gradlew :asphalt-sdk:test
```

This runs all JVM unit tests without an emulator.

---

## Project structure

```
asphalt/
  backend/                     Go backend server
    internal/
      api/                     HTTP handlers
      clustering/              DBSCAN clustering + confidence scoring
      ingestion/               Batch validation and storage
      model/                   Shared data types and validation
      storage/                 PostgreSQL persistence
  sdk/android/
    asphalt-sdk/               Android SDK (Kotlin, ships as AAR)
      src/main/
        detection/             AnomalyDetector, ThreeWheelerFilter, VehicleProfile
        location/              LocationTracker (GPS dual-mode)
        sensor/                SensorCollector
        upload/                BatchUploader, UploadWorker
      src/test/                JVM unit tests (no emulator required)
  demo-app/android/            Demo application (Kotlin)
  contracts/                   JSON schemas and OpenAPI spec
  docs/                        Architecture, sensor model, limitations
```

---

## Running tests

### Backend (Go)

```bash
cd backend
go test ./...                          # all packages
go test ./internal/model/... -v        # event validation
go test ./internal/api/... -v          # handler and tile projection
go test ./internal/clustering/... -v   # DBSCAN and confidence scoring
```

### Android SDK (JVM unit tests)

```bash
cd sdk/android
./gradlew :asphalt-sdk:test
```

Test reports land in `sdk/android/asphalt-sdk/build/reports/tests/`.

---

## Code style

### Go

- `gofmt` is mandatory — CI fails on unformatted code.
- `go vet` must pass.
- Standard library only by default. The backend has two dependencies (`pq`,
  `uuid`); new dependencies require maintainer approval and a written justification.
- Error strings are lower-case and do not end with punctuation (Go convention).

### Kotlin

- Follow the [Android Kotlin style guide](https://developer.android.com/kotlin/style-guide).
- Prefer `data class` for value types; Kotlin default parameter values over overloaded constructors.
- No new Android dependencies without discussion — each transitive dependency
  adds APK size for SDK integrators.
- Avoid `!!`. Use `?.let`, `?:`, or early `return`.

### Documentation

- All public API surface must have KDoc/GoDoc comments.
- Comments explain *why*, not *what*. Sensor physics explanations are especially
  valued because the detection logic is non-obvious.
- Docs in `docs/` use British English to match existing text.

---

## Submitting changes

### Branch naming

```
fix/<short-description>       bug fixes
feat/<short-description>      new features
docs/<short-description>      documentation only
test/<short-description>      tests only
refactor/<short-description>  no behaviour change
```

### Commit message format

```
Add TWO_WHEELER lean-angle suppression during turns

Sustained lean angles during motorcycle turns produce false positives
at the current gyro threshold. This adds an orientation-sensor check
to distinguish long turns from pothole-induced roll.

Closes #42
```

- Subject: imperative mood, no trailing period, ≤ 50 characters
- Body: explain *why* the change was needed and any non-obvious decisions
- Reference issues with `Closes #N`, `Fixes #N`, or `See #N`

### Pull request checklist

- [ ] All existing tests pass (`go test ./...` and `./gradlew :asphalt-sdk:test`)
- [ ] New behaviour is covered by at least one test
- [ ] No new dependencies added without prior discussion
- [ ] `contracts/event.schema.json` updated if the event payload changed
- [ ] Docs updated if public API or behaviour changed
- [ ] PR description explains *what* changed and *why*

### Review process

- Maintainers aim to respond within 7 days.
- Sensor physics changes (new vehicle profiles, threshold adjustments) require
  empirical justification — share field test data or reference peer-reviewed
  literature on vehicle dynamics.
- Breaking changes to the event schema require a deprecation plan.

---

## Issue reporting

Use the GitHub issue templates:

- **Bug report** — unexpected detections, crashes, wrong classifications
- **Feature request** — new vehicle types, API endpoints, platform support

Before opening: search existing issues (open and closed) to avoid duplicates.
For detection quality bugs, include: vehicle type, road type, country/city,
Android version, approximate speed, and whether it was a false positive or negative.

---

## Areas where help is most needed

| Area | Why it matters |
|------|---------------|
| iOS SDK | Large share of urban two-wheeler riders use iPhones |
| Electric vehicle profiles | EVs have different vibration signatures than ICE vehicles |
| Per-device calibration | Threshold auto-tuning based on first-session noise floor |
| Integration tests | End-to-end: sensor → batch → backend → cluster |
| Field validation dataset | Labelled ground-truth events from real Indian roads |
| Regional language docs | Lower barrier to entry for local developers |

If you are working on any of these, please open an issue first to coordinate.

---

*By submitting a contribution, you agree that it will be licensed under the
[Apache 2.0 License](LICENSE) that covers this project.*
