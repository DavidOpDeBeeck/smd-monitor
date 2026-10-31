import { createMemo, Index, Show, type JSX } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { LinkButton } from '../../components/controls';
import { Empty } from '../../components/empty';
import { Glyph, HandlingGlyph } from '../../components/Glyph';
import { Panel } from '../../components/Panel';
import { Sparkline } from '../../components/Sparkline';
import { EYEBROW } from '../../components/text';
import { Tweened, TweenedDuration } from '../../components/tween';
import { TypeFilter } from '../../components/TypeFilter';
import { applicationRows } from '../../lib/applications';
import { cx } from '../../lib/cx';
import { activityRows, overallEventDelay, SLOW_DELAY_MILLIS, windowFigures } from '../../lib/dashboard';
import { duration, KIND_LABEL, KINDS, lastPeriod, parseHandlingKey, perSecond, share, wholeCount } from '../../lib/format';
import { ApplicationList } from './ApplicationList';
import { FailuresPanel } from './FailuresPanel';
import { GroupsPanel } from './GroupsPanel';
import { InstancesPanel } from './InstancesPanel';
import { Neighbours } from './Neighbours';
import { ThroughputChart } from './ThroughputChart';
import { TypeTable } from './TypeTable';

function Tile(props: { label: string; title: string; children: JSX.Element }) {
    return (
        <div title={props.title} class="relative flex min-w-0 flex-col overflow-hidden rounded-lg border bg-surface px-4 pt-3 pb-3.5">
            <span class={EYEBROW}>{props.label}</span>
            {props.children}
        </div>
    );
}

function TileValue(props: { tone?: string; children: JSX.Element }) {
    return (
        <strong class={cx('mt-0.5 truncate text-3xl leading-[1.1] font-extrabold tracking-[-1px] tabular-nums transition-colors duration-500', props.tone)}>
            {props.children}
        </strong>
    );
}

function TileDetail(props: { children: JSX.Element }) {
    return <span class="mt-0.5 truncate text-sm text-muted">{props.children}</span>;
}

// The same tiles in every scope, over the application in scope or summed over every application.
function Tiles() {
    const app = useMonitorApp();
    const store = app.store;
    const end = () => store.end();
    const totals = () => app.scoped().totals;
    const system = () => app.scope() === null;
    const windowSeconds = () => store.view().windowSeconds;
    const last = () => lastPeriod(windowSeconds());
    const summary = createMemo(() => windowFigures(totals(), windowSeconds()));
    const failedSpark = () => totals().failedPerSecond.slice(-windowSeconds());
    const delay = createMemo(() => overallEventDelay(app.scoped().applications, app.scoped().handlings.values()));
    return (
        <div class="grid grid-cols-[repeat(auto-fit,minmax(196px,1fr))] gap-4">
            <Tile
                label="Entry points"
                title={`Messages dispatched without a parent message, per second over the ${last()}: the work arriving from outside the observed buses`}
            >
                <TileValue>
                    <Tweened value={summary().enteredPerSecond} format={value => `${perSecond(value)}/s`} />
                </TileValue>
                <div class="mt-auto pt-2">
                    <Sparkline
                        values={totals().enteredPerSecond}
                        endsAt={end()}
                        provisionalFrom={app.scoped().provisionalFrom}
                        width={240}
                        height={28}
                        class="block h-7 w-full"
                        lineClass="stroke-muted stroke-[1.25]"
                        areaClass="fill-muted/12"
                    />
                </div>
            </Tile>
            <Tile
                label="Failure rate"
                title={`Failed invocations in the ${last()} per invocation started, dispatches and handlings together. A failure that fails its callers too counts at each of them.`}
            >
                <TileValue tone={summary().failed ? 'text-failed' : undefined}>
                    <Tweened value={summary().failureRate ?? 0} format={share} />
                </TileValue>
                <TileDetail>
                    <Tweened value={summary().failed} format={wholeCount} /> failed
                </TileDetail>
                <div class="mt-auto pt-2">
                    <Sparkline
                        values={failedSpark()}
                        endsAt={end()}
                        provisionalFrom={app.scoped().provisionalFrom}
                        width={240}
                        height={28}
                        class="block h-7 w-full"
                        lineClass="stroke-failed stroke-[1.25]"
                        areaClass="fill-failed/12"
                    />
                </div>
            </Tile>
            <Tile
                label="p95 latency"
                title={
                    system()
                        ? `Approximate p95 duration of each kind's dispatches that completed in the ${last()}, the highest of any application's`
                        : `Approximate p95 duration of each kind's dispatches that completed in the ${last()}`
                }
            >
                <dl class="mt-1.5 grid gap-1">
                    <Index each={KINDS}>
                        {kind => (
                            <div class="flex items-center gap-2">
                                <Glyph kind={kind()} />
                                <dt class="flex-1 text-sm text-muted">{KIND_LABEL[kind()]}</dt>
                                <dd class="font-mono text-md font-semibold tabular-nums">
                                    <TweenedDuration millis={summary().p95ByKind[kind()]} />
                                </dd>
                            </div>
                        )}
                    </Index>
                </dl>
            </Tile>
            <Show when={delay().asynchronous}>
                <Tile
                    label="p95 event delay"
                    title={`How long after its publication returned a processing group started handling an event, p95 over the handlings of the ${last()} that started after it returned. It grows when groups fall behind.`}
                >
                    <TileValue tone={delay().p95Millis !== null && delay().p95Millis! >= SLOW_DELAY_MILLIS ? 'text-warning' : undefined}>
                        <Show when={delay().p95Millis !== null} fallback="—">
                            <Tweened value={delay().p95Millis!} format={duration} />
                        </Show>
                    </TileValue>
                    <TileDetail>
                        <Show when={delay().slowest} fallback={`no asynchronous handling in the ${last()}`}>
                            {slowest => (
                                <LinkButton
                                    title={`Inspect ${parseHandlingKey(slowest()).processingGroup} handling ${parseHandlingKey(slowest()).typeKey}`}
                                    onClick={() => app.select({ type: 'type', key: parseHandlingKey(slowest()).typeKey })}
                                >
                                    <HandlingGlyph />
                                    {parseHandlingKey(slowest()).processingGroup}
                                </LinkButton>
                            )}
                        </Show>
                    </TileDetail>
                </Tile>
            </Show>
        </div>
    );
}

