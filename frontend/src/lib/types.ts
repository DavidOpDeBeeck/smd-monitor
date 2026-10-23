import type { MessageKind, Outcome } from './wire';

export type { MessageKind, Outcome };

// The counts of a key and its p95, null when nothing completed.
export interface Brief {
    started: number;
    entered: number;
    failed: number;
    completed: number;
    p95Millis: number | null;
}

// A key's counts with every latency figure, each null when nothing completed.
export interface Statistics extends Brief {
    averageMillis: number | null;
    floorMillis: number | null;
    p50Millis: number | null;
    p99Millis: number | null;
    maxMillis: number | null;
}

export type DispatchStatus = Outcome['status'];
// A message type of one application: the same type name in two applications is two types.
export type TypeKey = `${string}::${MessageKind}:${string}`;
export type HandlingKey = `${TypeKey}@${string}`;
// A processing group of one application.
export type GroupKey = `${string}::GROUP:${string}`;

export interface Activity {
    // Hub-wide.
    sequence: number;
    application: string;
    messageId: string;
    // It continues another invocation, which the hub may not have found or may no longer retain; a dispatch without a parent is an entry point.
    hasParent: boolean;
    typeKey: TypeKey;
    kind: MessageKind;
    messageType: string;
    // Only an event has handlings, one per processing group; a command's or query's dispatch covers its handler.
    handling: boolean;
    processingGroup: string | null;
    startedAtMillis: number;
    threadName: string;
    instance: string;
    outcome: Outcome;
}

// The parent the hub found for an invocation: retained or not, it names its application, message, and type. A handling's parent is the dispatch
// of its own message.
export interface ParentRef {
    sequence: number;
    application: string;
    messageId: string;
    typeKey: TypeKey;
    kind: MessageKind;
    messageType: string;
    handling: boolean;
    processingGroup: string | null;
    // Another application dispatched the same message, which this application received.
    delivered: boolean;
    retained: boolean;
}

// A retained invocation with the parent the hub found for it, or null when it found none.
export interface Linked extends Activity {
    parent: ParentRef | null;
}

// A type an application dispatched, as the frame carries it.
export interface TypeEntry {
    key: TypeKey;
    application: string;
    kind: MessageKind;
    messageType: string;
    entryPoint: boolean;
    // Its dispatches the hub still retains.
    retained: number;
}

// A type with what it counted, which the type figures query answers.
export interface TypeStatistics extends TypeEntry {
    window: Statistics;
    // Every figure of the history is in the type's query.
    history: Brief;
    startedPerSecond: readonly number[];
}

// What a flow card shows of a message type, which the flow query answers.
export interface FlowTypeFigures {
    windowStarted: number;
    history: Brief;
}

// What a processing group's handling of an event counted, which the handlings query answers.
export interface HandlingFigures {
    window: Brief;
    history: Brief;
}

export interface HandlingStatistics {
    key: HandlingKey;
    typeKey: TypeKey;
    application: string;
    messageType: string;
    processingGroup: string;
    delivery: string;
    // Once the group started handling one of these events after its publication returned.
    asynchronous: boolean;
    // How long after its publication returned the group started handling the event, p95 over the window, or null.
    delayP95Millis: number | null;
}

export interface GroupStatistics {
    // The application and the processing group; group names repeat across applications.
    key: string;
    application: string;
    processingGroup: string;
    delivery: string;
    window: Brief;
    handlings: HandlingStatistics[];
}

// Events of the publishing application reaching a processing group, in the group's application or another.
export interface Route {
    publisher: string;
    event: TypeKey;
    group: GroupKey;
    invocations: number;
    lastSecond: number;
}

// Events the application published sent to one of its sinks, by the delivery of the medium the sink writes to.
export interface SinkRoute {
    event: TypeKey;
    delivery: string;
    invocations: number;
    lastSecond: number;
}

// Messages a processing group dispatched while handling events.
export interface GroupLink {
    group: GroupKey;
    child: TypeKey;
    invocations: number;
    lastSecond: number;
}

export interface Link {
    parent: TypeKey;
    child: TypeKey;
    invocations: number;
    lastSecond: number;
}

// Every failed invocation of a stage counted with its exception type, once for every message it failed, over the window and the history, and in
// each second of the window, which a failure idle in the window has none of.
export interface FailureCount {
    // The type or handling and the exception type.
    key: string;
    application: string;
    typeKey: TypeKey;
    kind: MessageKind;
    messageType: string;
    handling: boolean;
    processingGroup: string | null;
    failureType: string;
    window: number;
    history: number;
    windowPerSecond: readonly number[];
    // The invocations of its stage over the history, of which the history failed.
    started: number;
}

// What an application counted beyond the hub's caps.
export interface OtherCounts {
    dispatchWindow: Brief;
    dispatchHistory: Brief;
    handlingWindow: Brief;
    handlingHistory: Brief;
    edgeHistory: number;
    failureHistory: number;
}
