import { Key } from '@solid-primitives/keyed';
import { createMemo, Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { LinkButton } from '../../components/controls';
import { EmptyLine } from '../../components/empty';
import { ActivityGlyph } from '../../components/Glyph';
import { Panel } from '../../components/Panel';
import { animateReorder } from '../../components/reorder';
import { Tweened } from '../../components/tween';
import { cx } from '../../lib/cx';
import { hubRequest } from '../../lib/api';
import { causeExamples, lastOccurrence } from '../../lib/failures';
import { ago, lastPeriod, shortName, wholeCount } from '../../lib/format';
import { causes } from '../../lib/state';
import type { WireCauses, WireFailureCauses } from '../../lib/wire';
import { createHubQuery } from '../../store/hub-query';

const RECENT_CAUSES = 6;

// The causes that failed most in the window, counted and ranked by the hub whatever it retains (F2); a row opens the latest retained trace of its
// cause.
export function FailuresPanel() {
    const app = useMonitorApp();
    const store = app.store;
    const ranked = createHubQuery<WireCauses>(store, () => hubRequest('causes', { scope: app.scope(), order: 'recent', limit: RECENT_CAUSES }), {
        kept: true,
    });
    const answered = createMemo(() => causes(ranked.answer()));
    const recent = () => answered().causes;
    const keys = createMemo(() => recent().map(cause => cause.key));
    const query = createHubQuery<WireFailureCauses>(store, () => (keys().length ? hubRequest('failures', { cause: keys() }) : null), { kept: true });
    const latest = createMemo(() => new Map((query.answer()?.causes ?? []).map(causeExamples).map(examples => [examples.key, examples.latest[0]])));
    let list!: HTMLDivElement;
    animateReorder(
        () => list,
        () => keys().join(' '),
    );
    const last = () => lastPeriod(store.view().windowSeconds);
    // A cause names its application in the system scope.
    const named = () => app.scope() === null && app.system().applications.length > 1;

    return (
        <Panel
            title={`Failures, ${last()}`}
            note="by stage and exception type"
            action={
                <LinkButton class="text-xs font-semibold" onClick={() => app.show('failures')}>
                    All failures →
                </LinkButton>
            }
        >
            <div ref={list}>
                <Key
                    each={recent()}
                    by="key"
                    fallback={
                        <Show when={ranked.answer()}>
                            <EmptyLine class="py-2 text-left">No failure in the {last()}.</EmptyLine>
                        </Show>
                    }
                >
                    {cause => {
                        const example = () => latest().get(cause().key);
                        const at = () => lastOccurrence(cause(), answered().end ?? store.end());
                        return (
                            <button
                                type="button"
                                data-reorder={cause().key}
                                title={
                                    example()
                                        ? `Open the latest retained trace in which ${cause().processingGroup ?? shortName(cause().messageType)} failed with ${cause().failureType}`
                                        : `Inspect ${shortName(cause().messageType)}; the hub retains none of these failures`
                                }
                                onClick={() => {
                                    const message = example();
                                    if (message) app.openTrace(message.sequence);
                                    else app.select({ type: 'type', key: cause().typeKey });
                                }}
                                class="grid w-full grid-cols-[10px_minmax(0,1fr)_auto] items-center gap-x-2.5 border-b px-1 py-[7px] text-left last:border-b-0 hover:bg-surface-2"
                            >
                                <ActivityGlyph message={cause()} />
                                <span class="flex min-w-0 flex-col">
                                    <span class="flex min-w-0 items-baseline gap-1.5">
                                        <span class={cx('truncate font-semibold', cause().processingGroup && 'font-mono')}>
                                            {cause().processingGroup ?? shortName(cause().messageType)}
                                        </span>
                                        <Show when={cause().processingGroup}>
                                            <span class="truncate text-xs text-faint">{shortName(cause().messageType)}</span>
                                        </Show>
                                        <Show when={named()}>
                                            <span class="truncate text-xs text-faint">in {cause().application}</span>
                                        </Show>
                                    </span>
                                    <span class="truncate font-mono text-xs text-failed">✕ {shortName(cause().failureType)}</span>
                                </span>
                                <span class="flex flex-col items-end font-mono text-sm">
                                    <span class="font-semibold tabular-nums">
                                        ×<Tweened value={cause().window} format={wholeCount} />
                                    </span>
                                    <Show when={at()}>{time => <span class="text-xs text-muted">{ago(Math.max(0, store.secondNow() - time()))}</span>}</Show>
                                </span>
                            </button>
                        );
                    }}
                </Key>
            </div>
        </Panel>
    );
}
