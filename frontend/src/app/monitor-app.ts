import { createContext, createEffect, createMemo, createSignal, on, onCleanup, untrack, useContext } from 'solid-js';
import { applicationHue, applicationHues, scopeFromSearch, scopeSearch } from '../lib/applications';
import { readPreference, writePreference } from '../lib/browser';
import type { TypeSort } from '../lib/dashboard';
import { lensGraph, type LensHops, type LensKey } from '../lib/flow-lens';
import { parseTypeKey } from '../lib/format';
import type { LiveVisual } from '../lib/live';
import { fetchAnswer, hubRequest } from '../lib/api';
import { scopedState } from '../lib/scope';
import { flowEdges, handlingFigures } from '../lib/state';
import { traceDetail, type PinnedTrace, type TraceSummary } from '../lib/traces';
import { everyType, type TypeSelection } from '../lib/type-selection';
import type { TypeKey } from '../lib/types';
import type { WireFlow, WireHandlings, WireTraceDetail } from '../lib/wire';
import { createHubQuery } from '../store/hub-query';
import type { MonitorStore } from '../store/monitor-store';
import { createLiveState } from '../views/live/live-state';
import { createTracesState } from '../views/traces/traces-state';

export const VIEWS = ['overview', 'flow', 'traces', 'failures', 'live'] as const;
export type ViewName = (typeof VIEWS)[number];

export const VIEW_LABEL: Record<ViewName, string> = {
    overview: 'Overview',
    flow: 'Flow',
    traces: 'Traces',
    failures: 'Failures',
    live: 'Live',
};

// A message names its application when it is known, so revealing it can move the scope there.
type Selection = { type: 'type'; key: TypeKey } | { type: 'message'; sequence: number; application?: string } | { type: 'application'; name: string };
// The palette jumps to anything, or only to another scope.
export type PaletteScope = 'everything' | 'applications';

export const selectedTypeKey = (selection: Selection | null) => (selection?.type === 'type' ? selection.key : null);
export const selectedSequence = (selection: Selection | null) => (selection?.type === 'message' ? selection.sequence : null);
export const selectedApplication = (selection: Selection | null) => (selection?.type === 'application' ? selection.name : null);

export type MonitorApp = ReturnType<typeof createMonitorApp>;

