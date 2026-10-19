import { Key } from '@solid-primitives/keyed';
import { For, Show } from 'solid-js';
import { DetailsIcon } from '../../components/DetailsIcon';
import { Dot } from '../../components/Dot';
import { InstanceFlags } from '../../components/InstanceFlags';
import { Sparkline } from '../../components/Sparkline';
import { EYEBROW } from '../../components/text';
import { Tweened } from '../../components/tween';
import { HEALTH_TONE, reportingTone } from '../../components/tones';
import { useMonitorApp } from '../../app/monitor-app';
import { applicationHealth, type ApplicationRow, type InstanceRow } from '../../lib/applications';
import { cx } from '../../lib/cx';
import { ago, bytes, capitalized, lastPeriod, lastSpanAgo, rate, shortHistorySpan, wholeCount } from '../../lib/format';

const SPARK_WIDTH = 160;
// Past this many instances the summary counts the rest instead of drawing them.
const INSTANCE_DOTS = 12;
const NUMBER = 'px-3 text-right font-mono text-sm whitespace-nowrap tabular-nums';

export function ApplicationList(props: { rows: readonly ApplicationRow[]; endsAt: number }) {
    const app = useMonitorApp();
    const last = () => lastPeriod(app.store.view().windowSeconds);
    return (
        <div class="overflow-x-auto rounded-lg border bg-surface shadow-card">
            <table class="w-full min-w-[1000px] table-fixed border-collapse">
                <thead>
                    <tr class="border-b">
                        <Header class="pl-4 text-left">Application</Header>
                        <Header class="w-[180px] text-left" title="The instances the hub knows and whether they report">
                            Instances
                        </Header>
                        <Header class="w-[112px] text-right" title="When the latest span of any of its instances arrived">
                            Last span
                        </Header>
                        <Header class="w-[120px] text-right" title={`Dispatches per second over the ${last()}`}>
                            Dispatches /s
                        </Header>
                        <Header class="w-[128px] text-right" title={`Entry points per second over the ${last()}`}>
                            Entry points /s
                        </Header>
                        <Header class="w-[96px] text-right" title={`Failed dispatches and handlings over the ${last()}`}>
                            Failures
                        </Header>
                        <Header class="w-[96px] text-right" title="Estimated hub memory for the retained invocations and the spans it links by">
                            Memory
                        </Header>
                        <Header class="w-[192px] text-left" title={`Dispatches and failures per second over the ${last()}`}>
                            {capitalized(last())}
                        </Header>
                        <th scope="col" class="w-11">
                            <span class="sr-only">Details</span>
                        </th>
                    </tr>
                </thead>
                <tbody>
                    <Key each={props.rows} by={row => row.name}>
                        {row => <ApplicationListRow row={row()} endsAt={props.endsAt} />}
                    </Key>
                </tbody>
            </table>
        </div>
    );
}

function Header(props: { title?: string; class?: string; children: string }) {
    return (
        <th scope="col" title={props.title} class={cx(EYEBROW, 'px-3 pt-3 pb-2', props.class)}>
            {props.children}
        </th>
    );
}

const instanceTitle = (instance: InstanceRow) =>
    `${instance.name}, ${instance.silent ? `last span ${ago(instance.sinceReportMillis)}` : 'reporting'} · session ${instance.sessionId}`;

// Whether any instance's clock runs ahead, and the longest export lag of them, which flag the row.
function Flags(props: { instances: readonly InstanceRow[] }) {
    const app = useMonitorApp();
    const lags = () => props.instances.flatMap(instance => (instance.exportLagMillis === null ? [] : [instance.exportLagMillis]));
    return (
        <InstanceFlags
            clockAhead={props.instances.some(instance => instance.clockAhead)}
            exportLagMillis={lags().length ? Math.max(...lags()) : null}
            settleSeconds={app.store.view().settleSeconds}
        />
    );
}

// One instance names itself; several show a dot each, and how many of them report.
function InstanceSummary(props: { row: ApplicationRow }) {
    const instances = () => props.row.instances;
    const health = () => HEALTH_TONE[applicationHealth(props.row)];
    return (
        <Show when={instances().length !== 1} fallback={<SingleInstance instance={instances()[0]} />}>
            <div class="flex min-w-0 items-center gap-2" title={instances().map(instanceTitle).join('\n')}>
                <Show
                    when={instances().length}
                    fallback={
                        <span
                            class="text-sm text-faint"
                            title="The hub no longer lists its instances: they sent no spans for longer than its retire-after limit"
                        >
                            None listed
                        </span>
                    }
                >
                    <span class="flex flex-none items-center gap-[3px]" aria-hidden="true">
                        <For each={instances().slice(0, INSTANCE_DOTS)}>{instance => <Dot class={cx('size-1.5', reportingTone(instance.silent).dot)} />}</For>
                        <Show when={instances().length > INSTANCE_DOTS}>
                            <span class="ml-0.5 font-mono text-xs text-faint">+{instances().length - INSTANCE_DOTS}</span>
                        </Show>
                    </span>
                    <span class={cx('truncate text-sm', health().text)}>
                        {props.row.reporting} of {instances().length} reporting
                    </span>
                    <Flags instances={instances()} />
                </Show>
            </div>
        </Show>
    );
}

