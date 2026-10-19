import { onCleanup } from 'solid-js';
import type { MonitorApp } from './monitor-app';

export function installShortcuts(app: MonitorApp) {
    const keydown = (event: KeyboardEvent) => {
        if (event.defaultPrevented || app.palette()) return;
        const target = event.target instanceof HTMLElement ? event.target : document.body;
        const typing = target.closest('input, textarea, select, [contenteditable="true"]');
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
            event.preventDefault();
            app.openPalette();
            return;
        }
        if (event.key === 'Escape') {
            if (typing) {
                target.blur();
            } else if (app.selection()) {
                app.closeInspector();
            }
            return;
        }
        if (typing || event.ctrlKey || event.metaKey || event.altKey) return;
        if (event.key === '/') {
            event.preventDefault();
            app.openPalette();
        } else if (/^[1-9]$/.test(event.key) && Number(event.key) <= app.views.length) {
            app.show(app.views[Number(event.key) - 1]);
        } else if (event.key === ' ' && !target.closest('button, a, [role="button"]')) {
            event.preventDefault();
            app.store.togglePause();
        } else if (event.key === 'a') {
            event.preventDefault();
            app.openPalette('applications');
        } else if (event.key === 'f' && app.view() === 'flow') {
            app.showFlowFocus();
        }
    };
    document.addEventListener('keydown', keydown);
    onCleanup(() => document.removeEventListener('keydown', keydown));
}
