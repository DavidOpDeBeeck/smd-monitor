import { Show, type JSX } from 'solid-js';
import { cx } from '../lib/cx';
import { SECTION_TITLE } from './text';

export function Panel(props: { title: string; note?: string; action?: JSX.Element; class?: string; children: JSX.Element }) {
    return (
        <section class={cx('min-w-0 rounded-lg border bg-surface px-4 py-3.5', props.class)}>
            <header class="mb-2.5 flex flex-wrap items-baseline gap-x-2 gap-y-1.5">
                <h3 class={SECTION_TITLE}>{props.title}</h3>
                <Show when={props.note}>
                    <small class="text-2xs text-faint">{props.note}</small>
                </Show>
                <Show when={props.action}>
                    <span class="ml-auto">{props.action}</span>
                </Show>
            </header>
            {props.children}
        </section>
    );
}
