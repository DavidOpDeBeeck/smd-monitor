import { Key } from '@solid-primitives/keyed';
import { createMemo, Show, type JSX } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { DeliveryChip } from '../../components/chips';
import { Glyph, HandlingGlyph } from '../../components/Glyph';
import { Panel } from '../../components/Panel';
import { animateReorder } from '../../components/reorder';
import { Tweened, TweenedDuration } from '../../components/tween';
import { cx } from '../../lib/cx';
import { eventDelays, groupSections, SLOW_DELAY_MILLIS } from '../../lib/dashboard';
import { count, duration, historySpan, lastPeriod, parseTypeKey, plural, shortName, wholeCount } from '../../lib/format';
import type { Brief, GroupStatistics, HandlingKey, HandlingStatistics } from '../../lib/types';
import { HeaderCell, NameCell, NUMBER_CELL, Row, Table } from './table';

export function GroupsPanel() {
    const app = useMonitorApp();
    const sections = createMemo(() => groupSections(app.scoped().groups));
    const delays = createMemo(() => eventDelays(app.scoped().handlings.values()));
    const asynchronous = () => [...app.scoped().handlings.values()].some(handling => handling.asynchronous);
    const expanded = (section: GroupStatistics) => app.overview.expandedGroups().has(section.key);
    const last = () => lastPeriod(app.store.view().windowSeconds);
    const other = () => app.scoped().other;
    let body!: HTMLTableSectionElement;
    animateReorder(
        () => body,
        () =>
            sections()
                .flatMap(section => [section.key, ...(expanded(section) ? section.handlings.map(handling => handling.key) : [])])
                .join(' '),
    );
    return (
        <Show when={sections().length > 0 || other().handlingHistory.started > 0}>
            <Panel title="Processing groups" note={last()}>
                <Table
                    body={element => (body = element)}
                    header={
                        <>
                            <HeaderCell class="pl-1 text-left">Group</HeaderCell>
                            <HeaderCell class="w-[64px] text-right" title={`Events handled in the ${last()}`}>
                                Handled
                            </HeaderCell>
                            <HeaderCell class="w-[56px] text-right" title={`Handlings that failed in the ${last()}`}>
                                Failed
                            </HeaderCell>
                            <Show when={asynchronous()}>
                                <HeaderCell
                                    class="w-[68px] text-right"
                                    title={`How long after its publication returned the group started handling an event, p95 over the ${last()}; for a group, its slowest event type`}
                                >
                                    Delay
                                </HeaderCell>
                            </Show>
                            <HeaderCell class="w-[68px] pr-1 text-right" title={`Approximate p95 handling time over the ${last()}`}>
                                p95
                            </HeaderCell>
                        </>
                    }
                >
                    <Key each={sections()} by="key">
                        {section => (
                            <>
                                <GroupHeaderRow
                                    section={section()}
                                    delays={delays()}
                                    asynchronous={asynchronous()}
                                    expanded={expanded(section())}
                                    onToggle={() => app.overview.toggleGroup(section().key)}
                                />
                                <Show when={expanded(section())}>
                                    <Key each={section().handlings} by="key">
                                        {handling => <GroupRowView handling={handling()} delay={delays().get(handling().key)} asynchronous={asynchronous()} />}
                                    </Key>
                                </Show>
                            </>
                        )}
                    </Key>
                    <Show when={other().handlingHistory.started > 0}>
                        <tr class="border-b border-dashed last:border-b-0" title="Handlings beyond smd.monitor.hub.max-groups and max-types, counted together">
                            <td class="max-w-0 py-[7px] pr-2 pl-1">
                                <span class="flex min-w-0 items-center gap-2 pl-5 text-muted">
                                    <HandlingGlyph />
                                    <span class="truncate text-sm italic">Other groups</span>
                                </span>
                            </td>
                            <Figures
                                statistics={other().handlingWindow}
                                asynchronous={asynchronous()}
                                delay={undefined}
                                noDelay={<span class="text-faint">—</span>}
                            />
                        </tr>
                    </Show>
                </Table>
            </Panel>
        </Show>
    );
}

