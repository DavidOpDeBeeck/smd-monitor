import { createEffect, createMemo, createSignal, Match, on, onCleanup, onMount, Show, Switch } from 'solid-js';
import { selectedSequence, useMonitorApp } from '../../app/monitor-app';
import { RetentionBoundary } from '../../components/RetentionBoundary';
import { ApplicationFilter } from '../../components/ApplicationFilter';
import { FilterStatus, SearchField, Segmented, Toolbar } from '../../components/controls';
import { createHeldListing, createListHold } from '../../components/hold';
import { StatusFilter } from '../../components/StatusFilter';
import { TypeFilter } from '../../components/TypeFilter';
import { linked } from '../../lib/api';
import { count, parseTypeKey } from '../../lib/format';
import { liveKeeps, liveTypeFilter, tapeRequest, timelineRequest, type LiveVisual } from '../../lib/live';
import { marks as markedIn, spanLabel, TIMELINE_SPANS, traceMarks, type Mark } from '../../lib/timeline';
import type { Activity, Linked } from '../../lib/types';
import type { WireTapePage, WireTimeline } from '../../lib/wire';
import { createHubQuery, createPagedList } from '../../store/hub-query';
import { DurationThreshold } from './DurationThreshold';
import { LatencyScatter } from './LatencyScatter';
import { Tape } from './Tape';
import { TimelineLanes } from './TimelineLanes';

const VISUALS: readonly { value: LiveVisual; label: string; title: string }[] = [
    { value: 'lanes', label: 'Lanes', title: 'Bars from start to end, one lane per message type' },
    { value: 'scatter', label: 'Scatter', title: 'Dots by start time and latency, failures below the axis' },
    { value: 'tape', label: 'Tape', title: 'Every retained invocation, newest first' },
];

const SPANS = TIMELINE_SPANS.map(span => ({ value: span, label: spanLabel(span), title: `Show the last ${spanLabel(span)}` }));

// The lanes and the scatter draw at most this many of the latest dispatches of the span.
const MARK_LIMIT = 2000;

export function LiveView() {
    const app = useMonitorApp();
    const store = app.store;
    const live = app.live;
    const [time, setTime] = createSignal(store.currentTime());
    const hold = createListHold();
    const filters = createMemo(() => JSON.stringify(live.filters));
    const onTape = () => live.visual() === 'tape';

    // The tape pages every retained invocation the filters keep; the lanes and the scatter ask for the dispatches of the time span.
    const tape = createPagedList(
        store,
        () => (onTape() ? tapeRequest(live.filters, app.scope()) : null),
        (answer: WireTapePage) => ({ items: answer.invocations.map(linked), next: answer.next }),
        message => message.sequence,
    );
    const timeline = createHubQuery<WireTimeline>(store, () => (onTape() ? null : timelineRequest(live.filters, app.scope(), live.span(), MARK_LIMIT)), {
        kept: true,
    });
    const listing = createHeldListing(
        hold,
        (message: Activity) => message.sequence,
        () => {
            const tapeAnswer = tape.answer();
            const timelineAnswer = timeline.answer();
            return {
                items: onTape() ? tape.items() : timelineAnswer ? markedIn(timelineAnswer) : [],
                matching: (onTape() ? tapeAnswer?.matching : timelineAnswer?.matching) ?? 0,
                total: (onTape() ? tapeAnswer?.total : timelineAnswer?.inSpan) ?? 0,
                filters: `${app.scope()}|${filters()}|${live.visual()}|${onTape() ? '' : live.span()}`,
            };
        },
    );
    const marks = createMemo(() => (onTape() ? [] : (listing().items as Mark[])));
    const trace = createMemo(() => {
        const sequence = selectedSequence(app.selection());
        return sequence === null || onTape() ? null : traceMarks(marks(), sequence);
    });

    // A selection the filters no longer keep is cleared; a message is checked as it was listed.
    createEffect(
        on(
            filters,
            () => {
                const selection = app.selection();
                if (selection?.type === 'type') {
                    const { application, kind } = parseTypeKey(selection.key);
                    if (!liveTypeFilter(live.filters, app.scope())(application, kind, selection.key)) app.select(null);
                } else if (selection?.type === 'message') {
                    const message = listing().items.find(item => item.sequence === selection.sequence);
                    if (message && !liveKeeps(message, live.filters, app.scope())) app.select(null);
                }
            },
            { defer: true },
        ),
    );

    onMount(() => {
        let frame = requestAnimationFrame(function advance() {
            if (!hold.held() && !onTape()) setTime(store.currentTime());
            frame = requestAnimationFrame(advance);
        });
        onCleanup(() => cancelAnimationFrame(frame));
    });

    return (
        <section aria-label="Live invocations" class="absolute inset-0 flex flex-col bg-surface">
            <Toolbar>
                <Segmented label="Visual" value={live.visual()} options={VISUALS} onChange={live.setVisual} />
                <Show when={!onTape()}>
                    <Segmented label="Time span" value={live.span()} options={SPANS} onChange={live.setSpan} />
                </Show>
                <SearchField
                    label="Filter invocations"
                    value={live.filters.search}
                    onInput={search => live.setFilters('search', search)}
                    class="max-w-[340px] min-w-40 flex-1"
                />
                <TypeFilter selection={live.filters.types} onChange={types => live.setFilters('types', types)} class="w-60" />
                <ApplicationFilter
                    selection={live.filters.applications}
                    onChange={applications => live.setFilters('applications', applications)}
                    class="w-60"
                />
                <StatusFilter selection={live.filters.statuses} onChange={statuses => live.setFilters('statuses', statuses)} />
                <DurationThreshold value={live.filters.minDuration} onChange={millis => live.setFilters('minDuration', millis)} />
                <FilterStatus active={live.active()} onClear={live.clear}>
                    <Show
                        when={onTape()}
                        fallback={
                            <>
                                {count(listing().matching)} of {count(listing().total)} invocations in the last {spanLabel(live.span())}
                                <Show when={listing().matching > marks().length}> · drawing the latest {count(marks().length)}</Show>
                            </>
                        }
                    >
                        {count(listing().matching)} of {count(listing().total)} messages
                    </Show>
                </FilterStatus>
            </Toolbar>
            <RetentionBoundary applications={app.scoped().applications} class="border-b bg-warning/8 px-4 py-1.5 text-xs text-warning" />
            <div class="relative flex min-h-0 flex-1 flex-col">
                <Switch>
                    <Match when={live.visual() === 'lanes'}>
                        <TimelineLanes marks={marks()} time={time()} trace={trace()} hold={hold} />
                    </Match>
                    <Match when={live.visual() === 'scatter'}>
                        <LatencyScatter marks={marks()} loading={timeline.loading()} time={time()} trace={trace()} hold={hold} />
                    </Match>
                    <Match when={onTape()}>
                        <Tape
                            items={listing().items as Linked[]}
                            listed={listing().filters}
                            loaded={tape.loaded()}
                            matching={listing().matching}
                            total={listing().total}
                            slowest={tape.answer()?.slowestMillis ?? null}
                            more={tape.more()}
                            full={tape.full()}
                            onNearEnd={tape.loadMore}
                            onFollow={tape.follow}
                            hold={hold}
                        />
                    </Match>
                </Switch>
            </div>
        </section>
    );
}
