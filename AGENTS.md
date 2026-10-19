# AGENTS.md

SMD Monitor is an optional live monitor for applications built with [Simple Message Dispatch](https://github.com/DavidOpDeBeeck/simple-message-dispatch) (SMD). This
repository is one module, `smd-monitor-hub`: a standalone Spring Boot application that admits the spans of many applications over OTLP/HTTP, counts, links, and retains
them within one memory budget on one writer thread, and serves the only monitor UI.

## Priorities

Keep setup simple and figures honest about what they counted. Prefer, in order:

1. Observable correctness.
2. A small public API: the `smd.monitor.hub.*` properties, the page, and the OTLP endpoint.
3. A hub core free of Spring and OpenTelemetry.
4. Early validation and useful errors.
5. Direct code.
6. Deduplication.

## Packages

| Package                             | Responsibility                                                                      |
|-------------------------------------|-------------------------------------------------------------------------------------|
| `app.dodb.smd.monitor.hub`          | The hub, its configuration, writer thread, epochs, and the queries over an epoch    |
| `app.dodb.smd.monitor.hub.otlp`     | The OTLP/HTTP endpoint, decoding spans at the boundary                              |
| `app.dodb.smd.monitor.hub.span`     | The SMD spans the hub admits, as domain types                                       |
| `app.dodb.smd.monitor.hub.intake`   | Per-application intake queues and admission                                         |
| `app.dodb.smd.monitor.hub.counting` | Per-second counts, windows, history, and latency buckets                            |
| `app.dodb.smd.monitor.hub.join`     | Linking spans into traces within the link horizon                                   |
| `app.dodb.smd.monitor.hub.specimen` | The retained invocations and traces                                                 |
| `app.dodb.smd.monitor.hub.catalog`  | The capped message types, processing groups, and edges of each application          |
| `app.dodb.smd.monitor.hub.budget`   | The memory budget and what yields under pressure                                    |
| `app.dodb.smd.monitor.hub.health`   | The hub's health per product and per application                                    |
| `app.dodb.smd.monitor.hub.spring`   | The Spring Boot application, its endpoints, and its WebSocket                       |
| `app.dodb.smd.monitor.json`         | The JSON records of the UI's frames and query answers                               |
| `frontend`                          | The SolidJS + Tailwind CSS v4 page, bundled by Vite into the hub's resources        |

Only `hub.spring` and `hub.otlp` may know Spring or OpenTelemetry; only they and `json` know Jackson.

## Relationship with SMD

SMD knows nothing about the monitor. The span names and attributes the hub reads (`MessageSpans` in `smd-opentelemetry`) are a contract SMD owns: a new span or attribute
goes to SMD first, so any OpenTelemetry backend can use it, and the monitor builds on it once SMD publishes it. The build depends on the published SMD version in
`gradle/libs.versions.toml`, never on an SMD checkout.

Never default or enforce a monitored application's OpenTelemetry settings from the hub; document them in `docs/monitor.md`.

## Correctness

Threads, ordering, memory, and failure reporting are API behavior. For every change to the hub, establish:

- which thread runs each step: the receiving thread admits; the single writer thread counts, links, and retains; frames and queries read published epochs only;
- what each figure counts, in which second, and whether it survives eviction;
- what yields under memory pressure and in which order, and that the budget still bounds everything the hub keeps;
- that an application flooding the hub fills its own queue, not the others';
- what a span arriving late, early, twice, or never does to the counts and the links.

Never let a frame or query hold up ingest, and never mutate an epoch after publishing it. Preserve interruption and stop owned threads on shutdown.

`docs/monitor-hub-design.md` records the decisions behind these rules (T, C, M, J, A, W) and the performance budgets.

## Code

- Use domain verbs (`admit`, `count`, `link`, `retain`, `publish`); avoid `manager`, `util`, `process`, and `execute`.
- Records for immutable data and results; classes for stateful services, builders, adapters, and lifecycle owners. No `Impl` suffix.
- Keep interfaces narrow and visibility limited. Extract an abstraction only for a stable concept or a replaceable boundary.
- Preserve domain types; do not reduce IDs, names, or results to strings and maps.
- Sealed results when callers distinguish outcomes; booleans for predicates.
- Validate nullability and invariants in constructors with single-argument `requireNonNull(value)`; do not repeat it in methods. Do not copy collections defensively.
- Validate configuration once, before use. Errors at user-facing boundaries state what is invalid, what was expected, and the value or name involved.
- Contain reflection, unchecked casts, serialization, and nullable values at their boundaries.
- Write methods top to bottom: derive values, guard, act, translate boundary failures, return.
- `var` when the type is obvious. Streams for transformations and searches; loops for mutation, resources, and early exits.
- Keep Spring bean methods as plain assembly; keep policy in the core.
- Log operator-relevant state changes once, with identifiers.
- No Javadoc. Comment only concurrency, lifecycle, memory, or ownership intent the code cannot show.
- Follow `.editorconfig`: four spaces, same-line braces, one top-level type per file, no wildcard imports, no routine `final` on parameters or locals. No unrelated
  reformatting.

## Tests

- Test caller-visible behavior and likely regressions, not private interactions.
- Name tests `operation_condition_expectedOutcome`; separate arrange, act, and assert with blank lines.
- Use real objects and small handwritten fakes, not mocks. Use AssertJ.
- Cover each validation case separately.
- Make async tests deterministic with latches, atomics, and Awaitility; never sleep.
- Keep integration tests for Spring wiring and the HTTP and WebSocket endpoints.

## Frontend

- Keep logic that does not need Solid in `src/lib` as plain TypeScript; components bind state and render.
- Ask the hub for retained invocations with `createHubQuery` or `createPagedList` (`store/hub-query.ts`); frames carry statistics only.
- Never destructure Solid props or return early from a component.
- Key lists that rebuild every frame with `Key` from `@solid-primitives/keyed` and a stable ID.
- Wrap derived values in `createMemo` before passing them to `on(...)`.
- Use the theme tokens in `src/styles.css`; spell out per-kind class names in `components/tones.ts`.
- Animate live data with the shared motion (`Tweened`, `Sparkline`, `animateSeries`, `motion-value`, `motion-clock`) and end series at `store.throughputEnd()`.
- Run `npm run format` in `frontend/` after editing.

## Workflow

Before coding, name the owning package, the smallest public contract, the thread each step runs on, and the test boundary. Implement one semantic change at a time.

- Public behavior or configuration changed: update `docs/monitor.md`.
- A design decision or budget changed: update `docs/monitor-hub-design.md`.
- Ingest, frames, or queries changed: run the affected benchmarks (`./gradlew jmh -PjmhIncludes=...`).
- Documented throughput changed: keep `docs/monitor.md` and `SaturationCheck` identical.

```bash
./gradlew test
./gradlew integrationTest
./gradlew check                 # also compiles the benchmarks
./gradlew test --tests "app.dodb.smd.monitor.hub.MonitorHubTest"

./gradlew demo                  # a hub at http://127.0.0.1:8080/ with two applications exporting to it
./gradlew bootRun               # the hub alone

./gradlew jmh -PjmhIncludes=... # about 40 minutes unnarrowed
./gradlew saturation            # open-loop saturation runs; -PsaturationScenarios=quick to narrow
./gradlew saturationCheck       # checks the throughput docs/monitor.md documents
./gradlew saturationDashboard   # adds build/saturation to docs/saturation; needs Python

cd frontend && npm run dev      # with ./gradlew demo running
```