function ActivityPanel() {
    const app = useMonitorApp();
    const chart = createMemo(() => activityRows(app.scoped().totals));
    return (
        <Panel title="Activity" note={lastPeriod(app.store.view().historySeconds)}>
            <ThroughputChart
                rows={chart()}
                endsAt={app.store.end()}
                windowSeconds={app.store.view().windowSeconds}
                provisionalFrom={app.scoped().provisionalFrom}
            />
        </Panel>
    );
}

function MessageTypes() {
    const app = useMonitorApp();
    return (
        <Panel title="Message types" action={<TypeFilter selection={app.overview.types()} onChange={app.overview.setTypes} class="w-60" />}>
            <TypeTable />
        </Panel>
    );
}

// The system: its applications, then every application's message types and failures.
function SystemOverview() {
    const app = useMonitorApp();
    const rows = createMemo(() => applicationRows(app.scoped().applications, app.store.now(), app.store.view().windowSeconds));
    return (
        <Show
            when={rows().length}
            fallback={
                <Empty title="No application has reported yet" class="py-14">
                    Add <code class="font-mono">smd-opentelemetry</code> to an application and export its spans to this hub to see its messages here.
                </Empty>
            }
        >
            <Tiles />
            <section aria-label="Applications">
                <ApplicationList rows={rows()} endsAt={app.store.end()} />
            </section>
            <ActivityPanel />
            <MessageTypes />
            <FailuresPanel />
        </Show>
    );
}

function ApplicationOverview(props: { application: string }) {
    const app = useMonitorApp();
    return (
        <Show
            when={app.scoped().types.size > 0}
            fallback={
                <Empty title="No messages yet" class="py-14">
                    Commands, queries, and events of {props.application} appear here as soon as it dispatches them.
                </Empty>
            }
        >
            <Tiles />
            <Neighbours application={props.application} />
            <ActivityPanel />
            <MessageTypes />
            <div class="grid grid-cols-[minmax(0,1.7fr)_minmax(300px,1fr)] items-start gap-4 @max-4xl:grid-cols-1">
                <GroupsPanel />
                <FailuresPanel />
            </div>
            <InstancesPanel />
        </Show>
    );
}

export function OverviewView() {
    const app = useMonitorApp();
    return (
        <section aria-label="Overview" class="@container absolute inset-0 overflow-auto">
            <div class="mx-auto grid max-w-[1240px] gap-4 px-6 pt-[22px] pb-10 @max-md:px-4">
                <Show when={app.scope()} fallback={<SystemOverview />}>
                    {application => <ApplicationOverview application={application()} />}
                </Show>
            </div>
        </section>
    );
}
