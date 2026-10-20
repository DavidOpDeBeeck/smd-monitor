# Monitor hub design

How the hub works and why, for contributors. [monitor.md](monitor.md) documents its behavior for users; this document records the decisions behind it, the budgets it
is measured against, and what is still open. The rework it describes was decided on 2026-10-16 and completed in slices S0 to S8 by 2026-10-17; their measurements
are in the git history of this file.

- [Purpose](#purpose), [Requirements](#requirements), and [Envelope](#envelope): what the hub is and what bounds it
- [Decisions](#decisions): the rules the code follows, by ID
- [Runtime](#runtime) and [Packages](#packages): how it runs and where the code is
- [Results](#results), [Saturation](#saturation), and [Open items](#open-items): how it measures, and what is left

## Purpose

The hub is an in-memory, read-only, single-node diagnostic view of SMD message flow across applications. It answers what causes what, where time goes, and what
fails. It is exact about counts and lineage, and keeps a selection of traces to explain them. A restart loses everything.

Non-goals: persistence or audit, alerting, long-term metrics, a general tracing backend, high availability, running invocations, gap detection, retry controls,
payloads, exception messages and stack traces, and counts that account for sampling (they assume every SMD span is exported).

## Requirements

### Functional

| ID   | Requirement                                                                                                                   |
|------|-------------------------------------------------------------------------------------------------------------------------------|
| FR1  | Ingest OTLP/HTTP protobuf, gzipped or not. Ignore spans that are not SMD's. Identify application, instance, and session.     |
| FR2  | Learn the topology from span links: types, processing groups, edges within and across applications, entry points, and media. |
| FR3  | Live figures per scope, type, group, edge, and instance over the window; totals over the history.                            |
| FR4  | Cross-application traces from retained specimens, saying why a parent is missing.                                            |
| FR5  | Failures by where they were raised, with exact counts and example traces.                                                    |
| FR6  | A live stream of retained invocations, filtered, sorted, and paged by the hub.                                               |
| FR7  | The hub's own health: what it retains, evicts, refuses, and drops, by reason, and its memory against its budget.              |
| FR8  | The scope is the system or one application. An instance is a filter.                                                         |
| FR9  | Event delay per processing group and event type.                                                                             |
| FR10 | Failure propagation chains, as a heuristic over retained specimens, computed when queried.                                   |

### Technical

| ID   | Requirement                                                                                                         |
|------|---------------------------------------------------------------------------------------------------------------------|
| TR1  | Exactly once: a span is counted once, even if exported twice or arriving along two paths (within J3).              |
| TR2  | Linking gives the same result in any arrival order, across instances and applications.                             |
| TR3  | Counts never depend on specimen capacity, selection, or eviction.                                                   |
| TR4  | The time base, late spans, and clock skew follow written rules. A shown second is final, or the hub says it may change. |
| TR5  | A frame and the queries it triggers describe one epoch, named by session and sequence.                              |
| TR6  | Every figure has a written definition; approximate ones say so.                                                    |
| TR7  | One memory budget covers the hub. Every structure has a limit with a defined, reported behavior when reached.      |
| TR8  | Ingest never waits for frames, queries, or browsers.                                                                |
| TR9  | One application cannot evict another's specimens, cut its edges, or starve its ingest.                             |
| TR10 | Degradation order: specimens first, then join state, counting last.                                                |
| TR11 | Everything retires: an application with no instances for `retire-after` is removed with all its state.            |
| TR12 | Building a frame costs in proportion to the keys, not keys × history seconds.                                      |
| TR13 | A query reads an epoch without copying what the hub retains.                                                       |
| TR14 | The core has no Spring or OpenTelemetry dependency, takes an injected clock, and is tested deterministically.       |
| TR15 | The wire format is the only contract with the UI, and it is versioned.                                             |

## Envelope

### Design target

What the hub is tested and benchmarked at (`LoadProfile.STANDALONE`), and what the sizing guidelines of `monitor.md` are for.

| Dimension                                   | Design target |
|---------------------------------------------|---------------|
| Applications × instances                    | 50 × 20       |
| Span rate                                   | 10k/s         |
| Types and processing groups per application | 500 and 100   |
| Viewers at once                             | 16            |
| Window and history                          | 1 m and 15 m  |
| Memory budget                               | 2 GB          |

### Performance budgets

| Measure                                   | Budget        |
|-------------------------------------------|---------------|
| Writer time per 512-span batch, decoded   | < 5 ms p99    |
| Building an epoch                         | < 20 ms       |
| Delta frame, uncompressed                 | < 100 KB      |
| Query                                     | < 50 ms p99   |
| Hub CPU                                   | < 1 core      |

## Data products

The hub keeps four kinds of state, each with its own lifetime and bound.

| Product    | Holds                                                                                  | Lifetime                       | Bound                                              |
|------------|----------------------------------------------------------------------------------------|--------------------------------|----------------------------------------------------|
| Catalog    | Applications, instances, types, groups, edges, routes, failure causes, interned names  | While the application lives    | Caps per kind, with an `other` key beyond          |
| Aggregates | Per-second counts, failures, and latency histograms per catalog key                     | History                        | 50% of the budget                                  |
| Join state | Slim facts of recent spans, children awaiting their parent                              | Link horizon, or while retained | 25% of the budget, shared fairly                  |
| Specimens  | Retained invocations, assembled into traces                                            | Reservoirs                     | The rest of the budget, shared fairly              |

## Decisions

### Time base

| ID | Rule                                                                                                                                                       |
|----|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| T1 | Every count of a span lands in its start second: started, entry point, completed, failed, latency. A link lands in the child's start second, event delay in the handling's. |
| T2 | The window ends `settle` seconds before the hub's clock.                                                                                                   |
| T3 | A late span revises its own second; history is never shifted. Revisions are counted per application in health.                                           |
| T4 | Per application the hub tracks the p99 of arrival minus start; the frame carries the resulting `provisionalFrom` second, which the UI shades.               |
| T5 | A span that started before the history is linked and can be retained, but not counted in series; it is counted as beyond history.                          |
| T6 | A span may start at most 30 s after the hub's clock; admission refuses it beyond that and flags the instance as clock-ahead.                                |
| T7 | The hub does not correct clock skew. It reports export lag per instance (arrival minus end) apart from revisions.                                          |

### Concurrency

| ID  | Rule                                                                                                                                                         |
|-----|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| C1  | The core is a plain single-threaded object without locks. A writer thread and the intake queues wrap it.                                                   |
| C2  | Admission is synchronous on the receiving thread: application and instance limits, clock skew, and liveness. It answers OTLP partial success exactly.      |
| C3  | Processing is asynchronous. Spans dropped by shedding are reported in health, not in the OTLP response.                                                    |
| C4  | Each application has a bounded intake queue, drained round-robin. Half an equal share of the capacity is reserved for each application; the rest is a pool an application borrows from beyond its reserved room, up to half the pool. A full queue answers 503 with `Retry-After` for that application only. |
| C5  | Shedding only beyond an application's reserved room, by the fill of the pool or of the application's half of it behind the batch the writer takes, whichever is fuller: from 50% only failed and slow spans become specimens; from 80% spans are deduplicated but not indexed or joined. Counting is never skipped. |
| C6  | One epoch per second: one immutable view of catalog, aggregates, specimens, and health, shared by the frame and the queries.                               |
| C7  | Building an epoch costs in proportion to what changed: at most one copy of changed array chunks.                                                           |
| C8  | JSON is serialized off the writer, once per epoch, and shared by every client.                                                                             |
| C9  | At most 4 queries run at once; beyond that the hub answers 429.                                                                                             |
| C10 | An exception in one batch drops that batch, reports it, and logs it once. The writer keeps running.                                                         |
| C11 | Exports arrive on a port of their own, with its own Tomcat connector and threads; each port refuses the other's paths. The page can be published without the endpoint, and pages never take a thread from admission. |

### Memory and specimens

| ID | Rule                                                                                                                                                         |
|----|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| M1 | The budget is split by fixed ceilings: intake queues 10%, counting 50%, join state 25%, specimens the rest.                                                  |
| M2 | Beyond the counting ceiling, new keys overflow into `other` keys. Beyond the join ceiling, the application's effective link horizon shrinks.                |
| M3 | Max–min fair share per application, with borrowing. Under pressure the application furthest above its share gives way first. A trace that crosses applications is charged to each by its own spans. |
| M4 | Three reservoirs; a trace is retained while any holds it. **Recent** keeps every trace, first in first out. **Slowest** keeps the 5 slowest invocations per type in the window. **Failures** keeps the latest 20 traces per failure cause, never fewer than 1. Under pressure they shrink in that order. |
| M5 | Every span enters Recent first; the other reservoirs judge a trace when Recent evicts it. A span for a trace already dropped starts a reported fragment.    |
| M6 | A specimen is a compact record of primitive IDs and interned names, accounted by a fixed size per record kind (`Footprint`).                                |
| M7 | Failure counts come from aggregates; specimens provide example traces and chains. The UI shows "complete since".                                           |

### Join

| ID | Rule                                                                                                                                                          |
|----|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| J1 | One global span index keyed by trace ID and span ID as three longs. An entry is a slim fact or a specimen. Sends are slim entries.                           |
| J2 | An entry lives within the link horizon or while retained. Each application has an arrival-ordered queue of its entries and a fair share of the join budget.  |
| J3 | A span whose key the index holds is a duplicate. Guarantee: a span sent again within the horizon, or while retained, counts once.                            |
| J4 | A link is found when its second side arrives, in either order, and counted once per child. The child keeps a parent summary: application, kind, type, group, and whether the parent delivered the same message. |
| J5 | A slim child that expires unlinked is counted per application by reason: parent never received, or parent arrived after the horizon.                        |
| J6 | A trace is a connected component of retained specimens, merged by size as links arrive, of at most 10,000 invocations; beyond that a fragment starts. A guard handles link cycles. |
| J7 | An epoch's specimen view holds immutable snapshots of the traces that changed, and copy-on-write chunks of 4,096 specimens in sequence order.               |
| J8 | Edges of a parent arriving after its slim children expired are not counted; they are reported.                                                             |

### Aggregates

| ID | Rule                                                                                                                                                       |
|----|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| A1 | The catalog gives every key a dense int ID per application. Beyond a cap, counts go to the `other` key of that kind.                                      |
| A2 | Window and history totals per key are kept incrementally: added as counts arrive, subtracted as their second leaves.                                     |
| A3 | Per-second detail is stored sparsely, one block per second of its active keys, over window + settle + 30 s; older seconds fold into 10 s buckets. A timed cell keeps up to two latency buckets inline and grows an array at the third. |
| A4 | The Activity chart keeps 1 s over the whole history per application and kind.                                                                              |
| A5 | Sparklines read a small dense ring per key over the window.                                                                                                |
| A6 | Latency histograms have 19 buckets bounded at 0.1 ms to 60,000 ms; percentiles interpolate linearly within a bucket.                                      |
| A7 | Counters are mutable primitive arrays owned by the writer; updates allocate nothing.                                                                       |
| A8 | Counting is never shed: under pressure seconds fold sooner, then the effective history shortens. The window never shrinks.                                |

### Wire

| ID | Rule                                                                                                                                                        |
|----|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| W1 | One system-wide frame per epoch over the WebSocket. Scope stays on the client.                                                                             |
| W2 | Frames are deltas against the previous epoch, found by comparing two immutable snapshots. Each names its `epoch` and `base`.                               |
| W3 | A snapshot goes to a client on connect and to any client that missed a delta. Each is serialized at most once per epoch.                                   |
| W4 | JSON, compressed by `permessage-deflate`. Statistics travel as arrays of figures in a fixed order.                                                         |
| W5 | Specimens are pulled over HTTP from the epoch. Identical queries of an epoch are answered once; traces and invocations carry an ETag of their content.      |
| W6 | Frames and answers carry a protocol version and a build ID; the page reloads when either differs.                                                          |
| W7 | The hub computes what is displayed; the page only sums applications for the system scope.                                                                  |

### Failures

| ID | Rule                                                                                                                                                          |
|----|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| F1 | Every failed span counts under its own stage and exception class, once for every message it failed. Raised versus propagated is a heuristic on specimens, shown only in the chains. The Failures reservoir keys a trace by its deepest failure. |
| F2 | Failure shares, occurrence strips, and "Failures, last minute" come from aggregates.                                                                         |

## Runtime

```
 OTLP/HTTP                                                              ┌─► MonitorFrameSocket: delta to clients in sync,
 (own port)                                                             │   snapshot to clients that missed one
     └──────────► Admission ──► IntakeQueues ──► HubWriter ──► Epochs ──┤
                  limits,       per application,  drains round-robin,   │
                  skew,         bounded,          ticks each second,    └─► MonitorHubQueries: at most 4 at once,
                  liveness      shedding level    owns Hub                  per-epoch cache, ETag
                                                    │
                                Hub (single-threaded core)
                                  Catalog · Counters · SpanIndex · Specimens · MemoryBudget · Health
```

| Thread                          | Runs                                                   | Touches                                         |
|---------------------------------|--------------------------------------------------------|-------------------------------------------------|
| OTLP port threads (export)      | Decoding, `Admission`, `IntakeQueues.offer`            | Admission (own lock), the queues (thread-safe) |
| `smd-monitor-hub` writer        | Receiving batches, ticking, building epochs           | All mutable state of `Hub`, exclusively         |
| Frame publisher                 | Building, diffing, and serializing frames; async sends | The latest two epochs, immutable                |
| Page port threads (queries)     | `Queries` over the latest epoch                        | The epoch, immutable                            |

Per span, on the writer, in arrival order within its application:

1. `SpanIndex` stops a duplicate. At shedding level 2 the span is checked but not indexed.
2. `Catalog` interns its stage and failure cause, or assigns the `other` key.
3. `Counters` adds it to its start second, counting it as revised or beyond history when that second settled or left.
4. Each link found counts its edges, routes, and event delay in the child's start second; the child keeps the parent summary.
5. `Specimens` adds it to Recent and merges trace components; at shedding level 1 only failed and slow spans.

Per tick, after each second:

1. `Admission` names the applications without instances for `retire-after`; the hub drops their state.
2. `Counters` subtracts the seconds leaving the window and history, and folds old seconds into 10 s buckets.
3. `SpanIndex` forgets entries past the horizon, counting unlinked children.
4. `Specimens` evicts from Recent, letting Slowest and Failures judge each evicted trace.
5. `MemoryBudget` fits the products in the order of TR10; the specimens are also fitted after every batch.
6. The writer builds the `Epoch` and publishes it.

### Constants

| Constant                       | Value                                  |
|--------------------------------|----------------------------------------|
| Maximum clock skew             | 30 s                                   |
| Epoch                          | 1 s, just after each second            |
| Coarse bucket                  | 10 s                                   |
| Budget ceilings                | Queues 10%, counting 50%, join 25%     |
| Shedding thresholds            | 50% and 80% of the pool or of its half |
| Slowest and Failures K         | 5 per type, 20 per cause (minimum 1)   |
| Invocations per trace          | 10,000                                 |
| Concurrent queries             | 4                                      |
| Cached answers per epoch       | 256                                    |
| WebSocket clients              | 64, with a 5 s send timeout            |
| Specimen chunk                 | 4,096                                  |
| Request body                   | 16 MB, inflated or not                 |
| `Retry-After`                  | 2 s                                    |

The configurable limits and their ranges are in [monitor.md](monitor.md#limits) and `HubConfig`.

## Packages

Every package except `hub.otlp` and `hub.spring` is free of Spring and OpenTelemetry. Types are public only where another package needs them; the documented API
is the configuration properties and the hub's endpoints.

| Package          | Key types                                                                                                                          |
|------------------|------------------------------------------------------------------------------------------------------------------------------------|
| `hub`            | `MonitorHub` (the runtime facade: `submit` returns a sealed `Submission`), `HubConfig`, `Hub` (the core), `HubWriter`, `Epochs`, `Epoch`, `Queries` and its query and answer records |
| `hub.span`       | `MessageSpan`, `SinkSend`, `SpanRef`, `Stage`, `MessageType`, and the names: the records the OTLP adapter builds                     |
| `hub.intake`     | `SpanBatch`, `Admission` (limits, skew, liveness), `IntakeQueues`, `Shedding`, `Refusal`                                           |
| `hub.catalog`    | `Catalog` (dense IDs with caps), `Interned`, and the edge keys `CausalLink`, `Crossing`, `GroupLink`, `EventRoute`, `SinkRoute`, `RaisedFailure` |
| `hub.counting`   | `Counters` with its `Block`s, `Cell`s, and `Totals`; `ArrivalLag`                                                                    |
| `hub.join`       | `SpanIndex`, `SpanKey`, `Fact`, `Link`, `Unlinked`                                                                                  |
| `hub.specimen`   | `Specimens`, `Specimen`, `Reservoirs`, `TraceComponent`, `TraceSnapshot`, `SpecimenView` and its chunks, `Names`                   |
| `hub.budget`     | `MemoryBudget`, `Footprint` (bytes per record kind), `FairShare`, `HeapCheck`                                                      |
| `hub.health`     | `HealthReason`, `HealthView`, `ApplicationHealth`, `MemoryUse`, `ProductUse`                                                       |
| `hub.otlp`       | `OtlpTraceController` (partial success, 503, 400) and the protobuf decoding                                                        |
| `hub.spring`     | `MonitorHubApplication`, `MonitorHubProperties`, `MonitorHubPage`, `MonitorFrameSocket`, `MonitorFrames`, `MonitorHubQueries`, `QueryCache` |
| `json`           | `Protocol`, `FrameView`, `FrameDelta`, `Frame`, and the query views; `frontend/src/lib/wire.ts` and `mirror.ts` mirror them         |

## Results

Recorded on 2026-10-17 at the design target with a 2 GB budget, which keeps the whole history and horizon, with `./gradlew jmh` on an Intel Core i5-12600K (16
threads), 64 GB, Windows 11, OpenJDK 25, a 12 GB heap, and JMH in sample-time mode. The benchmarks are in `src/jmh` and share `LoadGenerator` and `LoadProfile`
with the tests: a seeded, clock-driven load with cross-application links, event-store handlings delayed by 20 ms to 8 s, 2% failed handlings, and spans exported
every 5 s in shuffled batches of up to 512. Each fork first fills the hub's whole history.

| Budget            | Measure                                                       | Benchmark                   | Result                                                   |        |
|-------------------|---------------------------------------------------------------|-----------------------------|----------------------------------------------------------|--------|
| < 5 ms p99        | Writer time per 512-span batch, with admission                | `ReceiveBenchmark`          | 5.6 ms p99, 1.5 ms p50                                   | missed |
| < 20 ms           | Building an epoch                                             | `FrameBenchmark.statistics` | 158 ms p99, 62 ms p50                                    | missed |
| < 100 KB          | Delta frame, median and largest                               | `FrameBenchmark`            | 1,440 KB, 2,841 KB; 286 KB, 613 KB deflated              | missed |
| < 50 ms p99       | Query: tape, traces, failures of 20 causes, timeline          | `QueryBenchmark`            | 20.6, 12.1, 96.6, 9.8 ms                                 | missed for failures |
| < 50 ms p99       | First query of an epoch                                       | `RelinkBenchmark`           | 24.0 ms                                                  | met    |
| < 1 core          | One second of writer work                                     | `WriterBenchmark`           | 105 ms p50, 203 ms p99                                   | met    |
| < 1 s             | Writing an epoch's delta, on the publisher                    | `FrameBenchmark.delta`      | 211 ms p50, 323 ms p99                                   | met    |
| Within the budget | Heap of the hub after a collection                            | `HeapBenchmark`             | 1,660 MB of 2,048 MB; 1,681 MB accounted                 | met    |
| Below 50%         | An application's busiest second against its queue share       | `WriterBenchmark`           | 631 spans, 11% of 5,741                                  | met    |

Before the rework, the same load took about 24 cores, 4.5 GB of heap without a bound, and 48 MB per frame. [Open items](#open-items) lists the levers for the
budgets still missed.

Correctness under load is covered by tests rather than benchmarks: `MonitorHubInvariantsTest` (linking in any arrival order, within and across applications,
duplicates), `CountingRecountTest` (every figure against T1 to T3, in any order and with late spans), `MonitorHubBudgetTest` (the degradation order and TR9), and
`MonitorFrameDeltaTest` and `MonitorFrameMirrorTest` (snapshot(e) = snapshot(e − 1) + delta(e), also through the page's `mirror.ts`).

## Saturation

The JMH benchmarks drive the core on one thread. A saturation run drives `MonitorHub` as it runs, to find where it stops: eight submitting threads admit and
queue batches as the servlet threads do, the writer drains and ticks, and a publisher writes each epoch's delta. Each scenario runs in a JVM of its own, fills its
whole history at its first rate, then offers each rate of a ladder up to 300,000 spans per second for 30 seconds, open loop: every second's exports are offered
whether or not the hub kept up, and a batch answered busy is not sent again. A step **keeps up** when nothing was refused or shed, the writer was busy less than
90% of the time, and the queues drained; it **holds** when it only shed. `SaturationScenario` defines the scenarios in sweeps, each varying one dimension of the
reference: 50 applications × 10 instances, 200 types and 50 groups each, 2 GB, and a 15-minute history. The writer's cost per span grows with the applications,
so that sweep has three points (5, 100, 500); the instances (100), types (1,000), and budget (256 MB) sweeps keep only the point where their dimension costs
most, as the points between tracked the reference. With the design target that makes eight scenarios.

```bash
./gradlew saturation                                   # every scenario, about 35 minutes
./gradlew saturation -PsaturationScenarios=quick       # the reference and the profiles; or a sweep, or IDs such as applications-500
./gradlew saturation -PsaturationScenarios=applications-500 -PsaturationRates=40000,50000 -PsaturationJfr=build/saturation/a500.jfr
./gradlew saturationDashboard                          # adds build/saturation to docs/saturation and writes build/saturation/dashboard.html
```

A run writes a table, every second as CSV, and its limits and steps as JSON to `build/saturation`, and the latest progress line to `build/saturation/progress.txt`.
Name the machine with `-PsaturationMachine="..."`, pass collector flags with `-PsaturationJvmArgs`, name the run on the page with `-PsaturationName="..."`
instead of Run N, and choose the Python of `saturationDashboard` with `-PsaturationPython`. `docs/saturation` keeps every run added; its page compares
them on one scale, so an improvement shows as a limit that moves right. A limit moves in steps of the ladder, and two runs of one commit can land a step
apart, so the page also compares the writer's capacity: the spans processed per second of the writer on a core, at the rates both runs offered. It moves by
a few percent between runs of one commit, so a change of a tenth shows plainly. Compare only runs of the same machine, and run on an idle one.

Run 1, 2026-10-20, on a Ryzen 7 5825U (16 threads, 32 GB, Linux) at the commit before the optimizations of pull requests #1 to #3, in spans per second:

| Sweep        | Scenario                  | Keeps up | Holds   | Fails at          |
|--------------|---------------------------|----------|---------|-------------------|
| Reference    | 50 × 10                   | 150,000  | 200,000 | 250,000, behind   |
| Profile      | 50 × 20, 500 types        | 100,000  | 200,000 | 250,000, refuses  |
| Applications | 5                         | 200,000  | 200,000 | 250,000, behind   |
|              | 100                       | 80,000   | 150,000 | 200,000, refuses  |
|              | 500                       | 30,000   | 60,000  | 80,000, refuses   |
| Instances    | 100                       | 150,000  | 150,000 | 200,000, behind   |
| Types        | 1,000                     | 125,000  | 150,000 | 200,000, refuses  |
| Budget       | 256 MB                    | 20,000   | 40,000  | 50,000, refuses   |

How to read it:

- The number of applications sets the ceiling, not the spans or the keys: costs that grow with evictions times applications dominate the writer at 500.
- The writer saturates before its CPU does: busy includes collection pauses and waiting for a core.
- Shedding starts before the writer is saturated, when an application's exports bunch up in its queue share. Counting stays exact throughout.
- Memory gives way before throughput: under load the link horizon and then the history shorten within the budget.
- Many instances cost little: 100 instances per application keep up where the reference's 10 do.

`./gradlew saturationCheck` checks the rates [monitor.md](monitor.md#throughput) documents, one per scenario in `SaturationCheck`. It fills the whole history at the
documented rate, a heavier steady state than one reached by stepping up, so the documented rates sit below these limits. Keep the two identical.

## Open items

Budgets still missed and work deferred, with the cause found and the levers to try.

- **Many applications cap the writer.** 500 applications first fail at 80,000 spans per second where 5 hold 200,000 (run 1 in `docs/saturation`). Already
  done: the reservoirs keep their entries by application, `Specimens` and `SpanIndex` keep running byte counts, `SpanIndex` fits from a heap of the applications
  above their share built once per fit, `Specimens`, which fits after every batch, keeps its heap from one fit to the next and sets aside the applications at
  their floor until Recent holds a trace of theirs or their reservoirs grow, sparklines copy out of their rings, failure series are read in one pass, handlings
  are summed from their groups, and span IDs are parsed once on the receiving thread. A running total keeps the counts it last read until it changes, so
  consecutive epochs share them; entry points are a bit per dispatch stage; a total adds and subtracts only the latency buckets a second used; and a series of
  zeros, such as the failures of a stage that never failed, is not copied. What is left: the epoch's statistics, still rebuilt for every key each second (about
  40% of the writer at 500 applications, spread over a lookup per key in each structure). Generational ZGC removed the tick's pause tail but cost the writer
  0.1 to 0.3 cores and lowered the limits; G1 stays.
- **Small budgets with many applications.** The queues' 10% split equally leaves small shares, so bunched exports shed a little with the writer below half busy.
  Lever: say in `monitor.md` how the budget should grow with the applications.
- **The publisher at 500 applications** writes a delta in about a second, so pages miss frames. Levers: those of the delta below, and rebuilding only the records
  that changed.
- **The delta frame is above 100 KB** (1.4 MB at the design target). Thousands of keys change every second as seconds enter and leave the window. Levers: refresh
  percentiles and history counts every few seconds instead of every second, or subscribe each page to the keys of its scope and view, which reverses W1.
- **The failures query takes 97 ms p99.** Each query walks every failed specimen and works out its propagation. Lever: index failed specimens by cause in the
  writer as they are retained, and their propagation as children link.
- **Building an epoch takes 158 ms p99.** It rewrites the statistics of 27,500 types every second and snapshots the changed traces; the tail is collection pauses.
  Counts that did not change are shared with the previous epoch already, but a key's record is new every second, as its sparkline moves with the window. Lever:
  rebuild only the keys that counted or changed, with a sparkline that moves without a copy, which also shortens the publisher's comparison.
- **The batch time is 5.6 ms p99.** The tail is collection pauses on a 2 GB live set. Levers: fewer allocations in decoding; a pause-time goal.
- **The span index takes about 300 bytes per fact**, so at small budgets the horizon shrinks first. Levers: compact facts the way `Specimen` is, or a lower
  specimen floor.
- **Shedding and the clock-ahead flag are covered by tests only.** The demo's in-process exporters never fill a queue share and share the hub's clock. Lever: a demo
  instance in another process that exports bursts, and one whose clock runs ahead.
