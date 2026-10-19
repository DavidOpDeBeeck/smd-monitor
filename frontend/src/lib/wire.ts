// Every second the hub sends every monitored application with its statistics, and the sequence number of what it retains, which moves whenever it
// retains another invocation or finds another parent; the page queries the retained invocations themselves. Per-second figures end at
// endEpochSecond. maxHeapBytes is null when the hub's JVM sets no maximum heap.
export interface MonitorFrame {
    hubSession: string;
    capturedAtMillis: number;
    sequence: number;
    endEpochSecond: number;
    maxHeapBytes: number | null;
    // The length of every per-second series that covers the window every live figure covers.
    windowSeconds: number;
    // The length of every per-second series that covers the history: the history the counts cover, shorter than configured under memory pressure.
    historySeconds: number;
    // How many seconds before the hub's clock the window ends, waiting for spans to arrive.
    settleSeconds: number;
    // How long an instance may send no spans before it leaves its application's instances.
    retireAfterSeconds: number;
    health: WireHealth;
    // Sorted by name, with their statistics whole.
    applications: WireApplication[];
}

// What the hub sends over the WebSocket (W1 to W3): the whole frame of an epoch, or the changes from the frame of the epoch it sent before
// (see mirror.ts). Each names the protocol and the build of the page the hub serves.
export interface SnapshotMessage {
    protocol: number;
    build: string;
    type: 'snapshot';
    epoch: number;
    base: null;
    frame: MonitorFrame;
}

export interface DeltaMessage {
    protocol: number;
    build: string;
    type: 'delta';
    epoch: number;
    base: number;
    // How many seconds every per-second series moved since the base.
    shift: number;
    changes: JsonValue;
}

export type JsonValue = null | boolean | number | string | JsonValue[] | { [field: string]: JsonValue };

export type FrameMessage = SnapshotMessage | DeltaMessage;

// What a product of the hub takes of the heap, by the hub's estimate, against what the memory budget allows it.
export interface WireProduct {
    bytes: number;
    limitBytes: number;
}

export type HealthReason =
    | 'REFUSED_BY_INSTANCE_LIMIT'
    | 'REFUSED_AHEAD_OF_CLOCK'
    | 'QUEUE_FULL'
    | 'SHED_UNRETAINED'
    | 'SHED_UNJOINED'
    | 'DUPLICATE'
    | 'REVISED'
    | 'BEYOND_HISTORY'
    | 'PARENT_NEVER_RECEIVED'
    | 'PARENT_AFTER_HORIZON'
    | 'FRAGMENT'
    | 'OVERFLOW'
    | 'EVICTED'
    | 'FAILED_BATCH';

export type Shedding = 'NONE' | 'INTERESTING_ONLY' | 'UNJOINED';

// The health of the hub: what each product takes of the memory budget, the history its counts cover against the configured one, whether seconds
// fold into 10-second buckets sooner under memory pressure, the configured link horizon, and the spans of applications beyond its limit. The
// specimens' limit is what the budget leaves them now.
export interface WireHealth {
    budgetBytes: number;
    queues: WireProduct;
    counting: WireProduct;
    join: WireProduct;
    specimens: WireProduct;
    historySeconds: number;
    effectiveHistorySeconds: number;
    foldsSooner: boolean;
    linkHorizonSeconds: number;
    refusedByApplicationLimit: number;
    applications: WireApplicationHealth[];
}

// Counts by reason since the application appeared; the heaviest shedding of its batches in the last second; what it takes of counting, and of the
// join and the specimens against its fair share of each. It retains every invocation that started after completeSinceMillis, null while the hub
// dropped or shed none; the oldest it retains started at oldestRetainedMillis, null while it retains none. Its effective horizon is how long the
// hub keeps its spans to link them, and slowestK and failuresK how many traces its reservoirs keep per type and per failure.
export interface WireApplicationHealth {
    name: string;
    reasons: Partial<Record<HealthReason, number>>;
    shedding: Shedding;
    countingBytes: number;
    join: WireProduct;
    specimens: WireProduct;
    retained: number;
    completeSinceMillis: number | null;
    oldestRetainedMillis: number | null;
    effectiveHorizonSeconds: number;
    slowestK: number;
    failuresK: number;
}

