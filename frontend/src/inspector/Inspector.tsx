import { Match, Show, Switch } from 'solid-js';
import { selectedApplication, selectedSequence, selectedTypeKey, useMonitorApp } from '../app/monitor-app';
import { IconButton } from '../components/controls';
import { ApplicationInspector } from './ApplicationInspector';
import { MessageInspector } from './MessageInspector';
import { TypeInspector } from './TypeInspector';

const INSPECTED = { type: 'Message type', message: 'Message', application: 'Application' } as const;

export function Inspector() {
    const app = useMonitorApp();
    return (
        <Show when={app.selection()}>
            {selection => (
                <aside
                    aria-label="Inspector"
                    data-inspector
                    class="flex min-h-0 w-[384px] animate-slide-in flex-col border-l bg-surface max-md:fixed max-md:inset-y-0 max-md:right-0 max-md:z-20 max-md:w-[min(400px,100%)] max-md:shadow-lift"
                >
                    <header class="flex min-h-[42px] items-center justify-between border-b pr-2 pl-[18px]">
                        <span class="text-xs font-bold tracking-[.08em] text-muted uppercase">{INSPECTED[selection().type]}</span>
                        <IconButton aria-label="Close inspector (Escape)" title="Close (Esc)" onClick={() => app.closeInspector()}>
                            ✕
                        </IconButton>
                    </header>
                    <div class="min-h-0 flex-1 overflow-auto px-[18px] pt-4 pb-7">
                        <Switch>
                            <Match when={selectedTypeKey(selection())}>{key => <TypeInspector typeKey={key()} />}</Match>
                            <Match when={selectedApplication(selection())}>{name => <ApplicationInspector name={name()} />}</Match>
                            <Match when={selectedSequence(selection()) !== null}>
                                <MessageInspector sequence={selectedSequence(selection())!} />
                            </Match>
                        </Switch>
                    </div>
                </aside>
            )}
        </Show>
    );
}
