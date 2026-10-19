import { Show, type JSX } from 'solid-js';
import { cx } from '../lib/cx';
import { LogoMark } from './Logo';

// Loaders fade in late, so an answer that arrives quickly never flashes one.
export function Loading(props: { title: string; children?: JSX.Element; class?: string }) {
    return (
        <div role="status" class={cx('flex animate-fade-in-late flex-col items-center justify-center gap-5 p-6 text-center', props.class)}>
            <LogoMark class="h-11 w-auto" transmitting />
            <div class="flex max-w-[440px] flex-col items-center gap-1">
                <h2 class="text-xl font-bold tracking-[-.3px]">{props.title}</h2>
                <Show when={props.children}>
                    <p class="text-muted">{props.children}</p>
                </Show>
            </div>
        </div>
    );
}

export function LoadingLine(props: { children: JSX.Element; class?: string }) {
    return (
        <div role="status" class={cx('flex animate-fade-in-late items-center justify-center gap-2 text-sm text-muted', props.class)}>
            <LogoMark class="h-[11px] w-auto flex-none" transmitting />
            <span>{props.children}</span>
        </div>
    );
}
