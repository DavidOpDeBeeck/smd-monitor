import { duration } from './format';
import { niceStep } from './traces';

// The part of a trace's time axis in view, in milliseconds from the start of the trace.
export interface TimeWindow {
    from: number;
    to: number;
}

// Zooming stops at a thousandth of the trace, and never below 10 microseconds.
const minimumSpan = (total: number) => Math.max(total / 1000, 0.01);

export const wholeTrace = (total: number): TimeWindow => ({ from: 0, to: total });

export const zoomed = (window: TimeWindow, total: number) => window.from > 0 || window.to < total;

function clampWindow(from: number, span: number, total: number): TimeWindow {
    const width = Math.min(total, Math.max(minimumSpan(total), span));
    const start = Math.min(total - width, Math.max(0, from));
    return { from: start, to: start + width };
}

// Zooms in (a factor below 1) or out around a point of the axis, given as its share of the track's width, which stays under the pointer.
export function zoomWindow(window: TimeWindow, total: number, anchor: number, factor: number): TimeWindow {
    const span = window.to - window.from;
    const at = window.from + anchor * span;
    const next = span * factor;
    return clampWindow(at - anchor * next, next, total);
}

// Moves the window by a share of its width, without leaving the trace.
export function panWindow(window: TimeWindow, total: number, share: number): TimeWindow {
    const span = window.to - window.from;
    return clampWindow(window.from + share * span, span, total);
}

export function windowTicks(window: TimeWindow, trackWidth: number, spacing: number): { left: string; label: string }[] {
    const span = window.to - window.from;
    const step = niceStep(span, Math.max(2, Math.min(8, Math.floor(Math.max(120, trackWidth) / spacing))));
    const ticks: { left: string; label: string }[] = [];
    for (let value = Math.ceil(window.from / step - 1e-9) * step; value <= window.to + step * 0.001; value += step) {
        ticks.push({ left: `${((value - window.from) / span) * 100}%`, label: value < step * 0.001 ? '0' : `+${duration(value)}` });
    }
    return ticks;
}