export type MessageKind = 'COMMAND' | 'QUERY' | 'EVENT';

export interface WireMessageType {
    kind: MessageKind;
    name: string;
}

// Statistics are positional, as thousands change every second: started, entered, failed, completed, then the average, floor, p50, p95, p99,
// and maximum milliseconds; brief statistics end at the p95. The latency figures are null when nothing completed. state.ts names them.
export type WireStatistics = [number, number, number, number, number | null, number | null, number | null, number | null, number | null, number | null];
export type WireBrief = [number, number, number, number, number | null];

// Its clock runs ahead while the hub refused a span of it for starting too far ahead within the last minute; its export lag is the p99 of how
// long its recent spans took to reach the hub after they ended, null while none arrived recently.
export interface WireInstance {
    name: string;
    sessionId: string;
    lastReportedAtMillis: number;
    silent: boolean;
    clockAhead: boolean;
    exportLagMillis: number | null;
    dispatches: WireBrief;
    handlings: WireBrief;
}

export interface WireApplication {
    name: string;

    // Every failed invocation the hub received of this application since it started.
    failedReceived: number;
    instances: WireInstance[];
    types: WireType[];
    groups: WireGroup[];
    // Between this application's types.
    links: WireLink[];
    // Into this application's types from a parent type of another application.
    crossings: WireCrossing[];
    // Events of any application reaching this application's processing groups.
    routes: WireRoute[];
    // This application's events sent to its sinks, by the delivery of each sink.
    sinkRoutes: WireSinkRoute[];
    // Messages this application's processing groups dispatched.
    groupLinks: WireGroupLink[];
    dispatches: WireBrief;
    handlings: WireBrief;
    // The p95 over every handling of the window that started after its publication returned, or null when none did.
    eventDelayP95Millis: number | null;
    kinds: WireKind[];
    enteredPerSecond: number[];
    failedPerSecond: number[];
    // Every failed invocation counted under its own stage and exception type, once for every message it failed.
    failures: WireFailure[];
    other: WireOther;
    // The seconds of the window from this one on may still grow, as the application's spans arrive that late.
    provisionalFromEpochSecond: number;
}

// The processing group is null for a dispatch, and only an event has handlings. A failure idle in the window has no seconds.
export interface WireFailure {
    kind: MessageKind;
    type: string;
    handling: boolean;
    processingGroup: string | null;
    failureType: string;
    window: number;
    history: number;
    windowPerSecond: number[];
}

// What the application counted beyond the hub's caps: the dispatches and handlings of the types and processing groups it did not keep apart, and
// the edges and failures beyond theirs over the history.
export interface WireOther {
    dispatchWindow: WireBrief;
    dispatchHistory: WireBrief;
    handlingWindow: WireBrief;
    handlingHistory: WireBrief;
    edgeHistory: number;
    failureHistory: number;
}

// The history is brief; the type's query answers it whole.
export interface WireType {
    kind: MessageKind;
    name: string;
    entryPoint: boolean;
    window: WireStatistics;
    history: WireBrief;
    // Its dispatches the hub still retains.
    retained: number;
    startedPerSecond: number[];
    failedPerSecond: number[];
}

export interface WireGroup {
    processingGroup: string;
    // How the group receives its events, as its latest handling named it: sync, async-await, async-fire-and-forget, event-store, or the name
    // an application's own source gives.
    delivery: string;
    window: WireBrief;
    // A handling is asynchronous once the group started handling one of its events after the publication returned; the delay is the p95 of how
    // long after over the window, or null.
    handlings: { name: string; window: WireBrief; history: WireBrief; asynchronous: boolean; delayP95Millis: number | null }[];
}

export interface WireLink {
    parent: WireMessageType;
    child: WireMessageType;
    history: number;
    lastSecond: number;
}

export interface WireRoute {
    publisher: string;
    event: WireMessageType;
    processingGroup: string;
    history: number;
    lastSecond: number;
}

export interface WireSinkRoute {
    event: WireMessageType;
    delivery: string;
    history: number;
    lastSecond: number;
}