function GroupHeaderRow(props: { section: GroupStatistics; delays: Map<HandlingKey, number>; asynchronous: boolean; expanded: boolean; onToggle: () => void }) {
    const sectionDelays = () => props.section.handlings.flatMap(handling => props.delays.get(handling.key) ?? []);
    const inline = () => !props.section.handlings.some(handling => handling.asynchronous);
    const slowestDelay = () => (sectionDelays().length ? Math.max(...sectionDelays()) : undefined);
    const idle = () => props.section.window.started === 0;
    const eventTypes = () => `${props.section.handlings.length} ${plural(props.section.handlings.length, 'event type')}`;
    return (
        <tr
            data-reorder={props.section.key}
            onClick={() => props.onToggle()}
            class={cx('cursor-pointer border-b transition-colors last:border-b-0 hover:bg-surface-2', props.expanded && 'bg-surface-2/50')}
        >
            <td class="max-w-0 py-[7px] pr-2 pl-1">
                <button
                    type="button"
                    aria-expanded={props.expanded}
                    aria-label={`${props.expanded ? 'Collapse' : 'Expand'} processing group ${props.section.processingGroup}, ${eventTypes()}`}
                    onClick={event => {
                        event.stopPropagation();
                        props.onToggle();
                    }}
                    class="flex w-full min-w-0 items-center gap-2 text-left"
                >
                    <span aria-hidden="true" class="w-3 flex-none text-xs text-muted">
                        {props.expanded ? '▾' : '▸'}
                    </span>
                    <HandlingGlyph />
                    <span class={cx('truncate font-mono text-sm font-semibold transition-colors duration-500', idle() && 'text-muted')}>
                        {props.section.processingGroup}
                    </span>
                    <span class="flex-none text-xs text-faint" title={`Handles ${eventTypes()}`}>
                        {props.section.handlings.length}
                    </span>
                    <DeliveryChip delivery={props.section.delivery} class="ml-auto flex-none" />
                </button>
            </td>
            <Figures
                statistics={props.section.window}
                asynchronous={props.asynchronous}
                delay={slowestDelay()}
                noDelay={
                    <Show when={inline()} fallback={<span class="text-faint">—</span>}>
                        <span class="text-xs text-faint" title="Handles its events while they are published">
                            inline
                        </span>
                    </Show>
                }
            />
        </tr>
    );
}

function GroupRowView(props: { handling: HandlingStatistics; delay: number | undefined; asynchronous: boolean }) {
    const app = useMonitorApp();
    const statistics = () => props.handling;
    const select = () => app.select({ type: 'type', key: statistics().typeKey });
    return (
        <Row
            reorder={statistics().key}
            onSelect={select}
            title={`Processing group ${statistics().processingGroup} handling ${statistics().messageType}: ${count(statistics().history.started)} handlings, ${count(statistics().history.failed)} failed, and p95 ${duration(statistics().history.p95Millis)} over the last ${historySpan(app.store.view().historySeconds)}`}
        >
            <NameCell indent onSelect={select} label={`Processing group ${statistics().processingGroup} handling ${shortName(statistics().messageType)}`}>
                <Glyph kind={parseTypeKey(statistics().typeKey).kind} />
                <span class={cx('truncate text-sm transition-colors duration-500', statistics().window.started === 0 && 'text-muted')}>
                    {shortName(statistics().messageType)}
                </span>
            </NameCell>
            <Figures statistics={props.handling.window} asynchronous={props.asynchronous} delay={props.delay} noDelay={<span class="text-faint">—</span>} />
        </Row>
    );
}

function Figures(props: { statistics: Brief; asynchronous: boolean; delay: number | undefined; noDelay: JSX.Element }) {
    return (
        <>
            <td class={cx(NUMBER_CELL, props.statistics.started === 0 && 'text-faint')}>
                <Tweened value={props.statistics.started} format={wholeCount} />
            </td>
            <td class={cx(NUMBER_CELL, props.statistics.failed ? 'font-semibold text-failed' : 'text-faint')}>
                <Tweened value={props.statistics.failed} format={value => (Math.round(value) ? wholeCount(value) : '—')} />
            </td>
            <Show when={props.asynchronous}>
                <td class={NUMBER_CELL}>
                    <Show when={props.delay !== undefined} fallback={props.noDelay}>
                        <span class={cx(props.delay! >= SLOW_DELAY_MILLIS && 'text-warning')}>
                            <Tweened value={props.delay!} format={duration} />
                        </span>
                    </Show>
                </td>
            </Show>
            <td class={cx(NUMBER_CELL, 'pr-1')}>
                <TweenedDuration millis={props.statistics.p95Millis} />
            </td>
        </>
    );
}
