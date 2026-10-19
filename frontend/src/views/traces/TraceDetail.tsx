import { Index, Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { KindChip, StatusChip } from '../../components/chips';
import { CopyButton, TEXT_BUTTON, TextButton } from '../../components/controls';
import { parentNote } from '../../lib/api';
import { clock, duration, shortName } from '../../lib/format';
import { exportTrace, traceStatus } from '../../lib/traces';
import type { DispatchStatus } from '../../lib/types';
import type { ShownTrace } from './TracesView';
import { Waterfall } from './Waterfall';

const TRACE_STATUS_LABEL: Record<DispatchStatus, string> = { FAILED: 'Contains failures', COMPLETED: 'Completed' };

export function TraceDetail(props: { trace: ShownTrace }) {
    const app = useMonitorApp();
    const trace = () => props.trace.detail.summary;
    const root = () => trace().root;
    const rootMember = () => props.trace.detail.members.find(member => member.sequence === root().sequence);
    const facts = () => {
        const list: [string, string][] = [
            ['Started', clock(trace().start)],
            ['Duration', duration(trace().durationMillis)],
            ['Messages', trace().messages.toLocaleString()],
            ['Threads', trace().threads.toLocaleString()],
        ];
        if (trace().handlings) list.push(['Handlings', trace().handlings.toLocaleString()]);
        if (trace().failures) list.push(['Failed', trace().failures.toLocaleString()]);
        const applications = trace().applications;
        if (applications.length > 1) list.push(['Applications', applications.join(', ')]);
        return list;
    };
    const pinned = () => app.pins().has(props.trace.key);

    return (
        <>
            <header class="grid grid-cols-[minmax(0,1fr)_auto] gap-x-4 gap-y-2.5 border-b bg-surface px-5 pt-4 pb-3.5">
                <div class="flex min-w-0 flex-wrap items-center gap-2.5">
                    <KindChip kind={root().kind} />
                    <h2 class="truncate text-xl font-bold tracking-[-.3px]" title={root().messageType}>
                        {shortName(root().messageType)}
                    </h2>
                    <StatusChip status={traceStatus(trace())}>{TRACE_STATUS_LABEL[traceStatus(trace())]}</StatusChip>
                </div>
                <dl class="col-start-1 flex flex-wrap gap-x-[22px] gap-y-1">
                    <Index each={facts()}>
                        {fact => (
                            <div class="flex items-baseline gap-1.5">
                                <dt class="text-xs text-muted">{fact()[0]}</dt>
                                <dd class="font-mono text-sm font-semibold">{fact()[1]}</dd>
                            </div>
                        )}
                    </Index>
                </dl>
                <div class="col-start-2 row-span-2 row-start-1 flex flex-wrap items-start justify-end gap-1.5">
                    <TextButton aria-pressed={pinned()} onClick={() => void app.togglePin(trace())}>
                        {pinned() ? '★ Pinned' : '☆ Pin'}
                    </TextButton>
                    <CopyButton class={TEXT_BUTTON} text={() => JSON.stringify(exportTrace(props.trace.detail), null, 2)}>
                        Copy JSON
                    </CopyButton>
                    <TextButton onClick={() => app.showType(root().typeKey)}>Show on flow</TextButton>
                </div>
                <Show when={!props.trace.evictedCopy && rootMember() && parentNote(rootMember()!)}>
                    {note => (
                        <p class="col-start-1 text-sm text-muted" title="The root has a parent message whose invocation the monitor does not retain">
                            ↰ {note()}
                        </p>
                    )}
                </Show>
                <Show when={props.trace.evictedCopy}>
                    <p class="col-span-full mt-2.5 rounded-md bg-warning/12 px-2.5 py-2 text-sm">
                        The root was evicted from the monitor. This is the copy pinned in this tab.
                    </p>
                </Show>
            </header>
            <Waterfall detail={props.trace.detail} />
        </>
    );
}
