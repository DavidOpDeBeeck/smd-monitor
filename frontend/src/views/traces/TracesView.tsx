import { createEffect, createMemo, Match, on, Show, Switch } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { RetentionBoundary } from '../../components/RetentionBoundary';
import { ApplicationFilter } from '../../components/ApplicationFilter';
import { FilterStatus, SearchField, Segmented, ToggleChip, Toolbar } from '../../components/controls';
import { Empty } from '../../components/empty';
import { createHeldListing, createListHold } from '../../components/hold';
import { Loading } from '../../components/loading';
import { StatusFilter } from '../../components/StatusFilter';
import { TypeFilter } from '../../components/TypeFilter';
import { hubRequest } from '../../lib/api';
import { sameFields, shareUnchanged } from '../../lib/sharing';
import { listPins, sharedDetail, traceDetail, traceRequest, traceSummary, type TraceDetail as Detail, type TraceItem, type TraceSort } from '../../lib/traces';
import type { WireTraceDetail, WireTracePage } from '../../lib/wire';
import { createHubQuery, createPagedList } from '../../store/hub-query';
import { TraceDetail } from './TraceDetail';
import { TraceList } from './TraceList';

const SORTS: { value: TraceSort; label: string; title: string }[] = [
    { value: 'newest', label: 'Newest', title: 'Most recent root first' },
    { value: 'slowest', label: 'Slowest', title: 'Longest duration first' },
    { value: 'largest', label: 'Largest', title: 'Most messages first' },
];

export interface ShownTrace {
    key: string;
    detail: Detail;
    pinned: boolean;
    // The copy pinned in this tab, shown once the hub evicted the trace.
    evictedCopy: boolean;
}

