import { NO_FIGURES } from './state';
import { ratePerSecond } from './throughput';
import type { FlowTypeFigures, GroupStatistics, HandlingFigures, HandlingKey } from './types';

// A card's figures: its rate over the window, and its p95 and failures over the history, like the steps between the cards.
export interface CardFigures {
    perSecond: number;
    p95Millis: number | null;
    failed: number;
    started: number;
}

// A type's card before the flow query answered.
export const NO_FLOW_TYPE: FlowTypeFigures = { windowStarted: 0, history: NO_FIGURES.history };

export const typeFigures = (type: FlowTypeFigures, windowSeconds: number): CardFigures => ({
    perSecond: ratePerSecond(type.windowStarted, windowSeconds),
    p95Millis: type.history.p95Millis,
    failed: type.history.failed,
    started: type.history.started,
});

// A processing group handles each of its event types on its own; its p95 is the slowest of theirs. Its handlings' figures come with the
// handlings query, so they count as far as it answered.
export function groupFigures(group: GroupStatistics, figures: ReadonlyMap<HandlingKey, HandlingFigures>, windowSeconds: number): CardFigures {
    const histories = group.handlings.flatMap(handling => {
        const counted = figures.get(handling.key);
        return counted ? [counted.history] : [];
    });
    const p95s = histories.flatMap(history => (history.p95Millis === null ? [] : [history.p95Millis]));
    return {
        perSecond: ratePerSecond(group.window.started, windowSeconds),
        p95Millis: p95s.length ? Math.max(...p95s) : null,
        failed: histories.reduce((sum, history) => sum + history.failed, 0),
        started: histories.reduce((sum, history) => sum + history.started, 0),
    };
}
