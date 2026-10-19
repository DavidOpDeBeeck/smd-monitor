import { activity, hubRequest, invocationParams, linked, type HubRequest } from './api';
import { failureTypeOf, parentMessageIdOf } from './model';
import { selectionInScope, selectionMatches, selectsEveryType, type TypeSelection } from './type-selection';
import type { Activity, DispatchStatus, Linked, MessageKind } from './types';
import type { WireTrace, WireTraceDetail } from './wire';

export type TraceSort = 'newest' | 'slowest' | 'largest';

export interface TraceFilters {
    search: string;
    types: TypeSelection;
    // Applies in the system scope only; an application's scope keeps it for the system scope.
    applications: readonly string[];
    statuses: readonly DispatchStatus[];
    chains: boolean;
    pinned: boolean;
    sort: TraceSort;
}

export const traceFiltersActive = (filters: TraceFilters, scope: string | null) =>
    filters.search.trim() !== '' ||
    !selectsEveryType(selectionInScope(filters.types, scope)) ||
    (scope === null && filters.applications.length > 0) ||
    filters.statuses.length > 0 ||
    filters.chains ||
    filters.pinned;

type TraceComposition = Record<MessageKind, number> & { handlings: number };

// A retained root and everything retained it caused, as the hub sums it up. Messages leave out the handlings; the composition counts the
// dispatches of each kind besides the root, and the handlings.
export interface TraceSummary {
    key: string;
    root: Activity;
    start: number;
    durationMillis: number;
    messages: number;
    handlings: number;
    failures: number;
    threads: number;
    // In the order the trace reached them.
    applications: readonly string[];
    composition: TraceComposition;
}

// The members in ascending sequence, each with its parent.
export interface TraceDetail {
    summary: TraceSummary;
    members: readonly Linked[];
}

export interface PinnedTrace {
    key: string;
    hubSession: string | null;
    detail: TraceDetail;
}

export interface TraceItem {
    key: string;
    summary: TraceSummary;
    pinned: boolean;
    evictedCopy: PinnedTrace | null;
}

export interface TraceRow {
    message: Linked;
    depth: number;
    parent: Linked | null;
    childCount: number;
}

export const traceKey = (root: Pick<Activity, 'messageId' | 'sequence'>) => `${root.messageId}:${root.sequence}`;

export const traceStatus = (trace: TraceSummary): DispatchStatus => (trace.failures ? 'FAILED' : 'COMPLETED');

export function traceSummary(trace: WireTrace): TraceSummary {
    const root = activity(trace.root);
    return {
        key: traceKey(root),
        root,
        start: trace.startedAtMillis,
        durationMillis: trace.durationMillis,
        messages: trace.messages,
        handlings: trace.handlings,
        failures: trace.failures,
        threads: trace.threads,
        applications: trace.applications,
        composition: {
            COMMAND: trace.composition.commands,
            QUERY: trace.composition.queries,
            EVENT: trace.composition.events,
            handlings: trace.composition.handlings,
        },
    };
}

export const traceDetail = (detail: WireTraceDetail): TraceDetail => ({ summary: traceSummary(detail.trace), members: detail.members.map(linked) });

// The parent of a member within its trace: the hub links a member to its parent only while it retains both, and then both are in one trace.
export const parentInTrace = (message: Linked) => (message.parent?.retained ? message.parent.sequence : null);

export const matchesSearch = (message: Activity, search: string) =>
    `${message.messageType} ${message.processingGroup ?? ''} ${message.messageId} ${message.instance} ${message.threadName} ${failureTypeOf(message) ?? ''}`
        .toLowerCase()
        .includes(search);

// The traces of the scope as the hub lists them; the pinned filter lists the pins of the page instead, so it asks nothing.
export function traceRequest(filters: TraceFilters, scope: string | null): HubRequest | null {
    if (filters.pinned) return null;
    return hubRequest('traces', {
        ...invocationParams(scope, filters.applications, filters.types, filters.statuses, filters.search),
        chains: filters.chains,
        order: filters.sort,
    });
}

// The pins of this run of the hub that the filters keep, in the order of the sort. A pinned trace belongs to every application it touches.
export function listPins(pins: ReadonlyMap<string, PinnedTrace>, hubSession: string | null, filters: TraceFilters, scope: string | null): PinnedTrace[] {
    const search = filters.search.trim().toLowerCase();
    const types = selectionInScope(filters.types, scope);
    const applications = scope === null ? filters.applications : [];
    const kept = [...pins.values()].filter(({ hubSession: pinnedIn, detail }) => {
        const { summary, members } = detail;
        if (pinnedIn !== hubSession || (scope !== null && !summary.applications.includes(scope))) return false;
        if (filters.statuses.length && !filters.statuses.includes(traceStatus(summary))) return false;
        if (filters.chains && summary.messages < 2) return false;
        if (applications.length && !summary.applications.some(application => applications.includes(application))) return false;
        if (!selectsEveryType(types) && !members.some(message => selectionMatches(types, message.kind, message.typeKey))) return false;
        return !search || members.some(message => matchesSearch(message, search));
    });
    const order: Record<TraceSort, (pin: PinnedTrace) => number> = {
        newest: pin => pin.detail.summary.root.sequence,
        slowest: pin => pin.detail.summary.durationMillis,
        largest: pin => pin.detail.summary.messages,
    };
    return kept.sort((a, b) => order[filters.sort](b) - order[filters.sort](a) || b.detail.summary.root.sequence - a.detail.summary.root.sequence);
}

