import { createEffect, createMemo, createSignal, on, onCleanup, untrack, type Accessor } from 'solid-js';
import { askHub, requestKey, withParams, type HubRequest } from '../lib/api';
import { withFirstPage } from '../lib/paging';
import { shareUnchanged } from '../lib/sharing';
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

function createAsked(store: MonitorStore, request: Accessor<HubRequest | null>) {
    return createMemo<Asked | null>(
        () => {
            const current = request();
            const hubSession = store.view().hubSession;
            return current && hubSession ? { request: current, hubSession, key: `${hubSession}|${requestKey(current)}` } : null;
        },
        null,
        { equals: sameRequest },
    );
}

// Runs again whenever the view's sequence moves while the page is visible: a hidden page asks nothing, and asks once it shows again, if the
// sequence moved meanwhile. While paused, the sequence stays.
function onEveryEpoch(store: MonitorStore, again: () => void) {
    let seen = untrack(() => store.view().sequence);
    const sequence = createMemo(() => (store.visible() ? store.view().sequence : null));
    createEffect(
        on(
            sequence,
            current => {
                if (current === null || current === seen) return;
                seen = current;
                again();
            },
            { defer: true },
        ),
    );
}

// What the hub answers to the request, asked again whenever the view's sequence moves: while paused, the answer stays. An answer the hub names
// with an ETag stays the same object until its content changes. A request that changes clears the answer, or keeps the previous one until the
// next arrives when kept; null asks nothing. One request runs at a time, and the latest wish follows it.
export function createHubQuery<T extends Answer>(store: MonitorStore, request: Accessor<HubRequest | null>, options: { kept?: boolean } = {}) {
    const [answer, setAnswer] = createSignal<T | null>(null);
    // The hub retains nothing under the request, such as an evicted invocation.
    const [missing, setMissing] = createSignal(false);
    const asked = createAsked(store, request);
    // The key of the latest request the hub answered, with an answer or as missing.
    const [answered, setAnswered] = createSignal<string | null>(null);
    // The ETag of the answer held for the current request.
    let eTag: string | null = null;
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
            const result = await askHub<T>(current.request, { signal: controller.signal, eTag });
            if (controller.signal.aborted || asked()?.key !== current.key) return;
            if (result.type === 'missing') {
                eTag = null;
                setMissing(true);
                setAnswered(current.key);
            } else if (result.type === 'unchanged') {
                setMissing(false);
                setAnswered(current.key);
            } else if (result.answer.hubSession === current.hubSession) {
                eTag = result.eTag;
                setMissing(false);
                setAnswer(() => result.answer);
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
                eTag = null;
                setMissing(false);
                if (key === undefined || !options.kept) setAnswer(null);
                if (key !== undefined) void ask();
            },
        ),
    );
    onEveryEpoch(store, () => void ask());
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

// A list the hub pages newest first: the first page loads with the request, and scrolling loads the next until the list holds LIST_MAX. Whenever
// the view's sequence moves, the first page loads again, so what arrived shows and the counts stay current: while the list is read from its top,
// the first page is the list; while it is read further down, the first page replaces the top of the list, and below it the list keeps what it
// loaded. An item that did not change stays the same object. The answer of the last first page holds the counts.
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
    const asked = createAsked(store, request);
    let running: AbortController | null = null;
    // The first page alone replaces the list, merges into its top, or the next page follows it.
    type Load = 'first' | 'top' | 'more';
    let wanted = new Set<Load>();
    // Whether the list is read from its top.
    let followed = true;
    // The ETag of the first page the list last loaded, which a load merging the first page into its top sends, so an unchanged one answers 304.
    let firstETag: string | null = null;

    async function load(kind: Load) {
        const current = asked();
        if (!current) return;
        if (running) {
            wanted.add(kind);
            return;
        }
        if (kind === 'more' && (next() === null || items().length >= LIST_MAX)) return;
        const paged = withParams(
            current.request,
            kind === 'more' ? { limit: Math.min(PAGE_SIZE, LIST_MAX - items().length), after: next()! } : { limit: PAGE_SIZE },
        );
        const controller = new AbortController();
        running = controller;
        try {
            const result = await askHub<T>(paged, { signal: controller.signal, eTag: kind === 'top' ? firstETag : null });
            if (controller.signal.aborted || asked()?.key !== current.key || result.type !== 'answer' || result.answer.hubSession !== current.hubSession)
                return;
            const received = page(result.answer);
            const held = { items: items(), next: next() };
            const known = new Set(held.items.map(keyOf));
            const listed =
                kind === 'more'
                    ? { items: [...held.items, ...received.items.filter(item => !known.has(keyOf(item)))], next: received.next }
                    : kind === 'top' && loaded() && !followed
                      ? withFirstPage(held, received, keyOf)
                      : received;
            if (kind !== 'more') {
                firstETag = result.eTag;
                setAnswer(() => result.answer);
                setLoaded(true);
            }
            setItems(shareUnchanged(held.items, listed.items, keyOf));
            setNext(listed.next);
        } catch (failure) {
            if (!controller.signal.aborted) console.warn('SMD monitor query failed', failure);
        } finally {
            if (running === controller) running = null;
        }
        // The first page before the page that follows; a first page that replaces the list makes merging another into its top needless.
        const following = (['first', 'top', 'more'] as const).find(each => wanted.has(each));
        wanted = following === 'more' || !wanted.has('more') ? new Set() : new Set(['more']);
        if (following) void load(following);
    }

    createEffect(
        on(
            () => asked()?.key,
            key => {
                running?.abort();
                running = null;
                wanted = new Set();
                firstETag = null;
                setNext(null);
                setLoaded(false);
                if (key === undefined) {
                    setItems([]);
                    setAnswer(null);
                } else {
                    // Starts over from the first page; the items of the previous request show until it arrives.
                    setItems(items().slice(0, PAGE_SIZE));
                    void load('first');
                }
            },
        ),
    );
    onEveryEpoch(store, () => void load('top'));
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
        // Whether the list is read from its top; a list read from its top again goes back to its first page.
        follow: (following: boolean) => {
            if (following && !followed && items().length > PAGE_SIZE) void load('first');
            followed = following;
        },
    };
}
