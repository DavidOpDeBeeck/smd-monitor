import { Key } from '@solid-primitives/keyed';
import { createEffect, createMemo, createSignal, For, Index, on, Show } from 'solid-js';
import { selectedTypeKey, useMonitorApp } from '../../app/monitor-app';
import { LinkButton, TextButton } from '../../components/controls';
import { Glyph } from '../../components/Glyph';
import { createHeldListing, createListHold } from '../../components/hold';
import { animateReorder } from '../../components/reorder';
import { Sparkline } from '../../components/Sparkline';
import { KIND_TONE } from '../../components/tones';
import { Tweened } from '../../components/tween';
import { cx } from '../../lib/cx';
import { typeRows, type TypeRow, type TypeSort } from '../../lib/dashboard';
import { capitalized, count, duration, historySpan, KIND_LABEL, lastPeriod, perSecond, roundDuration, share, shortName } from '../../lib/format';
import { latencyScale, scalePosition, scaleTicks, type LatencyScale, type LatencySpread } from '../../lib/latency-spread';
import { hubRequest } from '../../lib/api';
import { typeFigures } from '../../lib/state';
import { selectionInScope } from '../../lib/type-selection';
import { ratePerSecond } from '../../lib/throughput';
import type { Brief, MessageKind } from '../../lib/types';
import type { WireTypeFigures } from '../../lib/wire';
import { createHubQuery } from '../../store/hub-query';
import { HeaderCell, NameCell, NUMBER_CELL, Row, Table } from './table';

// The table asks the hub for the first types in its order, a page more at a time.
const PAGE = 100;

const NUMBER = cx(NUMBER_CELL, 'transition-colors duration-500');
const HIDDEN_BELOW_4XL = '@max-4xl:hidden';
const HIDDEN_BELOW_2XL = '@max-2xl:hidden';
const HIDDEN_BELOW_XL = '@max-xl:hidden';

type ColumnId = 'name' | 'application' | 'rate' | 'series' | 'failed' | 'latency' | 'p50' | 'p95' | 'p99';

interface Column {
    id: ColumnId;
    label: string;
    sort?: TypeSort;
    title: string;
    class: string;
    hidden?: string;
}

const columnsOver = (last: string): Column[] => [
    { id: 'name', label: 'Message type', sort: 'name', title: 'Message type', class: 'pl-1 text-left' },
    {
        id: 'application',
        label: 'Application',
        title: 'The application that dispatches it; select it to narrow the scope to it',
        class: 'w-[140px] text-left',
        hidden: HIDDEN_BELOW_XL,
    },
    { id: 'rate', label: 'Rate /s', sort: 'rate', title: `Dispatches per second over the ${last}`, class: 'w-[68px] text-right' },
    {
        id: 'series',
        label: capitalized(last),
        title: `Dispatches per second over the ${last}, on one scale for every row`,
        class: 'w-[112px] text-left',
        hidden: HIDDEN_BELOW_4XL,
    },
    {
        id: 'failed',
        label: 'Failed',
        sort: 'failed',
        title: `Share of this type of dispatch that failed in the ${last}: what its callers saw`,
        class: 'w-[64px] text-right',
    },
    {
        id: 'latency',
        label: 'Latency',
        title: `Latency of the dispatches completed in the ${last}, on one log scale for every row: the line runs from the fastest bucket to the slowest dispatch, the bar from p50 to p95, the tick marks p50, and the ring p99.`,
        class: 'pr-2 pl-3 text-left',
        hidden: HIDDEN_BELOW_2XL,
    },
    { id: 'p50', label: 'p50', sort: 'p50', title: `Approximate median latency over the ${last}`, class: 'w-[72px] text-right', hidden: HIDDEN_BELOW_XL },
    { id: 'p95', label: 'p95', sort: 'p95', title: `Approximate p95 latency over the ${last}`, class: 'w-[72px] text-right' },
    { id: 'p99', label: 'p99', sort: 'p99', title: `Approximate p99 latency over the ${last}`, class: 'w-[72px] pr-1 text-right', hidden: HIDDEN_BELOW_XL },
];

