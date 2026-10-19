import type { Statistics } from './types';

export interface LatencySpread {
    floorMillis: number;
    ceilingMillis: number;
    p50Millis: number;
    p95Millis: number;
    p99Millis: number;
    maxMillis: number;
}

export interface LatencyScale {
    lowMillis: number;
    highMillis: number;
}

const MIN_SCALE_DECADES = 3;

export function latencySpread(statistics: Statistics): LatencySpread | null {
    const { floorMillis, p50Millis, p95Millis, p99Millis, maxMillis } = statistics;
    if (floorMillis === null || p50Millis === null || p95Millis === null || p99Millis === null || maxMillis === null) return null;
    return { floorMillis, ceilingMillis: maxMillis, p50Millis, p95Millis, p99Millis, maxMillis };
}

export function latencyScale(spreads: Iterable<LatencySpread>): LatencyScale | null {
    let low = Infinity;
    let high = 0;
    for (const spread of spreads) {
        low = Math.min(low, spread.floorMillis);
        high = Math.max(high, spread.ceilingMillis);
    }
    if (!high) return null;
    return { lowMillis: low, highMillis: Math.max(high, low * 10 ** MIN_SCALE_DECADES) };
}

export function scalePosition(scale: LatencyScale, millis: number): number {
    const low = Math.log10(scale.lowMillis);
    const position = (Math.log10(Math.max(millis, scale.lowMillis)) - low) / (Math.log10(scale.highMillis) - low);
    return Math.min(1, position);
}

export function scaleTicks(scale: LatencyScale): number[] {
    const ticks = [];
    for (let decade = Math.ceil(Math.log10(scale.lowMillis)); decade <= Math.floor(Math.log10(scale.highMillis)); decade++) ticks.push(10 ** decade);
    return ticks;
}
