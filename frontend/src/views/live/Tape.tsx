import { Key } from '@solid-primitives/keyed';
import { createEffect, createMemo, createSignal, For, on, Show } from 'solid-js';
import { selectedSequence, useMonitorApp } from '../../app/monitor-app';
import { Empty } from '../../components/empty';
import { NewItemsButton, type ListHold } from '../../components/hold';
import { Loading, LoadingLine } from '../../components/loading';
import { prefersReducedMotion } from '../../components/motion';
import { createElementSize } from '../../components/size';
import { cx } from '../../lib/cx';
import type { Linked } from '../../lib/types';
import { LIST_MAX } from '../../store/hub-query';
import { TAPE_COLUMNS, TAPE_ROW_HEIGHT, TapeRow, tapeRowId } from './TapeRow';

const OVERSCAN = 8;
const HEADERS = ['Time', 'Message', 'Status', 'Duration', 'Instance · thread', 'Message ID', 'Parent'];

// Live's messages newest first; listed names what they were listed by, so new messages slide in only under the same filters. Scrolling near the
// end loads the next page while the hub has more, up to LIST_MAX.
export function Tape(props: {
    items: Linked[];
    listed: string;
    loaded: boolean;
    matching: number;
    total: number;
    slowest: number | null;
    more: boolean;
    full: boolean;
    onNearEnd: () => void;
    // Whether the tape is read from its top, as it scrolls.
    onFollow: (following: boolean) => void;
    hold: ListHold;
}) {
    const app = useMonitorApp();
    const live = app.live;
    let grid!: HTMLDivElement;
    let viewport!: HTMLDivElement;
    let rowgroup!: HTMLDivElement;
    const [scrollTop, setScrollTop] = createSignal(0);
    const [unseen, setUnseen] = createSignal(0);
    const size = createElementSize(() => viewport);
    const following = () => viewport.scrollTop < TAPE_ROW_HEIGHT / 2;
    const items = () => props.items;
    const showApplication = () => app.scope() === null && app.system().applications.length > 1;
    const maxDuration = createMemo(() => Math.max(1, props.slowest ?? 0, ...items().map(message => message.outcome.durationMillis)));
    const footer = () => props.more || props.full;
    const range = createMemo(() => {
        const first = Math.max(0, Math.floor(scrollTop() / TAPE_ROW_HEIGHT) - OVERSCAN);
        const last = Math.min(items().length, Math.ceil((scrollTop() + size().height) / TAPE_ROW_HEIGHT) + OVERSCAN);
        return { first, last, rows: items().slice(first, last) };
    });
    createEffect(() => {
        if (props.more && range().last >= items().length - OVERSCAN) props.onNearEnd();
    });

    const slideDown = (added: number) => {
        const sliding = rowgroup.getAnimations()[0];
        const current = sliding ? parseFloat(getComputedStyle(rowgroup).translate.split(' ')[1] ?? '0') : 0;
        const offset = current - added * TAPE_ROW_HEIGHT;
        sliding?.cancel();
        if (-offset > OVERSCAN * TAPE_ROW_HEIGHT || prefersReducedMotion()) return;
        rowgroup.animate({ translate: [`0 ${offset}px`, '0 0'] }, { duration: 260, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' });
    };

    let previousTop: number | null = null;
    let previousFilters = '';
    createEffect(
        on(items, current => {
            const added = previousTop === null ? 0 : current.findIndex(message => message.sequence <= previousTop!);
            if (added > 0 && props.listed === previousFilters) {
                if (following()) {
                    slideDown(added);
                } else {
                    viewport.scrollTop += added * TAPE_ROW_HEIGHT;
                    setUnseen(count => count + added);
                }
            }
            previousTop = current[0]?.sequence ?? previousTop;
            previousFilters = props.listed;
        }),
    );
    createEffect(() => {
        if (!live.focusRequested()) return;
        grid.focus();
        live.setFocusRequested(false);
    });

    const reveal = (sequence: number) => {
        app.select({ type: 'message', sequence });
        const index = items().findIndex(message => message.sequence === sequence);
        if (index < 0) return;
        const top = index * TAPE_ROW_HEIGHT;
        if (top < viewport.scrollTop || top > viewport.scrollTop + viewport.clientHeight - TAPE_ROW_HEIGHT) {
            viewport.scrollTop = Math.max(0, top - viewport.clientHeight / 3);
        }
    };
    const keydown = (event: KeyboardEvent) => {
        const selected = selectedSequence(app.selection());
        if (['ArrowDown', 'j', 'ArrowUp', 'k'].includes(event.key)) {
            event.preventDefault();
            const index = items().findIndex(message => message.sequence === selected);
            const step = event.key === 'ArrowDown' || event.key === 'j' ? 1 : -1;
            const next = items()[index < 0 ? 0 : Math.max(0, Math.min(items().length - 1, index + step))];
            if (next) reveal(next.sequence);
        } else if (event.key === 'p' && selected !== null) {
            event.preventDefault();
            const parent = items().find(message => message.sequence === selected)?.parent;
            if (parent?.retained) reveal(parent.sequence);
        } else if (event.key === 't' && selected !== null) {
            event.preventDefault();
            app.openTrace(selected);
        }
    };
    const activeRow = () => {
        const selected = selectedSequence(app.selection());
        return range().rows.some(message => message.sequence === selected) ? tapeRowId(selected!) : undefined;
    };
    const scrolled = () => {
        setScrollTop(viewport.scrollTop);
        if (following()) setUnseen(0);
        props.onFollow(following());
    };

    return (
        <div class="relative flex min-h-0 flex-1 flex-col" onPointerDown={props.hold.press}>
            <div
                ref={grid}
                tabIndex={0}
                role="grid"
                aria-label="Message tape, newest first. J and K move the selection."
                aria-rowcount={items().length + 1}
                aria-activedescendant={activeRow()}
                onKeyDown={keydown}
                class="flex min-h-0 flex-1 flex-col focus-visible:-outline-offset-2"
            >
                <div role="rowgroup">
                    <div
                        role="row"
                        aria-rowindex={1}
                        class={cx(TAPE_COLUMNS, 'min-h-[30px] border-b bg-surface-2 text-xs font-semibold tracking-[.05em] text-muted uppercase')}
                    >
                        <For each={HEADERS}>{header => <span role="columnheader">{header}</span>}</For>
                    </div>
                </div>
                <div class="relative min-h-0 flex-1">
                    <div ref={viewport} onScroll={scrolled} class="absolute inset-0 overflow-auto">
                        <div class="w-px" style={{ height: `${(items().length + (footer() ? 1 : 0)) * TAPE_ROW_HEIGHT}px` }} />
                        <Show when={footer()}>
                            <div
                                class="absolute inset-x-0 flex items-center justify-center text-sm text-muted"
                                style={{ top: `${items().length * TAPE_ROW_HEIGHT}px`, height: `${TAPE_ROW_HEIGHT}px` }}
                            >
                                <Show when={props.full} fallback={<LoadingLine>Loading more messages…</LoadingLine>}>
                                    Showing the newest {LIST_MAX.toLocaleString()} of {props.matching.toLocaleString()} messages · filter to narrow
                                </Show>
                            </div>
                        </Show>
                        <div
                            ref={rowgroup}
                            role="rowgroup"
                            class="absolute inset-x-0 top-0"
                            style={{ transform: `translateY(${range().first * TAPE_ROW_HEIGHT}px)` }}
                        >
                            <Key each={range().rows} by="sequence">
                                {(message, index) => (
                                    <TapeRow
                                        message={message()}
                                        rowIndex={range().first + index() + 2}
                                        maxDuration={maxDuration()}
                                        selected={selectedSequence(app.selection()) === message().sequence}
                                        showApplication={showApplication()}
                                        onRevealParent={reveal}
                                    />
                                )}
                            </Key>
                        </div>
                    </div>
                </div>
            </div>
            <Show when={unseen() > 0 && scrollTop() >= TAPE_ROW_HEIGHT / 2}>
                <NewItemsButton count={unseen()} onClick={() => (viewport.scrollTop = 0)} class="top-10" />
            </Show>
            <Show when={!props.loaded && items().length === 0}>
                <Loading title="Loading messages" class="absolute inset-x-0 top-[30px] bottom-0" />
            </Show>
            <Show when={props.loaded && items().length === 0}>
                <Empty
                    class="absolute inset-x-0 top-[30px] bottom-0"
                    title={props.total ? 'No matching messages' : 'Nothing on the tape yet'}
                    onClear={props.total ? live.clear : undefined}
                >
                    {props.total ? 'Widen the filters to see more.' : 'Every bus invocation appears here, newest first.'}
                </Empty>
            </Show>
        </div>
    );
}
