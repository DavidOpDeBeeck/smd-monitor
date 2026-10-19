import { createEffect, createMemo, createSignal, on, onCleanup, type Accessor } from 'solid-js';
import { fetchAnswer, requestKey, withParams, type HubRequest } from '../lib/api';
import type { MonitorStore } from './monitor-store';

interface Answer {
    hubSession: string;
}

interface Asked {
    request: HubRequest;
    hubSession: string;
    key: string;
}

// The view changes every frame; a request stays the same while it asks the same of the same run of the hub.
const sameRequest = (previous: Asked | null, next: Asked | null) => previous?.key === next?.key;

// What the hub answers to the request, asked again whenever the view's sequence moves: while paused, the answer stays. A request that changes
// clears the answer, or keeps the previous one until the next arrives when kept; null asks nothing. One request runs at a time, and the latest
// wish follows it.
export function createHubQuery<T extends Answer>(store: MonitorStore, request: Accessor<HubRequest | null>, options: { kept?: boolean } = {}) {
    const [answer, setAnswer] = createSignal<T | null>(null);
    // The hub retains nothing under the request, such as an evicted invocation.
    const [missing, setMissing] = createSignal(false);
    const asked = createMemo<Asked | null>(
        () => {
            const current = request();
            const hubSession = store.view().hubSession;
            return current && hubSession ? { request: current, hubSession, key: `${hubSession}|${requestKey(current)}` } : null;
        },
        null,
        { equals: sameRequest },
    );
    // The key of the latest request the hub answered, with an answer or as missing.
    const [answered, setAnswered] = createSignal<string | null>(null);
    let running: AbortController | null = null;
    let again = false;

    async function ask() {
        const current = asked();
        if (!current) return;
        if (running) {
            again = true;
            return;
        }
        const controller = new AbortController();
        running = controller;
        try {
            const result = await fetchAnswer<T>(current.request, controller.signal);
            if (controller.signal.aborted || asked()?.key !== current.key) return;
            if (result === null) {
                setMissing(true);
                setAnswered(current.key);
            } else if (result.hubSession === current.hubSession) {
                setMissing(false);
                setAnswer(() => result);
                setAnswered(current.key);
            }
        } catch (failure) {
            if (!controller.signal.aborted) console.warn('SMD monitor query failed', failure);
        } finally {
            if (running === controller) running = null;
        }
        if (again) {
            again = false;
            void ask();
        }
    }

    createEffect(
        on(
            () => asked()?.key,
            key => {
                running?.abort();
                running = null;
                again = false;
                setMissing(false);
                if (key === undefined || !options.kept) setAnswer(null);
                if (key !== undefined) void ask();
            },
        ),
    );
    createEffect(
        on(
            () => store.view().sequence,
            () => void ask(),
            { defer: true },
        ),
    );
    onCleanup(() => running?.abort());

    return {
        answer,
        missing,
        // The hub has not answered the current request yet; a kept answer is still the previous request's.
        loading: () => {
            const current = asked();
            return current !== null && answered() !== current.key;
        },
    };
}

export const PAGE_SIZE = 100;
export const LIST_MAX = 1000;

interface Page<Item> {
    items: Item[];
    next: string | null;
}

// A list the hub pages newest first: the first page loads with the request, scrolling loads the next until the list holds LIST_MAX, and whenever
// the view's sequence moves the list loads again from the top as far as it reached, so every item stays as the hub retains it. The answer of the
// last load from the top holds the counts.
export function createPagedList<T extends Answer, Item>(
    store: MonitorStore,
    request: Accessor<HubRequest | null>,
    page: (answer: T) => Page<Item>,
    keyOf: (item: Item) => string | number,
) {
    const [items, setItems] = createSignal<Item[]>([]);
    const [next, setNext] = createSignal<string | null>(null);
    const [answer, setAnswer] = createSignal<T | null>(null);
    const [loaded, setLoaded] = createSignal(false);
    const asked = createMemo<Asked | null>(
        () => {
            const current = request();
            const hubSession = store.view().hubSession;
            return current && hubSession ? { request: current, hubSession, key: `${hubSession}|${requestKey(current)}` } : null;
        },
        null,
        { equals: sameRequest },
    );
    let running: AbortController | null = null;
    let wanted = { top: false, more: false };

    async function load(kind: 'top' | 'more') {
        const current = asked();
        if (!current) return;
        if (running) {
            wanted = { ...wanted, [kind]: true };
            return;
        }
        if (kind === 'more' && (next() === null || items().length >= LIST_MAX)) return;
        const limit = kind === 'top' ? Math.min(LIST_MAX, Math.max(PAGE_SIZE, items().length)) : Math.min(PAGE_SIZE, LIST_MAX - items().length);
        const paged = withParams(current.request, kind === 'top' ? { limit } : { limit, after: next()! });
        const controller = new AbortController();
        running = controller;
        try {
            const result = await fetchAnswer<T>(paged, controller.signal);
            if (controller.signal.aborted || asked()?.key !== current.key || result === null || result.hubSession !== current.hubSession) return;
            const received = page(result);
            if (kind === 'top') {
                setItems(received.items);
                setAnswer(() => result);
                setLoaded(true);
            } else {
                const known = new Set(items().map(keyOf));
                setItems([...items(), ...received.items.filter(item => !known.has(keyOf(item)))]);
            }
            setNext(received.next);
        } catch (failure) {
            if (!controller.signal.aborted) console.warn('SMD monitor query failed', failure);
        } finally {
            if (running === controller) running = null;
        }
        const pending = wanted;
        wanted = { top: false, more: pending.top && pending.more };
        if (pending.top) void load('top');
        else if (pending.more) void load('more');
    }

    createEffect(
        on(
            () => asked()?.key,
            key => {
                running?.abort();
                running = null;
                wanted = { top: false, more: false };
                setNext(null);
                setLoaded(false);
                if (key === undefined) {
                    setItems([]);
                    setAnswer(null);
                } else {
                    // Starts over from the first page; the items of the previous request show until it arrives.
                    setItems(items().slice(0, PAGE_SIZE));
                    void load('top');
                }
            },
        ),
    );
    createEffect(
        on(
            () => store.view().sequence,
            () => void load('top'),
            { defer: true },
        ),
    );
    onCleanup(() => running?.abort());

    return {
        items,
        answer,
        // Whether the list holds the answer to the current request.
        loaded,
        // The hub has more than the list holds, and the list may load them.
        more: () => next() !== null && items().length < LIST_MAX,
        // The list stopped at LIST_MAX while the hub has more.
        full: () => next() !== null && items().length >= LIST_MAX,
        loadMore: () => void load('more'),
    };
}
