import { parseTypeKey, perKind } from './format';
import { EMPTY_TOTALS, NO_OTHER, type ApplicationState, type InstanceState, type MonitorState, type Totals } from './state';
import { sumSeries } from './throughput';
import type { Brief, GroupStatistics, HandlingKey, HandlingStatistics, Link, OtherCounts, TypeKey, TypeStatistics } from './types';

// What the page shows: the whole system when application is null, or else that one application.
export interface ScopedState {
    application: string | null;
    // Empty while the application in scope has not reported.
    applications: readonly ApplicationState[];
    types: ReadonlyMap<TypeKey, TypeStatistics>;
    groups: readonly GroupStatistics[];
    handlings: ReadonlyMap<HandlingKey, HandlingStatistics>;
    instances: readonly InstanceState[];
    other: OtherCounts;
    // The seconds from this moment on may still grow in some application in scope, or null without applications.
    provisionalFrom: number | null;
    totals: Totals;
    evicted: number;
    retainedBytes: number;
}

export function scopedState(state: MonitorState, scope: string | null): ScopedState {
    const applications = scope === null ? [...state.applications.values()] : [state.applications.get(scope)].filter(application => application !== undefined);
    const sum = (figure: (application: ApplicationState) => number) => applications.reduce((total, application) => total + figure(application), 0);
    return {
        application: scope,
        applications,
        types: new Map(applications.flatMap(application => [...application.types])),
        groups: applications.flatMap(application => application.groups),
        handlings: new Map(applications.flatMap(application => [...application.handlings])),
        instances: applications.flatMap(application => application.instances),
        other: combineOther(applications.map(application => application.other)),
        provisionalFrom: applications.length ? Math.min(...applications.map(application => application.provisionalFrom)) : null,
        totals: combineTotals(applications.map(application => application.totals)),
        evicted: sum(application => application.evicted),
        retainedBytes: sum(application => application.retainedBytes),
    };
}

function combineTotals(totals: readonly Totals[]): Totals {
    if (totals.length <= 1) return totals[0] ?? EMPTY_TOTALS;
    return {
        dispatches: combineBrief(totals.map(each => each.dispatches)),
        handlings: combineBrief(totals.map(each => each.handlings)),
        kinds: perKind(kind => combineBrief(totals.map(each => each.kinds[kind]))),
        startedPerSecond: perKind(kind => sumSeries(totals.map(each => each.startedPerSecond[kind]))),
        enteredPerSecond: sumSeries(totals.map(each => each.enteredPerSecond)),
        failedPerSecond: sumSeries(totals.map(each => each.failedPerSecond)),
    };
}

// Counts add up. The p95 of several applications cannot be combined from theirs, so it is the highest of them: no application's is above it.
function combineBrief(statistics: readonly Brief[]): Brief {
    const sum = (figure: (each: Brief) => number) => statistics.reduce((total, each) => total + figure(each), 0);
    const known = statistics.map(each => each.p95Millis).filter(value => value !== null);
    return {
        started: sum(each => each.started),
        entered: sum(each => each.entered),
        failed: sum(each => each.failed),
        completed: sum(each => each.completed),
        p95Millis: known.length ? Math.max(...known) : null,
    };
}

function combineOther(others: readonly OtherCounts[]): OtherCounts {
    if (others.length <= 1) return others[0] ?? NO_OTHER;
    const sum = (figure: (each: OtherCounts) => number) => others.reduce((total, each) => total + figure(each), 0);
    return {
        dispatchWindow: combineBrief(others.map(each => each.dispatchWindow)),
        dispatchHistory: combineBrief(others.map(each => each.dispatchHistory)),
        handlingWindow: combineBrief(others.map(each => each.handlingWindow)),
        handlingHistory: combineBrief(others.map(each => each.handlingHistory)),
        edgeHistory: sum(each => each.edgeHistory),
        failureHistory: sum(each => each.failureHistory),
    };
}

// An application linked to another one, with the arrows between their types over the history.
export interface NeighbourApplication {
    name: string;
    invocations: number;
    lastSecond: number;
}

// The applications whose types cause the application's, and those whose types the application's cause, busiest first.
export function neighbourApplications(state: MonitorState, application: string): { upstream: NeighbourApplication[]; downstream: NeighbourApplication[] } {
    const crossings = [...state.applications.values()].flatMap(each => each.crossings);
    const applicationOf = (key: TypeKey) => parseTypeKey(key).application;
    return {
        upstream: neighbours(crossings.filter(link => applicationOf(link.child) === application).map(link => [applicationOf(link.parent), link])),
        downstream: neighbours(crossings.filter(link => applicationOf(link.parent) === application).map(link => [applicationOf(link.child), link])),
    };
}

function neighbours(links: [string, Link][]): NeighbourApplication[] {
    const byName = new Map<string, NeighbourApplication>();
    for (const [name, link] of links) {
        const neighbour = byName.get(name) ?? { name, invocations: 0, lastSecond: 0 };
        byName.set(name, { name, invocations: neighbour.invocations + link.invocations, lastSecond: neighbour.lastSecond + link.lastSecond });
    }
    return [...byName.values()].sort((a, b) => b.invocations - a.invocations || a.name.localeCompare(b.name));
}
