import { createMemo, on, Show, untrack } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { Segmented } from '../../components/controls';
import { lens, LENS_HOPS, LENS_LIMIT, type LensHops, type LensKey } from '../../lib/flow-lens';
import { count } from '../../lib/format';
import { layoutJourney } from '../../lib/journey-layout';
import { JourneyCanvas } from './JourneyCanvas';

const HOPS_LABEL: Record<string, string> = { 1: '1', 2: '2', 3: '3', all: 'All' };

// The lens on one card: what caused it to the left and what it led to to the right, within the chosen hops. It lays out again only when a card
// or step appears, so the cards keep their places while their figures move.
export function Journey(props: { focus: LensKey }) {
    const flow = useMonitorApp().flow;
    const structure = createMemo(() => `${flow.graph().signature}\n${props.focus}\n${flow.hops()}`);
    const view = createMemo(
        on(structure, () => {
            const current = lens(
                untrack(flow.graph),
                untrack(() => props.focus),
                untrack(flow.hops),
            );
            return { lens: current, journey: layoutJourney(current) };
        }),
    );
    return (
        <div class="relative flex min-h-0 flex-1 flex-col">
            <JourneyCanvas lens={view().lens} journey={view().journey} graph={flow.graph()} />
            <Show when={view().lens.truncated > 0}>
                <span
                    class="absolute top-2 right-3 z-3 rounded-md border bg-surface px-2 py-0.5 text-xs text-warning shadow-card"
                    title={`The lens draws at most ${LENS_LIMIT} cards, the nearest first`}
                >
                    {count(view().lens.truncated)} more cards not drawn
                </span>
            </Show>
        </div>
    );
}

export function HopsControl() {
    const flow = useMonitorApp().flow;
    return (
        <span class="ml-auto flex items-center gap-2 text-sm text-muted">
            Hops
            <Segmented<LensHops>
                label="Hops up- and downstream"
                value={flow.hops()}
                options={LENS_HOPS.map(hops => ({
                    value: hops,
                    label: HOPS_LABEL[hops],
                    title: hops === 'all' ? 'Follow the flow to its ends' : `Show ${hops} ${hops === 1 ? 'step' : 'steps'} up- and downstream`,
                }))}
                onChange={flow.setHops}
            />
        </span>
    );
}
