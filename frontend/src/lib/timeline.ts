import { causalOrder, lensGraph } from './flow-lens';
import { isTypeKey } from './format';
import { activity } from './api';
import type { ApplicationState } from './state';
import type { Activity, TypeKey } from './types';
import type { WireTimeline } from './wire';

export const TIMELINE_SPANS = [10_000, 30_000, 60_000, 300_000] as const;
export type TimelineSpan = (typeof TIMELINE_SPANS)[number];

const TICK_STEPS = [1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000];

export interface TimelineTick {
    offset: number;
    label: string;
}

interface LaneGroup {
    application: string;
    lanes: TypeKey[];
}

export const laneSignature = (applications: readonly ApplicationState[]) => lensGraph(applications).signature;

// The lanes of each application in causal order: each type after the types it follows from, then in the order its flows reach it.
export function laneGroups(applications: readonly ApplicationState[]): LaneGroup[] {
    const lanes = causalOrder(lensGraph(applications)).filter(isTypeKey);
    return applications.map(application => ({ application: application.name, lanes: lanes.filter(key => application.types.has(key)) }));
}

export type LaneRow = { id: string; type: 'application'; application: string } | { id: string; type: 'lane'; key: TypeKey };

// The kept lanes, under a header for each application that keeps one when headed.
export function laneRows(groups: readonly LaneGroup[], headed: boolean, keeps: (key: TypeKey) => boolean): LaneRow[] {
    return groups.flatMap(group => {
        const lanes = group.lanes.filter(keeps).map((key): LaneRow => ({ id: key, type: 'lane', key }));
        if (!headed || !lanes.length) return lanes;
        // A type key goes on after its application's "::", so a header's ID is no lane's.
        return [{ id: `${group.application}::`, type: 'application', application: group.application } satisfies LaneRow, ...lanes];
    });
}

// A dispatch of the time span, with the root of its trace and its nearest ancestor among the marks, or null.
export interface Mark extends Activity {
    root: number;
    markedAncestor: number | null;
}

export const marks = (timeline: WireTimeline): Mark[] =>
    timeline.marks.map(mark => ({ ...activity(mark.invocation), root: mark.root, markedAncestor: mark.markedAncestor }));

// The marks of the trace the invocation belongs to, when it is marked.
export function traceMarks(marked: readonly Mark[], sequence: number): Set<number> | null {
    const root = marked.find(mark => mark.sequence === sequence)?.root;
    return root === undefined ? null : new Set(marked.filter(mark => mark.root === root).map(mark => mark.sequence));
}

export function timelineTicks(span: number, width: number, spacing = 110): TimelineTick[] {
    const target = span / Math.max(2, Math.floor(width / spacing));
    const step = TICK_STEPS.find(value => value >= target) ?? TICK_STEPS[TICK_STEPS.length - 1];
    const ticks: TimelineTick[] = [];
    for (let offset = 0; offset < span; offset += step) ticks.push({ offset, label: offsetLabel(offset) });
    return ticks;
}

export function spanLabel(span: number): string {
    return span < 60_000 ? `${span / 1000}s` : `${span / 60_000}m`;
}

function offsetLabel(offset: number): string {
    if (offset === 0) return 'now';
    const seconds = offset / 1000;
    if (seconds < 60) return `−${seconds}s`;
    return seconds % 60 ? `−${Math.floor(seconds / 60)}m ${seconds % 60}s` : `−${seconds / 60}m`;
}
