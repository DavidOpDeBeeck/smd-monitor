import { createSignal, For, onCleanup, Show, splitProps, type JSX } from 'solid-js';
import { copyText } from '../lib/browser';
import { cx } from '../lib/cx';

type ButtonProps = JSX.ButtonHTMLAttributes<HTMLButtonElement>;

const FIELD = 'h-[30px] rounded-md border border-line-strong bg-surface-2 px-2.5 py-1 text-sm';

export const TEXT_BUTTON =
    'inline-flex items-center gap-2 rounded-md border border-line-strong bg-surface px-2.5 py-1 font-medium whitespace-nowrap hover:border-faint hover:bg-surface-2 aria-pressed:border-warning aria-pressed:text-warning';

export function TextButton(props: ButtonProps & { wide?: boolean }) {
    const [local, rest] = splitProps(props, ['class', 'wide']);
    return <button type="button" {...rest} class={cx(TEXT_BUTTON, local.wide && 'w-full justify-center', local.class)} />;
}

export function IconButton(props: ButtonProps) {
    const [local, rest] = splitProps(props, ['class']);
    return (
        <button
            type="button"
            {...rest}
            class={cx(
                'inline-grid size-[30px] flex-none place-items-center rounded-md border border-transparent text-md text-muted hover:border-line hover:bg-surface-2 hover:text-fg',
                local.class,
            )}
        />
    );
}

export function LinkButton(props: ButtonProps) {
    const [local, rest] = splitProps(props, ['class']);
    return (
        <button
            type="button"
            {...rest}
            class={cx(
                'inline-flex max-w-full items-center gap-[5px] overflow-hidden text-left text-ellipsis whitespace-nowrap text-accent hover:underline',
                local.class,
            )}
        />
    );
}

export function ToggleChip(props: { pressed: boolean; onToggle: () => void; children: JSX.Element }) {
    return (
        <button
            type="button"
            aria-pressed={props.pressed}
            onClick={() => props.onToggle()}
            class="inline-flex items-center gap-1.5 rounded-full border border-line-strong px-2.5 py-0.5 text-sm font-medium text-muted aria-pressed:border-fg aria-pressed:bg-fg aria-pressed:text-surface"
        >
            {props.children}
        </button>
    );
}

export function CopyButton(props: { text: () => string; class: string; title?: string; children: JSX.Element }) {
    const [copied, setCopied] = createSignal(false);
    let timer: ReturnType<typeof setTimeout> | undefined;
    onCleanup(() => clearTimeout(timer));
    const copy = async () => {
        if (!(await copyText(props.text()))) return;
        setCopied(true);
        clearTimeout(timer);
        timer = setTimeout(() => setCopied(false), 1200);
    };
    return (
        <button type="button" class={props.class} title={props.title} onClick={copy}>
            {copied() ? 'Copied' : props.children}
        </button>
    );
}

export function Segmented<T extends string | number>(props: {
    label: string;
    value: T;
    options: readonly { value: T; label: string; title?: string }[];
    onChange: (value: T) => void;
}) {
    let group!: HTMLDivElement;
    const STEPS: Record<string, number> = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 };
    const move = (event: KeyboardEvent) => {
        const step = STEPS[event.key];
        if (!step) return;
        event.preventDefault();
        const count = props.options.length;
        const index = (props.options.findIndex(option => option.value === props.value) + step + count) % count;
        props.onChange(props.options[index].value);
        group.querySelectorAll<HTMLButtonElement>('[role="radio"]')[index]?.focus();
    };
    return (
        <div
            ref={group}
            role="radiogroup"
            aria-label={props.label}
            onKeyDown={move}
            class="inline-flex h-[30px] flex-none items-stretch gap-0.5 rounded-md border border-line-strong bg-surface-2 p-0.5"
        >
            <For each={props.options}>
                {option => (
                    <button
                        type="button"
                        role="radio"
                        aria-checked={option.value === props.value}
                        tabIndex={option.value === props.value ? 0 : -1}
                        title={option.title}
                        onClick={() => props.onChange(option.value)}
                        class="rounded-[5px] px-2 text-sm font-medium text-muted transition-colors duration-150 hover:text-fg aria-checked:bg-surface aria-checked:text-fg aria-checked:shadow-card"
                    >
                        {option.label}
                    </button>
                )}
            </For>
        </div>
    );
}

export function ClearFilters(props: { onClear: () => void }) {
    return (
        <button
            type="button"
            onClick={() => props.onClear()}
            class="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold whitespace-nowrap text-accent hover:bg-accent/10"
        >
            <span aria-hidden="true">×</span>
            Clear filters
        </button>
    );
}

export function FilterStatus(props: { active: boolean; onClear: () => void; children: JSX.Element }) {
    return (
        <span class="ml-auto flex items-center gap-2 text-xs text-muted">
            <Show when={props.active}>
                <ClearFilters onClear={props.onClear} />
            </Show>
            {props.children}
        </span>
    );
}

export function SearchField(props: { label: string; value: string; onInput: (value: string) => void; class?: string }) {
    return (
        <input
            type="search"
            placeholder="Filter by type, group, ID, thread, failure…"
            aria-label={props.label}
            value={props.value}
            onInput={event => props.onInput(event.currentTarget.value)}
            class={cx(FIELD, props.class)}
        />
    );
}

export function Toolbar(props: { class?: string; children: JSX.Element }) {
    return <div class={cx('flex flex-wrap items-center gap-2 border-b px-3', props.class ?? 'py-2.5')}>{props.children}</div>;
}

export function KeyHint(props: { keys: string; children: JSX.Element }) {
    return (
        <span>
            <kbd>{props.keys}</kbd> {props.children}
        </span>
    );
}