function SingleInstance(props: { instance: InstanceRow }) {
    const tone = () => reportingTone(props.instance.silent);
    return (
        <div class={cx('flex min-w-0 items-center gap-2', tone().text)} title={instanceTitle(props.instance)}>
            <Dot class={cx('size-1.5', tone().dot)} />
            <span class="truncate font-mono text-xs">{props.instance.name}</span>
            <Flags instances={[props.instance]} />
        </div>
    );
}

// An instance that sends no spans may be idle or gone; the hub cannot tell which, so it says when the latest span came. Once every instance
// retired, all it knows is that none came within its retire-after limit.
function LastSpan(props: { row: ApplicationRow }) {
    const app = useMonitorApp();
    const latest = () => Math.min(...props.row.instances.map(instance => instance.sinceReportMillis));
    const retired = () => {
        const seconds = app.store.view().retireAfterSeconds;
        return seconds === null ? 'a while ago' : `over ${shortHistorySpan(seconds)} ago`;
    };
    return (
        <Show when={props.row.instances.length} fallback={<span class="text-faint">{retired()}</span>}>
            <span class={props.row.reporting ? 'text-muted' : 'text-faint'}>{lastSpanAgo(latest(), !props.row.reporting)}</span>
        </Show>
    );
}

// Selecting an application narrows the scope to it; its details button opens it in the inspector instead.
function ApplicationListRow(props: { row: ApplicationRow; endsAt: number }) {
    const app = useMonitorApp();
    const health = () => HEALTH_TONE[applicationHealth(props.row)];
    const scale = () => Math.max(1, ...props.row.dispatchSeconds, ...props.row.failureSeconds);
    return (
        <tr class="group/row relative border-b transition-colors last:border-b-0 hover:bg-surface-2">
            <td class="max-w-0 py-2.5 pr-3 pl-4">
                <button
                    type="button"
                    onClick={() => app.setScope(props.row.name)}
                    title={`Narrow the scope to ${props.row.name}`}
                    class="flex max-w-full min-w-0 items-center gap-2 text-left font-semibold after:absolute after:inset-0 focus-visible:outline-none focus-visible:after:outline-2 focus-visible:after:outline-accent"
                >
                    <Dot class={health().dot} />
                    <span class="truncate">{props.row.name}</span>
                </button>
            </td>
            <td class="max-w-0 px-3">
                <InstanceSummary row={props.row} />
            </td>
            <td class={NUMBER}>
                <LastSpan row={props.row} />
            </td>
            <td class={NUMBER}>
                <Tweened value={props.row.dispatchesPerSecond} format={rate} />
            </td>
            <td class={NUMBER}>
                <Tweened value={props.row.entryPointsPerSecond} format={rate} />
            </td>
            <td class={cx(NUMBER, props.row.failures > 0 ? 'font-semibold text-failed' : 'text-faint')}>
                <Tweened value={props.row.failures} format={wholeCount} />
            </td>
            <td class={cx(NUMBER, 'text-muted')}>
                <Tweened value={props.row.heapBytes} format={bytes} />
            </td>
            <td class="py-2.5 pl-3">
                <div class="pointer-events-none relative h-7">
                    <Sparkline
                        values={props.row.dispatchSeconds}
                        endsAt={props.endsAt}
                        provisionalFrom={props.row.provisionalFrom}
                        width={SPARK_WIDTH}
                        height={28}
                        scale={scale()}
                        class="absolute inset-0 block h-7 w-full"
                        lineClass="stroke-muted stroke-[1.25]"
                        areaClass="fill-muted/10"
                    />
                    <Show when={props.row.failures > 0}>
                        <Sparkline
                            values={props.row.failureSeconds}
                            endsAt={props.endsAt}
                            width={SPARK_WIDTH}
                            height={28}
                            scale={scale()}
                            class="absolute inset-0 block h-7 w-full"
                            lineClass="stroke-transparent"
                            areaClass="fill-failed/55"
                        />
                    </Show>
                </div>
            </td>
            <td class="pr-2 text-right">
                <button
                    type="button"
                    aria-label={`Open ${props.row.name} in the inspector`}
                    title={`Open ${props.row.name} in the inspector`}
                    onClick={() => app.select({ type: 'application', name: props.row.name })}
                    class="relative z-1 inline-grid size-7 place-items-center rounded-md text-faint opacity-60 transition-[opacity,color,background-color] group-hover/row:opacity-100 hover:bg-sunken hover:text-fg focus-visible:opacity-100 pointer-coarse:opacity-100"
                >
                    <DetailsIcon />
                </button>
            </td>
        </tr>
    );
}
