import { Key } from '@solid-primitives/keyed';
import { createMemo, Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { Panel } from '../../components/Panel';
import { EYEBROW } from '../../components/text';
import { Tweened } from '../../components/tween';
import { count, historySpan, rate, shortHistorySpan } from '../../lib/format';
import { neighbourhood, NODE_HEIGHT, type NeighbourLink } from '../../lib/neighbourhood';
import { neighbourApplications, type NeighbourApplication } from '../../lib/scope';

const COLUMNS = 'grid grid-cols-[minmax(0,240px)_minmax(40px,1fr)_minmax(0,220px)_minmax(40px,1fr)_minmax(0,240px)] items-center';

function HueDot(props: { name: string }) {
    const app = useMonitorApp();
    return <i aria-hidden="true" class="size-2 flex-none rounded-full" style={{ background: `hsl(${app.applicationHue(props.name)} 55% 50%)` }} />;
}

// Where links arrive: the left edge of the application in scope, and of each application downstream.
function ArrowTip() {
    return (
        <svg aria-hidden="true" viewBox="0 0 6 8" class="absolute top-1/2 -left-[7px] h-2 w-1.5 -translate-y-1/2 fill-faint">
            <path d="M0 0 L6 4 L0 8 Z" />
        </svg>
    );
}

function NeighbourNode(props: { neighbour: NeighbourApplication; downstream: boolean }) {
    const app = useMonitorApp();
    const historySeconds = () => Math.max(1, app.store.view().historySeconds);
    return (
        <button
            type="button"
            title={`Narrow the scope to ${props.neighbour.name}: ${count(props.neighbour.invocations)} linked invocations over the last ${historySpan(app.store.view().historySeconds)}`}
            onClick={() => app.setScope(props.neighbour.name)}
            style={{ height: `${NODE_HEIGHT}px` }}
            class="relative flex w-full min-w-0 items-center gap-2 rounded-md border bg-surface-2 px-2.5 text-left text-sm hover:border-accent focus-visible:border-accent"
        >
            <Show when={props.downstream}>
                <ArrowTip />
            </Show>
            <HueDot name={props.neighbour.name} />
            <span class="min-w-0 flex-1 truncate font-semibold">{props.neighbour.name}</span>
            <span class="font-mono text-xs text-muted tabular-nums">
                <Tweened value={props.neighbour.invocations / historySeconds()} format={rate} />
            </span>
        </button>
    );
}

function NeighbourColumn(props: { neighbours: readonly NeighbourApplication[]; height: number; downstream: boolean; empty: string }) {
    return (
        <div class="flex min-w-0 flex-col justify-center gap-2" style={{ height: `${props.height}px` }}>
            <Key
                each={props.neighbours}
                by="name"
                fallback={
                    <span
                        style={{ height: `${NODE_HEIGHT}px` }}
                        class="flex items-center justify-center rounded-md border border-dashed px-2.5 text-xs text-faint"
                    >
                        {props.empty}
                    </span>
                }
            >
                {neighbour => <NeighbourNode neighbour={neighbour()} downstream={props.downstream} />}
            </Key>
        </div>
    );
}

// The links of one side as curves between the neighbours' rows and the application in scope; the drawing stretches across its column, its
// strokes keep their width.
function Links(props: { links: readonly NeighbourLink[]; height: number; centre: number; into: boolean }) {
    const app = useMonitorApp();
    const path = (link: NeighbourLink) =>
        props.into ? `M0 ${link.y} C50 ${link.y} 50 ${props.centre} 100 ${props.centre}` : `M0 ${props.centre} C50 ${props.centre} 50 ${link.y} 100 ${link.y}`;
    return (
        <svg
            aria-hidden="true"
            viewBox={`0 0 100 ${props.height}`}
            preserveAspectRatio="none"
            style={{ height: `${props.height}px` }}
            class="block w-full overflow-visible"
        >
            <Key each={props.links} by="name">
                {link => (
                    <path
                        d={path(link())}
                        fill="none"
                        stroke={`hsl(${app.applicationHue(link().name)} 55% 50% / 0.55)`}
                        stroke-width={link().width}
                        stroke-linecap="round"
                        vector-effect="non-scaling-stroke"
                        class="transition-[stroke-width] duration-500"
                    />
                )}
            </Key>
        </svg>
    );
}

// The applications linked to the application in scope by arrows and event routes across applications, with their average rate over the
// history: those whose messages caused its invocations on its left, those where its messages caused invocations on its right.
export function Neighbours(props: { application: string }) {
    const app = useMonitorApp();
    const neighbours = createMemo(() => neighbourApplications(app.store.view(), props.application));
    const layout = createMemo(() => neighbourhood(neighbours().upstream, neighbours().downstream));
    return (
        <Show when={neighbours().upstream.length > 0 || neighbours().downstream.length > 0}>
            <Panel title="Linked applications" note={`last ${shortHistorySpan(app.store.view().historySeconds)}`}>
                <div aria-label="Linked applications" class="overflow-x-auto">
                    <div class={`${COLUMNS} min-w-[640px] gap-y-2`}>
                        <span class={EYEBROW} title="Applications whose messages caused invocations of this application, handlings of their events included">
                            Upstream
                        </span>
                        <span />
                        <span />
                        <span />
                        <span class={EYEBROW} title="Applications where this application's messages caused invocations, handlings of its events included">
                            Downstream
                        </span>
                        <NeighbourColumn neighbours={neighbours().upstream} height={layout().height} downstream={false} empty="No application upstream" />
                        <Links links={layout().upstream} height={layout().height} centre={layout().centre} into />
                        <div class="relative flex min-w-0 items-center gap-2 rounded-md border border-accent/45 bg-surface px-3 py-2 shadow-card">
                            <Show when={neighbours().upstream.length > 0}>
                                <ArrowTip />
                            </Show>
                            <HueDot name={props.application} />
                            <span class="min-w-0 truncate font-semibold">{props.application}</span>
                        </div>
                        <Links links={layout().downstream} height={layout().height} centre={layout().centre} into={false} />
                        <NeighbourColumn neighbours={neighbours().downstream} height={layout().height} downstream empty="No application downstream" />
                    </div>
                </div>
            </Panel>
        </Show>
    );
}
