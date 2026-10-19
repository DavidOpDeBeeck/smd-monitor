import { Key } from '@solid-primitives/keyed';
import { createMemo, Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { LinkButton } from '../../components/controls';
import { EYEBROW } from '../../components/text';
import { Tweened } from '../../components/tween';
import { count, historySpan, rate } from '../../lib/format';
import { neighbourApplications, type NeighbourApplication } from '../../lib/scope';

function NeighbourList(props: { label: string; title: string; neighbours: readonly NeighbourApplication[] }) {
    const app = useMonitorApp();
    const historySeconds = () => Math.max(1, app.store.view().historySeconds);
    return (
        <Show when={props.neighbours.length > 0}>
            <div class="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-1" title={props.title}>
                <span class={EYEBROW}>{props.label}</span>
                <Key each={props.neighbours} by="name">
                    {neighbour => (
                        <LinkButton
                            title={`Narrow the scope to ${neighbour().name}: ${count(neighbour().invocations)} linked dispatches over the last ${historySpan(app.store.view().historySeconds)}`}
                            onClick={() => app.setScope(neighbour().name)}
                            class="font-semibold"
                        >
                            {neighbour().name}
                            <span class="font-mono text-xs font-normal text-muted tabular-nums">
                                <Tweened value={neighbour().invocations / historySeconds()} format={rate} />
                            </span>
                        </LinkButton>
                    )}
                </Key>
            </div>
        </Show>
    );
}

// The applications linked to the application in scope by arrows across applications, with their average rate over the history.
export function Neighbours(props: { application: string }) {
    const app = useMonitorApp();
    const neighbours = createMemo(() => neighbourApplications(app.store.view(), props.application));
    return (
        <Show when={neighbours().upstream.length > 0 || neighbours().downstream.length > 0}>
            <section aria-label="Linked applications" class="flex flex-wrap gap-x-8 gap-y-2 rounded-lg border bg-surface px-4 py-2.5 text-sm">
                <NeighbourList label="Upstream" title="Applications whose messages caused messages of this application" neighbours={neighbours().upstream} />
                <NeighbourList label="Downstream" title="Applications whose messages this application's messages caused" neighbours={neighbours().downstream} />
            </section>
        </Show>
    );
}
