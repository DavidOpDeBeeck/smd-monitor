import { createSignal } from 'solid-js';

const query = matchMedia('(prefers-reduced-motion: reduce)');
const [reduced, setReduced] = createSignal(query.matches);
query.addEventListener('change', event => setReduced(event.matches));

export const prefersReducedMotion = reduced;

const VALUE_EASE_MILLIS = 450;

const easeOut = (progress: number) => 1 - (1 - progress) ** 3;

export function easeFrames(onFrame: (eased: number) => void): () => void {
    const start = performance.now();
    let frame = requestAnimationFrame(function step(now) {
        const progress = Math.min(1, (now - start) / VALUE_EASE_MILLIS);
        onFrame(easeOut(progress));
        frame = progress < 1 ? requestAnimationFrame(step) : 0;
    });
    return () => cancelAnimationFrame(frame);
}
