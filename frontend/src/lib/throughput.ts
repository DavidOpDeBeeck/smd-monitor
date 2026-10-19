export const SECOND = 1000;

export const seriesEnd = (now: number) => Math.floor(now / SECOND) * SECOND;

// A chart draws one value before its series off its left edge, so that value can scroll in when the series moves on.
export const scrollable = (values: readonly number[]) => [values[0] ?? 0, ...values];

export function alignSeries(shown: readonly number[], shownEnd: number | undefined, next: readonly number[], nextEnd: number | undefined): number[] {
    const shift = shownEnd === undefined || nextEnd === undefined ? 0 : Math.round((nextEnd - shownEnd) / SECOND) + shown.length - next.length;
    return next.map((value, index) => shown[index + shift] ?? value);
}

// Zero before the first frame, which sets no window.
export const ratePerSecond = (started: number, windowSeconds: number) => (windowSeconds ? started / windowSeconds : 0);

// Series that end at the same second, summed second by second; a shorter one starts later.
export function sumSeries(series: readonly (readonly number[])[]): number[] {
    const sums: number[] = new Array(Math.max(0, ...series.map(values => values.length))).fill(0);
    for (const values of series) {
        const offset = sums.length - values.length;
        for (let index = 0; index < values.length; index++) sums[offset + index] += values[index];
    }
    return sums;
}

// How many of the last seconds of a series ending at the end may still grow, as spans of them arrive that late: those from provisional-from on.
export function provisionalSeconds(end: number | undefined, provisionalFrom: number | null | undefined, length: number): number {
    if (end === undefined || provisionalFrom === null || provisionalFrom === undefined) return 0;
    return Math.max(0, Math.min(length, Math.floor((end - provisionalFrom) / SECOND)));
}
