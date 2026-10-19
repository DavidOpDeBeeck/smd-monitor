import { Key } from '@solid-primitives/keyed';
import { createMemo, createSignal, createUniqueId, Show, type JSX } from 'solid-js';
import { cx } from '../lib/cx';
import { filterOptions, stepOption, type DropdownOption } from '../lib/dropdown';
import { Checkbox } from './Checkbox';
import { Chevron, closeOnOutsidePress, FIELD_LABEL, fieldTrigger, NoMatches, openOnArrow, POPOVER } from './popover';

// A field that opens a filterable list of checkable options; an option with children reads as their group.
// Nothing chosen reads as all options, so the field narrows nothing.
export function MultiSelect<T extends string>(props: {
    label: string;
    options: readonly DropdownOption<T>[];
    chosen: readonly string[];
    placeholder: string;
    onPick: (value: T) => void;
    leading?: JSX.Element;
    icon?: (value: T) => JSX.Element;
    class?: string;
}) {
    const id = createUniqueId();
    let root!: HTMLDivElement;
    let trigger!: HTMLButtonElement;
    let filter!: HTMLInputElement;
    const [open, setOpen] = createSignal(false);
    const [query, setQuery] = createSignal('');
    const [active, setActive] = createSignal<T | null>(null);

    const summary = () => props.chosen.join(', ') || 'All';
    const shown = createMemo(() => filterOptions(props.options, query()));
    const groups = createMemo(() => new Set(props.options.flatMap(option => (option.parent === undefined ? [] : [option.parent]))));
    const isShown = (value: T | null) => value !== null && shown().some(option => option.value === value);
    const optionId = (value: T) => `${id}-option-${shown().findIndex(option => option.value === value)}`;

    const reveal = (value: T | null) => {
        if (value !== null) document.getElementById(optionId(value))?.scrollIntoView({ block: 'nearest' });
    };
    const show = () => {
        const first = (props.options.find(option => option.selected === true) ?? props.options[0])?.value ?? null;
        setQuery('');
        setActive(() => first);
        setOpen(true);
        filter.focus();
        reveal(first);
    };
    const close = (refocus: boolean) => {
        setOpen(false);
        if (refocus) trigger.focus();
    };
    const keydown = (event: KeyboardEvent) => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            const next = stepOption(
                shown().map(option => option.value),
                active(),
                event.key,
            );
            setActive(() => next);
            reveal(next);
        } else if (event.key === 'Enter') {
            event.preventDefault();
            const value = active();
            if (isShown(value)) props.onPick(value!);
        } else if (event.key === 'Escape') {
            event.preventDefault();
            close(true);
        } else if (event.key === 'Tab') {
            close(false);
        }
    };
    closeOnOutsidePress(
        () => root,
        open,
        () => close(false),
    );

    return (
        <div ref={root} class={cx('relative', props.class)}>
            <button
                ref={trigger}
                type="button"
                aria-haspopup="listbox"
                aria-expanded={open()}
                aria-controls={open() ? `${id}-list` : undefined}
                aria-label={`${props.label}: ${summary()}`}
                title={summary()}
                onClick={() => (open() ? close(true) : show())}
                onKeyDown={openOnArrow(show)}
                class={cx(fieldTrigger(open()), 'w-full text-left')}
            >
                <span class={FIELD_LABEL}>{props.label}</span>
                {props.leading}
                <span class={cx('min-w-0 flex-1 truncate', props.chosen.length > 0 && 'font-semibold text-accent')}>{props.chosen[0] ?? 'All'}</span>
                <Show when={props.chosen.length > 1}>
                    <span class="flex-none rounded bg-accent/15 px-1 font-mono text-2xs font-semibold text-accent tabular-nums">
                        +{props.chosen.length - 1}
                    </span>
                </Show>
                <Chevron open={open()} />
            </button>
            <Show when={open()}>
                <div onKeyDown={keydown} class={cx(POPOVER, 'left-0 z-20 flex max-h-96 w-full min-w-64 origin-top-left flex-col overflow-hidden')}>
                    <input
                        ref={filter}
                        type="search"
                        placeholder={props.placeholder}
                        aria-label={`Filter ${props.label.toLowerCase()} options`}
                        aria-controls={`${id}-list`}
                        aria-activedescendant={isShown(active()) ? optionId(active()!) : undefined}
                        value={query()}
                        onInput={event => {
                            setQuery(event.currentTarget.value);
                            setActive(() => shown()[0]?.value ?? null);
                        }}
                        class="mx-1 mt-1 h-7 flex-none rounded-md border border-transparent bg-sunken px-2 text-sm outline-none placeholder:text-faint focus:border-line-strong"
                    />
                    <div id={`${id}-list`} role="listbox" aria-label={props.label} aria-multiselectable="true" class="min-h-0 overflow-auto p-1">
                        <Key each={shown()} by="value" fallback={<NoMatches />}>
                            {option => {
                                const value = option().value;
                                const grouping = () => groups().has(value);
                                return (
                                    <div
                                        id={optionId(value)}
                                        role="option"
                                        aria-selected={option().selected === true}
                                        data-active={active() === value || undefined}
                                        onPointerMove={() => setActive(() => value)}
                                        onClick={() => props.onPick(value)}
                                        title={option().label}
                                        class={cx(
                                            'flex cursor-pointer items-center gap-2 rounded-md py-[5px] pr-2 text-sm data-active:bg-accent/10',
                                            option().parent === undefined ? 'pl-2' : 'pl-6',
                                            grouping() && 'mt-1 first:mt-0',
                                        )}
                                    >
                                        <Checkbox selected={option().selected} />
                                        {props.icon?.(value)}
                                        <span class={cx('min-w-0 flex-1 truncate', grouping() ? 'font-semibold' : 'font-medium')}>{option().label}</span>
                                        <Show when={option().hint}>
                                            <span class="flex-none font-mono text-xs text-faint tabular-nums">{option().hint}</span>
                                        </Show>
                                    </div>
                                );
                            }}
                        </Key>
                    </div>
                </div>
            </Show>
        </div>
    );
}
