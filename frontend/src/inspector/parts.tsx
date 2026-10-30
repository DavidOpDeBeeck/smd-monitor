import { Index, Show, type JSX } from 'solid-js';
import { useMonitorApp } from '../app/monitor-app';
import { KindChip, StatusChip } from '../components/chips';
import { CopyButton, LinkButton } from '../components/controls';
import { ActivityGlyph, Glyph } from '../components/Glyph';
import { EYEBROW } from '../components/text';
import { cx } from '../lib/cx';
import { activityName, ago, count, duration, KIND_LABEL, parseTypeKey, shortHistorySpan, shortName, STATUS_LABEL } from '../lib/format';
import { failureTypeOf } from '../lib/model';
import type { Activity, DispatchStatus, MessageKind, TypeKey } from '../lib/types';

const COPYABLE = 'max-w-full text-left font-mono text-xs text-muted [overflow-wrap:anywhere] hover:text-fg';
const RELATION = 'grid w-full grid-cols-[14px_minmax(0,1fr)_auto] items-center gap-2 rounded-md border px-2 py-1.5 text-left';

export function InspectorHeading(props: { kind: MessageKind; messageType: string; application: string; status?: DispatchStatus }) {
    const app = useMonitorApp();
    return (
        <div class="flex flex-col gap-1.5">
            <div class="flex gap-1.5">
                <KindChip kind={props.kind} />
                <Show when={props.status}>{status => <StatusChip status={status()}>{STATUS_LABEL[status()]}</StatusChip>}</Show>
            </div>
            <h2 class="text-2xl font-bold tracking-[-.4px] [overflow-wrap:anywhere]">{shortName(props.messageType)}</h2>
            <p class="text-sm text-muted">
                in{' '}
                <LinkButton
                    title={`Inspect ${props.application}`}
                    onClick={() => app.reveal({ type: 'application', name: props.application })}
                    class="font-semibold"
                >
                    {props.application}
                </LinkButton>
            </p>
            <CopyButton class={COPYABLE} title="Copy class name" text={() => props.messageType}>
                {props.messageType}
            </CopyButton>
        </div>
    );
}

export function CopyableId(props: { value: string; title: string }) {
    return (
        <CopyButton class={COPYABLE} title={props.title} text={() => props.value}>
            {props.value}
        </CopyButton>
    );
}

export function InspectorSection(props: { title: string; note?: string; children: JSX.Element }) {
    return (
        <section class="mt-5">
            <h3 class="mb-2 flex items-baseline justify-between gap-2 text-xs font-bold tracking-[.06em] uppercase">
                {props.title}
                <Show when={props.note}>
                    <small class="text-2xs font-medium tracking-normal text-faint normal-case">{props.note}</small>
                </Show>
            </h3>
            {props.children}
        </section>
    );
}

// A type linked to the inspected one; a type of another application names it, and selecting it narrows an application's scope to it.
export function TypeRelation(props: { typeKey: TypeKey; invocations: number; from: string }) {
    const app = useMonitorApp();
    const type = () => parseTypeKey(props.typeKey);
    const elsewhere = () => type().application !== props.from;
    return (
        <button
            type="button"
            title={`${KIND_LABEL[type().kind]} ${type().messageType} in ${type().application} · ${count(props.invocations)} over the last ${shortHistorySpan(app.store.view().historySeconds)}`}
            onClick={() => app.reveal({ type: 'type', key: props.typeKey })}
            class={cx(RELATION, 'bg-surface-2 hover:border-accent')}
        >
            <Glyph kind={type().kind} />
            <span class="flex min-w-0 items-baseline gap-1.5">
                <span class="truncate font-semibold">{shortName(type().messageType)}</span>
                <Show when={elsewhere()}>
                    <span class="truncate text-xs text-muted">in {type().application}</span>
                </Show>
            </span>
            <span class="font-mono text-sm text-muted">×{count(props.invocations)}</span>
        </button>
    );
}

export function StaticRelation(props: { mark: string; label: string; invocations: number }) {
    return (
        <div class={cx(RELATION, 'border-dashed')}>
            <span class="text-center font-bold text-muted">{props.mark}</span>
            <span class="truncate font-semibold">{props.label}</span>
            <span class="font-mono text-sm text-muted">×{count(props.invocations)}</span>
        </div>
    );
}

export function MessageRow(props: { message: Activity }) {
    const app = useMonitorApp();
    const now = () => app.store.now();
    return (
        <button
            type="button"
            onClick={() => app.select({ type: 'message', sequence: props.message.sequence })}
            class="grid w-full grid-cols-[12px_minmax(0,1fr)_auto_70px] items-center gap-2 border-b px-1 py-1.5 text-left hover:bg-surface-2"
        >
            <ActivityGlyph message={props.message} />
            <span class={cx('truncate font-medium', props.message.processingGroup && 'font-mono text-sm')}>{activityName(props.message)}</span>
            <span class={cx('font-mono text-sm', props.message.outcome.status === 'FAILED' ? 'text-failed' : 'text-muted')}>
                {props.message.outcome.status === 'FAILED' ? shortName(failureTypeOf(props.message) ?? 'Failed') : ago(now() - props.message.startedAtMillis)}
            </span>
            <span class="text-right font-mono text-sm">{duration(props.message.outcome.durationMillis)}</span>
        </button>
    );
}

export type Stat = { label: string; value: string; detail?: string; tone?: string };

export function Stats(props: { stats: Stat[] }) {
    return (
        <dl class="mb-2.5 grid auto-cols-fr grid-flow-col gap-3">
            <Index each={props.stats}>
                {stat => (
                    <div class="flex min-w-0 flex-col gap-px">
                        <dt class={EYEBROW}>{stat().label}</dt>
                        <dd class={cx('font-mono text-[15px] font-semibold whitespace-nowrap', stat().tone)}>
                            {stat().value}
                            <Show when={stat().detail}>
                                <span class="ml-1 text-2xs font-medium">{stat().detail}</span>
                            </Show>
                        </dd>
                    </div>
                )}
            </Index>
        </dl>
    );
}
