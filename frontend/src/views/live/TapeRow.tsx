import { Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { ApplicationChip, GroupChip, InstanceChip, ThreadChip } from '../../components/chips';
import { LinkButton } from '../../components/controls';
import { ActivityGlyph } from '../../components/Glyph';
import { KIND_TONE, STATUS_TEXT } from '../../components/tones';
import { cx } from '../../lib/cx';
import { parentNote } from '../../lib/api';
import { clock, duration, shortName, STATUS_LABEL } from '../../lib/format';
import { failureTypeOf, parentMessageIdOf } from '../../lib/model';
import type { Linked } from '../../lib/types';

export const TAPE_COLUMNS =
    'grid grid-cols-[104px_minmax(180px,1.6fr)_minmax(90px,.8fr)_minmax(150px,1fr)_minmax(120px,.9fr)_86px_minmax(120px,.9fr)] items-center gap-3 px-4 ' +
    'max-lg:grid-cols-[96px_minmax(160px,1.6fr)_90px_minmax(130px,1fr)_minmax(110px,.9fr)] max-lg:[&>:nth-child(n+6)]:hidden ' +
    'max-sm:grid-cols-[84px_minmax(0,1fr)_80px] max-sm:px-2.5 max-sm:[&>:nth-child(n+4)]:hidden';

export const TAPE_ROW_HEIGHT = 34;

export const tapeRowId = (sequence: number) => `tape-row-${sequence}`;

export function TapeRow(props: {
    message: Linked;
    rowIndex: number;
    maxDuration: number;
    selected: boolean;
    showApplication: boolean;
    onRevealParent: (sequence: number) => void;
}) {
    const app = useMonitorApp();
    const message = () => props.message;
    const length = () => message().outcome.durationMillis;
    const parent = () => (message().parent?.retained ? message().parent : null);
    const parentId = () => parentMessageIdOf(message());
    const failed = () => message().outcome.status === 'FAILED';
    const barWidth = () => Math.max(2, (Math.log1p(length()) / Math.log1p(props.maxDuration)) * 100);

    return (
        <div
            role="row"
            id={tapeRowId(message().sequence)}
            aria-rowindex={props.rowIndex}
            aria-selected={props.selected}
            onClick={() => app.select({ type: 'message', sequence: message().sequence })}
            style={{ height: `${TAPE_ROW_HEIGHT}px` }}
            class={cx(
                TAPE_COLUMNS,
                'cursor-pointer border-b border-line/70 *:min-w-0 *:truncate',
                failed() ? (props.selected ? 'bg-failed/16' : 'bg-failed/7') : props.selected ? 'bg-accent/12' : 'hover:bg-fg/4',
                props.selected && 'shadow-[inset_3px_0_var(--color-accent)]',
            )}
        >
            <span role="gridcell" class="font-mono text-sm text-muted">
                {clock(message().startedAtMillis)}
            </span>
            <span role="gridcell" class="flex items-center gap-2">
                <ActivityGlyph message={message()} />
                <strong class="truncate font-semibold" title={message().messageType}>
                    {shortName(message().messageType)}
                </strong>
                <Show when={message().processingGroup}>{group => <GroupChip name={group()} class="flex-none" />}</Show>
                <Show when={props.showApplication}>
                    <ApplicationChip name={message().application} class="flex-none" />
                </Show>
            </span>
            <span role="gridcell" class={cx('text-sm', STATUS_TEXT[message().outcome.status])}>
                {failed() ? shortName(failureTypeOf(message()) ?? 'Failed') : STATUS_LABEL[message().outcome.status]}
            </span>
            <span role="gridcell" class="flex items-center gap-2">
                <span class="min-w-16 flex-none font-mono text-sm">{duration(length())}</span>
                <span
                    class={cx(
                        'h-1.5 max-w-[60%] flex-none rounded-[3px] opacity-75 transition-[width] motion-clock',
                        failed() ? 'bg-failed' : KIND_TONE[message().kind].bg,
                    )}
                    style={{ width: `${barWidth()}%` }}
                />
            </span>
            <span role="gridcell" class="flex min-w-0 items-center gap-1.5">
                <InstanceChip name={message().instance} class="shrink-0" />
                <ThreadChip name={message().threadName} class="min-w-0 shrink" />
            </span>
            <span role="gridcell" class="font-mono text-sm text-muted">
                {message().messageId.slice(0, 8)}
            </span>
            <span role="gridcell">
                <Show
                    when={parent()}
                    fallback={
                        <span class="text-muted">
                            {parentId() ? `${parentId()!.slice(0, 8)} · ${parentNote(message()) ?? 'Parent not found'}` : (parentNote(message()) ?? '—')}
                        </span>
                    }
                >
                    {parent => (
                        <LinkButton
                            class="font-mono text-sm"
                            title={`Select parent ${shortName(parent().messageType)}`}
                            onClick={event => {
                                event.stopPropagation();
                                props.onRevealParent(parent().sequence);
                            }}
                        >
                            ↖ {parent().processingGroup ?? shortName(parent().messageType)}
                        </LinkButton>
                    )}
                </Show>
            </span>
        </div>
    );
}
