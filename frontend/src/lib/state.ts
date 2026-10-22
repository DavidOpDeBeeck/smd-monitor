import { failureKey, groupKey, handlingKey, keyedApplication, perKind, typeKey } from './format';
import type {
    Brief,
    FailureCount,
    GroupLink,
    GroupStatistics,
    HandlingFigures,
    HandlingKey,
    HandlingStatistics,
    Link,
    MessageKind,
    OtherCounts,
    Route,
    SinkRoute,
    Statistics,
    TypeKey,
    TypeStatistics,
} from './types';
import type {
    MonitorFrame,
    WireApplication,
    WireApplicationHealth,
    WireBrief,
    WireHealth,
    WireInstance,
    WireLink,
    WireMessageType,
    WireOther,
    WireFlow,
    WireHandlings,
    WireStatistics,
    WireType,
    WireTypeLink,
} from './wire';

export interface Totals {
    dispatches: Brief;
    handlings: Brief;
    kinds: Record<MessageKind, Brief>;
    startedPerSecond: Record<MessageKind, readonly number[]>;
    enteredPerSecond: readonly number[];
    failedPerSecond: readonly number[];
}

export const NO_STATISTICS: Statistics = {
    started: 0,
    entered: 0,
    failed: 0,
    completed: 0,
    averageMillis: null,
    floorMillis: null,
    p50Millis: null,
    p95Millis: null,
    p99Millis: null,
    maxMillis: null,
};

export const NO_BRIEF: Brief = { started: 0, entered: 0, failed: 0, completed: 0, p95Millis: null };

export const NO_OTHER: OtherCounts = {
    dispatchWindow: NO_BRIEF,
    dispatchHistory: NO_BRIEF,
    handlingWindow: NO_BRIEF,
    handlingHistory: NO_BRIEF,
    edgeHistory: 0,
    failureHistory: 0,
};

export const EMPTY_TOTALS: Totals = {
    dispatches: NO_BRIEF,
    handlings: NO_BRIEF,
    kinds: { COMMAND: NO_BRIEF, QUERY: NO_BRIEF, EVENT: NO_BRIEF },
    startedPerSecond: { COMMAND: [], QUERY: [], EVENT: [] },
    enteredPerSecond: [],
    failedPerSecond: [],
};

// An instance of an application. Its clock runs ahead while the hub refused a span of it for starting too far ahead within the last minute; its
// export lag is the p99 of how long its recent spans took to reach the hub after they ended, or null.
export interface InstanceState {
    application: string;
    name: string;
    sessionId: string;
    lastReportedAtMillis: number;
    silent: boolean;
    clockAhead: boolean;
    exportLagMillis: number | null;
    dispatches: Brief;
    handlings: Brief;
}

export interface ApplicationState {
    name: string;
    retained: number;
    evicted: number;
    // When the oldest invocation it retains started, or null while it retains none.
    firstRetainedAt: number | null;
    // It retains every invocation that started after this moment, or every one it received while null.
    completeSince: number | null;
    // The hub's estimates of the heap its retained invocations, the spans it links by, and its counts take, and its fair shares of the first two.
    retainedBytes: number;
    retainedShare: number;
    linkBytes: number;
    linkShare: number;
    countingBytes: number;
    health: WireApplicationHealth | null;
    // Every failed invocation the hub received of it since the hub started.
    failedReceived: number;
    // How long after its publication returned a group started handling an event, p95 over the window, or null when none did.
    eventDelayP95Millis: number | null;
    instances: readonly InstanceState[];
    types: ReadonlyMap<TypeKey, TypeStatistics>;
    groups: readonly GroupStatistics[];
    handlings: ReadonlyMap<HandlingKey, HandlingStatistics>;
    // Into this application's types from a parent type of another application.
    crossings: readonly Link[];
    // Every failed invocation by its stage and exception type, once for every message it failed.
    failures: readonly FailureCount[];
    other: OtherCounts;
    // The seconds of the window from this moment on may still grow, as the application's spans arrive that late.
    provisionalFrom: number;
    totals: Totals;
}

// One frame of the hub. Frames replace it; nothing in it changes afterwards, so a paused view can keep it.
export interface MonitorState {
    hubSession: string | null;
    capturedAt: number;
    receivedAt: number;
    // The sequence number of what the hub retained: it moves whenever the hub retains another invocation or finds another parent.
    sequence: number;
    // Where the per-second figures end, in epoch milliseconds.
    end: number;
    windowSeconds: number;
    historySeconds: number;
    // How many seconds before the hub's clock the window ends, waiting for spans to arrive.
    settleSeconds: number;
    // Null from a hub older than the page, which the development server can proxy to.
    retireAfterSeconds: number | null;
    maxHeapBytes: number | null;
    // Null from a hub older than the page.
    health: WireHealth | null;
    // By name, in name order.
    applications: ReadonlyMap<string, ApplicationState>;
}

