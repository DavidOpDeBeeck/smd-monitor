import { For, Match, Show, Switch } from 'solid-js';
import { selectedSequence, useMonitorApp } from '../../app/monitor-app';
import { HueBadge } from '../../components/chips';
import { ActivityGlyph } from '../../components/Glyph';
import { HANDLING_TONE, KIND_TONE } from '../../components/tones';
import { cx } from '../../lib/cx';
import { activityName, duration, KIND_LABEL, shortName, STATUS_LABEL, threadLabel } from '../../lib/format';
import { endOf, failureTypeOf } from '../../lib/model';
import { BAR_LABEL_GAP, barLabelPlacement, barLabelWidth, type TraceRow, type TraceSummary } from '../../lib/traces';
import type { TimeWindow } from '../../lib/timeline-zoom';
import type { TableColumn } from '../../lib/waterfall-columns';
import { waitOf } from '../../lib/waterfall-filter';

const CELL = 'flex min-w-0 items-center border-r border-line/60 px-2 font-mono text-xs';

export function WaterfallRow(props: {
    row: TraceRow;
    trace: TraceSummary;
    window: TimeWindow;
    trackWidth: number;
    tabStop: boolean;
    columns: readonly TableColumn[];
    template: string;
    dimmed: boolean;
}) {
    const app = useMonitorApp();
    const message = () => props.row.message;
    const group = () => message().processingGroup;
    const description = () => {
        const name = `${KIND_LABEL[message().kind]} ${shortName(message().messageType)}`;
        if (group()) return `Processing group ${group()} handling ${name}`;
        return message().handling ? `Handler of ${name}` : name;
    };
    const length = () => message().outcome.durationMillis;
    // Where a moment of the trace falls in the window in view, and how much of the window a length takes, in percent.
    const span = () => props.window.to - props.window.from;
    const at = (millis: number) => ((millis - props.trace.start - props.window.from) / span()) * 100;
    const percentOf = (millis: number) => (millis / span()) * 100;
    const left = () => at(message().startedAtMillis);
    const width = () => Math.max(0.15, percentOf(length()));
    const parentEnd = () => {
        const parent = props.row.parent;
        return parent ? endOf(parent) : null;
    };
    const wait = () => waitOf(props.row);
    const asynchronous = () => wait() !== null;
    const nameTitle = () => {
        const name = message().handling ? `${description()} (${message().messageType})` : message().messageType;
        return asynchronous() ? `${name}\nStarted ${duration(wait()!)} after its parent returned, then ran ${duration(length())}` : name;
    };
    const crossing = () => props.row.parent !== null && props.row.parent.application !== message().application;
    const applicationTitle = () => {
        const parent = props.row.parent;
        if (!parent || !crossing()) return `Application ${message().application}`;
        const delivered = message().parent?.delivered;
        return `Application ${message().application}, ${delivered ? 'delivered from' : 'caused by a message of'} ${parent.application}`;
    };
    const collapsed = () => app.traces.collapsed().has(message().sequence);
    const selected = () => selectedSequence(app.selection()) === message().sequence;
    const barLabel = () => {
        if (message().outcome.status === 'FAILED') return `${duration(length())} · ${shortName(failureTypeOf(message()) ?? 'failure')}`;
        return duration(length());
    };
    const placement = () => barLabelPlacement(barLabel(), left(), width(), props.trackWidth);
    const inside = () => placement() === 'inside';
    const connectorTrim = () => (placement() === 'before' ? barLabelWidth(barLabel()) + 2 * BAR_LABEL_GAP : 0);
    const labelStyle = () => {
        switch (placement()) {
            case 'inside':
                return { right: `${Math.max(0, 100 - left() - width())}%`, 'max-width': `${width()}%` };
            case 'before':
                return { right: `calc(${100 - left()}% + ${BAR_LABEL_GAP}px)` };
            case 'after':
                return { left: `calc(${left() + width()}% + ${BAR_LABEL_GAP}px)` };
        }
    };

    return (
        <div
            role="treeitem"
            data-sequence={message().sequence}
            tabIndex={props.tabStop ? 0 : -1}
            aria-label={`${description()} in ${message().application}, ${STATUS_LABEL[message().outcome.status]}, ${duration(length())}`}
            aria-level={props.row.depth + 1}
            aria-expanded={props.row.childCount ? !collapsed() : undefined}
            aria-selected={selected()}
            style={{ 'grid-template-columns': props.template }}
            class={cx(
                'grid min-h-[30px] border-b border-line/40 transition-opacity duration-150 focus-visible:-outline-offset-2',
                selected() ? 'bg-accent/10' : 'hover:bg-fg/4',
                props.dimmed && !selected() && 'opacity-45 focus-within:opacity-100 hover:opacity-100',
            )}
        >
            <div
                class="flex min-w-0 items-center gap-1.5 border-r border-line/60 py-[3px] pr-2.5 depth-guides"
                style={{ '--depth': `${Math.min(props.row.depth, 12)}` }}
            >
                <Show when={props.row.childCount} fallback={<span class="w-4 flex-none" />}>
                    <button
                        type="button"
                        tabIndex={-1}
                        aria-label={`${collapsed() ? 'Expand' : 'Collapse'} ${props.row.childCount} children`}
                        onClick={() => app.traces.toggleCollapsed(message().sequence)}
                        class="h-5 w-4 flex-none text-xs text-muted"
                    >
                        {collapsed() ? '▸' : '▾'}
                    </button>
                </Show>
                <button
                    type="button"
                    tabIndex={-1}
                    onClick={() => app.select({ type: 'message', sequence: message().sequence })}
                    class="group/select flex min-w-0 items-center gap-[7px] rounded-sm px-1 py-0.5 text-left"
                >
                    <ActivityGlyph message={message()} />
                    <span
                        class={cx(
                            'truncate group-hover/select:underline',
                            group() ? 'font-mono text-sm font-medium' : 'font-semibold',
                            message().outcome.status === 'FAILED' && 'text-failed',
                        )}
                        title={nameTitle()}
                    >
                        {activityName(message())}
                    </span>
                </button>
            </div>
            <For each={props.columns}>
                {column => (
                    <div class={cx(CELL, column.numeric && 'justify-end tabular-nums')}>
                        <Switch>
                            <Match when={column.key === 'application'}>
                                <HueBadge
                                    value={message().application}
                                    hue={app.applicationHue(message().application)}
                                    parent={props.row.parent?.application}
                                    label={message().application}
                                    title={applicationTitle()}
                                    class="font-sans text-sm"
                                />
                            </Match>
                            <Match when={column.key === 'instance'}>
                                <HueBadge
                                    value={message().instance}
                                    parent={props.row.parent?.instance}
                                    label={message().instance}
                                    title={`Instance ${message().instance}${props.row.parent && props.row.parent.instance !== message().instance ? `, its parent ran on ${props.row.parent.instance}` : ''}`}
                                />
                            </Match>
                            <Match when={column.key === 'thread'}>
                                <HueBadge
                                    value={message().threadName}
                                    parent={props.row.parent?.threadName}
                                    label={threadLabel(message().threadName)}
                                    title={`Thread ${threadLabel(message().threadName)}${props.row.parent && props.row.parent.threadName !== message().threadName ? `, its parent ran on ${threadLabel(props.row.parent.threadName)}` : ''}`}
                                />
                            </Match>
                            <Match when={column.key === 'start'}>
                                <span class="text-muted">+{duration(message().startedAtMillis - props.trace.start)}</span>
                            </Match>
                            <Match when={column.key === 'wait'}>
                                <Show when={wait() !== null}>
                                    <span class="text-accent" title="Started this long after its parent returned">
                                        {duration(wait()!)}
                                    </span>
                                </Show>
                            </Match>
                            <Match when={column.key === 'duration'}>
                                <span class={message().outcome.status === 'FAILED' ? 'font-semibold text-failed' : 'text-fg'}>{duration(length())}</span>
                            </Match>
                        </Switch>
                    </div>
                )}
            </For>
            <div data-timeline class="relative overflow-hidden">
                <div class="absolute inset-y-0 right-[90px] left-0">
                    <Show when={asynchronous()}>
                        <span
                            class="absolute top-1/2 h-0 border-t-[1.5px] border-dotted border-accent/45 transition-[left,width] motion-clock"
                            style={{
                                left: `${at(parentEnd()!)}%`,
                                width: `max(0px, calc(${percentOf(wait()!)}% - ${connectorTrim()}px))`,
                            }}
                        />
                    </Show>
                    <span
                        class={cx(
                            'absolute top-1/2 h-3.5 min-w-[3px] -translate-y-1/2 rounded-[3px] transition-[left,width] motion-clock',
                            message().outcome.status === 'FAILED' ? 'bg-failed' : group() ? HANDLING_TONE.bg : KIND_TONE[message().kind].bg,
                        )}
                        style={{ left: `${left()}%`, width: `${width()}%` }}
                    />
                    <span
                        class={cx(
                            'absolute top-1/2 -translate-y-1/2 font-mono text-xs whitespace-nowrap transition-[left,right] motion-clock',
                            inside() && 'overflow-hidden pr-1.5 text-ellipsis',
                            inside()
                                ? group() && message().outcome.status !== 'FAILED'
                                    ? 'font-semibold text-event-ink'
                                    : 'font-semibold text-white [text-shadow:0_1px_1px_rgb(0_0_0/30%)]'
                                : message().outcome.status === 'FAILED'
                                  ? 'font-semibold text-failed'
                                  : 'font-medium text-muted',
                        )}
                        style={labelStyle()}
                    >
                        {barLabel()}
                    </span>
                </div>
            </div>
        </div>
    );
}
