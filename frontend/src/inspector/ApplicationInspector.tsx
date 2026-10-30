import { Key } from '@solid-primitives/keyed';
import { createMemo, Show } from 'solid-js';
import { useMonitorApp } from '../app/monitor-app';
import { TextButton } from '../components/controls';
import { Dot } from '../components/Dot';
import { Empty, EmptyLine } from '../components/empty';
import { InstanceFlags } from '../components/InstanceFlags';
import { Sparkline } from '../components/Sparkline';
import { HEALTH_TONE, reportingTone } from '../components/tones';
import { applicationHealth, applicationRows } from '../lib/applications';
import { cx } from '../lib/cx';
import { count, duration, lastPeriod, lastSpanAgo, percent, plural, rate, shortHistorySpan } from '../lib/format';
import { neighbourApplications, type NeighbourApplication } from '../lib/scope';
import { InspectorSection, Stats } from './parts';

const RELATION = 'grid w-full grid-cols-[minmax(0,1fr)_auto] items-center gap-2 rounded-md border bg-surface-2 px-2 py-1.5 text-left hover:border-accent';

function NeighbourRow(props: { neighbour: NeighbourApplication }) {
    const app = useMonitorApp();
    const historySeconds = () => Math.max(1, app.store.view().historySeconds);
    return (
        <button
            type="button"
            title={`${count(props.neighbour.invocations)} linked invocations over the last ${shortHistorySpan(app.store.view().historySeconds)}`}
            onClick={() => app.reveal({ type: 'application', name: props.neighbour.name })}
            class={RELATION}
        >
            <span class="truncate font-semibold">{props.neighbour.name}</span>
            <span class="font-mono text-sm text-muted">{rate(props.neighbour.invocations / historySeconds())}</span>
        </button>
    );
}

export function ApplicationInspector(props: { name: string }) {
    const app = useMonitorApp();
    const store = app.store;
    const application = createMemo(() => store.view().applications.get(props.name));
    const row = createMemo(() => {
        const state = application();
        return state ? applicationRows([state], store.view().capturedAt, store.view().windowSeconds)[0] : undefined;
    });
    const neighbours = createMemo(() => neighbourApplications(store.view(), props.name));

    return (
        <Show
            when={row()}
            fallback={
                <Empty class="h-full" title="Application not reporting">
                    The hub has no statistics of {props.name} in the current session.
                </Empty>
            }
        >
            {row => {
                const totals = () => application()!.totals;
                const invocations = () => totals().dispatches.started + totals().handlings.started;
                const health = () => HEALTH_TONE[applicationHealth(row())];
                return (
                    <>
                        <div class="flex flex-col gap-1.5">
                            <h2 class="text-2xl font-bold tracking-[-.4px] [overflow-wrap:anywhere]">{props.name}</h2>
                            <p class={cx('flex items-center gap-2 text-sm', health().text)}>
                                <Dot class={health().dot} />
                                {row().reporting} of {row().instances.length} {plural(row().instances.length, 'instance')} reporting
                            </p>
                        </div>
                        <div class="mt-3.5 flex gap-2">
                            <Show when={app.scope() !== props.name} fallback={<TextButton onClick={() => app.setScope(null)}>Show the system</TextButton>}>
                                <TextButton onClick={() => app.setScope(props.name)}>Narrow the scope to it</TextButton>
                            </Show>
                        </div>
                        <InspectorSection title="Activity" note={lastPeriod(store.view().windowSeconds)}>
                            <Stats
                                stats={[
                                    { label: 'Rate', value: rate(row().dispatchesPerSecond) },
                                    {
                                        label: 'Failed',
                                        value: count(row().failures),
                                        detail: row().failures ? percent(row().failures, invocations()) : undefined,
                                        tone: row().failures ? 'text-failed' : '',
                                    },
                                    { label: 'p95', value: duration(totals().dispatches.p95Millis) },
                                ]}
                            />
                            <div class="relative h-10">
                                <Sparkline
                                    values={row().dispatchSeconds}
                                    endsAt={store.end()}
                                    provisionalFrom={application()?.provisionalFrom}
                                    width={320}
                                    height={40}
                                    scale={Math.max(1, ...row().dispatchSeconds, ...row().failureSeconds)}
                                    class="absolute inset-0 h-10 w-full"
                                    lineClass="stroke-muted stroke-[1.5]"
                                    areaClass="fill-muted/10"
                                />
                                <Show when={row().failures > 0}>
                                    <Sparkline
                                        values={row().failureSeconds}
                                        endsAt={store.end()}
                                        width={320}
                                        height={40}
                                        scale={Math.max(1, ...row().dispatchSeconds, ...row().failureSeconds)}
                                        class="absolute inset-0 h-10 w-full"
                                        lineClass="stroke-failed stroke-[1.5]"
                                    />
                                </Show>
                            </div>
                        </InspectorSection>
                        <InspectorSection title="Upstream" note={`causes its invocations · last ${shortHistorySpan(store.view().historySeconds)}`}>
                            <div class="grid gap-[5px]">
                                <Key
                                    each={neighbours().upstream}
                                    by="name"
                                    fallback={<EmptyLine class="text-left">No other application's messages caused its invocations.</EmptyLine>}
                                >
                                    {neighbour => <NeighbourRow neighbour={neighbour()} />}
                                </Key>
                            </div>
                        </InspectorSection>
                        <InspectorSection title="Downstream" note={`its messages cause there · last ${shortHistorySpan(store.view().historySeconds)}`}>
                            <div class="grid gap-[5px]">
                                <Key
                                    each={neighbours().downstream}
                                    by="name"
                                    fallback={<EmptyLine class="text-left">Its messages caused no invocations of another application.</EmptyLine>}
                                >
                                    {neighbour => <NeighbourRow neighbour={neighbour()} />}
                                </Key>
                            </div>
                        </InspectorSection>
                        <InspectorSection title="Instances" note="reported in the last 10 minutes">
                            <Key each={row().instances} by="sessionId" fallback={<EmptyLine class="text-left">No instance reported.</EmptyLine>}>
                                {instance => (
                                    <button
                                        type="button"
                                        title={`Show the invocations of ${instance().name} on the tape`}
                                        onClick={() => app.filterTape(instance().name, props.name)}
                                        class="grid w-full grid-cols-[10px_minmax(0,1fr)_auto] items-center gap-2 border-b px-1 py-1.5 text-left hover:bg-surface-2"
                                    >
                                        <Dot class={reportingTone(instance().silent).dot} />
                                        <span class="flex min-w-0 items-center gap-1.5">
                                            <span class="truncate font-mono text-sm font-semibold">{instance().name}</span>
                                            <InstanceFlags
                                                clockAhead={instance().clockAhead}
                                                exportLagMillis={instance().exportLagMillis}
                                                settleSeconds={store.view().settleSeconds}
                                            />
                                        </span>
                                        <span class={cx('font-mono text-sm', reportingTone(instance().silent).text)}>
                                            {instance().silent && 'last span '}
                                            {lastSpanAgo(instance().sinceReportMillis, instance().silent)}
                                        </span>
                                    </button>
                                )}
                            </Key>
                        </InspectorSection>
                    </>
                );
            }}
        </Show>
    );
}
