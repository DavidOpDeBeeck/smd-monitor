import { createMemo, createSignal, createUniqueId, For, Match, Show, Switch, type JSX } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { Checkbox } from '../../components/Checkbox';
import { closeOnOutsidePress, FIELD_LABEL, NoMatches, POPOVER } from '../../components/popover';
import { ThresholdPanel } from '../../components/ThresholdPanel';
import { cx } from '../../lib/cx';
import { THRESHOLD_BUCKETS, thresholdBuckets } from '../../lib/duration-threshold';
import { count, hashHue } from '../../lib/format';
import type { TraceRow } from '../../lib/traces';
import {
    columnFiltered,
    columnMatches,
    columnValues,
    noWaterfallFilter,
    timeOf,
    toggleValue,
    valueLabel,
    type FilterColumn,
    type TimeColumn,
    type ValueColumn,
    type WaterfallFilter,
} from '../../lib/waterfall-filter';

const TIME_LABEL: Record<TimeColumn, string> = { start: 'Started later than', wait: 'Waited longer than', duration: 'Slower than' };

const INPUT = 'h-8 w-full rounded-md border border-line-strong bg-surface-2 px-2.5 text-sm outline-none placeholder:text-faint focus:border-accent';

function FunnelIcon(props: { filled: boolean }) {
    return (
        <svg
            class="size-3"
            viewBox="0 0 12 12"
            fill={props.filled ? 'currentColor' : 'none'}
            stroke="currentColor"
            stroke-width="1.3"
            stroke-linejoin="round"
            aria-hidden="true"
        >
            <path d="M1.5 2h9L7 6.25V10L5 9V6.25z" />
        </svg>
    );
}

// The heading every filter shares with the threshold panel: what it filters, and how many of the trace's messages it keeps.
function FilterHeading(props: { label: string; kept: number; total: number }) {
    return (
        <div class="mb-2 flex items-baseline justify-between gap-3">
            <span class={FIELD_LABEL}>{props.label}</span>
            <span class="text-xs text-muted tabular-nums">
                {count(props.kept)} of {count(props.total)} messages
            </span>
        </div>
    );
}

function ValueChoices(props: { column: ValueColumn; filter: WaterfallFilter; rows: readonly TraceRow[]; onChange: (filter: WaterfallFilter) => void }) {
    const app = useMonitorApp();
    // An application wears its assigned hue; an instance or a thread hashes to one.
    const hueOf = (value: string) => (props.column === 'application' ? app.applicationHue(value) : hashHue(value));
    const [query, setQuery] = createSignal('');
    const values = createMemo(() => columnValues(props.rows, props.column));
    const shown = createMemo(() => {
        const search = query().trim().toLowerCase();
        return search ? values().filter(entry => valueLabel(props.column, entry.value).toLowerCase().includes(search)) : values();
    });
    return (
        <>
            <Show when={values().length > 6}>
                <input
                    type="search"
                    placeholder="Find…"
                    aria-label="Find a value"
                    value={query()}
                    onInput={event => setQuery(event.currentTarget.value)}
                    class={cx(INPUT, 'mb-1.5')}
                />
            </Show>
            <div role="group" class="-mx-1 max-h-64 overflow-auto">
                <For each={shown()} fallback={<NoMatches />}>
                    {entry => (
                        <button
                            type="button"
                            role="checkbox"
                            aria-checked={props.filter[props.column].includes(entry.value)}
                            onClick={() => props.onChange(toggleValue(props.filter, props.column, entry.value))}
                            class="flex w-full items-center gap-2 rounded-md px-1 py-1.5 text-left outline-none hover:bg-accent/8 focus-visible:bg-accent/10"
                        >
                            <Checkbox selected={props.filter[props.column].includes(entry.value)} />
                            <span
                                style={{ '--hue': `${hueOf(entry.value)}` }}
                                class="min-w-0 truncate rounded-[5px] px-1.5 py-px font-mono text-xs font-medium hue-chip"
                                title={entry.value}
                            >
                                {valueLabel(props.column, entry.value)}
                            </span>
                            <span class="ml-auto flex-none font-mono text-xs text-faint tabular-nums">{entry.rows}</span>
                        </button>
                    )}
                </For>
            </div>
        </>
    );
}

