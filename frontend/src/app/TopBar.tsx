import { Show } from 'solid-js';
import { TextButton } from '../components/controls';
import { Dot } from '../components/Dot';
import { HEALTH_TONE } from '../components/tones';
import { applicationHealth } from '../lib/applications';
import { cx } from '../lib/cx';
import { plural } from '../lib/format';
import { Brand, CONNECTION, HEADER, ThemeToggle, ViewTabs } from './header';
import { useMonitorApp } from './monitor-app';

const PICKER =
    'flex max-w-full min-w-0 items-center gap-2 rounded-md border border-line-strong px-2.5 py-1 whitespace-nowrap transition-[border-color,background-color] duration-150 hover:border-faint hover:bg-surface-2';

// The picker shows the scope and opens the palette on the applications of the hub, where System clears it.
function ScopeControl() {
    const app = useMonitorApp();
    const instances = () => app.scoped().instances;
    const health = () => {
        const silent = instances().filter(instance => instance.silent).length;
        return HEALTH_TONE[applicationHealth({ reporting: instances().length - silent, instances: instances() })];
    };
    const applications = () => app.system().applications.length;
    return (
        <nav aria-label="Scope" class="flex min-w-0 items-center gap-1.5">
            <Show
                when={app.scope()}
                fallback={
                    <button
                        type="button"
                        aria-haspopup="dialog"
                        title="Narrow to one application (A)"
                        onClick={() => app.openPalette('applications')}
                        class={PICKER}
                    >
                        <span class="font-semibold">System</span>
                        <span class="text-sm text-muted max-xl:hidden">
                            {applications()} {plural(applications(), 'application')}
                        </span>
                        <span aria-hidden="true" class="text-xs text-muted">
                            ▾
                        </span>
                    </button>
                }
            >
                {application => (
                    <button type="button" aria-haspopup="dialog" title="Switch application (A)" onClick={() => app.openPalette('applications')} class={PICKER}>
                        <Dot class={health().dot} />
                        <span class="truncate font-semibold">{application()}</span>
                        <span aria-hidden="true" class="text-xs text-muted">
                            ▾
                        </span>
                    </button>
                )}
            </Show>
        </nav>
    );
}

export function TopBar() {
    const app = useMonitorApp();
    const store = app.store;
    const status = () => CONNECTION[store.status()];
    return (
        <header class={cx(HEADER, 'gap-5 max-md:flex-wrap max-md:gap-2.5')}>
            <Brand />
            <ScopeControl />
            <ViewTabs />
            <div class="ml-auto flex items-center gap-2.5 max-md:ml-0 max-md:w-full">
                <button
                    type="button"
                    aria-pressed={store.paused()}
                    title={store.paused() ? 'Resume live view (Space)' : 'Freeze the view while capture continues (Space)'}
                    onClick={() => store.togglePause()}
                    class={cx(
                        'inline-flex items-center gap-2 rounded-md border border-line-strong bg-surface px-2.5 py-1 font-medium whitespace-nowrap hover:border-faint hover:bg-surface-2 aria-pressed:border-warning aria-pressed:bg-warning/14',
                        status().class,
                    )}
                >
                    <Dot class={status().dot} />
                    <span class="grid">
                        <span role="status" class="col-start-1 row-start-1">
                            {status().text}
                        </span>
                        <span aria-hidden="true" class="invisible col-start-1 row-start-1">
                            {status().toggled}
                        </span>
                    </span>
                    <kbd class="max-lg:hidden">Space</kbd>
                </button>
                <TextButton aria-label="Search (Ctrl K)" onClick={() => app.openPalette()} class="text-muted">
                    <span class="max-md:hidden">Jump to…</span>
                    <kbd class="max-lg:hidden">Ctrl K</kbd>
                </TextButton>
                <ThemeToggle />
            </div>
        </header>
    );
}
