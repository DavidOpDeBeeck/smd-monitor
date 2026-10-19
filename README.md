# SMD Monitor

> **EXPERIMENTAL**: SMD Monitor is in early development and may introduce breaking changes while it remains in the `0.0.x` line.

A live monitor for applications built with [Simple Message Dispatch](https://github.com/DavidOpDeBeeck/simple-message-dispatch) (SMD). It shows what causes what, where
time goes, and what fails, across every application that sends it its commands, queries, and events.

Applications record their messages as OpenTelemetry spans with SMD's `smd-opentelemetry` and export them to the **hub**, a standalone Spring Boot application. The hub
counts, links, and retains them in memory and serves the monitor page. The monitor is optional: SMD does not depend on it, and the same spans can go to any
OpenTelemetry backend.

## Try it

```shell
./gradlew demo
```

Open <http://127.0.0.1:8080/>. The demo runs a hub and two applications that export to it, in one JVM, with no database or Collector.

## Run the hub

```shell
./gradlew bootJar
java -jar build/libs/smd-monitor-hub-0.0.1.jar
```

The hub serves the page at <http://127.0.0.1:8080/> and receives spans at `/v1/traces`. It listens on the loopback address only, because it has no authentication: put
a reverse proxy in front of it to reach it from other hosts.

Then add `smd-opentelemetry` and Spring Boot's OpenTelemetry tracing to each application, sample every span, and point its OTLP exporter at the hub. See
[Set up the monitor](docs/monitor.md#set-up-the-monitor).

The monitor needs SMD 0.0.12 or newer, which is not released yet.

## Documentation

- [Live monitor](docs/monitor.md): setup, configuration, security, and what the page shows
- [Hub design](docs/monitor-hub-design.md): the hub's design decisions, budgets, and benchmarks, for contributors
- [Brand](docs/brand.md): the SMD mark and its colors

## Build

Building needs Java 25 and Node.js 22.12 or newer on the `PATH`. The build uses the published SMD artifacts of the version in `gradle/libs.versions.toml`; until SMD
0.0.12 is released, run `./gradlew publishToMavenLocal` in [simple-message-dispatch](https://github.com/DavidOpDeBeeck/simple-message-dispatch) first.

```shell
./gradlew check
```

## License

[MIT](LICENSE)