export function emptyState(receivedAt: number): MonitorState {
    return {
        hubSession: null,
        capturedAt: Date.now(),
        receivedAt,
        sequence: 0,
        end: 0,
        windowSeconds: 0,
        historySeconds: 0,
        settleSeconds: 0,
        retireAfterSeconds: null,
        maxHeapBytes: null,
        health: null,
        applications: new Map(),
    };
}

class FrameError extends Error {}

export function mergeFrame(frame: MonitorFrame, receivedAt: number): MonitorState {
    if (typeof frame?.hubSession !== 'string' || typeof frame.sequence !== 'number' || !Array.isArray(frame.applications)) {
        throw new FrameError('Invalid monitor frame: expected hubSession, sequence, and applications');
    }
    return {
        hubSession: frame.hubSession,
        capturedAt: frame.capturedAtMillis,
        receivedAt,
        sequence: frame.sequence,
        end: frame.endEpochSecond * 1000,
        windowSeconds: frame.windowSeconds,
        historySeconds: frame.historySeconds,
        settleSeconds: frame.settleSeconds,
        retireAfterSeconds: frame.retireAfterSeconds ?? null,
        maxHeapBytes: frame.maxHeapBytes,
        health: frame.health ?? null,
        applications: new Map(
            frame.applications.map(application => [
                application.name,
                applicationState(application, frame.health?.applications.find(health => health.name === application.name) ?? null),
            ]),
        ),
    };
}

// How messages flow within an application, which the flow query answers: its links between its types, the routes of any application's events
// to its processing groups, its events sent to its sinks, and the messages its groups dispatched.
export interface FlowEdges {
    links: readonly Link[];
    routes: readonly Route[];
    sinkRoutes: readonly SinkRoute[];
    groupLinks: readonly GroupLink[];
}

export const NO_EDGES: FlowEdges = { links: [], routes: [], sinkRoutes: [], groupLinks: [] };

// Each application's flow from the flow query's answer, or none before it answered.
export function flowEdges(flow: WireFlow | null): ReadonlyMap<string, FlowEdges> {
    return new Map(
        (flow?.applications ?? []).map(application => {
            const name = application.name;
            const keyOf = (type: WireMessageType, owner = name) => typeKey(owner, type.kind, type.name);
            return [
                name,
                {
                    links: application.links.map(link => ({
                        parent: keyOf(link.parent),
                        child: keyOf(link.child),
                        invocations: link.history,
                        lastSecond: link.lastSecond,
                    })),
                    routes: application.routes.map(route => ({
                        publisher: route.publisher,
                        event: keyOf(route.event, route.publisher),
                        group: groupKey(name, route.processingGroup),
                        invocations: route.history,
                        lastSecond: route.lastSecond,
                    })),
                    sinkRoutes: application.sinkRoutes.map(route => ({
                        event: keyOf(route.event),
                        delivery: route.delivery,
                        invocations: route.history,
                        lastSecond: route.lastSecond,
                    })),
                    groupLinks: application.groupLinks.map(link => ({
                        group: groupKey(name, link.processingGroup),
                        child: keyOf(link.child),
                        invocations: link.history,
                        lastSecond: link.lastSecond,
                    })),
                },
            ];
        }),
    );
}

// The failures second by second of each type that failed in the window, from the flow query's answer.
export function flowFailures(flow: WireFlow | null): ReadonlyMap<TypeKey, readonly number[]> {
    return new Map(
        (flow?.applications ?? []).flatMap(application =>
            application.failingTypes.map(failing => [typeKey(application.name, failing.type.kind, failing.type.name), failing.failedPerSecond] as const),
        ),
    );
}

// What each handling counted, by its key, from the handlings query's answer, or nothing before it answered.
export function handlingFigures(answer: WireHandlings | null): ReadonlyMap<HandlingKey, HandlingFigures> {
    return new Map(
        (answer?.handlings ?? []).map(handling => [
            handlingKey(typeKey(handling.application, 'EVENT', handling.name), handling.processingGroup),
            { window: brief(handling.window), history: brief(handling.history) },
        ]),
    );
}

// What a handling counted while its figures have not arrived: nothing yet.
export const NO_FIGURES: HandlingFigures = {
    window: { started: 0, entered: 0, failed: 0, completed: 0, p95Millis: null },
    history: { started: 0, entered: 0, failed: 0, completed: 0, p95Millis: null },
};

// A link into or out of a type, as the type query answers it, each end named with its application.
export const typeLink = (link: WireTypeLink): Link => ({
    parent: typeKey(link.parentApplication, link.parent.kind, link.parent.name),
    child: typeKey(link.childApplication, link.child.kind, link.child.name),
    invocations: link.history,
    lastSecond: link.lastSecond,
});

