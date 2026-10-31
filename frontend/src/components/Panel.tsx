import { Show, type JSX } from 'solid-js';
import { cx } from '../lib/cx';
import { SECTION_TITLE } from './text';

// Every section's header is as tall as a control, so a section with filters or a link on the right lines up with one without.
export function Panel(props: { title: string; note?: string; action?: JSX.Element; class?: string; children: JSX.Element }) {
    return (
        <section class={cx('min-w-0 rounded-lg border bg-surface px-4 py-3.5', props.class)}>
            <header class="mb-3 flex min-h-[30px] flex-wrap items-center gap-x-3 gap-y-1.5">
                <div class="flex min-w-0 items-baseline gap-x-2">
                    <h3 class={SECTION_TITLE}>{props.title}</h3>
                    <Show when={props.note}>
                        <small class="text-2xs text-faint">{props.note}</small>
                    </Show>
                </div>
                <Show when={props.action}>
                    <div class="ml-auto flex items-center gap-2">{props.action}</div>
                </Show>
            </header>
            {props.children}
        </section>
    );
}
