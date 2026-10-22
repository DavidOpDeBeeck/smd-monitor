import { Key } from '@solid-primitives/keyed';
import { createMemo, Show } from 'solid-js';
import { useMonitorApp } from '../app/monitor-app';
import { DeliveryChip } from '../components/chips';
import { TextButton } from '../components/controls';
import { Empty, EmptyLine } from '../components/empty';
import { LoadingLine } from '../components/loading';
import { Sparkline } from '../components/Sparkline';
import { KIND_TONE } from '../components/tones';
import { cx } from '../lib/cx';
import { HandlingGlyph } from '../components/Glyph';
import { ago, count, duration, percent, rate, shortHistorySpan, shortName, STATUS_LABEL } from '../lib/format';
import { activity, hubRequest } from '../lib/api';
import { statistics, typeLink } from '../lib/state';
import { ratePerSecond } from '../lib/throughput';
import type { Activity, HandlingStatistics, TypeKey } from '../lib/types';
import type { WireRetainedType } from '../lib/wire';
import { createHubQuery } from '../store/hub-query';
import { InspectorHeading, InspectorSection, StaticRelation, Stats, TypeRelation } from './parts';

function InvocationRow(props: { message: Activity }) {
    const app = useMonitorApp();
    const now = () => app.store.now();
    return (
        <button
            type="button"
            onClick={() => app.select({ type: 'message', sequence: props.message.sequence })}
            class="grid w-full grid-cols-[minmax(0,1fr)_auto_70px] items-center gap-2 border-b px-1 py-1.5 text-left hover:bg-surface-2"
        >
            <span class={cx('truncate font-mono text-sm', props.message.outcome.status === 'FAILED' ? 'text-failed' : 'text-muted')}>
                {props.message.outcome.status === 'FAILED' ? `✕ ${shortName(props.message.outcome.failureType)}` : STATUS_LABEL[props.message.outcome.status]}
            </span>
            <span class="font-mono text-sm text-muted">{ago(now() - props.message.startedAtMillis)}</span>
            <span class="text-right font-mono text-sm">{duration(props.message.outcome.durationMillis)}</span>
        </button>
    );
}

function HandlingRow(props: { handling: HandlingStatistics }) {
    const completed = () => props.handling.history.completed;
    return (
        <div class="grid grid-cols-[14px_minmax(0,1fr)_auto_auto] items-center gap-2 rounded-md border px-2 py-1.5">
            <HandlingGlyph />
            <span class="flex min-w-0 items-center gap-1.5">
                <span class="truncate font-mono text-sm font-semibold" title={`Processing group ${props.handling.processingGroup}`}>
                    {props.handling.processingGroup}
                </span>
                <DeliveryChip delivery={props.handling.delivery} class="flex-none" />
            </span>
            <span class="font-mono text-sm whitespace-nowrap text-muted">
                ×{count(props.handling.history.started)}
                <Show when={props.handling.history.failed > 0}>
                    <span class="ml-1.5 text-failed" title={`${count(props.handling.history.failed)} of ${count(completed())} completed handlings failed`}>
                        {percent(props.handling.history.failed, completed())} failed
                    </span>
                </Show>
            </span>
            <span class="min-w-[92px] text-right font-mono text-sm whitespace-nowrap text-muted" title="Approximate p95 handling time">
                {props.handling.history.p95Millis === null ? 'p95 —' : `p95 ${duration(props.handling.history.p95Millis)}`}
            </span>
        </div>
    );
}

