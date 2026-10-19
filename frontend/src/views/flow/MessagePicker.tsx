import { Key } from '@solid-primitives/keyed';
import { createMemo, createSignal, createUniqueId, Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { Glyph, HandlingGlyph } from '../../components/Glyph';
import { Chevron, closeOnOutsidePress, FIELD_LABEL, fieldTrigger, NoMatches, openOnArrow, POPOVER } from '../../components/popover';
import { cx } from '../../lib/cx';
import { stepOption } from '../../lib/dropdown';
import { groupFigures, typeFigures } from '../../lib/flow-figures';
import { lensApplication, lensName, type LensKey } from '../../lib/flow-lens';
import { count, groupKey, isTypeKey, parseTypeKey, rate } from '../../lib/format';

type Section = 'Entry points' | 'Messages' | 'Processing groups';

interface Choice {
    key: LensKey;
    section: Section;
    name: string;
    application: string;
    perSecond: number;
    failed: number;
}

const SECTIONS: readonly Section[] = ['Entry points', 'Messages', 'Processing groups'];

const kindGlyph = (key: LensKey) => (isTypeKey(key) ? <Glyph kind={parseTypeKey(key).kind} /> : <HandlingGlyph />);

// The field that puts the flow's lens on a message type or processing group of the scope: entry points first, as flows start from them, then
// the other messages and the groups, each by name, so the list holds still while its figures move.
export function MessagePicker(props: { focus: LensKey | null; onPick: (key: LensKey) => void }) {
    const app = useMonitorApp();
    const id = createUniqueId();
    let root!: HTMLDivElement;
    let trigger!: HTMLButtonElement;
    let filter!: HTMLInputElement;
    const [open, setOpen] = createSignal(false);
    const [query, setQuery] = createSignal('');
    const [active, setActive] = createSignal<LensKey | null>(null);

    const choices = createMemo(() => {
        const windowSeconds = app.store.view().windowSeconds;
        const types = [...app.scoped().types.values()].map((type): Choice => ({
            key: type.key,
            section: type.entryPoint ? 'Entry points' : 'Messages',
            name: lensName(type.key),
            application: type.application,
            ...typeFigures(type, windowSeconds),
        }));
        const groups = app.scoped().groups.map((group): Choice => {
            const key = groupKey(group.application, group.processingGroup);
            return { key, section: 'Processing groups', name: group.processingGroup, application: group.application, ...groupFigures(group, windowSeconds) };
        });
        return [...types, ...groups].sort(
            (a, b) =>
                SECTIONS.indexOf(a.section) - SECTIONS.indexOf(b.section) ||
                a.name.localeCompare(b.name) ||
                a.application.localeCompare(b.application) ||
                a.key.localeCompare(b.key),
        );
    });
    const shown = createMemo(() => {
        const search = query().trim().toLowerCase();
        return search ? choices().filter(choice => `${choice.name} ${choice.application}`.toLowerCase().includes(search)) : choices();
    });
    const isShown = (key: LensKey | null) => key !== null && shown().some(choice => choice.key === key);
    const optionId = (key: LensKey) => `${id}-option-${shown().findIndex(choice => choice.key === key)}`;
    const reveal = (key: LensKey | null) => {
        if (key !== null) document.getElementById(optionId(key))?.scrollIntoView({ block: 'nearest' });
    };

    const show = () => {
        const first = isShown(props.focus) ? props.focus : (choices()[0]?.key ?? null);
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
    const pick = (key: LensKey) => {
        close(true);
        props.onPick(key);
    };
    const keydown = (event: KeyboardEvent) => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            const next = stepOption(
                shown().map(choice => choice.key),
                active(),
                event.key,
            );
            setActive(() => next);
            reveal(next);
        } else if (event.key === 'Enter') {
            event.preventDefault();
            const key = active();
            if (isShown(key)) pick(key!);
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

    const system = () => app.scope() === null;
    return (
        <div ref={root} class="relative w-[360px] max-w-full flex-none">
            <button
                ref={trigger}
                type="button"
                aria-haspopup="listbox"
                aria-expanded={open()}
                aria-controls={open() ? `${id}-list` : undefined}
                aria-label={props.focus === null ? 'Choose a message to follow' : `Following ${lensName(props.focus)} in ${lensApplication(props.focus)}`}
                onClick={() => (open() ? close(true) : show())}
                onKeyDown={openOnArrow(show)}
                class={cx(fieldTrigger(open()), 'w-full text-left')}
            >
                <span class={FIELD_LABEL}>Message</span>
                <Show when={props.focus} fallback={<span class="min-w-0 flex-1 truncate font-normal text-faint">Choose a message or group</span>}>
                    {focus => (
                        <>
                            {kindGlyph(focus())}
                            <span class="min-w-0 truncate font-semibold">{lensName(focus())}</span>
                            <span class="min-w-0 flex-1 truncate text-xs font-normal text-muted">{lensApplication(focus())}</span>
                        </>
                    )}
                </Show>
                <Chevron open={open()} />
            </button>
            <Show when={open()}>
                <div
                    onKeyDown={keydown}
                    class={cx(POPOVER, 'left-0 z-20 flex max-h-[28rem] w-[440px] max-w-[calc(100vw-24px)] origin-top-left flex-col overflow-hidden')}
                >
                    <input
                        ref={filter}
                        type="search"
                        placeholder="Find a message, group, or application"
                        aria-label="Find a message to follow"
                        aria-controls={`${id}-list`}
                        aria-activedescendant={isShown(active()) ? optionId(active()!) : undefined}
                        value={query()}
                        onInput={event => {
                            setQuery(event.currentTarget.value);
                            setActive(() => shown()[0]?.key ?? null);
                        }}
                        class="mx-1 mt-1 h-7 flex-none rounded-md border border-transparent bg-sunken px-2 text-sm outline-none placeholder:text-faint focus:border-line-strong"
                    />
                    <div id={`${id}-list`} role="listbox" aria-label="Messages and processing groups" class="min-h-0 overflow-auto p-1">
                        <Key each={shown()} by="key" fallback={<NoMatches />}>
                            {(choice, index) => {
                                const key = choice().key;
                                const opensSection = () => index() === 0 || shown()[index() - 1]?.section !== choice().section;
                                return (
                                    <>
                                        <Show when={opensSection()}>
                                            <div
                                                role="presentation"
                                                class="px-2 pt-2.5 pb-1 text-2xs font-semibold tracking-[.08em] text-faint uppercase first:pt-1"
                                            >
                                                {choice().section}
                                            </div>
                                        </Show>
                                        <div
                                            id={optionId(key)}
                                            role="option"
                                            aria-selected={props.focus === key}
                                            data-active={active() === key || undefined}
                                            onPointerMove={() => setActive(() => key)}
                                            onClick={() => pick(key)}
                                            title={`${choice().name} in ${choice().application}`}
                                            class="flex cursor-pointer items-center gap-2 rounded-md px-2 py-[5px] text-sm aria-selected:text-accent data-active:bg-accent/10"
                                        >
                                            {kindGlyph(key)}
                                            <span class={cx('min-w-0 truncate', isTypeKey(key) ? 'font-medium' : 'text-muted')}>{choice().name}</span>
                                            <span class="min-w-0 flex-1 truncate text-xs text-faint">{system() ? choice().application : ''}</span>
                                            <Show when={choice().failed > 0}>
                                                <span class="flex-none font-mono text-xs font-semibold text-failed tabular-nums" title="Failed in the history">
                                                    {count(choice().failed)} failed
                                                </span>
                                            </Show>
                                            <span class="w-14 flex-none text-right font-mono text-xs text-faint tabular-nums">{rate(choice().perSecond)}</span>
                                        </div>
                                    </>
                                );
                            }}
                        </Key>
                    </div>
                </div>
            </Show>
        </div>
    );
}
