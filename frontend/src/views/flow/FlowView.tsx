import { createMemo, Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { Toolbar } from '../../components/controls';
import { Empty } from '../../components/empty';
import { lensApplication, lensName } from '../../lib/flow-lens';
import { isTypeKey } from '../../lib/format';
import { HopsControl, Journey } from './Journey';
import { MessagePicker } from './MessagePicker';

// The flow through the message type or processing group chosen at the top, or a prompt to choose one. A focus the hub no longer reports, such
// as after its application retired, falls back to the prompt.
export function FlowView() {
    const app = useMonitorApp();
    const focus = createMemo(() => {
        const key = app.flow.focus();
        return key !== null && app.flow.graph().keys.has(key) ? key : null;
    });
    const lost = () => {
        const key = app.flow.focus();
        return key !== null && focus() === null ? key : null;
    };
    return (
        <section aria-label="Message flow" class="absolute inset-0 flex flex-col">
            <Toolbar class="bg-surface py-2">
                <MessagePicker
                    focus={focus()}
                    onPick={key => {
                        app.flow.focusOn(key);
                        // A message type is inspected too, as when its card is selected.
                        if (isTypeKey(key)) app.select({ type: 'type', key });
                    }}
                />
                <Show when={focus()}>
                    <HopsControl />
                </Show>
            </Toolbar>
            <Show
                when={focus()}
                fallback={
                    <Empty title="Follow a message" class="flex-1">
                        <Show when={lost()}>
                            {key => (
                                <p class="mb-2">
                                    {lensName(key())} in {lensApplication(key())} is no longer reported.
                                </p>
                            )}
                        </Show>
                        Choose a message or processing group at the top to see what caused it and what it led to, across applications and media. The entry
                        points, where flows start, come first.
                    </Empty>
                }
            >
                {key => <Journey focus={key()} />}
            </Show>
        </section>
    );
}
