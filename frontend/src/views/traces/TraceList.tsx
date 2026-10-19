import { Key } from '@solid-primitives/keyed';
import { createEffect, createSignal, on, Show, untrack } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { Empty } from '../../components/empty';
import { Loading, LoadingLine } from '../../components/loading';
import { cx } from '../../lib/cx';
import type { TraceItem } from '../../lib/traces';
import { LIST_MAX } from '../../store/hub-query';
import { TraceListItem } from './TraceListItem';

const FOLLOW_DISTANCE = 24;
// How close to its end the list loads the next page.
const LOAD_DISTANCE = 400;

function TraceSlot(props: { item: TraceItem; arrived: boolean }) {
    const [growing, setGrowing] = createSignal(untrack(() => props.arrived));
    return (
        <div
            role="listitem"
            data-key={props.item.key}
            class={cx(growing() && 'grid animate-grow-in')}
            onAnimationEnd={event => event.target === event.currentTarget && setGrowing(false)}
        >
            <div class={cx(growing() && 'min-h-0 overflow-hidden')}>
                <TraceListItem item={props.item} />
            </div>
        </div>
    );
}

// The list loads the next page as it scrolls near its end, while the hub has more and the list holds fewer than LIST_MAX.
export function TraceList(props: {
    items: TraceItem[];
    matching: number;
    total: number;
    listed: boolean;
    more: boolean;
    full: boolean;
    onNearEnd: () => void;
}) {
    const app = useMonitorApp();
    const traces = app.traces;
    let list!: HTMLDivElement;
    const [away, setAway] = createSignal(false);
    const [unseen, setUnseen] = createSignal(0);
    const following = () => list.scrollTop < FOLLOW_DISTANCE;

    let seen: number | null = null;
    const arrived = (item: TraceItem) => seen !== null && item.summary.root.sequence > seen;

    let anchor: { key: string; position: number } | null = null;
    let anchorFilters = '';
    const rows = () => [...list.children].filter((child): child is HTMLElement => child instanceof HTMLElement && child.dataset.key !== undefined);
    const rowTop = (row: HTMLElement) => row.getBoundingClientRect().top - list.getBoundingClientRect().top;
    const anchorRows = () => {
        const row = rows().find(candidate => rowTop(candidate) + candidate.offsetHeight > 0);
        anchor = row ? { key: row.dataset.key!, position: rowTop(row) + list.scrollTop } : null;
        anchorFilters = JSON.stringify(traces.filters);
    };
    createEffect(
        on(
            () => props.items,
            items => {
                const held = anchor;
                if (held && !following() && anchorFilters === JSON.stringify(traces.filters)) {
                    const row = rows().find(candidate => candidate.dataset.key === held.key);
                    if (row) list.scrollTop += rowTop(row) + list.scrollTop - held.position;
                    const index = items.findIndex(item => item.key === held.key);
                    const above = items.slice(0, Math.max(0, index)).filter(arrived).length;
                    if (above) setUnseen(count => count + above);
                }
                seen = Math.max(seen ?? 0, ...items.map(item => item.summary.root.sequence));
                loadWhenNearEnd();
                setAway(!following());
                anchorRows();
            },
        ),
    );

    createEffect(
        on(traces.revealed, () => {
            list.querySelector(`[data-root="${traces.selected()}"]`)?.scrollIntoView({ block: 'nearest' });
        }),
    );

    const keydown = (event: KeyboardEvent) => {
        if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
        const rows = [...list.querySelectorAll<HTMLButtonElement>('[data-trace-open]')];
        const index = rows.indexOf(document.activeElement as HTMLButtonElement);
        const next = rows[Math.max(0, Math.min(rows.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1)))];
        if (!next) return;
        event.preventDefault();
        next.focus();
        next.click();
    };
    function loadWhenNearEnd() {
        if (props.more && list.scrollTop + list.clientHeight > list.scrollHeight - LOAD_DISTANCE) props.onNearEnd();
    }
    const scrolled = () => {
        loadWhenNearEnd();
        setAway(!following());
        if (following()) setUnseen(0);
        anchorRows();
    };

    return (
        <>
            <div ref={list} role="list" aria-label="Traces" onKeyDown={keydown} onScroll={scrolled} class="min-h-0 flex-1 overflow-auto [overflow-anchor:none]">
                <Key
                    each={props.items}
                    by="key"
                    fallback={
                        <Show when={props.listed} fallback={<Loading title="Loading traces" class="py-14" />}>
                            <Empty title={props.total ? 'No matching traces' : 'No traces yet'} onClear={props.total ? traces.clear : undefined} class="py-14">
                                {props.total ? 'Widen the filters to see more.' : 'Traces appear as soon as messages are dispatched.'}
                            </Empty>
                        </Show>
                    }
                >
                    {item => <TraceSlot item={item()} arrived={arrived(item()) && following()} />}
                </Key>
                <Show when={props.more}>
                    <LoadingLine class="p-3">Loading more traces…</LoadingLine>
                </Show>
                <Show when={props.full}>
                    <div class="p-3 text-center text-sm text-muted">
                        Showing the first {LIST_MAX.toLocaleString()} of {props.matching.toLocaleString()} traces · filter to narrow
                    </div>
                </Show>
            </div>
            <Show when={unseen() > 0 && away()}>
                <button
                    type="button"
                    onClick={() => (list.scrollTop = 0)}
                    class="absolute top-2.5 left-1/2 z-3 -translate-x-1/2 rounded-full bg-accent px-3.5 py-[5px] font-semibold text-white shadow-lift"
                >
                    ↑ {unseen().toLocaleString()} new
                </button>
            </Show>
        </>
    );
}
