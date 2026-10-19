import { createMemo, createSignal } from 'solid-js';
import { createStore } from 'solid-js/store';
import { liveFiltersActive, noLiveFilters, type LiveFilters, type LiveVisual } from '../../lib/live';
import type { TimelineSpan } from '../../lib/timeline';

// One stream drawn three ways: every visual applies the same filters, and the time span limits the lanes and the scatter.
export function createLiveState(scope: () => string | null) {
    const [visual, setVisual] = createSignal<LiveVisual>('lanes');
    const [span, setSpan] = createSignal<TimelineSpan>(30_000);
    const [filters, setFilters] = createStore<LiveFilters>(noLiveFilters());
    const [focusRequested, setFocusRequested] = createSignal(false);
    const active = createMemo(() => liveFiltersActive(filters, scope()));
    return {
        visual,
        setVisual,
        span,
        setSpan,
        filters,
        setFilters,
        active,
        clear: () => setFilters(noLiveFilters()),
        // The tape takes the keyboard focus once it shows.
        focusRequested,
        setFocusRequested,
    };
}
