import { createSignal } from 'solid-js';
import { createStore } from 'solid-js/store';
import { readPreference, writePreference } from '../../lib/browser';
import { traceFiltersActive, type TraceFilters } from '../../lib/traces';
import { everyType } from '../../lib/type-selection';
import { parseWaterfallTable, type WaterfallTable } from '../../lib/waterfall-columns';
import { noWaterfallFilter, type WaterfallFilter } from '../../lib/waterfall-filter';

const noFilters = (): Omit<TraceFilters, 'sort'> => ({ search: '', types: everyType(), applications: [], statuses: [], chains: false, pinned: false });

function toggled<T>(values: ReadonlySet<T>, value: T): ReadonlySet<T> {
    const next = new Set(values);
    if (!next.delete(value)) next.add(value);
    return next;
}

export function createTracesState(scope: () => string | null) {
    const [filters, setFilters] = createStore<TraceFilters>({ ...noFilters(), sort: 'newest' });
    const [collapsed, setCollapsed] = createSignal<ReadonlySet<number>>(new Set());
    // The root of the selected trace, or an invocation of it while the trace is opened from elsewhere, until the hub names its root.
    const [selected, setSelected] = createSignal<number | null>(null);
    const [opened, setOpened] = createSignal(false);
    const [revealed, setRevealed] = createSignal(0);
    // The waterfall's columns, their order and widths are a preference of this browser, kept across traces and visits.
    const [table, setTable] = createSignal<WaterfallTable>(parseWaterfallTable(readPreference('waterfall-table')));
    // Narrows the rows of every trace opened while it is set.
    const [waterfallFilter, setWaterfallFilter] = createSignal<WaterfallFilter>(noWaterfallFilter());
    const saveTable = (next: WaterfallTable) => {
        setTable(next);
        writePreference('waterfall-table', JSON.stringify(next));
    };
    return {
        filters,
        setFilters,
        active: () => traceFiltersActive(filters, scope()),
        clear: () => setFilters(noFilters()),
        collapsed,
        table,
        changeTable: (change: (table: WaterfallTable) => WaterfallTable) => saveTable(change(table())),
        waterfallFilter,
        changeWaterfallFilter: (change: (filter: WaterfallFilter) => WaterfallFilter) => setWaterfallFilter(change),
        clearWaterfallFilter: () => setWaterfallFilter(noWaterfallFilter()),
        toggleCollapsed: (sequence: number) => setCollapsed(rows => toggled(rows, sequence)),
        selected,
        setSelected(rootSequence: number | null) {
            setOpened(false);
            setSelected(rootSequence);
        },
        opened,
        revealed,
        open(sequence: number) {
            setOpened(true);
            setSelected(sequence);
            setRevealed(value => value + 1);
        },
        // The hub named the root of the opened trace.
        rootFound(rootSequence: number) {
            setOpened(false);
            setSelected(rootSequence);
            setRevealed(value => value + 1);
        },
    };
}