function LatencyCell(props: { millis: number | undefined; class?: string }) {
    return (
        <td class={cx(NUMBER, props.class)}>
            <Show when={props.millis !== undefined} fallback="—">
                <Tweened value={props.millis!} format={duration} />
            </Show>
        </td>
    );
}

const MOVING = 'motion-value transition-[left,width]';

const at = (scale: LatencyScale, millis: number) => `${scalePosition(scale, millis) * 100}%`;
const between = (scale: LatencyScale, from: number, to: number) => `${(scalePosition(scale, to) - scalePosition(scale, from)) * 100}%`;

function LatencyAxis(props: { scale: LatencyScale }) {
    return (
        <div aria-hidden="true" class="relative h-3.5">
            <Index each={scaleTicks(props.scale)}>
                {tick => (
                    <span
                        class={cx('absolute -translate-x-1/2 font-mono font-medium tracking-normal text-faint normal-case', MOVING)}
                        style={{ left: at(props.scale, tick()) }}
                    >
                        {roundDuration(tick())}
                    </span>
                )}
            </Index>
        </div>
    );
}

function LatencyStrip(props: { spread: LatencySpread; scale: LatencyScale; kind: MessageKind }) {
    return (
        <div aria-hidden="true" class="relative h-[22px]">
            <Index each={scaleTicks(props.scale)}>
                {tick => <span class={cx('absolute inset-y-0 w-px bg-line', MOVING)} style={{ left: at(props.scale, tick()) }} />}
            </Index>
            <span
                class={cx('absolute top-1/2 h-px -translate-y-1/2 opacity-70', KIND_TONE[props.kind].bg, MOVING)}
                style={{ left: at(props.scale, props.spread.floorMillis), width: between(props.scale, props.spread.floorMillis, props.spread.maxMillis) }}
            />
            <span
                class={cx('absolute top-1/2 h-2 min-w-0.5 -translate-y-1/2 rounded-[2px]', KIND_TONE[props.kind].bar, MOVING)}
                style={{ left: at(props.scale, props.spread.p50Millis), width: between(props.scale, props.spread.p50Millis, props.spread.p95Millis) }}
            />
            <span class={cx('absolute top-1/2 h-3 w-[1.5px] -translate-1/2 bg-fg', MOVING)} style={{ left: at(props.scale, props.spread.p50Millis) }} />
            <span
                class={cx('absolute top-1/2 size-[7px] -translate-1/2 rounded-full border-[1.5px] border-fg bg-surface', MOVING)}
                style={{ left: at(props.scale, props.spread.p99Millis) }}
            />
        </div>
    );
}

function spreadSummary(row: TypeRow, spread: LatencySpread, last: string) {
    const completed = row.statistics.window.completed;
    return `${count(completed)} completed in the ${last}: p50 ${duration(spread.p50Millis)}, p95 ${duration(spread.p95Millis)}, p99 ${duration(spread.p99Millis)}, slowest ${duration(spread.maxMillis)}`;
}

