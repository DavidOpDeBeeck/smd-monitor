import { For, Show } from 'solid-js';
import { IconButton } from '../components/controls';
import { LogoMark } from '../components/Logo';
import { stepTab } from '../lib/tabs';
import type { ConnectionStatus } from '../store/monitor-store';
import { useMonitorApp, VIEW_LABEL, type ViewName } from './monitor-app';

export const CONNECTION: Record<ConnectionStatus, { text: string; toggled: string; class: string; dot: string }> = {
    connecting: { text: 'Connecting', toggled: 'Connecting', class: 'text-muted', dot: 'bg-faint' },
    live: { text: 'Live', toggled: 'Paused', class: 'text-fg', dot: 'bg-query animate-live-ring' },
    paused: { text: 'Paused', toggled: 'Live', class: 'text-muted', dot: 'bg-warning' },
    reconnecting: { text: 'Reconnecting', toggled: 'Paused · offline', class: 'text-failed', dot: 'bg-failed' },
    'paused-offline': { text: 'Paused · offline', toggled: 'Reconnecting', class: 'text-failed', dot: 'bg-failed' },
};

export const HEADER = 'flex min-h-[54px] items-center border-b bg-surface px-4 py-2';

// Home is the system's overview, reached without reloading the page.
export function Brand() {
    const app = useMonitorApp();
    const store = app.store;
    const offline = () => store.status() === 'connecting' || store.status() === 'reconnecting';
    const goHome = (event: MouseEvent) => {
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        app.setScope(null);
        app.show('overview');
    };
    return (
        <a class="flex items-center gap-2.5 text-md whitespace-nowrap" href="." onClick={goHome} aria-label="SMD Monitor home">
            <LogoMark class="h-[17px] w-auto" transmitting={offline()} />
            <span class="text-lg leading-none">
                <strong class="font-extrabold tracking-[-.4px]">smd</strong> <span class="font-medium text-muted">monitor</span>
            </span>
        </a>
    );
}

export function ThemeToggle() {
    const app = useMonitorApp();
    return (
        <IconButton title="Toggle light and dark theme" aria-label="Toggle light and dark theme" onClick={() => app.toggleTheme()}>
            ◐
        </IconButton>
    );
}

export function ViewTabs() {
    const app = useMonitorApp();
    const FILTERED: Partial<Record<ViewName, () => boolean>> = { traces: app.traces.active, live: app.live.active };
    const keydown = (event: KeyboardEvent) => {
        const next = stepTab(app.views, app.view(), event.key);
        if (!next) return;
        event.preventDefault();
        app.show(next);
        document.getElementById(`view-tab-${next}`)?.focus();
    };
    return (
        <nav class="group/tabs flex gap-0.5 rounded-[9px] bg-sunken p-[3px] max-sm:w-full" role="tablist" aria-label="Views" onKeyDown={keydown}>
            <For each={app.views}>
                {(view, index) => (
                    <button
                        type="button"
                        role="tab"
                        id={`view-tab-${view}`}
                        aria-controls="view-panel"
                        aria-selected={app.view() === view}
                        tabIndex={app.view() === view ? 0 : -1}
                        onClick={() => app.show(view)}
                        class="flex items-center gap-[7px] rounded-md px-2.5 py-[5px] font-medium text-muted hover:text-fg aria-selected:bg-surface aria-selected:text-fg aria-selected:shadow-card max-sm:flex-1 max-sm:justify-center max-sm:px-1"
                    >
                        {VIEW_LABEL[view]}
                        <Show when={FILTERED[view]?.()}>
                            <span class="size-1.5 rounded-full bg-accent" title="Filters are on" />
                            <span class="sr-only">, filtered</span>
                        </Show>
                        <Show when={view === 'failures' && app.unseenFailures() > 0}>
                            <span
                                class="min-w-[18px] rounded-full bg-failed px-[5px] text-center text-2xs leading-[17px] font-bold text-white"
                                title={`${app.unseenFailures()} new failed invocations since you last opened this view`}
                            >
                                {app.unseenFailures() > 99 ? '99+' : app.unseenFailures()}
                            </span>
                        </Show>
                        <kbd class="opacity-0 transition-opacity group-focus-within/tabs:opacity-100 group-hover/tabs:opacity-100 max-xl:hidden">
                            {index() + 1}
                        </kbd>
                    </button>
                )}
            </For>
        </nav>
    );
}
