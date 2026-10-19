import { createEffect, createMemo, createSignal, For, on, onCleanup, onMount, Show, untrack } from 'solid-js';
import { useMonitorApp, VIEW_LABEL, type MonitorApp, type PaletteScope } from '../app/monitor-app';
import { KeyHint } from '../components/controls';
import { Dot } from '../components/Dot';
import { Glyph } from '../components/Glyph';
import { Modal } from '../components/Modal';
import { NoMatches } from '../components/popover';
import { HEALTH_TONE } from '../components/tones';
import { applicationHealth, applicationRows, matchApplications } from '../lib/applications';
import { cx } from '../lib/cx';
import { activity, fetchAnswer, hubRequest } from '../lib/api';
import { activityName, count, KIND_LABEL, rate, shortName } from '../lib/format';
import { matchScore } from '../lib/search';
import type { Activity, MessageKind } from '../lib/types';
import type { WireFound } from '../lib/wire';

interface PaletteResult {
    group: 'Message types' | 'Messages' | 'Applications' | 'Actions';
    label: string;
    hint?: string;
    // Figures that line up in columns across the results, in place of the hint.
    stats?: string[];
    current?: boolean;
    kind?: MessageKind;
    // A dot in the tone of an application's health.
    dot?: string;
    run: () => void;
}

const MESSAGE_ID_PREFIX = /^[0-9a-f-]{4,}$/;
const FOUND_MESSAGES = 6;
// Typing settles before the hub is asked.
const FIND_DELAY_MILLIS = 120;

function actions(app: MonitorApp): Omit<PaletteResult, 'group'>[] {
    return [
        ...app.views.map((view, index) => ({ label: `Go to ${VIEW_LABEL[view]}`, hint: String(index + 1), run: () => app.show(view) })),
        { label: 'Show Live as lanes', run: () => app.showLive('lanes') },
        { label: 'Show Live as a latency scatter', run: () => app.showLive('scatter') },
        { label: 'Show Live as a tape', run: () => app.showLive('tape') },
        { label: app.store.paused() ? 'Resume live view' : 'Pause view', hint: 'Space', run: () => app.store.togglePause() },
        { label: 'Show the focus of the flow', hint: 'F', run: () => app.showFlowFocus() },
        { label: 'Toggle light and dark theme', run: () => app.toggleTheme() },
    ];
}

// The scopes: the whole system, then the applications of the hub with their health and traffic; the current scope is marked.
function applications(app: MonitorApp, query: string, limit: number): PaletteResult[] {
    const live = app.store.live();
    const current = app.scope();
    const results: PaletteResult[] = [];
    if (!query || matchScore('System', query) >= 0 || matchScore('All applications', query) >= 0) {
        results.push({ group: 'Applications', label: 'System', current: current === null, run: () => app.setScope(null) });
    }
    const rows = matchApplications(applicationRows(live.applications.values(), live.capturedAt, live.windowSeconds), query).slice(0, limit);
    for (const row of rows) {
        results.push({
            group: 'Applications',
            label: row.name,
            dot: HEALTH_TONE[applicationHealth(row)].dot,
            current: row.name === current,
            // Failures come first, so that rows without them keep the other figures against the right edge.
            stats: [
                row.failures ? `${count(row.failures)} failed` : '',
                `${row.reporting} of ${row.instances.length} reporting`,
                rate(row.dispatchesPerSecond),
            ],
            run: () => app.setScope(row.name),
        });
    }
    return results;
}

// The messages are those the hub found by the query as a message ID prefix, newest first.
function search(app: MonitorApp, scope: PaletteScope, query: string, messages: readonly Activity[]): PaletteResult[] {
    if (scope === 'applications') return applications(app, query, Infinity);
    // Every application's types and messages, each named with its application; without a query the scope's busiest types come first.
    const current = app.scope();
    const preferred = (application: string) => (!query && application === current ? 1 : 0);
    const results: PaletteResult[] = [];
    const types = [...app.system().types.values()]
        .map(type => ({ type, score: query ? matchScore(shortName(type.messageType), query) : type.history.started }))
        .filter(entry => entry.score >= 0)
        .sort((a, b) => preferred(b.type.application) - preferred(a.type.application) || b.score - a.score)
        .slice(0, query ? 8 : 6);
    for (const { type } of types) {
        results.push({
            group: 'Message types',
            kind: type.kind,
            label: shortName(type.messageType),
            hint: `${type.application} · ${KIND_LABEL[type.kind]} · ${count(type.history.started)} calls`,
            run: () => app.showType(type.key),
        });
    }
    for (const message of messages) {
        results.push({
            group: 'Messages',
            kind: message.kind,
            label: `${activityName(message)} · ${message.messageId.slice(0, 13)}`,
            hint: `${message.application} · ${message.outcome.status.toLowerCase()}`,
            run: () => app.reveal({ type: 'message', sequence: message.sequence, application: message.application }),
        });
    }
    results.push(...applications(app, query, query ? 6 : 4));
    for (const action of actions(app)) {
        if (!query || matchScore(action.label, query) >= 0) results.push({ group: 'Actions', ...action });
    }
    return results;
}

const PLACEHOLDER: Record<PaletteScope, string> = {
    everything: 'Jump to a message type, message ID, application, or action…',
    applications: 'Narrow to an application, or show the system…',
};