function TypeTableRow(props: { row: TypeRow; end: number; scale: number; latencyScale: LatencyScale | null; selected: boolean; system: boolean }) {
    const app = useMonitorApp();
    const type = () => props.row.statistics;
    const select = () => app.select({ type: 'type', key: type().key });
    return (
        <Row reorder={type().key} onSelect={select} selected={props.selected}>
            <NameCell onSelect={select} label={`${KIND_LABEL[type().kind]} ${shortName(type().messageType)}`} title={type().messageType}>
                <Glyph kind={type().kind} />
                <span class="truncate font-semibold">{shortName(type().messageType)}</span>
            </NameCell>
            <Show when={props.system}>
                <td class={cx('max-w-0 px-2', HIDDEN_BELOW_XL)}>
                    <LinkButton
                        title={`Narrow the scope to ${type().application}`}
                        onClick={event => {
                            event.stopPropagation();
                            app.setScope(type().application);
                        }}
                        class="text-sm text-muted"
                    >
                        <span class="truncate">{type().application}</span>
                    </LinkButton>
                </td>
            </Show>
            <td
                class={NUMBER}
                title={`${count(type().window.started)} dispatches in the ${lastPeriod(app.store.view().windowSeconds)}; ${count(type().history.started)} in the last ${historySpan(app.store.view().historySeconds)}`}
            >
                <Tweened value={props.row.rate} format={perSecond} />
            </td>
            <td class={cx('px-2', HIDDEN_BELOW_4XL)}>
                <Sparkline
                    values={type().startedPerSecond}
                    endsAt={props.end}
                    provisionalFrom={app.store.view().applications.get(type().application)?.provisionalFrom}
                    width={96}
                    height={22}
                    scale={props.scale}
                    class="block h-[22px] w-24"
                    lineClass={cx('stroke-[1.25]', KIND_TONE[type().kind].spark)}
                    areaClass={KIND_TONE[type().kind].sparkArea}
                />
            </td>
            <td
                class={cx(NUMBER, type().window.failed ? 'font-semibold text-failed' : 'text-faint')}
                title={`${count(type().window.failed)} of ${count(type().window.started)} dispatches failed in the ${lastPeriod(app.store.view().windowSeconds)}; ${count(type().history.failed)} in the last ${historySpan(app.store.view().historySeconds)}`}
            >
                <Tweened value={props.row.failedShare ?? 0} format={value => (type().window.failed ? share(value) : '—')} />
            </td>
            <td class={cx('pr-2 pl-3', HIDDEN_BELOW_2XL)}>
                <Show when={props.row.latency && props.latencyScale}>
                    <div title={spreadSummary(props.row, props.row.latency!, lastPeriod(app.store.view().windowSeconds))} class="px-1">
                        <LatencyStrip spread={props.row.latency!} scale={props.latencyScale!} kind={type().kind} />
                    </div>
                </Show>
            </td>
            <LatencyCell millis={props.row.latency?.p50Millis} class={cx('text-muted', HIDDEN_BELOW_XL)} />
            <LatencyCell millis={props.row.latency?.p95Millis} class="font-semibold" />
            <LatencyCell millis={props.row.latency?.p99Millis} class={cx('pr-1 text-muted', HIDDEN_BELOW_XL)} />
        </Row>
    );
}

// What the scope counted of types beyond the hub's cap on types per kind, summed in one row with the counts and p95 the hub keeps of them.
function OtherTypesRow(props: { columns: readonly Column[]; other: Brief; windowSeconds: number }) {
    const failed = () => (props.other.started ? props.other.failed / props.other.started : 0);
    const cell = (column: Column) => {
        switch (column.id) {
            case 'name':
                return (
                    <td class="max-w-0 py-[7px] pr-2 pl-1">
                        <span class="flex min-w-0 items-center gap-2 text-muted" title="Types beyond smd.monitor.hub.max-types, counted together">
                            <i aria-hidden="true" class="inline-block size-[9px] flex-none rounded-[2px] border border-dashed border-faint" />
                            <span class="truncate italic">Other types</span>
                        </span>
                    </td>
                );
            case 'rate':
                return (
                    <td class={cx(NUMBER, 'text-muted')}>
                        <Tweened value={ratePerSecond(props.other.started, props.windowSeconds)} format={perSecond} />
                    </td>
                );
            case 'failed':
                return (
                    <td class={cx(NUMBER, props.other.failed ? 'font-semibold text-failed' : 'text-faint')}>
                        <Tweened value={failed()} format={value => (props.other.failed ? share(value) : '—')} />
                    </td>
                );
            case 'p95':
                return <LatencyCell millis={props.other.p95Millis ?? undefined} class="text-muted" />;
            default:
                return <td class={cx(NUMBER, 'text-faint', column.hidden)}>{column.id === 'p50' || column.id === 'p99' ? '—' : ''}</td>;
        }
    };
    return (
        <tr class="border-b border-dashed last:border-b-0">
            <For each={props.columns}>{column => cell(column)}</For>
        </tr>
    );
}

