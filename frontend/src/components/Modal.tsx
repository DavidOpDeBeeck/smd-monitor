import { createEffect, Show, type JSX } from 'solid-js';
import { cx } from '../lib/cx';

export function Modal(props: { open: boolean; onClose: () => void; label: string; class?: string; children: JSX.Element }) {
    let dialog!: HTMLDialogElement;
    createEffect(() => {
        if (props.open && !dialog.open) dialog.showModal();
        else if (!props.open && dialog.open) dialog.close();
    });
    return (
        <dialog
            ref={dialog}
            aria-label={props.label}
            onCancel={event => {
                event.preventDefault();
                props.onClose();
            }}
            onClick={event => {
                if (event.target === dialog) props.onClose();
            }}
            onClose={() => props.onClose()}
            class={cx(
                'm-auto rounded-xl border border-line-strong bg-surface p-0 text-fg shadow-lift backdrop:bg-black/35 backdrop:backdrop-blur-[2px]',
                props.class,
            )}
        >
            <Show when={props.open}>{props.children}</Show>
        </dialog>
    );
}
