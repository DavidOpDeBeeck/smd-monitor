import { Key } from '@solid-primitives/keyed';
import { createMemo, createSignal, For, Show } from 'solid-js';
import { Dot } from '../components/Dot';
import { Modal } from '../components/Modal';
import { FIELD_LABEL } from '../components/popover';
import { Tweened } from '../components/tween';
import { cx } from '../lib/cx';
import { bytes, clock, count, historySpan, percent } from '../lib/format';
import { filled, hubHealth, SHEDDING_LABEL, type ApplicationHealthRow } from '../lib/health';
import { useMonitorApp } from './monitor-app';

const AMOUNT = 'text-right font-mono text-fg tabular-nums';

function Bar(props: { share: number; warning?: boolean }) {
    return (
        <span class="block h-1.5 overflow-hidden rounded-[3px] bg-sunken">
            <span
                class={cx('block h-full transition-[width] motion-value', props.warning ? 'bg-warning' : 'bg-accent/70')}
                style={{ width: `${Math.max(1, props.share * 100)}%` }}
            />
        </span>
    );
}

function Use(props: { label: string; bytes: number; limitBytes: number; limitLabel?: string }) {
    const share = () => filled(props);
    return (
        <div class="grid grid-cols-[minmax(0,1fr)_auto] items-baseline gap-x-3 gap-y-1">
            <span class="text-muted">{props.label}</span>
            <span class={AMOUNT}>
                <Tweened value={props.bytes} format={bytes} />
                <span class="text-faint">
                    {' '}
                    {props.limitLabel ?? 'of'} {bytes(props.limitBytes)}
                </span>
            </span>
            <span class="col-span-2">
                <Bar share={share()} warning={share() >= 1} />
            </span>
        </div>
    );
}

function ApplicationHealthCard(props: { application: ApplicationHealthRow; linkHorizonSeconds: number; inScope: boolean }) {
    const application = () => props.application;
    return (
        <article class={cx('rounded-lg border bg-surface-2 p-3', props.inScope && 'border-accent/60')}>
            <header class="flex items-center gap-2">
                <Dot class={application().degraded ? 'bg-warning' : 'bg-query'} />
                <h3 class="truncate font-semibold">{application().name}</h3>
                <span class={cx('ml-auto text-xs', application().shedding === 'NONE' ? 'text-muted' : 'font-semibold text-warning')}>
                    Shedding: {SHEDDING_LABEL[application().shedding]}
                </span>
            </header>
            <div class="mt-2.5 grid grid-cols-2 gap-x-5 gap-y-2.5 text-xs max-sm:grid-cols-1">
                <Use label="Linking, of its fair share" bytes={application().join.bytes} limitBytes={application().join.limitBytes} />
                <Use label="Retained traces, of its fair share" bytes={application().specimens.bytes} limitBytes={application().specimens.limitBytes} />
                <dl class="grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-1 text-muted">
                    <dt>Counts</dt>
                    <dd class={AMOUNT}>{bytes(application().countingBytes)}</dd>
                    <dt>Effective horizon</dt>
                    <dd class={cx(AMOUNT, application().effectiveHorizonSeconds < props.linkHorizonSeconds && 'text-warning')}>
                        {application().effectiveHorizonSeconds} s <span class="text-faint">of {props.linkHorizonSeconds} s</span>
                    </dd>
                    <dt title="How many traces the Slowest reservoir keeps per type, and Failures per deepest failure">K, slowest and failures</dt>
                    <dd class={AMOUNT}>
                        {application().slowestK} · {application().failuresK}
                    </dd>
                    <dt>Retained invocations</dt>
                    <dd class={AMOUNT}>{count(application().retained)}</dd>
                    <dt>Complete since</dt>
                    <dd class={cx(AMOUNT, application().completeSince !== null && 'text-warning')}>
                        {application().completeSince === null ? 'it started' : clock(application().completeSince!, false)}
                    </dd>
                </dl>
                <div>
                    <span class={FIELD_LABEL}>Counts by reason</span>
                    <Show when={application().reasons.length > 0} fallback={<p class="mt-1 text-muted">Nothing refused, dropped, or let go.</p>}>
                        <dl class="mt-1 grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-0.5">
                            <For each={application().reasons}>
                                {reason => (
                                    <>
                                        <dt class={reason.losing ? 'text-warning' : 'text-muted'}>{reason.label}</dt>
                                        <dd class={AMOUNT}>{count(reason.count)}</dd>
                                    </>
                                )}
                            </For>
                        </dl>
                    </Show>
                </div>
            </div>
        </article>
    );
}

