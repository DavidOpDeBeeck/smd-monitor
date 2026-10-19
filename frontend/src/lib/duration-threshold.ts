// The latency axis of the tape threshold and the timeline scatter: log scale from 10 µs to 60 s, the top bound of the hub's latency histograms.
const LOW = -2;
const HIGH = Math.log10(60_000);

export const THRESHOLD_STOPS: readonly number[] = [
    0,
    ...[-2, -1, 0, 1, 2, 3].flatMap(power => [1, 2, 5].map(step => step * 10 ** power)).slice(1),
    10_000,
    20_000,
    60_000,
];

export const THRESHOLD_PRESETS: readonly number[] = [0, 1, 10, 100, 1000];

export function axisPosition(millis: number): number {
    if (millis <= 0) return 0;
    return Math.min(1, Math.max(0, (Math.log10(millis) - LOW) / (HIGH - LOW)));
}

export function stopAt(position: number): number {
    let nearest = THRESHOLD_STOPS[0];
    for (const stop of THRESHOLD_STOPS) if (Math.abs(axisPosition(stop) - position) < Math.abs(axisPosition(nearest) - position)) nearest = stop;
    return nearest;
}

export function stepStop(threshold: number, direction: 1 | -1): number {
    if (direction > 0) return THRESHOLD_STOPS.find(stop => stop > threshold) ?? THRESHOLD_STOPS.at(-1)!;
    return THRESHOLD_STOPS.findLast(stop => stop < threshold) ?? THRESHOLD_STOPS[0];
}

export const THRESHOLD_BUCKETS = 36;

// The buckets the hub counts too: equal widths on the axis, the outer ones also holding everything beyond it.
export function thresholdBuckets(values: readonly number[], buckets: number): number[] {
    const counts = new Array<number>(buckets).fill(0);
    for (const value of values) counts[Math.min(buckets - 1, Math.floor(axisPosition(value) * buckets))]++;
    return counts;
}
