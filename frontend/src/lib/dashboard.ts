import { KINDS, shortName } from './format';
import { latencySpread, type LatencySpread } from './latency-spread';
import type { ApplicationState, Totals } from './state';
import { ratePerSecond, scrollable } from './throughput';
import { selectionMatches, type TypeSelection } from './type-selection';
import type { GroupStatistics, HandlingKey, HandlingStatistics, MessageKind, TypeStatistics } from './types';

export type TypeSort = 'rate' | 'failed' | 'p50' | 'p95' | 'p99' | 'name';

export interface TypeRow {
    statistics: TypeStatistics;
    rate: number;
    failedShare: number | null;
    latency: LatencySpread | null;
}

interface WindowFigures {
    enteredPerSecond: number;
    failed: number;
    failureRate: number | null;
    p95ByKind: Record<MessageKind, number | null>;
}

const nameOf = (statistics: TypeStatistics) => shortName(statistics.messageType);

const COMPARE: Record<TypeSort, (a: TypeRow, b: TypeRow) => number> = {
    rate: (a, b) => b.rate - a.rate || b.statistics.history.started - a.statistics.history.started,
    failed: (a, b) => (b.failedShare ?? -1) - (a.failedShare ?? -1) || b.statistics.window.failed - a.statistics.window.failed,
    p50: (a, b) => (b.latency?.p50Millis ?? -1) - (a.latency?.p50Millis ?? -1),
    p95: (a, b) => (b.latency?.p95Millis ?? -1) - (a.latency?.p95Millis ?? -1),
    p99: (a, b) => (b.latency?.p99Millis ?? -1) - (a.latency?.p99Millis ?? -1),
    name: () => 0,
};

export function typeRows(types: Iterable<TypeStatistics>, sort: TypeSort, selection: TypeSelection, windowSeconds: number): TypeRow[] {
    return [...types]
        .filter(statistics => selectionMatches(selection, statistics.kind, statistics.key))
        .map(statistics => ({
            statistics,
            rate: ratePerSecond(statistics.window.started, windowSeconds),
            failedShare: statistics.window.started ? statistics.window.failed / statistics.window.started : null,
            latency: latencySpread(statistics.window),
        }))
        .sort((a, b) => COMPARE[sort](a, b) || nameOf(a.statistics).localeCompare(nameOf(b.statistics)) || a.statistics.key.localeCompare(b.statistics.key));
}

// The busiest groups first, each with its busiest event types first.
export function groupSections(groups: readonly GroupStatistics[]): GroupStatistics[] {
    return groups
        .map(group => ({
            ...group,
            handlings: [...group.handlings].sort(
                (a, b) => b.window.started - a.window.started || b.history.started - a.history.started || a.typeKey.localeCompare(b.typeKey),
            ),
        }))
        .sort((a, b) => b.window.started - a.window.started || a.processingGroup.localeCompare(b.processingGroup));
}

export function windowFigures(totals: Totals, windowSeconds: number): WindowFigures {
    const failed = totals.dispatches.failed + totals.handlings.failed;
    const invocations = totals.dispatches.started + totals.handlings.started;
    return {
        enteredPerSecond: ratePerSecond(totals.dispatches.entered, windowSeconds),
        failed,
        failureRate: invocations ? failed / invocations : null,
        p95ByKind: Object.fromEntries(KINDS.map(kind => [kind, totals.kinds[kind].p95Millis])) as Record<MessageKind, number | null>,
    };
}

export interface ActivityRow {
    kind: MessageKind | null;
    label: string;
    values: readonly number[];
}

const KIND_PLURAL: Record<MessageKind, string> = { COMMAND: 'Commands', QUERY: 'Queries', EVENT: 'Events' };

// Per second over the history: the dispatches of each kind, then the failed dispatches and handlings together.
export function activityRows(totals: Totals): ActivityRow[] {
    return [
        ...KINDS.map(kind => ({ kind, label: KIND_PLURAL[kind], values: scrollable(totals.startedPerSecond[kind]) })),
        { kind: null, label: 'Failures', values: scrollable(totals.failedPerSecond) },
    ];
}

// The p95 event delay of each handling that ran asynchronously in the window; a handling that only ran inline has none.
export function eventDelays(handlings: Iterable<HandlingStatistics>): Map<HandlingKey, number> {
    return new Map([...handlings].flatMap(handling => (handling.delayP95Millis === null ? [] : [[handling.key, handling.delayP95Millis] as const])));
}

interface OverallEventDelay {
    p95Millis: number | null;
    slowest: HandlingKey | null;
    asynchronous: boolean;
}

// The p95 over every handling of the application, or the highest of any application's in the system scope; groups handled asynchronously once.
export function overallEventDelay(applications: readonly ApplicationState[], handlings: Iterable<HandlingStatistics>): OverallEventDelay {
    const all = [...handlings];
    const delays = applications.flatMap(application => (application.eventDelayP95Millis === null ? [] : [application.eventDelayP95Millis]));
    let slowest: HandlingStatistics | null = null;
    for (const handling of all) {
        if (handling.delayP95Millis !== null && (slowest === null || handling.delayP95Millis > slowest.delayP95Millis!)) slowest = handling;
    }
    return {
        p95Millis: delays.length ? Math.max(...delays) : null,
        slowest: slowest?.key ?? null,
        asynchronous: all.some(handling => handling.asynchronous),
    };
}

// Since when the applications retain every invocation, or null while the hub dropped or shed none of theirs: before it, they retain the failed and
// the slowest only. Each application keeps its own, so of several it is the latest.
export function completeSince(applications: readonly ApplicationState[]): number | null {
    const since = applications.flatMap(application => (application.completeSince === null ? [] : [application.completeSince]));
    return since.length ? Math.max(...since) : null;
}

// When the oldest invocation retained of any of the applications started, or null while they retain none.
export function retainedSince(applications: readonly ApplicationState[]): number | null {
    const starts = applications.flatMap(application => (application.firstRetainedAt === null ? [] : [application.firstRetainedAt]));
    return starts.length ? Math.min(...starts) : null;
}

export const SLOW_DELAY_MILLIS = 1000;
