import type { JSX } from 'solid-js';
import { EYEBROW } from '../../components/text';
import { cx } from '../../lib/cx';

export const NUMBER_CELL = 'px-2 text-right font-mono text-sm whitespace-nowrap tabular-nums';

export function Table(props: { header: JSX.Element; body?: (element: HTMLTableSectionElement) => void; children: JSX.Element }) {
    return (
        <table class="w-full table-fixed border-collapse">
            <thead>
                <tr class="border-b">{props.header}</tr>
            </thead>
            <tbody ref={props.body}>{props.children}</tbody>
        </table>
    );
}

export function HeaderCell(props: { title?: string; sort?: 'ascending' | 'descending'; class?: string; children: JSX.Element }) {
    return (
        <th scope="col" title={props.title} aria-sort={props.sort} class={cx(EYEBROW, 'px-2 pb-1.5', props.class)}>
            {props.children}
        </th>
    );
}

export function Row(props: { onSelect: () => void; title?: string; reorder?: string; selected?: boolean; children: JSX.Element }) {
    return (
        <tr
            data-reorder={props.reorder}
            title={props.title}
            onClick={() => props.onSelect()}
            class={cx('group/row cursor-pointer border-b transition-colors last:border-b-0', props.selected ? 'bg-accent/10' : 'hover:bg-surface-2')}
        >
            {props.children}
        </tr>
    );
}

export function NameCell(props: { onSelect: () => void; label?: string; title?: string; indent?: boolean; children: JSX.Element }) {
    return (
        <td class={cx('max-w-0 py-[7px] pr-2', props.indent ? 'pl-[42px]' : 'pl-1')}>
            <button
                type="button"
                aria-label={props.label}
                title={props.title}
                onClick={event => {
                    event.stopPropagation();
                    props.onSelect();
                }}
                class="flex w-full min-w-0 items-center gap-2 text-left"
            >
                {props.children}
            </button>
        </td>
    );
}
