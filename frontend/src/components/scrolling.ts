import { createEffect, createSignal, on, onCleanup, type Accessor } from 'solid-js';
import { alignSeries, SECOND } from '../lib/throughput';
import { easeFrames, prefersReducedMotion } from './motion';

const RESCALE_TIME = 250;
// A frame further apart than this, after a reconnect say, redraws the series in place rather than scroll for that long.
const MAX_SCROLLED = 10 * SECOND;

export function animateSeries(chart: {
    scroller: () => SVGGElement;
    scaler: () => SVGGElement;
    endsAt: () => number | undefined;
    step: () => number;
    max: () => number;
    bottom: () => number;
    extent: () => number;
}) {
    let frame = 0;
    let shift = 0;
    let shiftedAt = -Infinity;
    // How long the series takes to scroll in what the latest frame added: the seconds it covers, so it moves at a step a second however far
    // apart the hub sends frames.
    let gap = SECOND;
    let shownMax: number | null = null;
    let drawnAt = 0;

    const draw = (now: number) => {
        const progress = Math.min(1, (now - shiftedAt) / gap);
        const max = chart.max();
        const eased = shownMax === null || prefersReducedMotion() ? max : shownMax + (max - shownMax) * (1 - Math.exp(-(now - drawnAt) / RESCALE_TIME));
        shownMax = Math.abs(eased - max) < max * 0.002 ? max : eased;
        drawnAt = now;
        chart.scroller().setAttribute('transform', `translate(${(shift * (1 - progress)).toFixed(2)} 0)`);
        chart.scaler().setAttribute('transform', `translate(0 ${chart.bottom()}) scale(1 ${(-chart.extent() / shownMax).toFixed(5)})`);
        frame = progress < 1 || shownMax !== max ? requestAnimationFrame(draw) : 0;
    };
    const redraw = () => {
        if (!frame) draw(performance.now());
    };

    createEffect(
        on(chart.endsAt, (time, previous) => {
            if (time !== undefined && previous !== undefined && time > previous && time - previous <= MAX_SCROLLED && !prefersReducedMotion()) {
                gap = time - previous;
                shift = (chart.step() * gap) / SECOND;
                shiftedAt = performance.now();
            }
            redraw();
        }),
    );
    createEffect(on([chart.max, chart.bottom, chart.extent], redraw, { defer: true }));
    onCleanup(() => cancelAnimationFrame(frame));
}

export function createSeriesTween(values: Accessor<readonly number[]>, endsAt: Accessor<number | undefined>): Accessor<readonly number[]> {
    const [shown, setShown] = createSignal<readonly number[]>(values());
    let shownEnd = endsAt();
    let cancel = () => {};
    createEffect(
        on(
            [values, endsAt],
            ([next, end]) => {
                cancel();
                const from = alignSeries(shown(), shownEnd, next, end);
                shownEnd = end;
                if (prefersReducedMotion() || from.every((value, index) => value === next[index])) {
                    setShown(next);
                    return;
                }
                cancel = easeFrames(eased => setShown(next.map((value, index) => from[index] + (value - from[index]) * eased)));
            },
            { defer: true },
        ),
    );
    onCleanup(() => cancel());
    return shown;
}
