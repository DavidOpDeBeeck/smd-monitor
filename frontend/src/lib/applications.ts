import { hashHue, isTypeKey, KINDS, parseTypeKey } from './format';
import { matchScore } from './search';
import type { ApplicationState } from './state';
import { ratePerSecond, sumSeries } from './throughput';
import type { TypeKey } from './types';

// The first application's hue, a blue; the others follow around the circle.
const FIRST_HUE = 212;

// Each application wears a hue of its own wherever it shows. The hub's applications share the circle evenly, in name order, so no two look
// alike however their names hash; a name the hub does not know hashes to its hue.
export function applicationHues(names: Iterable<string>): ReadonlyMap<string, number> {
    const sorted = [...new Set(names)].sort((a, b) => a.localeCompare(b));
    return new Map(sorted.map((name, index) => [name, Math.round(FIRST_HUE + (index * 360) / sorted.length) % 360]));
}

export const applicationHue = (hues: ReadonlyMap<string, number>, name: string) => hues.get(name) ?? hashHue(name);

export interface InstanceRow {
    name: string;
    sessionId: string;
    sinceReportMillis: number;
    silent: boolean;
    clockAhead: boolean;
    exportLagMillis: number | null;
}

export interface ApplicationRow {
    name: string;
    instances: InstanceRow[];
    reporting: number;
    dispatchesPerSecond: number;
    entryPointsPerSecond: number;
    failures: number;
    // The window.
    dispatchSeconds: readonly number[];
    failureSeconds: readonly number[];
    // The seconds from this moment on may still grow.
    provisionalFrom: number;
    // The retained invocations and the facts of the spans the hub links by.
    heapBytes: number;
}

export type ApplicationHealth = 'reporting' | 'partial' | 'silent';

export function applicationRows(applications: Iterable<ApplicationState>, now: number, windowSeconds: number): ApplicationRow[] {
    return [...applications].map(application => {
        const instances = application.instances.map(instance => {
            const sinceReportMillis = Math.max(0, now - instance.lastReportedAtMillis);
            return {
                name: instance.name,
                sessionId: instance.sessionId,
                sinceReportMillis,
                silent: instance.silent,
                clockAhead: instance.clockAhead,
                exportLagMillis: instance.exportLagMillis,
            };
        });
        const totals = application.totals;
        return {
            name: application.name,
            instances,
            reporting: instances.filter(instance => !instance.silent).length,
            dispatchesPerSecond: ratePerSecond(totals.dispatches.started, windowSeconds),
            entryPointsPerSecond: ratePerSecond(totals.dispatches.entered, windowSeconds),
            failures: totals.dispatches.failed + totals.handlings.failed,
            dispatchSeconds: sumSeries(KINDS.map(kind => totals.startedPerSecond[kind])).slice(-windowSeconds),
            failureSeconds: totals.failedPerSecond.slice(-windowSeconds),
            provisionalFrom: application.provisionalFrom,
            heapBytes: application.retainedBytes + application.linkBytes + application.countingBytes,
        };
    });
}

// An application whose instances all retired has none left to report.
export function applicationHealth(row: { reporting: number; instances: readonly unknown[] }): ApplicationHealth {
    if (row.reporting === 0) return 'silent';
    return row.reporting === row.instances.length ? 'reporting' : 'partial';
}

export function matchApplications(rows: readonly ApplicationRow[], query: string): ApplicationRow[] {
    if (!query) return [...rows];
    return rows
        .map(row => ({ row, score: matchScore(row.name, query) }))
        .filter(entry => entry.score >= 0)
        .sort((left, right) => right.score - left.score)
        .map(entry => entry.row);
}

// The scope is ?scope=<application>, absent for the whole system.
export function scopeFromSearch(search: string): string | null {
    return new URLSearchParams(search).get('scope') || null;
}

export function scopeSearch(scope: string | null): string {
    return scope === null ? '' : `?scope=${encodeURIComponent(scope)}`;
}

// ?type= holds a type key, unless someone mistyped its encoded application.
export function typeFromSearch(search: string): TypeKey | null {
    const key = new URLSearchParams(search).get('type');
    if (!key || !isTypeKey(key)) return null;
    try {
        parseTypeKey(key);
        return key;
    } catch {
        return null;
    }
}
