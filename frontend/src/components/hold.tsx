import { createMemo, createSignal, onCleanup, type Accessor } from 'solid-js';
import { cx } from '../lib/cx';
import { holdList } from '../lib/hold';

export function createListHold() {
    const [pressed, setPressed] = createSignal(false);
    const release = () => setPressed(false);
    window.addEventListener('pointerup', release);
    window.addEventListener('pointercancel', release);
    onCleanup(() => {
        window.removeEventListener('pointerup', release);
        window.removeEventListener('pointercancel', release);
    });
    return {
        held: pressed,
        press: () => setPressed(true),
    };
}

export type ListHold = ReturnType<typeof createListHold>;

export function createHeldListing<T, Listing extends { items: T[]; filters: string }>(
    hold: ListHold,
    key: (item: T) => string | number,
    current: () => Listing,
): Accessor<Listing> {
    return createMemo<Listing>(previous => {
        const next = current();
        if (!hold.held() || !previous || previous.filters !== next.filters) return next;
        return { ...next, items: holdList(previous.items, next.items, key) };
    });
}

export function NewItemsButton(props: { count: number; onClick: () => void; class: string }) {
    return (
        <button
            type="button"
            onClick={() => props.onClick()}
            class={cx('absolute left-1/2 z-3 -translate-x-1/2 rounded-full bg-accent px-3.5 py-[5px] font-semibold text-white shadow-lift', props.class)}
        >
            ↑ {props.count.toLocaleString()} new
        </button>
    );
}
