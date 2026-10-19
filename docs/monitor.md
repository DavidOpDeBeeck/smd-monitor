# Live monitor

The monitor shows the command, query, and event bus invocations of SMD applications live in a browser. It answers three questions: what causes what, where time goes,
and what fails. It is read-only, keeps everything in memory, and starts empty after a restart. It is a live diagnostic window, not an audit log or a metrics store.

- [Set up the monitor](#set-up-the-monitor): record spans in your applications and run the hub
- [Configuration](#configuration): limits, memory, and throughput
- [Secure the hub](#secure-the-hub)
- [Using the monitor](#using-the-monitor): the views
- [What the figures mean](#what-the-figures-mean)
- [How the hub behaves](#how-the-hub-behaves): threads, load, and endpoints
- [Develop the UI](#develop-the-ui)

## Set up the monitor

| What                                         | Where                                    | Does                                                                                |
|----------------------------------------------|------------------------------------------|-------------------------------------------------------------------------------------|
| The SMD starter and an OpenTelemetry SDK     | Every application you want to see        | Records the application's bus invocations as OpenTelemetry spans; serves nothing    |
| The hub, `smd-monitor-hub`                   | Once, as an application of its own       | Receives the spans over OTLP/HTTP, combines them per application, serves the page   |

The monitor needs SMD 0.0.12 or newer.

### Record an application's messages

```kotlin
implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
```

With `@EnableSMD`, the SMD starter then records the standard buses (see SMD's
[OpenTelemetry](https://github.com/DavidOpDeBeeck/simple-message-dispatch/blob/master/docs/spring-boot.md#opentelemetry)). Then configure:

```yaml
spring:
  application:
    name: tickets
management:
  tracing:
    sampling:
      probability: 1.0
  opentelemetry:
    resource-attributes:
      "[host.name]": ${HOSTNAME:${COMPUTERNAME:localhost}}
      "[service.instance.id]": ${random.uuid}
    tracing:
      export:
        otlp:
          endpoint: http://smd-monitor-hub:8080/v1/traces
        schedule-delay: 1s
  otlp:
    metrics:
      export:
        enabled: false
```

Each setting matters:

- **`spring.application.name`** sets `service.name`, which groups instances into an application.
- **Sampling.** The hub counts the spans it receives, so a sampled-out span is an invocation it never counts. Spring Boot samples 10% by default and SMD does not change
  that. Either sample everything, which also samples the application's other spans, or define a `Sampler` that keeps every SMD span. Spans the exporter drops, because
  its queue is full or the hub is unreachable, are missing from the counts too.
- **`host.name`** names the instance, such as its pod name. Without it the hub falls back to `service.instance.id`, and without either, to `unnamed`. Copies of one
  application on one host need different host names.
- **`service.instance.id`** tells each run of an instance apart, so a restart is recognized. Spring Boot sets neither attribute.
- **`schedule-delay`** is optional. Spring Boot exports every 5 seconds, so a second's spans reach the hub up to 5 seconds late. Exporting every second completes the
  newest seconds sooner; then set the hub's `settle` to `2s`.
- **`otlp.metrics.export.enabled`** turns off the starter's OTLP metrics export, which otherwise sends metrics to `localhost:4318`. The hub receives spans only.

The OpenTelemetry SDK exports on its own thread, so a slow or unavailable hub never delays a message. Gateways you create yourself need `MessageTracing` installed
explicitly. Lineage travels in the message metadata through a text-map propagator, which Spring Boot configures by default.

The spans are standard OpenTelemetry spans: to also send them to Jaeger, Tempo, or another backend, point the exporter at a Collector that forwards to both. The hub
ignores spans of other instrumentation and accepts OTLP/HTTP binary protobuf, gzipped or not; not OTLP/JSON or gRPC. SMD's
[Spans](https://github.com/DavidOpDeBeeck/simple-message-dispatch/blob/master/docs/opentelemetry.md#spans) documents what each span carries.

Without Spring, record the spans as [OpenTelemetry without Spring](https://github.com/DavidOpDeBeeck/simple-message-dispatch/blob/master/docs/opentelemetry.md#without-spring)
describes, with a sampler that keeps every SMD span.

### Run the hub

```shell
./gradlew bootJar
java -jar build/libs/smd-monitor-hub-0.0.1.jar --smd.monitor.hub.memory=2GB
```

The hub needs Java 25. Configure it like any Spring Boot application: arguments, environment variables such as `SMD_MONITOR_HUB_MEMORY=2GB`, or an
`application.properties` beside the jar. It listens on port 8080 of `127.0.0.1` only; read [Secure the hub](#secure-the-hub) before you change `server.address`.

| Path         | Serves                                                     |
|--------------|------------------------------------------------------------|
| `/`          | The monitor page                                           |
| `/v1/traces` | The OTLP/HTTP endpoint the applications export to          |
| `/live`      | The WebSocket that sends the page its frames               |
| `/api/...`   | The queries the page makes for retained invocations        |

Set `server.servlet.context-path` to serve them under a prefix, such as `/ops/monitor`.

### Try the demo

```shell
./gradlew demo
```

Open <http://127.0.0.1:8080/>. The demo runs the hub and a web shop of four applications in one JVM. Each application appends its events to an in-memory
topic that the processing groups of the next one read:

- `storefront`, on two instances, serves searches and product pages and places an order about every 1.5 s per instance.
- `inventory` reserves the stock of each order; about 3% of the reservations fail with `OutOfStockException`.
- `payments` scores the fraud risk and charges the card gateway, which is sometimes slow and times out about 2% of the time; about 8% of the payments are
  declined, and storefront cancels their orders, which releases their stock.
- `shipping` schedules a shipment for each captured payment, and storefront emails the customer; about 5% of the shipping labels fail, asynchronously.

Pass `--args="--server.port=8083"` to change the port, and prefix an argument with an application's name, such as `--storefront.smd.demo.orders-per-second=50`,
to pass it to that application instead of the hub.

## Configuration

### Limits

Everything the hub keeps is bounded by these properties; invalid values fail at startup.

| Property                           | Default | Meaning                                                                                                                                                     |
|------------------------------------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `smd.monitor.hub.memory`           | `256MB` | The budget that bounds everything the hub keeps; see [Memory budget](#memory-budget). `32MB` to `8GB`, and at most 75% of the JVM's maximum heap.             |
| `smd.monitor.hub.window`           | `1m`    | The window every live figure covers: rates, failures, percentiles, event delays, and sparklines. Whole seconds, from `10s` to the history.                  |
| `smd.monitor.hub.history`          | `5m`    | How long counts are kept: the Activity chart and the totals of types, arrows, groups, and failures. Whole seconds, from the window to `15m`.                |
| `smd.monitor.hub.settle`           | `5s`    | How long the hub waits for a second's spans before showing it. Matches a 5-second export; use `2s` with 1-second exports. `1s` to `10s`.                     |
| `smd.monitor.hub.link-horizon`     | `60s`   | How long the hub remembers a span to link it to a parent or child arriving later, and to recognize it if it arrives again. `10s` to the history.          |
| `smd.monitor.hub.max-applications` | `100`   | Applications the hub monitors; spans of further applications are refused until one retires.                                                                |
| `smd.monitor.hub.max-instances`    | `256`   | Instances per application; a further instance replaces the longest silent one, or is refused when none is silent.                                          |
| `smd.monitor.hub.max-types`        | `1000`  | Message types counted apart per application, up to 8,000; further types count together as **Other types**.                                                 |
| `smd.monitor.hub.max-groups`       | `200`   | Processing groups counted apart per application, up to 2,000; further groups count together as **Other groups**.                                           |
| `smd.monitor.hub.max-edges`        | `5000`  | Arrows, routes, and failure causes counted apart per application and kind, up to 80,000; further ones count together.                                      |
| `smd.monitor.hub.silent-after`     | `10s`   | How long an instance may send nothing before it shows as silent.                                                                                           |
| `smd.monitor.hub.retire-after`     | `10m`   | How long before a silent instance leaves the list; an application whose instances all left is dropped with everything the hub holds of it.                  |

Counts beyond the caps stay in each application's totals but are not shown apart. Raise the link horizon when arrows go missing because handlers run long after
the publication, such as from an event store that falls behind. Seconds older than the window, the settle, and 30 seconds more are kept in 10-second buckets, so
history totals move in 10-second steps. A long window or history costs every page bandwidth, since frames carry per-second series.

### Memory budget

The hub estimates everything it keeps against `smd.monitor.hub.memory`, by a fixed size per record kind. Roughly: a retained invocation takes 540 bytes, plus 380 for
its trace and 140 for its parent's summary; a span the hub no longer retains takes 300 bytes in the link index until it leaves the horizon; a count of one type,
group, or instance in one second takes 72 bytes, and of an arrow or failure cause 16. Measured at the design target, the heap the hub took stayed within 7% of its
estimate and below the budget.

The budget is split by fixed ceilings: the intake queues up to 10%, counting up to 50%, the link index up to 25%, and retained invocations whatever those leave.
The link index and the retained invocations are shared fairly: an application may borrow what others do not use, and the one furthest above its share gives way
first. An application within its equal share keeps what it has, however much another sends.

When the hub needs room, it gives way in this order:

1. **Retained invocations.** The application furthest above its share drops its oldest traces, keeping only those of the 5 slowest dispatches per type in the window
   and the latest 20 traces per failure cause; then it keeps fewer, down to one trace per failure cause.
2. **The link index.** That application forgets spans before they leave the horizon, so its effective horizon is shorter and links to older parents are missed.
3. **Counting.** Seconds fold into 10-second buckets sooner, then the history shortens 10 seconds at a time. The window never shrinks.

Each step recovers once the hub uses less than 80% of what it may. When even the minimum does not fit, the hub keeps the window's counts and one trace per failure,
and its health shows the overrun.

What a budget keeps at the [design target](monitor-hub-design.md#design-target), 50 applications of 20 instances sending 10,000 spans per second over 500 message
types each, with a 15-minute history, at its steady state:

| Budget | Counts cover             | Link horizon | Retained invocations | Heap after a collection |
|--------|--------------------------|--------------|----------------------|-------------------------|
| `1GB`  | 680 s, folded sooner     | none         | 761,000              | 979 MB                  |
| `2GB`  | The whole 15 minutes     | 60 s         | 1,652,000            | 1,660 MB                |
| `4GB`  | The whole 15 minutes     | 60 s         | 4,554,000            | 3,612 MB                |

**Sizing:** the default `256MB` suits a few applications. Give a hub of the design target's size `2GB`, and the JVM at least four times the budget, which leaves room
for requests and frames in flight and for the collector. The hub warns when the budget exceeds 25% of the maximum heap and fails to start above 75%.

### Throughput

How many spans per second a hub keeps up with depends first on how many applications send to it, then on the machine. Measured on an Intel Core i5-12600K (16
threads, 64 GB, Windows 11) with OpenJDK 25, a 12 GB heap, and G1. Each row fills the hub's whole history at its rate, then offers that rate for 30 seconds: the hub
refused and shed nothing, its writer thread was busy less than 90% of the time, and its queues drained. Every row has a `2GB` budget and a 15-minute history;
applications have 200 message types and 50 processing groups each, except the design target's 500 and 100. Traces cross applications, and 2% of handlings fail.

| Applications × instances   | Keeps up with   |
| -------------------------- | --------------- |
| 5 × 10                     | 150,000 spans/s |
| 20 × 10                    | 100,000 spans/s |
| 50 × 10                    | 60,000 spans/s  |
| 50 × 20, the design target | 80,000 spans/s  |
| 100 × 10                   | 40,000 spans/s  |
| 200 × 10                   | 20,000 spans/s  |
| 500 × 10                   | 5,000 spans/s   |

- Above these rates the hub first sheds: it still counts every span, but retains or links fewer. Then it answers 503 to the applications whose queues are full.
- A busy hub keeps a shorter link horizon and history within its budget; a larger budget keeps more. Give a hub of many applications more than the default.
- The rates leave out decoding requests, which runs on the servlet threads, the network, queries, and frames.

To check these rates on another machine or after a change, run `./gradlew saturationCheck` on an otherwise idle machine (about 40 minutes); it fails naming each row
that no longer keeps up. [Saturation](monitor-hub-design.md#saturation) describes the runs that find where each shape stops.

## Secure the hub

The hub has no authentication: whoever reaches it can open the page and send it spans. That is why it listens on the loopback address only. To open it to other
hosts, put a reverse proxy in front of it, on the same host or on a network only the proxy reaches, and set `server.address` to match:

- Authenticate the operators who open `/`, its assets, `/api`, and the WebSocket handshake at `/live`; all are read-only `GET`s.
- Let the instances or a Collector reach `POST /v1/traces`. Exporters cannot log in, so restrict it by network, or require headers you configure on the exporter with
  `management.opentelemetry.tracing.export.otlp.headers`.
- Forward WebSocket upgrades, keep the `Host` header, send `X-Forwarded-Proto`, and set `server.forward-headers-strategy=native`: the WebSocket only accepts handshakes
  from the page's own origin. HTTPS pages use `wss:` automatically.

The page loads no external resources. Payloads, results, principals, custom metadata, exception messages, and stack traces are never recorded: spans carry message
and parent IDs, class names, thread names, timing, status, and the failure's exception class.

## Using the monitor

### Scope

The page shows one **scope**: the whole **System**, which it opens on, or one application (**System › tickets**). Select **System** to widen it, or the scope, or press
**A**, to pick another. Every view works in every scope, and changing scope keeps the view and the selection. Narrowing never hides what crosses into other
applications: a trace keeps its other applications' messages, dimmed, and Flow keeps every application its flow reaches. An instance is not a scope; find its
invocations by name in the filters of Traces and Live. The URL holds the scope, so links and the back button work: `?scope=tickets&type=tickets::command:OpenTicketCommand#/flow`
opens Flow on a type.

### Views

**Overview** sums up the scope's last minute.

- **Entry points**: the rate of messages dispatched without a parent, the work arriving from outside.
- **Failure rate**: the share of invocations started, dispatches and handlings together, that failed.
- **p95 latency** per kind. In the system scope this is the highest of any application's, since percentiles cannot be summed.
- **Event delay**: how long after its publication returned a processing group started handling an event, p95. It grows when groups fall behind and names the slowest.
- The **applications** (system scope) with their instances, rate, failures, and memory; or **upstream** and **downstream** applications (application scope).
- The **Activity** chart: commands, queries, events, and failures per second over the history, each row on its own scale.
- **Message types**: each type's rate, failed share, and a latency band (p50 to p95, a tick at p50, a ring at p99), sortable and filterable.
- **Failures, last minute**, **Processing groups** with their delivery (`sync`, `async await`, `fire and forget`, `event store`) and delay, and **Instances**.

**Flow** follows one message type or processing group through the system. Pick it in the **Message** field; entry points come first. The lens shows what caused it
on the left and what it led to on the right, **Hops** far, each application in its own lane. Commands are blue diamonds, events orange squares, queries green
circles, and processing groups pills. A line's style tells how an event reached a group: solid on the publishing thread, dashed on a worker the publication waits
for, dotted on one it does not. Groups that read from an event store or another medium read from a station on that medium's lane. Steps the lens does not draw
are counted as **+N**. Drag or scroll to move, **F** to recenter.

**Traces** lists retained root messages with everything they caused, newest first, across applications. Filter by text or instance name, type, status, chains,
or pins; sort by recency, duration, or size. The waterfall shows each invocation on the trace's time axis with its application, instance, thread, start, wait
after its parent returned, and duration; every column header filters. A command's or query's row covers its handler, with what the handler dispatched nested
below; under each event, a row per processing group shows it handling the event, with what its handlers dispatched nested below. **Pin** keeps a copy of a trace in the tab so it survives eviction; **Copy JSON** exports it.

**Failures** lists every **cause** of the last five minutes, a stage (a type's dispatch or a group's handling) failing with one exception class, ranked by the
last minute. Each shows its counts, share, and a strip of failures per second. For the causes shown, the page asks for their latest retained traces and failure
chains, from the entry point down to where the failure was **raised**, or the last step it was **propagated** from. The chain is a heuristic over retained
invocations: a message that catches a child's failure and throws its own also shows as propagating it. The tab's badge counts failures since you last looked.

**Live** streams the scope's retained invocations three ways: **Lanes**, a lane per message type in causal order; **Scatter**, on a log latency axis from 10 µs to
60 s; and **Tape**, a log newest first that follows new messages until you scroll away. **Slower** filters by a minimum duration over a histogram. Lanes and
Scatter leave out handlings, which would overlap their event.

**Inspector**: select any card, row, bar, or dot to inspect a message type (activity, latency, causes and effects, recent invocations), an invocation (lineage,
timing, thread, IDs, failure), or an application (instances, rate, upstream and downstream). It stays open across scopes.

Retained lists load 100 entries at a time, up to 1,000, then say how many more match. Lanes and Scatter draw the latest 2,000 dispatches. While the pointer is held on
a list or chart it stops moving, so clicks land.

### Keyboard

| Key              | Does                                                    |
|------------------|---------------------------------------------------------|
| **Ctrl K**, **/** | Palette: jump to a type, a message by ID prefix, a scope, or an action |
| **A**            | Palette of scopes                                       |
| **1**–**5**      | Overview, Flow, Traces, Failures, Live                  |
| **Space**        | Pause the display; the page keeps receiving updates     |
| **Esc**          | Close the inspector                                     |
| **F**            | Bring Flow's lens into view                             |
| **J**, **K**, **P**, **T** | On the tape: next, previous, parent, open trace |

### Hub health

The status bar shows **Hub healthy**, or **Hub degraded** in amber while the hub sheds, drops, or refuses spans, keeps a shorter history or horizon than configured,
or keeps an application's invocations completely only since some moment. It opens the health panel: each part of the budget against its ceiling, the budget
against the maximum heap, and per application its shedding level, its use against its fair share, its effective horizon, and its counts of lost or refused
spans by reason.

## What the figures mean

**Counts are exact; retained invocations are a selection.** The hub counts every span it receives and keeps the counts for the history, whatever it evicts. Traces,
Failures' examples, Live, and the inspector show only retained invocations. Once the hub had to drop invocations of an application, it keeps every invocation since
then and only the failed and slowest ones before: the views say **Complete since** that moment.

**Every figure of an invocation counts in the second it started**: its start, entry, failure, and latency. The window ends `settle` seconds before now, by default
60 whole seconds ending 5 seconds ago, so every instance has exported those seconds. A span that arrives later still counts in its own second; when an application's
spans arrive later than the settle, the charts shade in amber the seconds that may still grow.

**Completed** means the chain returned normally, not that a transaction committed: a later commit can still fail, a synchronous group can roll back with its caller,
and an event-store batch that fails is replayed, showing one handling per attempt. **Failed** means the chain threw. A failure that fails its callers on the way back
counts at each of them. An asynchronous group's failure does not fail its publication, so it only shows under its group. A publication that returns may not have
reached every listener yet.

**A command or query is one invocation, its dispatch, which covers its handler**: its latency includes the handler, and a handler's failure is its failure.
Only events have handlings, one per processing group, and the handlings the figures count are theirs.

**Lineage comes from span links alone.** A handling's parent is the dispatch of its event. A message dispatched by a command or query handler is a child of that
command's or query's dispatch, and one dispatched by an event handler a child of its group's handling, on any thread or instance, and in any application. A dispatch without a link is an entry point. The hub links a child and its parent in whichever order they arrive, as long
as the second arrives within the link horizon of the first, or while the first is retained; otherwise the link, and its arrow in Flow, is not counted. A span that
arrives twice within the horizon counts once. A message forwarded to another application with its message ID and metadata, such as by a broker, continues the same
trace. When a parent is evicted, the child names its type and application; when it was never received, the child says **Parent not found**.

**Latency percentiles are approximate**: interpolated within buckets bounded at 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 25, 50, 100, 250, 500, 1,000, 2,500, 5,000, 10,000,
25,000, and 60,000 ms; slower invocations fall in the last bucket. Durations and start times are the span's. Clocks of different instances disagree, so a child on
another instance can appear to start before its parent.

**Instances** are tracked by name. An instance that sends nothing for `silent-after` shows as silent; the hub cannot tell an idle instance from a stopped one. A
restart with a new `service.instance.id` continues under the same name and keeps its counts. Two flags point at an instance's setup, which the hub does not correct:
**clock ahead** when it refused a span starting more than 30 seconds after the hub's clock, and **export lag** when its spans arrive later than the settle.

The monitor shows no running invocations: a span ends when its handlers return, and the hub learns of the invocation only then.

## How the hub behaves

**Threads.** The servlet threads decode requests and admit spans into one bounded queue per application. A single writer thread, `smd-monitor-hub`, drains the queues
in turn and owns everything the hub counts and retains; a flooding application fills only its own queue. Just after each second the writer publishes an immutable
**epoch**. Frames and queries read the latest epoch and never hold up the writer. At the design target with a `2GB` budget, the writer needs about a tenth of a core.

**Under load.** When an application's queue is half full, the writer retains only its failed spans and those slower than their type's p95; at 80% it only counts
them, without linking. It counts every span either way. When the queue is full, the endpoint answers 503.

**The OTLP endpoint** answers once it admitted a request, before the writer processes it:

| Request                                                                                  | Answer                                                                               |
|------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| Accepted                                                                                 | An empty `ExportTraceServiceResponse`                                                |
| Some spans unreadable, beyond the limits, or starting more than 30 s after the hub's clock | Partial success counting the rejected spans with the first reason; the rest are received |
| An application's queue is full                                                           | 503 with `Retry-After: 2`; the exporter retries, and spans already received count once |
| Not valid protobuf or gzip, or larger than 16 MB                                         | 400; nothing is received                                                             |

**Live updates.** A page receives the whole system on one WebSocket: a snapshot when it connects, then each second only what changed. At the design target the
snapshot is about 41 MB of JSON and a delta about 1.4 MB, 290 KB compressed by `permessage-deflate`, so pages on a large hub need bandwidth to match. A client that
falls behind receives the next snapshot. The hub accepts 64 clients. The page reconnects with backoff, keeping its last state visible, and reloads when the hub serves
another build.

**Queries.** The page asks for retained invocations under `/api` (`tape`, `traces`, `failures`, `timeline`, `invocations`, `types`) and asks again when the hub
retains more. Identical queries of one epoch are answered once for every page. At most 4 queries run at once; a further one gets 429 and the page keeps its last
answer. An evicted invocation or trace answers 404.

## Develop the UI

The page lives in `frontend`: SolidJS with Tailwind CSS, bundled by Vite into the hub's resources whenever Gradle processes them, so building needs Node.js 22.12 or
newer. The hub's tests also run `src/lib/mirror.ts` with Node.js 22.18 or newer, and skip it without Node.js.

For live reloading, run the demo and the Vite dev server, then open <http://localhost:5173/>. The dev server proxies the WebSocket and the queries to
`127.0.0.1:8080`; set `SMD_MONITOR_TARGET` (in the environment or `frontend/.env.local`) to proxy to another hub.

```shell
./gradlew demo
cd frontend && npm run dev
```

| Directory                                   | Contents                                                                                                                     |
|---------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `src/lib`                                   | Framework-free TypeScript: the wire contract (`wire.ts`, mirroring `app.dodb.smd.monitor.json`), the snapshot and delta mirror (`mirror.ts`), queries (`api.ts`), and the views' logic |
| `src/store`                                 | The WebSocket connection, pause, the reactive state, and the queries that refresh as the hub retains more                   |
| `src/app`                                   | Shell, navigation, selection, pins, and keyboard shortcuts                                                                   |
| `src/views`, `src/inspector`, `src/palette` | One folder per view                                                                                                          |
| `src/components`                            | Shared components and the class names per message kind                                                                       |
