import { Key } from '@solid-primitives/keyed';
import { createMemo, Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { Dot } from '../../components/Dot';
import { InstanceFlags } from '../../components/InstanceFlags';
import { reportingTone } from '../../components/tones';
import { Tweened, TweenedDuration } from '../../components/tween';
import { cx } from '../../lib/cx';
import { keyedApplication, lastPeriod, lastSpanAgo, perSecond, share } from '../../lib/format';
import { instanceRows } from '../../lib/instances';
import { Panel } from '../../components/Panel';
import { HeaderCell, NameCell, NUMBER_CELL, Row, Table } from './table';

export function InstancesPanel() {
    const app = useMonitorApp();
    const store = app.store;
    const rows = createMemo(() => instanceRows(app.scoped().instances, store.view().windowSeconds));
    const last = () => lastPeriod(store.view().windowSeconds);
    return (
        <Show when={rows().length > 1}>
            <Panel title="Instances">
                <Table
                    header={
                        <>
                            <HeaderCell class="pl-1 text-left">Instance</HeaderCell>
                            <HeaderCell class="w-[72px] text-right" title={`Dispatches per second over the ${last()}`}>
                                Rate /s
                            </HeaderCell>
                            <HeaderCell
                                class="w-[112px] text-right"
                                title={`Failures per invocation started in the ${last()}, dispatches and handlings together`}
                            >
                                Failure rate
                            </HeaderCell>
                            <HeaderCell class="w-[120px] text-right" title={`Approximate p95 over every dispatch completed in the ${last()}`}>
                                p95
                            </HeaderCell>
                            <HeaderCell class="w-[104px] pr-1 text-right" title="When the hub last received a report from it">
                                Reported
                            </HeaderCell>
                        </>
                    }
                >
                    <Key each={rows()} by={row => `${keyedApplication(row.application)}/${row.name}`}>
                        {row => {
                            const filter = () => app.filterTape(row().name);
                            return (
                                <Row onSelect={filter} title={`Show the invocations of ${row().name} on the tape`}>
                                    <NameCell onSelect={filter}>
                                        <Dot class={reportingTone(row().silent).dot} />
                                        <span class="truncate font-mono font-semibold">{row().name}</span>
                                        <InstanceFlags
                                            clockAhead={row().clockAhead}
                                            exportLagMillis={row().exportLagMillis}
                                            settleSeconds={store.view().settleSeconds}
                                        />
                                    </NameCell>
                                    <td class={NUMBER_CELL}>
                                        <Tweened value={row().dispatchesPerSecond} format={perSecond} />
                                    </td>
                                    <td class={cx(NUMBER_CELL, row().failureRate ? 'text-failed' : 'text-faint')}>
                                        <Show when={row().failureRate !== null} fallback="—">
                                            <Tweened value={row().failureRate!} format={share} />
                                        </Show>
                                    </td>
                                    <td class={NUMBER_CELL}>
                                        <TweenedDuration millis={row().p95Millis} />
                                    </td>
                                    <td class={cx(NUMBER_CELL, 'pr-1 text-xs', reportingTone(row().silent).text)}>
                                        {row().silent && 'last span '}
                                        {lastSpanAgo(Math.max(0, store.secondNow() - row().lastReportedAtMillis), row().silent)}
                                    </td>
                                </Row>
                            );
                        }}
                    </Key>
                </Table>
            </Panel>
        </Show>
    );
}