function PaletteContent(props: { scope: PaletteScope }) {
    const app = useMonitorApp();
    const [query, setQuery] = createSignal('');
    const [active, setActive] = createSignal(0);
    let input!: HTMLInputElement;
    let list!: HTMLDivElement;
    const [found, setFound] = createSignal<{ query: string; messages: Activity[] }>({ query: '', messages: [] });
    const normalized = createMemo(() => query().trim().toLowerCase());
    createEffect(
        on(normalized, current => {
            if (props.scope !== 'everything' || !MESSAGE_ID_PREFIX.test(current)) return;
            const timer = setTimeout(async () => {
                try {
                    const answer = await fetchAnswer<WireFound>(hubRequest('invocations', { messageIdPrefix: current, limit: FOUND_MESSAGES }));
                    if (answer && normalized() === current) setFound({ query: current, messages: answer.invocations.map(activity) });
                } catch (failure) {
                    console.warn('SMD monitor message search failed', failure);
                }
            }, FIND_DELAY_MILLIS);
            onCleanup(() => clearTimeout(timer));
        }),
    );
    // Applications refresh with every frame; the other results stay put while the query does.
    const applicationsChanged = createMemo(() => app.store.live().applications);
    const results = createMemo(() => {
        const current = normalized();
        const messages = found().query === current ? found().messages : [];
        applicationsChanged();
        return untrack(() => search(app, props.scope, current, messages));
    });
    createEffect(on(query, () => setActive(0)));
    createEffect(() => list.querySelector(`#palette-option-${active()}`)?.scrollIntoView({ block: 'nearest' }));
    onMount(() => input.focus());

    const choose = (index: number) => {
        const result = results()[index];
        if (!result) return;
        app.closePalette();
        result.run();
    };
    const keydown = (event: KeyboardEvent) => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            const step = event.key === 'ArrowDown' ? 1 : -1;
            setActive(index => (index + step + results().length) % Math.max(1, results().length));
        } else if (event.key === 'Enter') {
            event.preventDefault();
            choose(active());
        }
    };

    return (
        <>
            <input
                ref={input}
                autofocus
                type="text"
                placeholder={PLACEHOLDER[props.scope]}
                aria-label="Command palette"
                role="combobox"
                aria-expanded="true"
                aria-controls="palette-results"
                aria-activedescendant={results().length ? `palette-option-${active()}` : undefined}
                autocomplete="off"
                spellcheck={false}
                value={query()}
                onInput={event => setQuery(event.currentTarget.value)}
                onKeyDown={keydown}
                class="w-full border-b bg-transparent px-[18px] py-4 text-md outline-none"
            />
            <div
                ref={list}
                id="palette-results"
                role="listbox"
                class="grid max-h-[min(420px,55vh)] grid-cols-[14px_minmax(0,1fr)_repeat(3,auto)] content-start gap-x-2.5 overflow-auto p-1.5"
            >
                <For each={results()} fallback={<NoMatches class="col-span-full" />}>
                    {(result, index) => (
                        <>
                            <Show when={index() === 0 || results()[index() - 1].group !== result.group}>
                                <div role="presentation" class="col-span-full px-2.5 pt-2 pb-1 text-2xs font-bold tracking-[.07em] text-faint uppercase">
                                    {result.group}
                                </div>
                            </Show>
                            <button
                                type="button"
                                id={`palette-option-${index()}`}
                                role="option"
                                aria-selected={index() === active()}
                                tabIndex={-1}
                                onClick={() => choose(index())}
                                onPointerMove={() => setActive(index())}
                                class={cx(
                                    'col-span-full grid grid-cols-subgrid items-center rounded-md px-2.5 py-2 text-left',
                                    index() === active() && 'bg-accent/13',
                                )}
                            >
                                <Show
                                    when={result.kind}
                                    fallback={
                                        <Show when={result.dot} fallback={<span class="text-center font-bold text-faint">›</span>}>
                                            {dot => <Dot class={cx('justify-self-center', dot())} />}
                                        </Show>
                                    }
                                >
                                    {kind => <Glyph kind={kind()} />}
                                </Show>
                                <span class="flex min-w-0 items-baseline gap-2">
                                    <span class="truncate font-medium">{result.label}</span>
                                    <Show when={result.current}>
                                        <span class="text-xs text-muted">current</span>
                                    </Show>
                                </span>
                                <Show when={result.stats} fallback={<span class="col-[3/-1] text-right text-xs text-muted">{result.hint ?? ''}</span>}>
                                    {stats => (
                                        <For each={stats()}>
                                            {stat => <span class={cx('text-right text-xs text-muted tabular-nums', stat && 'pl-3')}>{stat}</span>}
                                        </For>
                                    )}
                                </Show>
                            </button>
                        </>
                    )}
                </For>
            </div>
            <footer class="flex gap-4 border-t px-3.5 py-2 text-xs text-muted">
                <KeyHint keys="↑↓">move</KeyHint>
                <KeyHint keys="Enter">open</KeyHint>
                <KeyHint keys="Esc">close</KeyHint>
            </footer>
        </>
    );
}

export function Palette() {
    const app = useMonitorApp();
    return (
        <Modal open={app.palette() !== null} onClose={() => app.closePalette()} label="Command palette" class="mt-[12vh] w-[min(620px,calc(100vw-32px))]">
            <Show when={app.palette()} keyed>
                {scope => <PaletteContent scope={scope} />}
            </Show>
        </Modal>
    );
}
