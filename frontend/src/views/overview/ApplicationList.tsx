import { Key } from '@solid-primitives/keyed';
import { Show } from 'solid-js';
import { Dot } from '../../components/Dot';
import { InstanceFlags } from '../../components/InstanceFlags';
import { Panel } from '../../components/Panel';
import { Sparkline } from '../../components/Sparkline';
import { Tweened } from '../../components/tween';
import { HEALTH_TONE } from '../../components/tones';
import { selectedApplication, useMonitorApp } from '../../app/monitor-app';
import { applicationHealth, instancesSummary, type ApplicationRow, type InstanceRow } from '../../lib/applications';
import { cx } from '../../lib/cx';
import { ago, bytes, capitalized, lastPeriod, perSecond, shortHistorySpan, wholeCount } from '../../lib/format';
import { HeaderCell, NameCell, NUMBER_CELL, Row, Table } from './table';

const SPARK_WIDTH = 160;

// The applications of the system, built like the other tables of the overview: the unit of a column in its header, a dash for no failures, and
// how the instances report in words, with the hub's memory for the application last.
export function ApplicationList(props: { rows: readonly ApplicationRow[]; endsAt: number }) {
    const app = useMonitorApp();
    const last = () => lastPeriod(app.store.view().windowSeconds);
    // The other columns take 752px, so the application's name keeps 176px at least; a narrower stage scrolls the table instead.
    return (
        <Panel title="Applications">
            <div class="overflow-x-auto">
                <div class="min-w-[928px]">
                    <Table
                        header={
                            <>
                                <HeaderCell class="pl-1 text-left">Application</HeaderCell>
                                <HeaderCell class="w-[160px] text-left" title="The instances the hub lists and whether they report">
                                    Instances
                                </HeaderCell>
                                <HeaderCell class="w-[112px] text-right" title={`Dispatches per second over the ${last()}`}>
                                    Dispatches /s
                                </HeaderCell>
                                <HeaderCell class="w-[120px] text-right" title={`Entry points per second over the ${last()}`}>
                                    Entry points /s
                                </HeaderCell>
                                <HeaderCell class="w-[80px] text-right" title={`Failed dispatches and handlings over the ${last()}`}>
                                    Failed
                                </HeaderCell>
                                <HeaderCell
                                    class="w-[104px] text-right"
                                    title="What the hub holds of the application: its retained invocations and the spans it links by"
                                >
                                    Hub memory
                                </HeaderCell>
                                <HeaderCell class="w-[176px] pl-4 text-left" title={`Dispatches and failures per second over the ${last()}`}>
                                    {capitalized(last())}
                                </HeaderCell>
                            </>
                        }
                    >
                        <Key each={props.rows} by={row => row.name}>
                            {row => <ApplicationListRow row={row()} endsAt={props.endsAt} />}
                        </Key>
                    </Table>
                </div>
            </div>
        </Panel>
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

// How the instances report, in words of one tone, with the flags of their setup; every instance names itself in the title.
function InstanceSummary(props: { row: ApplicationRow }) {
    const app = useMonitorApp();
    const health = () => applicationHealth(props.row);
    const retired = () => {
        const seconds = app.store.view().retireAfterSeconds;
        return `The hub no longer lists its instances: they sent no spans for ${seconds === null ? 'a while' : `over ${shortHistorySpan(seconds)}`}`;
    };
    return (
        <div
            class={cx('flex min-w-0 items-center gap-2 text-sm', health() === 'reporting' ? 'text-muted' : 'text-warning')}
            title={props.row.instances.length ? props.row.instances.map(instanceTitle).join('\n') : retired()}
        >
            <span class="truncate">{instancesSummary(props.row)}</span>
            <Flags instances={props.row.instances} />
        </div>
    );
}

// Selecting an application opens it in the inspector, as selecting a row of any table does; the inspector narrows the scope to it.
function ApplicationListRow(props: { row: ApplicationRow; endsAt: number }) {
    const app = useMonitorApp();
    const health = () => HEALTH_TONE[applicationHealth(props.row)];
    const scale = () => Math.max(1, ...props.row.dispatchSeconds, ...props.row.failureSeconds);
    const select = () => app.select({ type: 'application', name: props.row.name });
    return (
        <Row onSelect={select} selected={selectedApplication(app.selection()) === props.row.name}>
            <NameCell onSelect={select} label={`Application ${props.row.name}`}>
                <Dot class={health().dot} />
                <span class="truncate font-semibold">{props.row.name}</span>
            </NameCell>
            <td class="max-w-0 px-2">
                <InstanceSummary row={props.row} />
            </td>
            <td class={NUMBER_CELL}>
                <Tweened value={props.row.dispatchesPerSecond} format={perSecond} />
            </td>
            <td class={cx(NUMBER_CELL, !props.row.entryPointsPerSecond && 'text-faint')}>
                <Tweened value={props.row.entryPointsPerSecond} format={perSecond} />
            </td>
            <td class={cx(NUMBER_CELL, props.row.failures > 0 ? 'text-failed' : 'text-faint')}>
                <Tweened value={props.row.failures} format={value => (props.row.failures ? wholeCount(value) : '—')} />
            </td>
            <td class={cx(NUMBER_CELL, 'text-muted')}>
                <Tweened value={props.row.heapBytes} format={bytes} />
            </td>
            <td class="pr-1 pl-4">
                <div class="pointer-events-none relative h-[22px]">
                    <Sparkline
                        values={props.row.dispatchSeconds}
                        endsAt={props.endsAt}
                        provisionalFrom={props.row.provisionalFrom}
                        width={SPARK_WIDTH}
                        height={22}
                        scale={scale()}
                        class="absolute inset-0 block h-[22px] w-full"
                        lineClass="stroke-muted stroke-[1.25]"
                        areaClass="fill-muted/10"
                    />
                    <Show when={props.row.failures > 0}>
                        <Sparkline
                            values={props.row.failureSeconds}
                            endsAt={props.endsAt}
                            width={SPARK_WIDTH}
                            height={22}
                            scale={scale()}
                            class="absolute inset-0 block h-[22px] w-full"
                            lineClass="stroke-transparent"
                            areaClass="fill-failed/55"
                        />
                    </Show>
                </div>
            </td>
        </Row>
    );
}