export function TypeInspector(props: { typeKey: TypeKey }) {
    const app = useMonitorApp();
    const store = app.store;
    const type = createMemo(() => app.system().types.get(props.typeKey));
    const query = createHubQuery<WireRetainedType>(store, () => hubRequest('types', { type: props.typeKey }));
    const retained = () => query.answer();
    const latest = createMemo(() => retained()?.latest.map(activity) ?? []);
    // A type of any application, with the arrows within its application and across applications, as its query answers them.
    const incoming = createMemo(() => retained()?.incoming.map(typeLink) ?? []);
    const outgoing = createMemo(() => retained()?.outgoing.map(typeLink) ?? []);
    const failureTypes = () => retained()?.failureTypes ?? [];
    // Every figure of the history, which the frame carries only the counts and p95 of.
    const history = createMemo(() => {
        const wire = retained()?.history;
        return wire ? statistics(wire) : null;
    });
    const provisionalFrom = () => store.view().applications.get(type()?.application ?? '')?.provisionalFrom;
    const completed = () => type()?.history.completed ?? 0;
    const handlings = () => [...app.system().handlings.values()].filter(handling => handling.typeKey === props.typeKey);

    return (
        <Show
            when={type()}
            fallback={
                <Empty class="h-full" title="Type not observed">
                    This type has no invocations in the current session.
                </Empty>
            }
        >
            {type => (
                <>
                    <InspectorHeading kind={type().kind} messageType={type().messageType} application={type().application} />
                    <InspectorSection
                        title="Activity"
                        note={`last ${shortHistorySpan(store.view().historySeconds)} · chart and rate: last ${shortHistorySpan(store.view().windowSeconds)}`}
                    >
                        <Stats
                            stats={[
                                { label: 'Calls', value: count(type().history.started) },
                                { label: 'Rate', value: rate(ratePerSecond(type().window.started, store.view().windowSeconds)) },
                                {
                                    label: 'Failed',
                                    value: count(type().history.failed),
                                    detail: type().history.failed ? percent(type().history.failed, completed()) : undefined,
                                    tone: type().history.failed ? 'text-failed' : '',
                                },
                            ]}
                        />
                        <div class="relative h-10">
                            <Sparkline
                                values={type().startedPerSecond}
                                endsAt={store.end()}
                                provisionalFrom={provisionalFrom()}
                                width={320}
                                height={40}
                                class="absolute inset-0 h-10 w-full"
                                lineClass={cx('stroke-[1.5]', KIND_TONE[type().kind].spark)}
                                areaClass={KIND_TONE[type().kind].sparkArea}
                            />
                            <Show when={type().failedPerSecond.some(Boolean)}>
                                <Sparkline
                                    values={type().failedPerSecond}
                                    endsAt={store.end()}
                                    width={320}
                                    height={40}
                                    class="absolute inset-0 h-10 w-full"
                                    lineClass="stroke-failed stroke-[1.5]"
                                />
                            </Show>
                        </div>
                        <Show when={failureTypes().length > 0}>
                            <ul class="mt-2.5 grid gap-1" title="Failure types among retained invocations">
                                <Key each={failureTypes()} by="failureType">
                                    {entry => (
                                        <li class="flex items-baseline justify-between gap-2 font-mono text-sm text-failed" title={entry().failureType}>
                                            <span class="truncate">✕ {shortName(entry().failureType)}</span>
                                            <span class="text-muted">×{count(entry().count)} retained</span>
                                        </li>
                                    )}
                                </Key>
                            </ul>
                        </Show>
                    </InspectorSection>
                    <InspectorSection
                        title="Latency"
                        note={completed() ? `avg ${duration(history()?.averageMillis)} · ${count(completed())} completed` : undefined}
                    >
                        <Stats
                            stats={[
                                { label: 'p50', value: duration(history()?.p50Millis) },
                                { label: 'p95', value: duration(history()?.p95Millis ?? type().history.p95Millis) },
                                { label: 'p99', value: duration(history()?.p99Millis) },
                                { label: 'Max', value: duration(history()?.maxMillis) },
                            ]}
                        />
                    </InspectorSection>
                    <InspectorSection title="Caused by" note={`last ${shortHistorySpan(store.view().historySeconds)}`}>
                        <div class="grid gap-[5px]">
                            <Show when={type().entryPoint}>
                                <StaticRelation mark="→" label="Entry point (no parent)" invocations={type().history.entered} />
                            </Show>
                            <Key each={incoming()} by="parent">
                                {link => <TypeRelation typeKey={link().parent} invocations={link().invocations} from={type().application} />}
                            </Key>
                        </div>
                    </InspectorSection>
                    <Show when={outgoing().length > 0}>
                        <InspectorSection title="Causes">
                            <div class="grid gap-[5px]">
                                <Key each={outgoing()} by="child">
                                    {link => <TypeRelation typeKey={link().child} invocations={link().invocations} from={type().application} />}
                                </Key>
                            </div>
                        </InspectorSection>
                    </Show>
                    <Show when={handlings().length > 0}>
                        <InspectorSection title="Handled by" note={`processing groups, last ${shortHistorySpan(store.view().historySeconds)}`}>
                            <div class="grid gap-[5px]">
                                <Key each={handlings()} by="key">
                                    {handling => <HandlingRow handling={handling()} />}
                                </Key>
                            </div>
                        </InspectorSection>
                    </Show>
                    <InspectorSection title="Recent invocations" note="retained">
                        <Key
                            each={latest()}
                            by="sequence"
                            fallback={
                                <Show
                                    when={retained()}
                                    fallback={
                                        <Show when={query.loading()}>
                                            <LoadingLine class="justify-start">Loading invocations…</LoadingLine>
                                        </Show>
                                    }
                                >
                                    <EmptyLine class="text-left">All invocations of this type have been evicted.</EmptyLine>
                                </Show>
                            }
                        >
                            {message => <InvocationRow message={message()} />}
                        </Key>
                    </InspectorSection>
                    <TextButton wide class="mt-[18px]" onClick={() => app.showTypeTraces(props.typeKey)}>
                        Show traces
                    </TextButton>
                    <TextButton wide class="mt-2" onClick={() => app.filterTape(shortName(type().messageType), type().application)}>
                        Show on tape
                    </TextButton>
                </>
            )}
        </Show>
    );
}