// Filters one column of the waterfall: the message by text, a value column by the values the trace has, a time column by a minimum over its histogram.
export function ColumnFilter(props: {
    column: FilterColumn;
    label: string;
    filter: WaterfallFilter;
    rows: readonly TraceRow[];
    traceStart: number;
    alignEnd?: boolean;
    onChange: (filter: WaterfallFilter) => void;
}) {
    const id = createUniqueId();
    let root!: HTMLDivElement;
    let trigger!: HTMLButtonElement;
    let panel: HTMLDivElement | undefined;
    const [open, setOpen] = createSignal(false);
    const active = () => columnFiltered(props.filter, props.column);
    const timeColumn = () => (props.column === 'start' || props.column === 'wait' || props.column === 'duration' ? props.column : null);
    // The time column's values; a row that did not wait has no wait.
    const times = createMemo(() => {
        const column = timeColumn();
        return column ? props.rows.map(row => timeOf(row, column, props.traceStart)).filter((time): time is number => time !== null) : [];
    });
    const kept = () => props.rows.filter(row => columnMatches(row, props.filter, props.column, props.traceStart)).length;
    const close = (refocus: boolean) => {
        setOpen(false);
        if (refocus) trigger.focus();
    };
    const show = () => {
        setOpen(true);
        panel?.querySelector<HTMLElement>('input, [role="slider"], [role="checkbox"]')?.focus();
    };
    // Clearing a column keeps the filters of the others.
    const clear = () => props.onChange({ ...props.filter, [props.column]: noWaterfallFilter()[props.column] });
    closeOnOutsidePress(
        () => root,
        open,
        () => close(false),
    );

    const footer = (): JSX.Element => (
        <Show when={active()}>
            <div class="mt-3 border-t pt-2">
                <button type="button" onClick={clear} class="text-sm font-medium text-accent hover:underline">
                    Clear this filter
                </button>
            </div>
        </Show>
    );

    return (
        <div ref={root} class="relative flex-none">
            <button
                ref={trigger}
                type="button"
                aria-haspopup="dialog"
                aria-expanded={open()}
                aria-controls={open() ? `${id}-filter` : undefined}
                aria-label={`Filter ${props.label}${active() ? ', filtered' : ''}`}
                title={`Filter ${props.label}`}
                onPointerDown={event => event.stopPropagation()}
                onClick={() => (open() ? close(true) : show())}
                class={cx(
                    'grid size-5 place-items-center rounded transition-colors duration-150',
                    active() ? 'bg-accent/15 text-accent' : 'text-faint hover:bg-surface hover:text-fg',
                    open() && 'text-accent',
                )}
            >
                <FunnelIcon filled={active()} />
            </button>
            <Show when={open()}>
                <div
                    ref={panel}
                    id={`${id}-filter`}
                    role="dialog"
                    aria-label={`Filter ${props.label}`}
                    onKeyDown={event => {
                        if (event.key !== 'Escape' && !(event.key === 'Enter' && timeColumn())) return;
                        event.preventDefault();
                        close(true);
                    }}
                    class={cx(
                        POPOVER,
                        'z-20 w-80 p-3 font-normal tracking-normal normal-case',
                        props.alignEnd ? 'right-0 origin-top-right' : 'left-0 origin-top-left',
                    )}
                >
                    <Switch>
                        <Match when={timeColumn()}>
                            {column => (
                                <ThresholdPanel
                                    label={TIME_LABEL[column()]}
                                    value={props.filter[column()]}
                                    buckets={thresholdBuckets(times(), THRESHOLD_BUCKETS)}
                                    kept={kept()}
                                    total={props.rows.length}
                                    onChange={millis => props.onChange({ ...props.filter, [column()]: millis })}
                                />
                            )}
                        </Match>
                        <Match when={props.column === 'message'}>
                            <FilterHeading label="Message contains" kept={kept()} total={props.rows.length} />
                            <input
                                type="search"
                                placeholder="Name or class…"
                                aria-label="Message contains"
                                value={props.filter.message}
                                onInput={event => props.onChange({ ...props.filter, message: event.currentTarget.value })}
                                class={INPUT}
                            />
                        </Match>
                        <Match when={props.column !== 'message'}>
                            <FilterHeading label={props.label} kept={kept()} total={props.rows.length} />
                            <ValueChoices column={props.column as ValueColumn} filter={props.filter} rows={props.rows} onChange={props.onChange} />
                        </Match>
                    </Switch>
                    {footer()}
                </div>
            </Show>
        </div>
    );
}