export interface WireGroupLink {
    processingGroup: string;
    child: WireMessageType;
    history: number;
    lastSecond: number;
}

export interface WireCrossing {
    parentApplication: string;
    link: WireLink;
}

export interface WireKind {
    kind: MessageKind;
    window: WireBrief;
    startedPerSecond: number[];
}

export type Outcome = { status: 'COMPLETED'; durationMillis: number } | { status: 'FAILED'; durationMillis: number; failureType: string };

export interface WireInvocation {
    // Hub-wide: unique across applications and increasing.
    sequence: number;
    application: string;
    messageId: string;
    // Its span links to the invocation it continues, which the hub may not have found or may no longer retain.
    hasParent: boolean;
    kind: MessageKind;
    type: string;
    handling: boolean;
    // Null for a dispatch; only an event has handlings, as a command's or query's dispatch covers its handler.
    processingGroup: string | null;
    instance: string;
    startedAtMillis: number;
    threadName: string;
    outcome: Outcome;
}

// The parent the hub found for an invocation, which stays known with its application and message after the hub evicted it. A handling's
// parent is the dispatch of its own message.
export interface WireParent {
    sequence: number;
    application: string;
    messageId: string;
    kind: MessageKind;
    type: string;
    handling: boolean;
    processingGroup: string | null;
    delivered: boolean;
    retained: boolean;
}

// What every query answers with: the hub's run, and the sequence number of what it retained when it answered.
interface WireAnswer {
    hubSession: string;
    sequence: number;
}

// The parent is null when the hub found none.
export interface WireRetainedInvocation {
    invocation: WireInvocation;
    parent: WireParent | null;
}

// Newest first; the next page follows the cursor, which is null on the last page.
export interface WireTapePage extends WireAnswer {
    invocations: WireRetainedInvocation[];
    total: number;
    matching: number;
    slowestMillis: number | null;
    next: string | null;
}

export interface WireDurations extends WireAnswer {
    invocations: number;
    slower: number;
    buckets: number[];
}

export interface WireTrace {
    root: WireInvocation;
    startedAtMillis: number;
    durationMillis: number;
    messages: number;
    handlings: number;
    failures: number;
    threads: number;
    // In the order the trace reached them.
    applications: string[];
    // The dispatches of each kind besides the root, and the handlings.
    composition: { commands: number; queries: number; events: number; handlings: number };
}

export interface WireTracePage extends WireAnswer {
    traces: WireTrace[];
    total: number;
    matching: number;
    next: string | null;
}

// The members in ascending sequence.
export interface WireTraceDetail extends WireAnswer {
    trace: WireTrace;
    members: WireRetainedInvocation[];
}

export interface WireFailureStep {
    application: string;
    kind: MessageKind;
    type: string;
    handling: boolean;
    processingGroup: string | null;
    failureType: string;
}

// The retained occurrences of a failure cause: the latest newest first, and the most frequent chains, each from the entry point to the
// invocation that raised the failure.
export interface WireFailureCause {
    cause: WireFailureStep;
    retained: number;
    latest: WireInvocation[];
    chains: { steps: WireFailureStep[]; occurrences: number }[];
}

// In the order asked.
export interface WireFailureCauses extends WireAnswer {
    causes: WireFailureCause[];
}

// The latest started first.
export interface WireTimeline extends WireAnswer {
    endMillis: number;
    inSpan: number;
    matching: number;
    marks: { invocation: WireInvocation; root: number; markedAncestor: number | null }[];
}

// The ancestors run from the root of the trace down to the parent; the children are the first of childCount.
export interface WireMessageDetail extends WireAnswer {
    invocation: WireRetainedInvocation;
    root: number;
    ancestors: WireRetainedInvocation[];
    children: WireInvocation[];
    childCount: number;
    propagatedFrom: WireInvocation | null;
}

// The history holds every figure of the type, null when its application did not report it.
export interface WireRetainedType extends WireAnswer {
    history: WireStatistics | null;
    retained: number;
    failureTypes: { failureType: string; count: number }[];
    latest: WireInvocation[];
}

// Newest first.
export interface WireFound {
    hubSession: string;
    invocations: WireInvocation[];
}
