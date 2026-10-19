import { Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { Glyph } from '../../components/Glyph';
import { cx } from '../../lib/cx';
import { ago, duration, KINDS, shortName } from '../../lib/format';
import { traceStatus, type TraceItem, type TraceSummary } from '../../lib/traces';
import type { DispatchStatus } from '../../lib/types';

const OPEN_BUTTON = 'grid min-w-0 flex-1 grid-cols-[minmax(0,1fr)_auto] gap-x-2.5 gap-y-[3px] py-[9px] pr-1 pl-3.5 text-left hover:bg-fg/4';
const STATUS_BAR: Record<DispatchStatus, string> = { FAILED: 'before:bg-failed', COMPLETED: '' };

const plural = (amount: number, noun: string) => `${amount} ${noun}${amount === 1 ? '' : 's'}`;

function Subtext(props: { trace: TraceSummary; age: string; applications: readonly string[] }) {
    const composition = () => {
        if (props.trace.messages + props.trace.handlings <= 1) return '';
        const counts = props.trace.composition;
        const parts = KINDS.filter(kind => counts[kind] > 0).map(kind => plural(counts[kind], kind.toLowerCase()));
        if (counts.handlings) parts.push(plural(counts.handlings, 'handling'));
        return parts.join(' · ');
    };
    return (
        <span class="col-span-2 flex min-w-0 items-baseline gap-1.5 text-xs whitespace-nowrap text-muted tabular-nums">
            <Show when={props.applications.length}>
                <span class="truncate font-semibold" title={`Ran in ${props.applications.join(', ')}`}>
                    {props.applications.join(', ')}
                </span>
                <span class="flex-none text-faint">·</span>
            </Show>
            <Show when={composition()}>
                <span class="truncate" title={composition()}>
                    {composition()}
                </span>
                <span class="flex-none text-faint">·</span>
            </Show>
            <Show when={props.trace.failures}>
                <span class="flex-none font-semibold text-failed">{props.trace.failures} failed</span>
                <span class="flex-none text-faint">·</span>
            </Show>
            <span class="flex-none text-faint">{props.age}</span>
        </span>
    );
}

export function TraceListItem(props: { item: TraceItem }) {
    const app = useMonitorApp();
    const trace = () => props.item.summary;
    const root = () => trace().root;
    const now = () => app.store.now();
    const selected = () => app.traces.selected() === root().sequence;
    // Where a trace ran, when that is not just the application in scope.
    const applications = () => {
        const names = trace().applications;
        return names.length > 1 || (app.scope() === null && app.system().applications.length > 1) ? names : [];
    };
    return (
        <div
            data-root={root().sequence}
            class={cx(
                'relative flex items-stretch border-b before:absolute before:inset-y-0 before:left-0 before:w-[3px]',
                STATUS_BAR[traceStatus(trace())],
                selected() && 'bg-accent/9',
                selected() && 'after:absolute after:inset-y-0 after:right-0 after:w-0.5 after:bg-accent',
            )}
        >
            <button
                type="button"
                aria-current={selected() ? 'true' : undefined}
                data-trace-open
                onClick={() => app.traces.setSelected(root().sequence)}
                class={OPEN_BUTTON}
            >
                <span class="flex min-w-0 items-center gap-[7px]">
                    <Glyph kind={root().kind} />
                    <strong class="truncate font-semibold" title={root().messageType}>
                        {shortName(root().messageType)}
                    </strong>
                </span>
                <span class={cx('font-mono text-sm font-semibold tabular-nums', trace().failures > 0 && 'text-failed')}>
                    {duration(trace().durationMillis)}
                </span>
                <Subtext trace={trace()} applications={applications()} age={props.item.evictedCopy ? 'pinned copy' : ago(now() - trace().start)} />
            </button>
            <button
                type="button"
                title={props.item.pinned ? 'Unpin trace' : 'Pin trace (keeps a copy in this tab)'}
                aria-label={props.item.pinned ? 'Unpin trace' : 'Pin trace'}
                aria-pressed={props.item.pinned}
                onClick={() => void app.togglePin(trace())}
                class={cx('w-[34px] text-md hover:text-warning', props.item.pinned ? 'text-warning' : 'text-faint')}
            >
                {props.item.pinned ? '★' : '☆'}
            </button>
        </div>
    );
}