function applicationState(application: WireApplication, health: WireApplicationHealth | null): ApplicationState {
    const name = application.name;
    const keyOf = (type: WireMessageType, owner = name) => typeKey(owner, type.kind, type.name);
    const linkOf = (link: WireLink, parentApplication: string): Link => ({
        parent: keyOf(link.parent, parentApplication),
        child: keyOf(link.child),
        invocations: link.history,
        lastSecond: link.lastSecond,
    });
    const groups = application.groups.map(group => ({
        key: `${keyedApplication(name)}@${group.processingGroup}`,
        application: name,
        processingGroup: group.processingGroup,
        delivery: group.delivery,
        window: brief(group.window),
        handlings: group.handlings.map(handling => {
            const type = typeKey(name, 'EVENT', handling.name);
            return {
                key: handlingKey(type, group.processingGroup),
                typeKey: type,
                application: name,
                messageType: handling.name,
                processingGroup: group.processingGroup,
                delivery: group.delivery,
                asynchronous: handling.asynchronous,
                delayP95Millis: handling.delayP95Millis,
            };
        }),
    }));
    const kinds = new Map(application.kinds.map(kind => [kind.kind, kind]));
    return {
        name,
        retained: health?.retained ?? 0,
        evicted: health?.reasons.EVICTED ?? 0,
        firstRetainedAt: health?.oldestRetainedMillis ?? null,
        completeSince: health?.completeSinceMillis ?? null,
        retainedBytes: health?.specimens.bytes ?? 0,
        retainedShare: health?.specimens.limitBytes ?? 0,
        linkBytes: health?.join.bytes ?? 0,
        linkShare: health?.join.limitBytes ?? 0,
        countingBytes: health?.countingBytes ?? 0,
        health,
        failedReceived: application.failedReceived,
        eventDelayP95Millis: application.eventDelayP95Millis,
        instances: application.instances.map(instance => instanceState(name, instance)),
        types: new Map(application.types.map(type => [keyOf(type), typeStatistics(name, keyOf(type), type)])),
        groups,
        handlings: new Map(groups.flatMap(group => group.handlings.map(handling => [handling.key, handling]))),
        crossings: application.crossings.map(crossing => linkOf(crossing.link, crossing.parentApplication)),
        failures: application.failures.map(failure => {
            const type = typeKey(name, failure.kind, failure.type);
            return {
                key: failureKey(type, failure.handling, failure.processingGroup, failure.failureType),
                application: name,
                typeKey: type,
                kind: failure.kind,
                messageType: failure.type,
                handling: failure.handling,
                processingGroup: failure.processingGroup,
                failureType: failure.failureType,
                window: failure.window,
                history: failure.history,
                windowPerSecond: failure.windowPerSecond,
            };
        }),
        other: otherCounts(application.other),
        provisionalFrom: application.provisionalFromEpochSecond * 1000,
        totals: {
            dispatches: brief(application.dispatches),
            handlings: brief(application.handlings),
            kinds: perKind(kind => {
                const window = kinds.get(kind)?.window;
                return window ? brief(window) : NO_BRIEF;
            }),
            startedPerSecond: perKind(kind => kinds.get(kind)?.startedPerSecond ?? []),
            enteredPerSecond: application.enteredPerSecond,
            failedPerSecond: application.failedPerSecond,
        },
    };
}

function typeStatistics(application: string, key: TypeKey, type: WireType): TypeStatistics {
    return {
        key,
        application,
        kind: type.kind,
        messageType: type.name,
        entryPoint: type.entryPoint,
        window: statistics(type.window),
        history: brief(type.history),
        retained: type.retained,
        startedPerSecond: type.startedPerSecond,
    };
}

export function statistics(wire: WireStatistics): Statistics {
    const [started, entered, failed, completed, averageMillis, floorMillis, p50Millis, p95Millis, p99Millis, maxMillis] = wire;
    return { started, entered, failed, completed, averageMillis, floorMillis, p50Millis, p95Millis, p99Millis, maxMillis };
}

export function brief(wire: WireBrief): Brief {
    const [started, entered, failed, completed, p95Millis] = wire;
    return { started, entered, failed, completed, p95Millis };
}

function instanceState(application: string, instance: WireInstance): InstanceState {
    return {
        application,
        name: instance.name,
        sessionId: instance.sessionId,
        lastReportedAtMillis: instance.lastReportedAtMillis,
        silent: instance.silent,
        clockAhead: instance.clockAhead,
        exportLagMillis: instance.exportLagMillis,
        dispatches: brief(instance.dispatches),
        handlings: brief(instance.handlings),
    };
}

function otherCounts(other: WireOther): OtherCounts {
    return {
        dispatchWindow: brief(other.dispatchWindow),
        dispatchHistory: brief(other.dispatchHistory),
        handlingWindow: brief(other.handlingWindow),
        handlingHistory: brief(other.handlingHistory),
        edgeHistory: other.edgeHistory,
        failureHistory: other.failureHistory,
    };
}