function SortHeader(props: { column: Column; latencyScale: LatencyScale | null }) {
    const overview = useMonitorApp().overview;
    const sorted = () => props.column.sort !== undefined && overview.sort() === props.column.sort;
    return (
        <HeaderCell
            title={props.column.title}
            sort={sorted() ? (props.column.sort === 'name' ? 'ascending' : 'descending') : undefined}
            class={cx(props.column.class, props.column.hidden)}
        >
            <Show
                when={props.column.sort}
                fallback={
                    <Show when={props.column.id === 'latency' && props.latencyScale} fallback={props.column.label}>
                        {scale => <LatencyAxis scale={scale()} />}
                    </Show>
                }
            >
                {sort => (
                    <button type="button" onClick={() => overview.setSort(sort())} class={cx('uppercase hover:text-fg', sorted() && 'text-fg')}>
                        {props.column.label}
                        <span aria-hidden="true" class={cx('ml-0.5', !sorted() && 'invisible')}>
                            {sort() === 'name' ? '↑' : '↓'}
                        </span>
                    </button>
                )}
            </Show>
        </HeaderCell>
    );
}

export function TypeTable() {
    const app = useMonitorApp();
    const overview = app.overview;
    const system = () => app.scope() === null;
    const last = createMemo(() => lastPeriod(app.store.view().windowSeconds));
    const columns = createMemo(() => columnsOver(last()).filter(column => column.id !== 'application' || system()));
    const selection = createMemo(() => selectionInScope(overview.types(), app.scope()));
    const filters = createMemo(() => `${app.scope()}|${overview.sort()}|${selection().kinds.join(' ')}|${selection().types.join(' ')}`);
    const [shown, setShown] = createSignal(PAGE);
    createEffect(on(filters, () => setShown(PAGE), { defer: true }));
    const query = createHubQuery<WireTypeFigures>(
        app.store,
        () => hubRequest('type-figures', { scope: app.scope(), kind: selection().kinds, type: selection().types, sort: overview.sort(), limit: shown() }),
        { kept: true },
    );
    const figures = createMemo(() => typeFigures(query.answer()));
    const hold = createListHold();
    // A new scope applies at once, like a filter.
    const listing = createHeldListing(
        hold,
        (row: TypeRow) => row.statistics.key,
        () => ({ items: typeRows(figures().types, app.store.view().windowSeconds), filters: filters() }),
    );
    const more = () => figures().total - figures().types.length;
    const scale = createMemo(() => Math.max(1, ...listing().items.flatMap(row => row.statistics.startedPerSecond)));
    const spreadScale = createMemo(() => latencyScale(listing().items.flatMap(row => row.latency ?? [])));
    let body!: HTMLTableSectionElement;
    animateReorder(
        () => body,
        () =>
            listing()
                .items.map(row => row.statistics.key)
                .join(' '),
    );

    return (
        <div class="@container relative" onPointerDown={hold.press}>
            <Table
                body={element => (body = element)}
                header={<For each={columns()}>{column => <SortHeader column={column} latencyScale={spreadScale()} />}</For>}
            >
                <Key each={listing().items} by={row => row.statistics.key}>
                    {row => (
                        <TypeTableRow
                            row={row()}
                            end={figures().end ?? app.store.end()}
                            scale={scale()}
                            latencyScale={spreadScale()}
                            selected={selectedTypeKey(app.selection()) === row().statistics.key}
                            system={system()}
                        />
                    )}
                </Key>
                <Show when={app.scoped().other.dispatchHistory.started > 0}>
                    <OtherTypesRow columns={columns()} other={app.scoped().other.dispatchWindow} windowSeconds={app.store.view().windowSeconds} />
                </Show>
            </Table>
            <Show when={more() > 0}>
                <TextButton wide class="mt-2" onClick={() => setShown(count => count + PAGE)}>
                    Show {Math.min(PAGE, more()).toLocaleString()} more of {more().toLocaleString()} types
                </TextButton>
            </Show>
        </div>
    );
}
