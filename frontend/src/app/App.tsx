import { createEffect, onCleanup, onMount, Show, untrack, type Component } from 'solid-js';
import { Dynamic } from 'solid-js/web';
import { Loading } from '../components/loading';
import { Inspector } from '../inspector/Inspector';
import { typeFromSearch } from '../lib/applications';
import { cx } from '../lib/cx';
import { Palette } from '../palette/Palette';
import { createMonitorStore } from '../store/monitor-store';
import { FailuresView } from '../views/failures/FailuresView';
import { FlowView } from '../views/flow/FlowView';
import { LiveView } from '../views/live/LiveView';
import { OverviewView } from '../views/overview/OverviewView';
import { TracesView } from '../views/traces/TracesView';
import { applyStoredTheme, createMonitorApp, MonitorAppContext, useMonitorApp, type ViewName } from './monitor-app';
import { installShortcuts } from './shortcuts';
import { StatusBar } from './StatusBar';
import { TopBar } from './TopBar';

const VIEW_COMPONENTS: Record<ViewName, Component> = {
    overview: OverviewView,
    flow: FlowView,
    traces: TracesView,
    failures: FailuresView,
    live: LiveView,
};

export function App() {
    // Read before the app settles the URL on its scope and view.
    let requestedType = typeFromSearch(location.search);
    const store = createMonitorStore();
    let stage!: HTMLElement;
    const app = createMonitorApp(store, () => stage);

    applyStoredTheme();
    installShortcuts(app);
    const hashChanged = () => app.showFromHash();
    window.addEventListener('hashchange', hashChanged);
    onCleanup(() => window.removeEventListener('hashchange', hashChanged));
    onMount(() => store.connect());
    createEffect(() => {
        if (requestedType === null || !app.system().types.has(requestedType)) return;
        const key = requestedType;
        requestedType = null;
        untrack(() => app.showType(key));
    });

    return (
        <MonitorAppContext.Provider value={app}>
            <Monitor stage={element => (stage = element)} />
        </MonitorAppContext.Provider>
    );
}

function Monitor(props: { stage: (element: HTMLElement) => void }) {
    const app = useMonitorApp();
    const store = app.store;
    return (
        <>
            <div class="grid h-dvh grid-cols-[minmax(0,1fr)] grid-rows-[auto_minmax(0,1fr)_auto]">
                <TopBar />
                <div class="grid min-h-0 grid-cols-[minmax(0,1fr)_auto] max-md:grid-cols-[minmax(0,1fr)]">
                    <main
                        ref={props.stage}
                        class={cx(
                            'relative min-h-0 min-w-0 overflow-hidden',
                            store.paused() &&
                                'after:pointer-events-none after:absolute after:inset-0 after:z-30 after:inset-ring-2 after:inset-ring-warning/55',
                        )}
                    >
                        <div id="view-panel" role="tabpanel" aria-labelledby={`view-tab-${app.view()}`} class="absolute inset-0">
                            <Show when={store.status() !== 'connecting'} fallback={<Loading title="Connecting to the hub" class="absolute inset-0" />}>
                                <Dynamic component={VIEW_COMPONENTS[app.view()]} />
                            </Show>
                        </div>
                    </main>
                    <Inspector />
                </div>
                <StatusBar />
            </div>
            <Palette />
        </>
    );
}
