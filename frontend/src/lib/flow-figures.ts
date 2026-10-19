import { ratePerSecond } from './throughput';
import type { GroupStatistics, TypeStatistics } from './types';

// A card's figures: its rate over the window, and its p95 and failures over the history, like the steps between the cards.
export interface CardFigures {
    perSecond: number;
    p95Millis: number | null;
    failed: number;
    started: number;
}

export const typeFigures = (type: TypeStatistics, windowSeconds: number): CardFigures => ({
    perSecond: ratePerSecond(type.window.started, windowSeconds),
    p95Millis: type.history.p95Millis,
    failed: type.history.failed,
    started: type.history.started,
});

// A processing group handles each of its event types on its own; its p95 is the slowest of theirs.
export function groupFigures(group: GroupStatistics, windowSeconds: number): CardFigures {
    const p95s = group.handlings.flatMap(handling => (handling.history.p95Millis === null ? [] : [handling.history.p95Millis]));
    return {
        perSecond: ratePerSecond(group.window.started, windowSeconds),
        p95Millis: p95s.length ? Math.max(...p95s) : null,
        failed: group.handlings.reduce((sum, handling) => sum + handling.history.failed, 0),
        started: group.handlings.reduce((sum, handling) => sum + handling.history.started, 0),
    };
}