export function traceRows(members: readonly Linked[]): TraceRow[] {
    const bySequence = new Map(members.map(member => [member.sequence, member]));
    const children = new Map<number, Linked[]>();
    const roots: Linked[] = [];
    for (const member of members) {
        const parent = parentInTrace(member);
        if (parent !== null && bySequence.has(parent)) {
            const siblings = children.get(parent);
            if (siblings) siblings.push(member);
            else children.set(parent, [member]);
        } else {
            roots.push(member);
        }
    }
    const rows: TraceRow[] = [];
    const stack: Omit<TraceRow, 'childCount'>[] = roots.toReversed().map(message => ({ message, depth: 0, parent: null }));
    while (stack.length) {
        const row = stack.pop()!;
        const own = children.get(row.message.sequence) ?? [];
        rows.push({ ...row, childCount: own.length });
        for (let index = own.length - 1; index >= 0; index--) stack.push({ message: own[index], depth: row.depth + 1, parent: row.message });
    }
    return rows;
}

export function visibleRows(rows: readonly TraceRow[], collapsed: ReadonlySet<number>): TraceRow[] {
    const hidden = new Set<number>();
    return rows.filter(row => {
        if (row.parent && (hidden.has(row.parent.sequence) || collapsed.has(row.parent.sequence))) {
            hidden.add(row.message.sequence);
            return false;
        }
        return true;
    });
}

type TreeAction = { type: 'focus' | 'toggle' | 'select'; sequence: number };

export function treeAction(rows: readonly TraceRow[], current: number, key: string, collapsed: ReadonlySet<number>): TreeAction | null {
    const index = rows.findIndex(row => row.message.sequence === current);
    if (index < 0) return null;
    const row = rows[index];
    const focus = (target: TraceRow | undefined): TreeAction | null => (target ? { type: 'focus', sequence: target.message.sequence } : null);
    const expanded = row.childCount > 0 && !collapsed.has(current);
    switch (key) {
        case 'ArrowDown':
            return focus(rows[index + 1]);
        case 'ArrowUp':
            return focus(rows[index - 1]);
        case 'Home':
            return focus(rows[0]);
        case 'End':
            return focus(rows.at(-1));
        case 'ArrowRight':
            if (!row.childCount) return null;
            return expanded ? focus(rows[index + 1]) : { type: 'toggle', sequence: current };
        case 'ArrowLeft':
            if (expanded) return { type: 'toggle', sequence: current };
            return focus(rows.find(candidate => candidate.message === row.parent));
        case 'Enter':
        case ' ':
            return { type: 'select', sequence: current };
        default:
            return null;
    }
}

type BarLabelPlacement = 'inside' | 'after' | 'before';

export const BAR_LABEL_GAP = 6;
export const BAR_LABEL_GUTTER = 90;
const BAR_LABEL_CHARACTER = 6.6;

export const barLabelWidth = (label: string) => label.length * BAR_LABEL_CHARACTER;

export function barLabelPlacement(label: string, leftPercent: number, widthPercent: number, trackWidth: number): BarLabelPlacement {
    const labelWidth = barLabelWidth(label) + BAR_LABEL_GAP;
    const left = (leftPercent / 100) * trackWidth;
    const width = (widthPercent / 100) * trackWidth;
    if (leftPercent + widthPercent > 72 && width >= labelWidth + BAR_LABEL_GAP) return 'inside';
    if (left + width + labelWidth <= trackWidth + BAR_LABEL_GUTTER) return 'after';
    if (left >= labelWidth) return 'before';
    return 'inside';
}

export function niceStep(total: number, target: number): number {
    const raw = total / target;
    const magnitude = 10 ** Math.floor(Math.log10(raw || 1));
    return [1, 2, 2.5, 5, 10].map(value => value * magnitude).find(value => value >= raw) ?? raw;
}

export function exportTrace(detail: TraceDetail) {
    return {
        root: detail.summary.root.messageId,
        messages: detail.members.map(message => ({
            sequence: message.sequence,
            application: message.application,
            messageId: message.messageId,
            parentMessageId: parentMessageIdOf(message),
            parentSequence: parentInTrace(message),
            kind: message.kind,
            messageType: message.messageType,
            handling: message.handling,
            processingGroup: message.processingGroup,
            startedAt: new Date(message.startedAtMillis).toISOString(),
            threadName: message.threadName,
            status: message.outcome.status,
            durationMillis: message.outcome.durationMillis,
            failureType: failureTypeOf(message),
        })),
    };
}
