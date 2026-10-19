import { Key } from '@solid-primitives/keyed';
import { createEffect, createMemo, createSignal, Index, on, onCleanup, onMount, Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { ClearFilters } from '../../components/controls';
import { HandlingGlyph } from '../../components/Glyph';
import { createElementSize } from '../../components/size';
import { cx } from '../../lib/cx';
import { duration, shortName } from '../../lib/format';
import { panWindow, wholeTrace, windowTicks, zoomed, zoomWindow, type TimeWindow } from '../../lib/timeline-zoom';
import { traceRows, treeAction, visibleRows, type TraceDetail } from '../../lib/traces';
import { tableColumns, waterfallLayout } from '../../lib/waterfall-columns';
import { filterRows, waterfallFiltered } from '../../lib/waterfall-filter';
import { WaterfallHeader } from './WaterfallHeader';
import { WaterfallRow } from './WaterfallRow';

const ROW_LIMIT = 1500;
const TICK_SPACING = 110;
// How much one notch of the wheel zooms.
const WHEEL_ZOOM = 0.0015;

export function Waterfall(props: { detail: TraceDetail }) {
    const app = useMonitorApp();
    let scroller!: HTMLDivElement;
    let track!: HTMLDivElement;
    let tree!: HTMLDivElement;
    const trackSize = createElementSize(() => track);
    const trackWidth = () => trackSize().width;

    const trace = () => props.detail.summary;
    const total = () => Math.max(0.001, trace().durationMillis);
    const table = () => app.traces.table();
    const columns = createMemo(() => tableColumns(table()));
    const layout = createMemo(() => waterfallLayout(table()));
    const filter = () => app.traces.waterfallFilter();
    const allRows = createMemo(() => traceRows(props.detail.members));
    const filtered = createMemo(() => filterRows(allRows(), filter(), trace().start));
    const rows = createMemo(() => visibleRows(filtered().rows, app.traces.collapsed()));
    const shown = createMemo(() => rows().slice(0, ROW_LIMIT));
    const [focused, setFocused] = createSignal<number | null>(null);
    const tabStop = createMemo(() => {
        const sequence = focused();
        return shown().some(row => row.message.sequence === sequence) ? sequence : (shown()[0]?.message.sequence ?? null);
    });
    const rowOf = (target: EventTarget | null) => (target instanceof Element ? target.closest<HTMLElement>('[data-sequence]') : null);
    const keydown = (event: KeyboardEvent) => {
        const row = rowOf(event.target);
        if (!row || (row !== event.target && (event.key === 'Enter' || event.key === ' '))) return;
        const action = treeAction(shown(), Number(row.dataset.sequence), event.key, app.traces.collapsed());
        if (!action) return;
        event.preventDefault();
        if (action.type === 'toggle') app.traces.toggleCollapsed(action.sequence);
        else if (action.type === 'select') app.select({ type: 'message', sequence: action.sequence });
        else tree.querySelector<HTMLElement>(`[data-sequence="${action.sequence}"]`)?.focus();
    };
    // The zoomed window, or null for the whole trace, which grows with it while its members arrive; another trace starts whole.
    const [zoom, setZoom] = createSignal<TimeWindow | null>(null);
    const traceKey = createMemo(() => trace().key);
    createEffect(on(traceKey, () => setZoom(null), { defer: true }));
    const view = () => zoom() ?? wholeTrace(total());
    // The bars follow the pointer while it zooms or pans, instead of easing after it.
    const [moving, setMoving] = createSignal(false);
    let settle: ReturnType<typeof setTimeout> | undefined;
    onCleanup(() => clearTimeout(settle));
    const changeView = (next: TimeWindow) => {
        setMoving(true);
        clearTimeout(settle);
        settle = setTimeout(() => setMoving(false), 200);
        setZoom(zoomed(next, total()) ? next : null);
    };
    const ticks = createMemo(() => windowTicks(view(), trackWidth(), TICK_SPACING));
    // The share of the time axis under the pointer, or null when it is not over the axis.
    const axisShare = (clientX: number) => {
        const bounds = track.getBoundingClientRect();
        return clientX >= bounds.left && clientX <= bounds.right ? (clientX - bounds.left) / bounds.width : null;
    };

    // Over the timeline, the wheel zooms, and sideways or with Shift it pans; over the other columns it scrolls the rows as usual.
    onMount(() => {
        const wheel = (event: WheelEvent) => {
            const share = axisShare(event.clientX);
            if (share === null) return;
            const sideways = event.shiftKey || Math.abs(event.deltaX) > Math.abs(event.deltaY);
            if (sideways && zoom()) {
                event.preventDefault();
                changeView(panWindow(view(), total(), (event.shiftKey ? event.deltaY : event.deltaX) / Math.max(1, trackWidth())));
            } else if (!sideways && timelineOf(event)) {
                event.preventDefault();
                changeView(zoomWindow(view(), total(), share, Math.exp(event.deltaY * WHEEL_ZOOM)));
            }
        };
        scroller.addEventListener('wheel', wheel, { passive: false });
        onCleanup(() => scroller.removeEventListener('wheel', wheel));
    });
    const [drag, setDrag] = createSignal<{ x: number; window: TimeWindow } | null>(null);
    const axisOf = (event: Event) => (event.target as Element).closest<HTMLElement>('[data-axis]');
    const timelineOf = (event: Event) => (event.target as Element).closest<HTMLElement>('[data-axis], [data-timeline]');
    const zoomLabel = () => {
        const range = zoom();
        return range ? `+${duration(range.from)} – +${duration(range.to)}` : '';
    };

    return (
        <div
            ref={scroller}
            class={cx(
                'min-h-0 flex-1 overflow-auto pb-3',
                moving() && '[&_*]:transition-none',
                zoom() && '[&_[data-axis]]:cursor-grab [&_[data-timeline]]:cursor-grab',
                drag() && '[&_*]:cursor-grabbing',
            )}
            onPointerDown={event => {
                if (!timelineOf(event) || event.button !== 0 || !zoom()) return;
                event.preventDefault();
                scroller.setPointerCapture(event.pointerId);
                setDrag({ x: event.clientX, window: view() });
            }}
            onPointerMove={event => {
                const start = drag();
                if (start) changeView(panWindow(start.window, total(), (start.x - event.clientX) / Math.max(1, trackWidth())));
            }}
            onPointerUp={() => setDrag(null)}
            onPointerCancel={() => setDrag(null)}
            onDblClick={event => {
                if (axisOf(event)) setZoom(null);
            }}
        >
            <div style={{ 'min-width': layout().minWidth }}>
                <WaterfallHeader
                    table={table()}
                    template={layout().template}
                    ticks={ticks()}
                    onChange={app.traces.changeTable}
                    filter={filter()}
                    rows={allRows()}
                    traceStart={trace().start}
                    onFilter={next => app.traces.changeWaterfallFilter(() => next)}
                />
                <Show when={zoom()}>
                    <div class="flex items-center gap-2 border-b bg-accent/6 px-5 py-1.5 text-xs text-muted">
                        <span>
                            Showing <span class="font-mono text-fg tabular-nums">{zoomLabel()}</span> of the trace; scroll to zoom, drag to pan.
                        </span>
                        <button
                            type="button"
                            onClick={() => setZoom(null)}
                            class="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold whitespace-nowrap text-accent hover:bg-accent/10"
                        >
                            Reset zoom
                        </button>
                    </div>
                </Show>
                <Show when={waterfallFiltered(filter())}>
                    <div class="flex items-center gap-2 border-b bg-accent/6 px-5 py-1.5 text-xs text-muted">
                        <Show when={filtered().matched.size} fallback="No message of this trace matches the filters.">
                            {filtered().matched.size} of {allRows().length} messages match; faded rows lead to them.
                        </Show>
                        <ClearFilters onClear={app.traces.clearWaterfallFilter} />
                    </div>
                </Show>
                <div class="relative">
                    <div ref={track} class="pointer-events-none absolute inset-y-0 right-[90px]" style={{ left: layout().trackLeft }}>
                        <Index each={ticks()}>{tick => <span class="absolute inset-y-0 border-l border-dashed" style={{ left: tick().left }} />}</Index>
                    </div>
                    <div
                        ref={tree}
                        class="relative"
                        role="tree"
                        aria-label={`Trace of ${shortName(trace().root.messageType)}`}
                        onKeyDown={keydown}
                        onFocusIn={event => {
                            const row = rowOf(event.target);
                            if (row) setFocused(Number(row.dataset.sequence));
                        }}
                    >
                        <Key each={shown()} by={row => row.message.sequence}>
                            {row => (
                                <WaterfallRow
                                    row={row()}
                                    trace={trace()}
                                    window={view()}
                                    trackWidth={trackWidth()}
                                    tabStop={tabStop() === row().message.sequence}
                                    columns={columns()}
                                    template={layout().template}
                                    dimmed={
                                        !filtered().matched.has(row().message.sequence) || (app.scope() !== null && row().message.application !== app.scope())
                                    }
                                />
                            )}
                        </Key>
                        <Show when={rows().length > ROW_LIMIT}>
                            <div class="p-3 text-center text-sm text-muted">
                                {rows().length - ROW_LIMIT} more messages not drawn · collapse branches to focus
                            </div>
                        </Show>
                    </div>
                </div>
            </div>
            <p class="flex flex-wrap gap-[18px] px-5 pt-3.5 text-xs text-muted">
                <Show when={trace().handlings}>
                    <span class="flex items-center gap-[7px]">
                        <HandlingGlyph />a handling: a processing group handling its event, or the handler of a command or query
                    </span>
                </Show>
                <span class="flex items-center gap-[7px]">
                    <i class="w-[22px] border-t-[1.5px] border-dotted border-accent/45" />
                    wait: started after its parent returned
                </span>
                <span>
                    Each application, instance, and thread has a colour of its own:{' '}
                    <span class="rounded-[5px] px-1.5 py-px font-medium hue-chip" style={{ '--hue': '200' }}>
                        full
                    </span>{' '}
                    where it differs from the parent's,{' '}
                    <span class="rounded-[5px] px-1.5 py-px font-medium hue-chip opacity-40" style={{ '--hue': '200' }}>
                        faded
                    </span>{' '}
                    where it matches.
                </span>
            </p>
        </div>
    );
}
