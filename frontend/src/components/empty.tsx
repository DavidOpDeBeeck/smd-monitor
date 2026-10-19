import { Show, type JSX } from 'solid-js';
import { cx } from '../lib/cx';
import { ClearFilters } from './controls';
import { LogoMark } from './Logo';

// Laid out like Loading with the mark at rest, so a pane that loads nothing settles in place.
export function Empty(props: { title: string; children?: JSX.Element; onClear?: () => void; class?: string }) {
    return (
        <div class={cx('flex flex-col items-center justify-center gap-5 p-6 text-center', props.class)}>
            <LogoMark class="h-11 w-auto" />
            <div class="flex max-w-[440px] flex-col items-center gap-1">
                <h2 class="text-xl font-bold tracking-[-.3px]">{props.title}</h2>
                <Show when={props.children}>
                    <div class="text-muted">{props.children}</div>
                </Show>
                <Show when={props.onClear}>
                    {clear => (
                        <div class="mt-1">
                            <ClearFilters onClear={clear()} />
                        </div>
                    )}
                </Show>
            </div>
        </div>
    );
}

export function EmptyLine(props: { children: JSX.Element; class?: string }) {
    return <p class={cx('text-center text-sm text-muted', props.class)}>{props.children}</p>;
}
