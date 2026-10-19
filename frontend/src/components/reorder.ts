import { createEffect, createMemo, on, type Accessor } from 'solid-js';
import { prefersReducedMotion } from './motion';

const FADE = { duration: 420, easing: 'ease-out' };

export function animateReorder(container: () => HTMLElement | undefined, order: Accessor<string>) {
    let previous: Map<string, number> | null = null;
    createEffect(
        on(createMemo(order), () => {
            const root = container();
            const items = root ? [...root.querySelectorAll<HTMLElement>('[data-reorder]')] : [];
            const before = previous;
            previous = new Map(items.map((item, index) => [item.dataset.reorder!, index]));
            if (!before || prefersReducedMotion()) return;
            const kept = items.filter(item => before.has(item.dataset.reorder!));
            const settled = longestOrderedRun(kept.map(item => before.get(item.dataset.reorder!)!));
            const moved = new Set(kept.filter((_, index) => !settled.has(index)));
            for (const item of items) {
                if (!before.has(item.dataset.reorder!)) item.animate([{ opacity: 0 }, { opacity: 1 }], FADE);
                else if (moved.has(item)) item.animate([{ opacity: 0.25 }, { opacity: 1 }], FADE);
            }
        }),
    );
}

// The rows that kept their relative order form the longest increasing run of their previous
// places; only the rows outside it changed rank, so only those are worth drawing attention to.
function longestOrderedRun(places: readonly number[]): Set<number> {
    const tails: number[] = [];
    const parents = new Array<number>(places.length).fill(-1);
    for (let index = 0; index < places.length; index++) {
        let low = 0;
        let high = tails.length;
        while (low < high) {
            const middle = (low + high) >> 1;
            if (places[tails[middle]] < places[index]) low = middle + 1;
            else high = middle;
        }
        if (low > 0) parents[index] = tails[low - 1];
        tails[low] = index;
    }
    const run = new Set<number>();
    for (let index = tails.at(-1) ?? -1; index >= 0; index = parents[index]) run.add(index);
    return run;
}
