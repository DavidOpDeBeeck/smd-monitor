import { createEffect, onCleanup } from 'solid-js';
import { cx } from '../lib/cx';
import { EmptyLine } from './empty';

export const POPOVER = 'absolute top-[calc(100%+5px)] animate-pop-in rounded-lg border border-line-strong bg-surface shadow-lift';

export const FIELD_LABEL = 'flex-none text-2xs font-semibold tracking-[.08em] text-muted uppercase';

export const TRIGGER_OPEN = 'border-accent bg-surface shadow-[0_0_0_3px_color-mix(in_srgb,var(--color-accent)_16%,transparent)]';

export const fieldTrigger = (open: boolean) =>
    cx(
        'flex h-[30px] items-center gap-2 rounded-md border px-2.5 py-1 text-sm font-medium transition-[border-color,box-shadow,background-color] duration-150',
        open ? TRIGGER_OPEN : 'border-line-strong bg-surface-2 hover:border-faint hover:bg-surface',
    );

export function Chevron(props: { open: boolean }) {
    return (
        <svg
            class={cx('size-3 flex-none text-muted transition-transform duration-150', props.open && 'rotate-180 text-accent')}
            viewBox="0 0 12 12"
            fill="none"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
        >
            <path d="M3 4.5 6 7.5 9 4.5" />
        </svg>
    );
}

export function NoMatches(props: { class?: string }) {
    return <EmptyLine class={cx('px-2.5 py-5', props.class)}>No matches</EmptyLine>;
}

export function openOnArrow(open: () => void) {
    return (event: KeyboardEvent) => {
        if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
        event.preventDefault();
        open();
    };
}

export function closeOnOutsidePress(root: () => HTMLElement, open: () => boolean, close: () => void) {
    createEffect(() => {
        if (!open()) return;
        const outside = (event: PointerEvent) => {
            if (!root().contains(event.target as Node)) close();
        };
        document.addEventListener('pointerdown', outside, true);
        onCleanup(() => document.removeEventListener('pointerdown', outside, true));
    });
}
