import { useMonitorApp } from '../../app/monitor-app';
import { KIND_TONE } from '../../components/tones';
import { cx } from '../../lib/cx';
import { clock, duration, shortName, STATUS_LABEL } from '../../lib/format';
import type { Activity } from '../../lib/types';
import { failureTypeOf } from '../../lib/model';

export const TIMELINE_LANE_HEIGHT = 28;
const MARK_HEIGHT = 12;
const MIN_MARK_WIDTH = 4;

export function TimelineMark(props: { message: Activity; lane: number; origin: number; scale: number; selected: boolean; dimmed: boolean }) {
    const app = useMonitorApp();
    const length = () => props.message.outcome.durationMillis;
    const failure = () => failureTypeOf(props.message);
    const label = () =>
        `${shortName(props.message.messageType)} · ${STATUS_LABEL[props.message.outcome.status]} · ${duration(length())} · ${clock(props.message.startedAtMillis)}`;
    return (
        <div
            title={label() + (failure() ? ` · ${shortName(failure()!)}` : '')}
            onClick={() => app.select({ type: 'message', sequence: props.message.sequence })}
            class={cx(
                'absolute cursor-pointer rounded-[3px] hover:brightness-115',
                props.message.outcome.status === 'FAILED' ? 'bg-failed' : KIND_TONE[props.message.kind].bg,
                props.selected && 'z-1 ring-2 ring-accent ring-offset-1 ring-offset-surface',
                props.dimmed && 'opacity-20',
            )}
            style={{
                top: `${props.lane * TIMELINE_LANE_HEIGHT + (TIMELINE_LANE_HEIGHT - MARK_HEIGHT) / 2}px`,
                height: `${MARK_HEIGHT}px`,
                left: `${(props.message.startedAtMillis - props.origin) * props.scale}px`,
                width: `${Math.max(MIN_MARK_WIDTH, length() * props.scale)}px`,
            }}
        />
    );
}
