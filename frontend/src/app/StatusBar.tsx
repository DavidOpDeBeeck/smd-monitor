import { Show } from 'solid-js';
import { RetentionBoundary } from '../components/RetentionBoundary';
import { count } from '../lib/format';
import { HealthPanel } from './HealthPanel';
import { useMonitorApp } from './monitor-app';

// The hub's health, and what the scope retains: of its application, or of every application.
export function StatusBar() {
    const app = useMonitorApp();
    const state = () => app.store.live();
    const retained = () => app.scoped().applications.reduce((total, application) => total + application.retained, 0);
    return (
        <footer class="flex min-h-7 items-center gap-[18px] border-t bg-surface px-4 py-1 text-xs text-muted">
            <Show when={state().hubSession} fallback={<span>Waiting for the hub</span>}>
                <HealthPanel />
            </Show>
            <span class="max-md:hidden">
                {count(retained())} retained · {app.scoped().evicted ? `${count(app.scoped().evicted)} evicted` : 'nothing evicted'}
            </span>
            <RetentionBoundary applications={app.scoped().applications} />
        </footer>
    );
}