export function TracesView() {
    const app = useMonitorApp();
    const store = app.store;
    const traces = app.traces;
    const hold = createListHold();
    const filters = createMemo(() => JSON.stringify(traces.filters));

    // The hub pages the traces of the scope; the pinned filter lists the pins of this tab instead.
    const paged = createPagedList(
        store,
        () => traceRequest(traces.filters, app.scope()),
        (answer: WireTracePage) => ({ items: answer.traces.map(traceSummary), next: answer.next }),
        summary => summary.key,
    );
    const pinned = createMemo(() => (traces.filters.pinned ? listPins(app.pins(), store.view().hubSession, traces.filters, app.scope()) : []));
    // An item stays the same object while its trace and pin do, so its row renders nothing again.
    const items = createMemo<TraceItem[]>(
        previous =>
            shareUnchanged(
                previous,
                traces.filters.pinned
                    ? pinned().map(pin => ({ key: pin.key, summary: pin.detail.summary, pinned: true, evictedCopy: pin }))
                    : paged.items().map(summary => ({ key: summary.key, summary, pinned: app.pins().has(summary.key), evictedCopy: null })),
                item => item.key,
                sameFields,
            ),
        [],
    );
    const listing = createHeldListing(
        hold,
        (item: TraceItem) => item.key,
        () => {
            const answer = paged.answer();
            return {
                items: items(),
                matching: traces.filters.pinned ? items().length : (answer?.matching ?? 0),
                total: traces.filters.pinned ? app.pins().size : (answer?.total ?? 0),
                filters: `${app.scope()}|${filters()}`,
            };
        },
    );
    const filtered = () => listing().items;
    const listed = () => traces.filters.pinned || paged.loaded();

    const firstListed = () => filtered().find(item => item.summary.messages > 1) ?? filtered()[0];
    createEffect(() => {
        if (traces.selected() !== null || !listed()) return;
        const first = firstListed();
        if (first) traces.setSelected(first.summary.root.sequence);
    });
    createEffect(
        on(listed, loaded => {
            const selected = traces.selected();
            if (!loaded || traces.opened() || selected === null || filtered().some(item => item.summary.root.sequence === selected)) return;
            traces.setSelected(firstListed()?.summary.root.sequence ?? null);
        }),
    );

    // Any member finds its trace, so an opened invocation selects the root of its trace once the hub answers.
    const detail = createHubQuery<WireTraceDetail>(
        store,
        () => {
            const selected = traces.selected();
            return selected === null ? null : hubRequest(`traces/${selected}`);
        },
        { kept: true },
    );
    createEffect(
        on(detail.answer, answer => {
            if (answer && traces.opened()) traces.rootFound(answer.trace.root.sequence);
        }),
    );
    const shown = createMemo<ShownTrace | null>(previous => {
        const selected = traces.selected();
        if (selected === null) return null;
        const pin = [...app.pins().values()].find(candidate => candidate.detail.summary.root.sequence === selected);
        const answer = detail.answer();
        if (!detail.missing() && answer) {
            const retained = sharedDetail(previous?.evictedCopy === false ? previous.detail : null, traceDetail(answer));
            return { key: retained.summary.key, detail: retained, pinned: app.pins().has(retained.summary.key), evictedCopy: false };
        }
        return pin && detail.missing() ? { key: pin.key, detail: pin.detail, pinned: true, evictedCopy: true } : null;
    }, null);

    return (
        <section
            aria-label="Traces"
            onPointerDown={hold.press}
            class="absolute inset-0 grid grid-cols-[minmax(300px,380px)_minmax(0,1fr)] max-md:grid-cols-1 max-md:grid-rows-[minmax(220px,40%)_minmax(0,1fr)]"
        >
            <div class="flex min-h-0 flex-col border-r bg-surface max-md:border-r-0 max-md:border-b">
                <Toolbar>
                    <SearchField label="Filter traces" value={traces.filters.search} onInput={search => traces.setFilters('search', search)} class="w-full" />
                    <TypeFilter selection={traces.filters.types} onChange={types => traces.setFilters('types', types)} class="min-w-40 flex-1" />
                    <ApplicationFilter
                        selection={traces.filters.applications}
                        onChange={applications => traces.setFilters('applications', applications)}
                        class="min-w-40 flex-1"
                    />
                </Toolbar>
                <Toolbar class="py-2">
                    <Segmented label="Sort traces" value={traces.filters.sort} options={SORTS} onChange={sort => traces.setFilters('sort', sort)} />
                    <StatusFilter selection={traces.filters.statuses} onChange={statuses => traces.setFilters('statuses', statuses)} />
                    <ToggleChip pressed={traces.filters.chains} onToggle={() => traces.setFilters('chains', chains => !chains)}>
                        Chains
                    </ToggleChip>
                    <ToggleChip pressed={traces.filters.pinned} onToggle={() => traces.setFilters('pinned', pinned => !pinned)}>
                        Pinned
                    </ToggleChip>
                    <FilterStatus active={traces.active()} onClear={traces.clear}>
                        {listing().matching.toLocaleString()} of {listing().total.toLocaleString()} traces
                    </FilterStatus>
                </Toolbar>
                <RetentionBoundary applications={app.scoped().applications} class="border-b bg-warning/8 px-4 py-1.5 text-xs text-warning" />
                <div class="relative flex min-h-0 flex-1 flex-col">
                    <TraceList
                        items={filtered()}
                        matching={listing().matching}
                        total={listing().total}
                        listed={listed()}
                        more={!traces.filters.pinned && paged.more()}
                        full={!traces.filters.pinned && paged.full()}
                        onNearEnd={paged.loadMore}
                        onFollow={paged.follow}
                    />
                </div>
            </div>
            <div class="flex min-h-0 min-w-0 flex-col bg-bg">
                <Show
                    when={shown()}
                    fallback={
                        <Switch
                            fallback={
                                <Empty class="h-full" title="Pick a trace">
                                    A trace is a root message and everything it caused: nested commands, published events, and queries sent from handlers.
                                </Empty>
                            }
                        >
                            <Match when={detail.missing()}>
                                <Empty class="h-full" title="No longer retained">
                                    The hub evicted this trace. Pin a trace to keep a copy of it in this tab.
                                </Empty>
                            </Match>
                            <Match when={detail.loading()}>
                                <Loading title="Loading the trace" class="h-full" />
                            </Match>
                        </Switch>
                    }
                >
                    {trace => <TraceDetail trace={trace()} />}
                </Show>
            </div>
        </section>
    );
}
