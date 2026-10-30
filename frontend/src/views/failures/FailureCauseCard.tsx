import { Key } from '@solid-primitives/keyed';
import { Index, Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { LinkButton } from '../../components/controls';
import { GroupChip } from '../../components/chips';
import { ActivityGlyph, Glyph } from '../../components/Glyph';
import { Sparkline } from '../../components/Sparkline';
import { Tweened } from '../../components/tween';
import { cx } from '../../lib/cx';
import { raisedHere, type CauseExamples, type FailureChain } from '../../lib/failures';
import { ago, clock, KIND_LABEL, lastPeriod, percent, shortName, wholeCount } from '../../lib/format';
import type { FailureCount } from '../../lib/types';

function Chain(props: { chain: FailureChain; showCount: boolean }) {
    const app = useMonitorApp();
    // A chain across applications names the application wherever it enters one.
    const crosses = () => props.chain.steps.some(step => step.application !== props.chain.steps[0].application);
    const entersApplication = (index: number) =>
        crosses() && (index === 0 || props.chain.steps[index - 1].application !== props.chain.steps[index].application);
    const origin = () => props.chain.steps.length - 1;
    return (
        <li class="flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
            <Index each={props.chain.steps}>
                {(step, index) => (
                    <>
                        <Show when={index > 0}>
                            <span aria-hidden="true" class="text-faint">
                                →
                            </span>
                        </Show>
                        <Show when={entersApplication(index)}>
                            <span class="text-xs font-semibold text-muted" title={`Application ${step().application}`}>
                                {step().application}:
                            </span>
                        </Show>
                        <LinkButton
                            title={
                                (step().processingGroup
                                    ? `Processing group ${step().processingGroup} handling ${KIND_LABEL[step().kind]} ${step().messageType}`
                                    : `${KIND_LABEL[step().kind]} ${step().messageType}`) +
                                `, failed with ${step().failureType}` +
                                (index === origin() ? ', where the failure was raised' : '')
                            }
                            onClick={() => app.select({ type: 'type', key: step().typeKey })}
                            class={cx(
                                index === origin() ? 'text-failed' : 'text-fg',
                                index === props.chain.causeAt && 'font-semibold underline decoration-dotted underline-offset-4',
                            )}
                        >
                            <ActivityGlyph message={step()} />
                            {step().processingGroup ?? shortName(step().messageType)}
                        </LinkButton>
                    </>
                )}
            </Index>
            <span class="text-xs text-muted">{raisedHere(props.chain) ? 'raised here' : 'propagated'}</span>
            <Show when={props.showCount}>
                <span class="font-mono text-xs text-muted">×{props.chain.occurrences.toLocaleString()}</span>
            </Show>
        </li>
    );
}

// A stage's failures with one exception type: its counts over the history and the window, its share of the stage's invocations, when in the
// window it failed, and, of what the hub retains, the chains its failures took and the latest traces.
export function FailureCauseCard(props: {
    cause: FailureCount;
    examples: CauseExamples | undefined;
    started: number;
    end: number;
    windowSeconds: number;
    provisionalFrom: number | null;
}) {
    const app = useMonitorApp();
    const last = () => lastPeriod(props.windowSeconds);
    return (
        <article class="relative overflow-hidden rounded-lg border bg-surface py-3.5 pr-4 pl-5 shadow-card before:absolute before:inset-y-0 before:left-0 before:w-1 before:bg-failed">
            <header class="flex items-baseline justify-between gap-2.5">
                <h3 class="truncate text-lg font-bold text-failed" title={props.cause.failureType}>
                    {shortName(props.cause.failureType)}
                </h3>
                <span class="flex-none text-sm text-muted">
                    <strong class="font-mono text-md text-fg">
                        ×<Tweened value={props.cause.history} format={wholeCount} />
                    </strong>{' '}
                    · {props.cause.window ? `${props.cause.window.toLocaleString()} in the ${last()}` : `none in the ${last()}`}
                </span>
            </header>
            <p class="mt-1 text-muted">
                at{' '}
                <LinkButton class="font-semibold" onClick={() => app.select({ type: 'type', key: props.cause.typeKey })}>
                    <Glyph kind={props.cause.kind} />
                    {shortName(props.cause.messageType)}
                </LinkButton>{' '}
                {props.cause.handling ? `${KIND_LABEL[props.cause.kind].toLowerCase()} handling` : KIND_LABEL[props.cause.kind].toLowerCase()}
                <Show when={props.cause.processingGroup}>
                    {group => (
                        <>
                            {' '}
                            in <GroupChip name={group()} />
                        </>
                    )}
                </Show>
                <Show when={props.started > 0}>
                    {' '}
                    · {percent(props.cause.history, props.started)} of {props.started.toLocaleString()} {props.cause.handling ? 'handlings' : 'dispatches'}
                </Show>
            </p>
            <Sparkline
                values={props.cause.windowPerSecond}
                endsAt={props.end}
                provisionalFrom={props.provisionalFrom}
                width={320}
                height={22}
                class="mt-3 block h-[22px] w-full"
                lineClass="stroke-failed stroke-[1.5]"
                areaClass="fill-failed/12"
            />
            <Show when={props.examples} fallback={<p class="mt-2 text-xs text-faint">Asking the hub for its retained occurrences…</p>}>
                {examples => (
                    <Show
                        when={examples().retained > 0}
                        fallback={<p class="mt-2 text-xs text-muted">The hub retains none of its occurrences; its counts remain.</p>}
                    >
                        <div class="mt-2.5 rounded-md bg-sunken px-2.5 py-2">
                            <ul class="grid gap-1">
                                <Key each={examples().chains} by="key">
                                    {chain => <Chain chain={chain()} showCount={examples().chains.length > 1} />}
                                </Key>
                            </ul>
                        </div>
                        <div class="mt-2 flex flex-wrap items-center gap-1.5 text-xs">
                            <span class="text-muted">Latest traces</span>
                            <Key each={examples().latest} by="sequence">
                                {message => (
                                    <button
                                        type="button"
                                        title={`Open the trace of ${message().messageId}, ${ago(props.end - message().startedAtMillis)}`}
                                        onClick={() => app.openTrace(message().sequence)}
                                        class="rounded-md border border-line-strong bg-surface-2 px-2 py-0.5 font-mono text-xs font-medium hover:border-failed hover:text-failed"
                                    >
                                        {clock(message().startedAtMillis, false)}
                                    </button>
                                )}
                            </Key>
                        </div>
                    </Show>
                )}
            </Show>
        </article>
    );
}
