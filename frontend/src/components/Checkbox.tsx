import { Show } from 'solid-js';
import { cx } from '../lib/cx';

export function Checkbox(props: { selected: boolean | 'mixed' }) {
    return (
        <span
            aria-hidden="true"
            class={cx(
                'grid size-3.5 flex-none place-items-center rounded-[4px] border transition-colors duration-100',
                props.selected ? 'border-accent bg-accent text-white' : 'border-line-strong bg-surface',
            )}
        >
            <svg class="size-2.5" viewBox="0 0 10 10" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <Show when={props.selected === 'mixed'} fallback={<path d="m2 5.2 2 2L8 3" class={cx(props.selected !== true && 'hidden')} />}>
                    <path d="M2.5 5h5" />
                </Show>
            </svg>
        </span>
    );
}
