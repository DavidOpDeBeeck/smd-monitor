import { Key } from '@solid-primitives/keyed';
import { createMemo, createSignal, on, createEffect, Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { LinkButton, TextButton } from '../../components/controls';
import { Empty } from '../../components/empty';
import { RetentionBoundary } from '../../components/RetentionBoundary';
import { hubRequest } from '../../lib/api';
import { causeExamples, causesByApplication, stageStarted } from '../../lib/failures';
import { ago, historySpan, plural, shortHistorySpan } from '../../lib/format';
import type { FailureCount } from '../../lib/types';
import { causes } from '../../lib/state';
import type { WireCauses, WireFailureCauses } from '../../lib/wire';
import { createHubQuery } from '../../store/hub-query';
import { FailureCauseCard } from './FailureCauseCard';
import { FailureLeaderboard } from './FailureLeaderboard';

// The view asks the hub for the causes it shows, ranked, and their retained occurrences, a page at a time.
const PAGE = 20;

function BigNumber(props: { value: string; label: string }) {
    return (
        <div class="flex flex-col">
            <strong class="text-3xl leading-[1.1] font-extrabold tracking-[-1px]">{props.value}</strong>
            <span class="text-sm text-muted">{props.label}</span>
        </div>
    );
}

function ApplicationHeading(props: { application: string; causes: readonly FailureCount[] }) {
    const app = useMonitorApp();
    return (
        <h2 class="mt-1.5 flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
            <LinkButton
                title={`Inspect ${props.application}`}
                onClick={() => app.select({ type: 'application', name: props.application })}
                class="text-md font-bold text-fg"
            >
                {props.application}
            </LinkButton>
            <span class="text-sm text-muted">
                {props.causes.length} {plural(props.causes.length, 'cause')} shown
            </span>
        </h2>
    );
}

// Every failure the hub counted in the scope, by the stage and exception type that failed (F1): the counts and their share come from the hub's
// counts, whatever it retains, and the chains and traces of the causes shown from what it retains.
export function FailuresView() {
    const app = useMonitorApp();
    const store = app.store;
    const [shown, setShown] = createSignal(PAGE);
    createEffect(on(app.scope, () => setShown(PAGE), { defer: true }));
    const query = createHubQuery<WireCauses>(store, () => hubRequest('causes', { scope: app.scope(), order: 'ranked', limit: shown() }), { kept: true });
    const ranked = createMemo(() => causes(query.answer()));
    const total = () => ranked().total;
    const visible = createMemo(() => ranked().causes.slice(0, shown()));
    const groups = createMemo(() => causesByApplication(visible(), app.scope()));
    const headed = () => groups().length > 1 || (app.scope() === null && app.system().applications.length > 1);
    const keys = createMemo(() => visible().map(cause => cause.key));
    const occurrences = createHubQuery<WireFailureCauses>(store, () => (keys().length ? hubRequest('failures', { cause: keys() }) : null), { kept: true });
    const examples = createMemo(() => new Map((occurrences.answer()?.causes ?? []).map(causeExamples).map(each => [each.key, each])));
    const failedSeries = () => app.scoped().totals.failedPerSecond;
    const failed = () => failedSeries().reduce((sum, value) => sum + value, 0);
    const lastFailure = () => {
        const series = failedSeries();
        const index = series.findLastIndex(value => value > 0);
        return index < 0 ? null : store.end() - (series.length - index) * 1000;
    };
    const other = () => app.scoped().other.failureHistory;
    const history = () => store.view().historySeconds;

    return (
        <section aria-label="Failures" class="absolute inset-0 overflow-auto">
            <div class="mx-auto max-w-[1240px] px-6 pt-[22px] pb-10">
                <header class="flex flex-wrap items-end gap-x-10 gap-y-4 border-b pb-5">
                    <BigNumber value={failed().toLocaleString()} label={`failed invocations, last ${shortHistorySpan(history())}`} />
                    <BigNumber value={total().toLocaleString()} label={plural(total(), 'cause')} />
                    <BigNumber value={lastFailure() === null ? '—' : ago(Math.max(0, store.secondNow() - lastFailure()!))} label="last failure" />
                    <p class="basis-full text-sm text-muted">
                        A cause is a stage, a dispatch or a handling, failing with one exception type. A failure counts at every message it failed, so one that
                        propagated to its callers counts at each of them; the chains of the retained occurrences show where it was raised, as far as the
                        retained traces tell. Exception messages and stack traces are not captured.
                    </p>
                    <RetentionBoundary applications={app.scoped().applications} class="basis-full text-sm text-warning" />
                </header>
                <Show
                    when={query.answer() === null || total() > 0 || other() > 0}
                    fallback={
                        <Empty title="No failures" class="pt-20 pb-14">
                            Every observed bus invocation completed without a failure in the last {historySpan(history())}. Completed does not mean committed.
                        </Empty>
                    }
                >
                    <div class="grid grid-cols-[minmax(0,1.6fr)_minmax(280px,1fr)] gap-6 pt-5 max-lg:grid-cols-1">
                        <div class="grid content-start gap-3.5">
                            <Key each={groups()} by="application">
                                {group => (
                                    <section aria-label={`Failures of ${group().application}`} class="grid content-start gap-3.5">
                                        <Show when={headed()}>
                                            <ApplicationHeading application={group().application} causes={group().causes} />
                                        </Show>
                                        <Key each={group().causes} by="key">
                                            {cause => (
                                                <FailureCauseCard
                                                    cause={cause()}
                                                    examples={examples().get(cause().key)}
                                                    started={stageStarted(cause(), app.scoped(), app.handlingFigures())}
                                                    end={store.end()}
                                                    windowSeconds={store.view().windowSeconds}
                                                    historySeconds={history()}
                                                    provisionalFrom={store.view().applications.get(cause().application)?.provisionalFrom ?? null}
                                                />
                                            )}
                                        </Key>
                                    </section>
                                )}
                            </Key>
                            <Show when={total() > shown()}>
                                <TextButton wide onClick={() => setShown(count => count + PAGE)}>
                                    Show {Math.min(PAGE, total() - shown())} more of {total() - shown()} causes
                                </TextButton>
                            </Show>
                            <Show when={other() > 0}>
                                <p
                                    class="rounded-lg border border-dashed px-4 py-3 text-sm text-muted"
                                    title="Causes beyond smd.monitor.hub.max-edges, counted together"
                                >
                                    <strong class="font-mono text-fg">×{other().toLocaleString()}</strong> failures of other causes, beyond the hub's cap on
                                    causes, over the last {shortHistorySpan(history())}
                                </p>
                            </Show>
                        </div>
                        <FailureLeaderboard />
                    </div>
                </Show>
            </div>
        </section>
    );
}
