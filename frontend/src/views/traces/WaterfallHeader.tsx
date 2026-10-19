import { createSignal, For, Index } from 'solid-js';
import { cx } from '../../lib/cx';
import type { TraceRow } from '../../lib/traces';
import { MESSAGE_WIDTH, moveColumn, resizeColumn, specOf, tableColumns, type WaterfallColumn, type WaterfallTable } from '../../lib/waterfall-columns';
import type { WaterfallFilter } from '../../lib/waterfall-filter';
import { ColumnFilter } from './ColumnFilter';

export const HEADER_CELL = 'relative flex min-w-0 items-center border-r border-line px-2';

type Side = 'before' | 'after';

function ResizeHandle(props: { label: string; width: number; defaultWidth: number; onResize: (width: number) => void }) {
    let start: { x: number; width: number } | null = null;
    return (
        <span
            role="separator"
            aria-orientation="vertical"
            aria-label={`Resize ${props.label}`}
            title="Drag to resize; double-click to reset"
            onPointerDown={event => {
                event.preventDefault();
                event.currentTarget.setPointerCapture(event.pointerId);
                start = { x: event.clientX, width: props.width };
            }}
            onPointerMove={event => {
                if (start) props.onResize(start.width + event.clientX - start.x);
            }}
            onPointerUp={() => (start = null)}
            onPointerCancel={() => (start = null)}
            onDblClick={() => props.onResize(props.defaultWidth)}
            class="absolute inset-y-0 -right-[3px] z-1 w-1.5 cursor-col-resize touch-none after:absolute after:inset-y-1.5 after:left-[2px] after:w-0.5 after:rounded-full after:transition-colors hover:after:bg-accent"
        />
    );
}

export function WaterfallHeader(props: {
    table: WaterfallTable;
    template: string;
    ticks: readonly { left: string; label: string }[];
    onChange: (change: (table: WaterfallTable) => WaterfallTable) => void;
    filter: WaterfallFilter;
    rows: readonly TraceRow[];
    traceStart: number;
    onFilter: (filter: WaterfallFilter) => void;
}) {
    let header!: HTMLDivElement;
    const [dragged, setDragged] = createSignal<WaterfallColumn | null>(null);
    const [target, setTarget] = createSignal<{ key: WaterfallColumn; side: Side } | null>(null);
    const columns = () => tableColumns(props.table);
    // The column header under the pointer, and which half of it.
    const targetAt = (x: number): { key: WaterfallColumn; side: Side } | null => {
        for (const cell of header.querySelectorAll<HTMLElement>('[data-column]')) {
            const bounds = cell.getBoundingClientRect();
            if (x >= bounds.left && x < bounds.right) {
                return { key: cell.dataset.column as WaterfallColumn, side: x < bounds.left + bounds.width / 2 ? 'before' : 'after' };
            }
        }
        return null;
    };
    // A press becomes a drag once the pointer moves a few pixels, so a click on a header stays a click.
    let press: { key: WaterfallColumn; x: number } | null = null;
    const grab = (key: WaterfallColumn) => (event: PointerEvent) => {
        if (event.button !== 0) return;
        (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
        press = { key, x: event.clientX };
    };
    const pull = (event: PointerEvent) => {
        if (!press) return;
        if (dragged() === null && Math.abs(event.clientX - press.x) < 4) return;
        setDragged(press.key);
        setTarget(targetAt(event.clientX));
    };
    const release = () => {
        const over = target();
        if (dragged() !== null && over) drop(over.key, over.side);
        press = null;
        endDrag();
    };
    // Dropping after a column puts it in front of whatever follows that column.
    const drop = (key: WaterfallColumn, side: Side) => {
        const moving = dragged();
        if (moving === null || moving === key) return;
        const order = props.table.columns.map(column => column.key);
        const before = side === 'before' ? key : (order[order.indexOf(key) + 1] ?? null);
        props.onChange(table => moveColumn(table, moving, before));
    };
    const endDrag = () => {
        setDragged(null);
        setTarget(null);
    };

    return (
        <div
            ref={header}
            style={{ 'grid-template-columns': props.template }}
            class="sticky top-0 z-2 grid h-8 border-b bg-surface-2 text-2xs font-semibold tracking-[.06em] text-muted uppercase"
        >
            <div class={cx(HEADER_CELL, 'gap-1.5 pl-5')}>
                <span class="min-w-0 flex-1 truncate">Message</span>
                <ColumnFilter
                    column="message"
                    label="Message"
                    filter={props.filter}
                    rows={props.rows}
                    traceStart={props.traceStart}
                    onChange={props.onFilter}
                />
                <ResizeHandle
                    label="Message"
                    width={props.table.messageWidth}
                    defaultWidth={MESSAGE_WIDTH}
                    onResize={width => props.onChange(table => resizeColumn(table, 'message', width))}
                />
            </div>
            <For each={columns()}>
                {column => (
                    <div
                        data-column={column.key}
                        class={cx(
                            HEADER_CELL,
                            'gap-1',
                            dragged() === column.key && 'opacity-40',
                            target()?.key === column.key &&
                                (target()!.side === 'before'
                                    ? 'before:absolute before:inset-y-1 before:-left-px before:w-0.5 before:bg-accent'
                                    : 'before:absolute before:inset-y-1 before:-right-px before:w-0.5 before:bg-accent'),
                        )}
                    >
                        <span
                            title={`${column.title}\nDrag to move`}
                            onPointerDown={grab(column.key)}
                            onPointerMove={pull}
                            onPointerUp={release}
                            onPointerCancel={() => {
                                press = null;
                                endDrag();
                            }}
                            class={cx(
                                'min-w-0 flex-1 touch-none truncate select-none',
                                column.numeric && 'text-right',
                                dragged() ? 'cursor-grabbing' : 'cursor-grab',
                            )}
                        >
                            {column.label}
                        </span>
                        <ColumnFilter
                            column={column.key}
                            label={column.label}
                            filter={props.filter}
                            rows={props.rows}
                            traceStart={props.traceStart}
                            alignEnd={column.numeric}
                            onChange={props.onFilter}
                        />
                        <ResizeHandle
                            label={column.label}
                            width={column.width}
                            defaultWidth={specOf(column.key).width}
                            onResize={width => props.onChange(table => resizeColumn(table, column.key, width))}
                        />
                    </div>
                )}
            </For>
            <div
                data-axis
                title="Scroll to zoom, drag to pan, double-click to see the whole trace"
                class="relative touch-none overflow-hidden font-normal tracking-normal normal-case select-none"
            >
                <div class="absolute inset-y-0 right-[90px] left-0">
                    <Index each={props.ticks}>
                        {tick => (
                            <span
                                class="absolute top-[9px] border-l border-line-strong pl-1 font-mono text-2xs font-medium whitespace-nowrap"
                                style={{ left: tick().left }}
                            >
                                {tick().label}
                            </span>
                        )}
                    </Index>
                </div>
            </div>
        </div>
    );
}