// The hub's health, opened from the status bar (FR7): its memory budget per product, the history its counts cover, and for every application
// what it takes against its fair share, how the hub degrades it, and what it counted per reason.
export function HealthPanel() {
    const app = useMonitorApp();
    const [open, setOpen] = createSignal(false);
    const health = createMemo(() => hubHealth(app.store.live()));
    const applications = createMemo(() => {
        const rows = health()?.applications ?? [];
        const scope = app.scope();
        return [...rows].sort(
            (a, b) => Number(b.name === scope) - Number(a.name === scope) || Number(b.degraded) - Number(a.degraded) || a.name.localeCompare(b.name),
        );
    });
    return (
        <Show when={health()}>
            {health => (
                <>
                    <button
                        type="button"
                        aria-haspopup="dialog"
                        onClick={() => setOpen(true)}
                        title="Open the hub's health"
                        class="flex items-center gap-1.5 rounded-sm underline decoration-line-strong decoration-dotted underline-offset-[3px] hover:text-fg hover:decoration-faint"
                    >
                        <Dot class={health().degraded ? 'bg-warning' : 'bg-query'} />
                        <span class={cx(health().degraded && 'text-warning')}>{health().degraded ? 'Hub degraded' : 'Hub healthy'}</span>·
                        <span class="tabular-nums">
                            <Tweened value={health().usedBytes} format={bytes} /> of {bytes(health().budgetBytes)}
                        </span>
                    </button>
                    <Modal open={open()} onClose={() => setOpen(false)} label="Hub health" class="mt-[8vh] w-[min(860px,calc(100vw-32px))]">
                        <div class="max-h-[80vh] overflow-auto p-5 text-sm">
                            <header class="flex items-baseline justify-between gap-3">
                                <h2 class="text-lg font-bold">Hub health</h2>
                                <button type="button" onClick={() => setOpen(false)} class="text-muted hover:text-fg">
                                    Close
                                </button>
                            </header>
                            <section class="mt-3 grid grid-cols-2 gap-x-6 gap-y-3 text-xs max-sm:grid-cols-1">
                                <div class="grid content-start gap-2.5">
                                    <span class={FIELD_LABEL}>Memory budget</span>
                                    <For each={health().products}>
                                        {product => <Use label={product.label} bytes={product.bytes} limitBytes={product.limitBytes} />}
                                    </For>
                                    <Use label="In all" bytes={health().usedBytes} limitBytes={health().budgetBytes} />
                                    <p class="text-muted">
                                        <Show when={health().maxHeapBytes !== null} fallback="The JVM sets no maximum heap.">
                                            The budget is {percent(health().budgetBytes, health().maxHeapBytes!)} of the maximum heap of{' '}
                                            {bytes(health().maxHeapBytes!)}.
                                        </Show>
                                    </p>
                                </div>
                                <div class="grid content-start gap-2">
                                    <span class={FIELD_LABEL}>History</span>
                                    <p class={cx(health().effectiveHistorySeconds < health().historySeconds ? 'text-warning' : 'text-muted')}>
                                        Counts cover the last {historySpan(health().effectiveHistorySeconds)} of {historySpan(health().historySeconds)}.
                                    </p>
                                    <Show when={health().foldsSooner}>
                                        <p class="text-warning">Seconds fold into 10-second buckets sooner to stay within the budget.</p>
                                    </Show>
                                    <Show when={health().refusedByApplicationLimit > 0}>
                                        <p class="text-warning">
                                            Refused {count(health().refusedByApplicationLimit)} spans of applications beyond smd.monitor.hub.max-applications.
                                        </p>
                                    </Show>
                                    <p class="leading-relaxed text-muted">
                                        Under memory pressure the hub first keeps fewer traces, then links within a shorter horizon, and only then counts a
                                        shorter history; an application above its fair share gives way first. Raise{' '}
                                        <code class="font-mono text-fg">smd.monitor.hub.memory</code> to keep more.
                                    </p>
                                </div>
                            </section>
                            <section class="mt-5 grid gap-2.5">
                                <span class={FIELD_LABEL}>Applications</span>
                                <Key each={applications()} by="name">
                                    {application => (
                                        <ApplicationHealthCard
                                            application={application()}
                                            linkHorizonSeconds={health().linkHorizonSeconds}
                                            inScope={application().name === app.scope()}
                                        />
                                    )}
                                </Key>
                            </section>
                        </div>
                    </Modal>
                </>
            )}
        </Show>
    );
}
