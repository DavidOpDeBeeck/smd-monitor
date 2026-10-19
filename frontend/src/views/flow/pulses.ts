import { onCleanup } from 'solid-js';
import { KIND_TONE } from '../../components/tones';
import type { MessageKind } from '../../lib/types';

const MAX_PULSES = 80;
const STAGGER = 160;
const ACTIVE_EDGE = 900;

interface Pulse {
    path: SVGPathElement;
    dot: SVGCircleElement;
    start: number;
    duration: number;
}

export function createPulses(layer: () => SVGGElement) {
    let pulses: Pulse[] = [];
    let frame = 0;
    const activeTimers = new Map<SVGPathElement, ReturnType<typeof setTimeout>>();

    const animate = (time: number) => {
        pulses = pulses.filter(pulse => {
            const progress = (time - pulse.start) / pulse.duration;
            if (progress >= 1 || !pulse.path.isConnected || time - pulse.start > 5000) {
                pulse.dot.remove();
                return false;
            }
            if (progress < 0) return true;
            const eased = progress < 0.5 ? 2 * progress * progress : 1 - (-2 * progress + 2) ** 2 / 2;
            const point = pulse.path.getPointAtLength(eased * pulse.path.getTotalLength());
            pulse.dot.setAttribute('cx', String(point.x));
            pulse.dot.setAttribute('cy', String(point.y));
            pulse.dot.setAttribute('opacity', String(Math.min(1, (1 - progress) * 4, progress * 8)));
            return true;
        });
        frame = pulses.length ? requestAnimationFrame(animate) : 0;
    };

    onCleanup(() => {
        cancelAnimationFrame(frame);
        activeTimers.forEach(timer => clearTimeout(timer));
    });

    return {
        spawn(path: SVGPathElement, kind: MessageKind, amount: number) {
            const start = performance.now();
            const length = path.getTotalLength();
            for (let index = 0; index < amount && pulses.length < MAX_PULSES; index++) {
                const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
                dot.setAttribute('r', '3.5');
                dot.setAttribute('opacity', '0');
                dot.setAttribute('class', `stroke-surface stroke-[1.5] ${KIND_TONE[kind].fill}`);
                layer().append(dot);
                pulses.push({ path, dot, start: start + index * STAGGER, duration: 600 + Math.min(600, length) });
            }
            path.dataset.active = '';
            clearTimeout(activeTimers.get(path));
            activeTimers.set(
                path,
                setTimeout(() => {
                    delete path.dataset.active;
                    activeTimers.delete(path);
                }, ACTIVE_EDGE),
            );
            if (!frame && pulses.length) frame = requestAnimationFrame(animate);
        },
    };
}
