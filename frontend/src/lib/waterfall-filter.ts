import { activityName, threadLabel } from './format';
import { endOf } from './model';
import type { TraceRow } from './traces';
import type { Linked } from './types';

export type ValueColumn = 'application' | 'instance' | 'thread';
export type TimeColumn = 'start' | 'wait' | 'duration';

// Every column narrows the rows; an empty choice or a zero minimum keeps every row.
export interface WaterfallFilter {
    message: string;
    application: readonly string[];
    instance: readonly string[];
    thread: readonly string[];
    // Minimums in milliseconds.
    start: number;
    wait: number;
    duration: number;
}

export const noWaterfallFilter = (): WaterfallFilter => ({ message: '', application: [], instance: [], thread: [], start: 0, wait: 0, duration: 0 });

export const VALUE_COLUMNS: readonly ValueColumn[] = ['application', 'instance', 'thread'];
export const TIME_COLUMNS: readonly TimeColumn[] = ['start', 'wait', 'duration'];

export function columnFiltered(filter: WaterfallFilter, column: 'message' | ValueColumn | TimeColumn): boolean {
    if (column === 'message') return filter.message.trim() !== '';
    if (column === 'start' || column === 'wait' || column === 'duration') return filter[column] > 0;
    return filter[column].length > 0;
}

export const waterfallFiltered = (filter: WaterfallFilter) =>
    columnFiltered(filter, 'message') ||
    VALUE_COLUMNS.some(column => columnFiltered(filter, column)) ||
    TIME_COLUMNS.some(column => columnFiltered(filter, column));

export function valueOf(message: Linked, column: ValueColumn): string {
    if (column === 'application') return message.application;
    return column === 'instance' ? message.instance : message.threadName;
}

export const valueLabel = (column: ValueColumn, value: string) => (column === 'thread' ? threadLabel(value) : value);

// How long after its parent returned a row started, or null when it started before.
export function waitOf(row: TraceRow): number | null {
    if (!row.parent) return null;
    const gap = row.message.startedAtMillis - endOf(row.parent);
    return gap >= 0 ? gap : null;
}

export function timeOf(row: TraceRow, column: TimeColumn, traceStart: number): number | null {
    if (column === 'start') return row.message.startedAtMillis - traceStart;
    return column === 'wait' ? waitOf(row) : row.message.outcome.durationMillis;
}

export type FilterColumn = 'message' | ValueColumn | TimeColumn;

const FILTER_COLUMNS: readonly FilterColumn[] = ['message', ...VALUE_COLUMNS, ...TIME_COLUMNS];

// Whether a row passes one column's filter; a column without a filter passes every row.
export function columnMatches(row: TraceRow, filter: WaterfallFilter, column: FilterColumn, traceStart: number): boolean {
    if (!columnFiltered(filter, column)) return true;
    if (column === 'message') {
        const search = filter.message.trim().toLowerCase();
        return activityName(row.message).toLowerCase().includes(search) || row.message.messageType.toLowerCase().includes(search);
    }
    if (column === 'start' || column === 'wait' || column === 'duration') return (timeOf(row, column, traceStart) ?? -1) >= filter[column];
    return filter[column].includes(valueOf(row.message, column));
}

const rowMatches = (row: TraceRow, filter: WaterfallFilter, traceStart: number) =>
    FILTER_COLUMNS.every(column => columnMatches(row, filter, column, traceStart));

// Keeps the rows that match and the rows that lead to them, so the tree stays whole; the leading rows are context, not matches.
export function filterRows(rows: readonly TraceRow[], filter: WaterfallFilter, traceStart: number): { rows: TraceRow[]; matched: ReadonlySet<number> } {
    if (!waterfallFiltered(filter)) return { rows: [...rows], matched: new Set(rows.map(row => row.message.sequence)) };
    const matched = new Set(rows.filter(row => rowMatches(row, filter, traceStart)).map(row => row.message.sequence));
    const bySequence = new Map(rows.map(row => [row.message.sequence, row]));
    const kept = new Set(matched);
    for (const sequence of matched) {
        let parent = bySequence.get(sequence)?.parent ?? null;
        while (parent && !kept.has(parent.sequence)) {
            kept.add(parent.sequence);
            parent = bySequence.get(parent.sequence)?.parent ?? null;
        }
    }
    return { rows: rows.filter(row => kept.has(row.message.sequence)), matched };
}

// The values of a column in the trace, with how many rows have each, most common first.
export function columnValues(rows: readonly TraceRow[], column: ValueColumn): { value: string; rows: number }[] {
    const counts = new Map<string, number>();
    for (const row of rows) {
        const value = valueOf(row.message, column);
        counts.set(value, (counts.get(value) ?? 0) + 1);
    }
    return [...counts].map(([value, count]) => ({ value, rows: count })).sort((a, b) => b.rows - a.rows || a.value.localeCompare(b.value));
}

export function toggleValue(filter: WaterfallFilter, column: ValueColumn, value: string): WaterfallFilter {
    const values = filter[column];
    return { ...filter, [column]: values.includes(value) ? values.filter(other => other !== value) : [...values, value] };
}
