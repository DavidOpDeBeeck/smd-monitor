import { createEffect, createSignal, on, onCleanup, type Accessor } from 'solid-js';
import { alignSeries, SECOND } from '../lib/throughput';
import { easeFrames, prefersReducedMotion } from './motion';

const RESCALE_TIME = 250;

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
    let shownMax: number | null = null;
    let drawnAt = 0;

    const draw = (now: number) => {
        const progress = Math.min(1, (now - shiftedAt) / SECOND);
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
            if (time !== undefined && previous !== undefined && time > previous && !prefersReducedMotion()) {
                shift = chart.step();
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
