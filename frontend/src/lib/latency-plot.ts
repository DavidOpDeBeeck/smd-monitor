import { axisPosition } from './duration-threshold';

export const LATENCY_TICKS: readonly number[] = [0.1, 1, 10, 100, 1000, 10_000, 60_000];

export const FAILED_LATENCY_TICKS: readonly number[] = [10, 1000];

const FAILED_SHARE = 0.25;
const SCATTER_INSET = 8;

export function scatterBaseline(height: number): number {
    return Math.round(height * (1 - FAILED_SHARE));
}

export function scatterY(latency: number, failed: boolean, height: number): number {
    const baseline = scatterBaseline(height);
    const position = axisPosition(latency);
    if (failed) return baseline + SCATTER_INSET + position * (height - baseline - 2 * SCATTER_INSET);
    return baseline - SCATTER_INSET - position * (baseline - 2 * SCATTER_INSET);
}

export interface PlottedPoint<T> {
    item: T;
    x: number;
    y: number;
}

export function nearestPoint<T>(points: readonly PlottedPoint<T>[], x: number, y: number, radius: number): PlottedPoint<T> | null {
    let nearest: PlottedPoint<T> | null = null;
    let best = radius * radius;
    for (const point of points) {
        const distance = (point.x - x) ** 2 + (point.y - y) ** 2;
        if (distance <= best) {
            best = distance;
            nearest = point;
        }
    }
    return nearest;
}