// The view the URL names, or the first view.
function viewFromHash(): ViewName {
    const name = location.hash.replace(/^#\/?/, '');
    return VIEWS.find(candidate => candidate === name) ?? VIEWS[0];
}

export function applyStoredTheme() {
    const theme = readPreference('theme');
    if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
}

export function createMonitorApp(store: MonitorStore, stage: () => HTMLElement) {
    const [view, setView] = createSignal<ViewName>(viewFromHash());
    const [scope, setScopeSignal] = createSignal<string | null>(scopeFromSearch(location.search));
    const [selection, setSelection] = createSignal<Selection | null>(null);
    const [pins, setPins] = createSignal<ReadonlyMap<string, PinnedTrace>>(new Map());
    const [seenFailures, setSeenFailures] = createSignal<ReadonlyMap<string, number>>(new Map());
    const [palette, setPalette] = createSignal<PaletteScope | null>(null);
    const [typeSort, setTypeSort] = createSignal<TypeSort>('name');
    const [typeFilter, setTypeFilter] = createSignal<TypeSelection>(everyType());
    const [expandedGroups, setExpandedGroups] = createSignal<ReadonlySet<string>>(new Set());
    // The card the flow's lens is on, or null before one is chosen; the lens keeps it whatever the scope.
    const [flowFocus, setFlowFocus] = createSignal<LensKey | null>(null);
    const [flowHops, setFlowHops] = createSignal<LensHops>(3);
    // Moves on when the lens should bring its focus into view.
    const [flowReveal, setFlowReveal] = createSignal(0);
    const traces = createTracesState(scope);
    const live = createLiveState(scope);

    // The URL names the scope and the view.
    history.replaceState(null, '', `${location.pathname}${scopeSearch(scope())}${location.hash && `#/${view()}`}`);
    const showRoute = () => {
        const next = viewFromHash();
        setView(next);
        if (location.hash && location.hash !== `#/${next}`) history.replaceState(null, '', `#/${next}`);
    };
    const restore = () => {
        const next = scopeFromSearch(location.search);
        setScopeSignal(next);
        showRoute();
    };
    window.addEventListener('popstate', restore);
    onCleanup(() => window.removeEventListener('popstate', restore));

    const system = createMemo(() => scopedState(store.view(), null));
    const scoped = createMemo(() => (scope() === null ? system() : scopedState(store.view(), scope())));
    // Only the flow view draws how messages flow, so only it asks the hub for the edges; the graph holds the frame's types and groups meanwhile.
    const flowQuery = createHubQuery<WireFlow>(store, () => (view() === 'flow' ? hubRequest('flow') : null), { kept: true });
    const flowGraph = createMemo(() => lensGraph(system().applications, flowEdges(flowQuery.answer())));
    // What each handling counted is only an expanded group's, the failures view's, and the flow view's, so only they ask the hub for it.
    const handlingsQuery = createHubQuery<WireHandlings>(
        store,
        () => {
            if (view() === 'flow') return hubRequest('handlings');
            if (view() === 'failures' || (view() === 'overview' && expandedGroups().size > 0)) return hubRequest('handlings', { scope: scope() });
            return null;
        },
        { kept: true },
    );
    const figures = createMemo(() => handlingFigures(handlingsQuery.answer()));
    const hues = createMemo(() => applicationHues(system().applications.map(application => application.name)));
    // The failures the hub received of each application in scope since its failures were last shown; seen per application, so looking at one
    // application's failures does not count another's as seen. The live state counts them, so the badge moves while the view is paused.
    const liveScoped = createMemo(() => scopedState(store.live(), scope()).applications);
    const unseenFailures = createMemo(() =>
        liveScoped().reduce((sum, application) => sum + Math.max(0, application.failedReceived - (seenFailures().get(application.name) ?? 0)), 0),
    );
    createEffect(() => {
        if (view() !== 'failures' || unseenFailures() === 0) return;
        const next = new Map(seenFailures());
        for (const application of liveScoped()) next.set(application.name, application.failedReceived);
        setSeenFailures(next);
    });
    const hubSession = createMemo(() => store.view().hubSession);
    createEffect(
        on(
            hubSession,
            (_, previous) => {
                // The first frame starts the session; what a link selected before it arrived stays.
                if (!previous) return;
                setSelection(null);
                setSeenFailures(new Map());
                traces.setSelected(null);
                setFlowFocus(null);
            },
            { defer: true },
        ),
    );

    let returnFocus: HTMLElement | null = null;
    function select(next: Selection | null) {
        const active = document.activeElement;
        if (next && active instanceof HTMLElement && active !== document.body && !active.closest('[data-inspector]')) returnFocus = active;
        setSelection(next);
    }

    function show(next: ViewName) {
        setView(next);
        if (location.hash !== `#/${next}`) history.replaceState(null, '', `#/${next}`);
    }

    // Keeps the view, the selection, the flow's lens, and the connection; only what the views show changes.
    function setScope(next: string | null) {
        if (next === scope()) return;
        setScopeSignal(next);
        history.pushState(null, '', `${location.pathname}${scopeSearch(next)}${location.hash}`);
    }

    // An application's scope moves to the application of what is shown from another one; the system scope holds every application.
    function follow(next: Selection) {
        const application = next.type === 'type' ? parseTypeKey(next.key).application : next.type === 'message' ? next.application : next.name;
        if (application !== undefined && scope() !== null && scope() !== application) setScope(application);
    }

    // While the flow is shown, its lens follows the selected type, whether selected on the lens, in the inspector, or by a link; showing the
    // flow moves the lens to the type selected elsewhere.
    createEffect(
        on(
            () => selectedTypeKey(selection()),
            key => {
                if (key !== null && view() === 'flow' && flowGraph().keys.has(key)) setFlowFocus(key);
            },
            { defer: true },
        ),
    );
    createEffect(
        on(
            view,
            current => {
                const key = selectedTypeKey(untrack(selection));
                if (current === 'flow' && key !== null && flowGraph().keys.has(key)) setFlowFocus(key);
            },
            { defer: true },
        ),
    );

    return {
        store,
        views: VIEWS,
        view,
        show,
        showFromHash: showRoute,
        // The application in scope, or null for the whole system.
        scope,
        setScope,
        // The statistics of the scope, and of the whole system for what reaches across applications.
        scoped,
        system,
        selection,
        select,
        // Selects a type, message, or application of any application, and narrows the scope to it when another application is in scope.
        reveal(next: Selection) {
            follow(next);
            select(next);
        },
        closeInspector() {
            const target = returnFocus?.isConnected ? returnFocus : stage().querySelector<HTMLElement>('[data-card], [tabindex="0"], button');
            returnFocus = null;
            setSelection(null);
            target?.focus({ preventScroll: true });
        },
        pins,
        // Pinning keeps a copy of the whole trace in this tab, so it outlives its eviction from the hub.
        async togglePin(trace: TraceSummary) {
            if (pins().has(trace.key)) {
                setPins(current => {
                    const next = new Map(current);
                    next.delete(trace.key);
                    return next;
                });
                return;
            }
            const hubSession = store.live().hubSession;
            const detail = await fetchAnswer<WireTraceDetail>(hubRequest(`traces/${trace.root.sequence}`));
            if (!detail || detail.hubSession !== hubSession) return;
            const copy = traceDetail(detail);
            setPins(current => new Map(current).set(copy.summary.key, { key: copy.summary.key, hubSession, detail: copy }));
        },
        // Opens the trace that holds the invocation, which the hub finds by any of its members.
        openTrace(sequence: number) {
            show('traces');
            traces.open(sequence);
            select({ type: 'message', sequence });
        },
        // The traces that hold the type, in the type's application when another application is in scope.
        showTypeTraces(key: TypeKey) {
            follow({ type: 'type', key });
            show('traces');
            traces.setFilters('types', { kinds: [], types: [key] });
        },
        showLive(visual: LiveVisual) {
            show('live');
            live.setVisual(visual);
        },
        // Live's tape filtered by the text; an application's scope moves to the application whose messages the text finds, when given.
        filterTape(text: string, application: string | null = null) {
            if (application !== null) follow({ type: 'application', name: application });
            show('live');
            live.setVisual('tape');
            live.setFilters('search', text);
            live.setFocusRequested(true);
        },
        // The flow's lens on the type, which the scope moves to when another application is in scope.
        showType(key: TypeKey) {
            follow({ type: 'type', key });
            select({ type: 'type', key });
            setFlowFocus(key);
            show('flow');
            setFlowReveal(request => request + 1);
        },
        showFlowFocus() {
            show('flow');
            setFlowReveal(request => request + 1);
        },
        // The failures of the scope that arrived since the failures of their application were last shown.
        unseenFailures,
        // The hue an application wears everywhere, the same in every scope.
        applicationHue: (name: string) => applicationHue(hues(), name),
        palette,
        openPalette(scope: PaletteScope = 'everything') {
            setPalette(scope);
        },
        closePalette() {
            setPalette(null);
        },
        toggleTheme() {
            const root = document.documentElement;
            const current = root.dataset.theme ?? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
            const next = current === 'dark' ? 'light' : 'dark';
            root.dataset.theme = next;
            writePreference('theme', next);
        },
        overview: {
            sort: typeSort,
            setSort: setTypeSort,
            types: typeFilter,
            setTypes: setTypeFilter,
            expandedGroups,
            toggleGroup(key: string) {
                setExpandedGroups(groups => {
                    const next = new Set(groups);
                    if (!next.delete(key)) next.add(key);
                    return next;
                });
            },
        },
        // What each handling counted, while a view that shows it is open.
        handlingFigures: figures,
        flow: {
            // Every application's types and processing groups with the steps between them.
            graph: flowGraph,
            focus: flowFocus,
            focusOn: (key: LensKey) => setFlowFocus(key),
            hops: flowHops,
            setHops: setFlowHops,
            reveal: flowReveal,
        },
        traces,
        live,
    };
}

export const MonitorAppContext = createContext<MonitorApp>();

export function useMonitorApp(): MonitorApp {
    const app = useContext(MonitorAppContext);
    if (!app) throw new Error('useMonitorApp must be called below MonitorAppContext.Provider');
    return app;
}
