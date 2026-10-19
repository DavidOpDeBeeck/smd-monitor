import { createSignal, createUniqueId, Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { Chevron, closeOnOutsidePress, FIELD_LABEL, fieldTrigger, POPOVER } from '../../components/popover';
import { ThresholdPanel } from '../../components/ThresholdPanel';
import { cx } from '../../lib/cx';
import { THRESHOLD_BUCKETS } from '../../lib/duration-threshold';
import { roundDuration } from '../../lib/format';
import { durationsRequest } from '../../lib/live';
import type { WireDurations } from '../../lib/wire';
import { createHubQuery } from '../../store/hub-query';

function Stopwatch(props: { class?: string }) {
    return (
        <svg
            class={cx('size-3.5 flex-none', props.class)}
            viewBox="0 0 14 14"
            fill="none"
            stroke="currentColor"
            stroke-width="1.4"
            stroke-linecap="round"
            aria-hidden="true"
        >
            <circle cx="7" cy="8" r="4.75" />
            <path d="M7 8V5.5M5.5 1.5h3M11 3.5l-.8.8" />
        </svg>
    );
}

// The minimum duration of Live, over a histogram of how long the invocations every other filter keeps took, which the hub counts while it is open.
export function DurationThreshold(props: { value: number; onChange: (millis: number) => void }) {
    const app = useMonitorApp();
    const id = createUniqueId();
    let root!: HTMLDivElement;
    let trigger!: HTMLButtonElement;
    let track: HTMLDivElement | undefined;
    const [open, setOpen] = createSignal(false);

    const durations = createHubQuery<WireDurations>(app.store, () => (open() ? durationsRequest(app.live.filters, app.scope(), THRESHOLD_BUCKETS) : null), {
        kept: true,
    });
    const close = (refocus: boolean) => {
        setOpen(false);
        if (refocus) trigger.focus();
    };
    const show = () => {
        setOpen(true);
        track?.focus();
    };

    closeOnOutsidePress(
        () => root,
        open,
        () => close(false),
    );

    return (
        <div
            ref={root}
            class="relative"
            onFocusOut={event => {
                if (open() && event.relatedTarget instanceof Node && !root.contains(event.relatedTarget)) close(false);
            }}
        >
            <button
                ref={trigger}
                type="button"
                aria-haspopup="dialog"
                aria-expanded={open()}
                aria-controls={open() ? `${id}-panel` : undefined}
                aria-label={props.value ? `Slower than ${roundDuration(props.value)}` : 'Slower than: any duration'}
                onClick={() => (open() ? close(true) : show())}
                class={fieldTrigger(open())}
            >
                <span class={FIELD_LABEL}>Slower</span>
                <Stopwatch class={props.value ? 'text-accent' : 'text-muted'} />
                <span class={cx('min-w-12 text-left font-mono text-sm', props.value > 0 && 'font-semibold text-accent')}>
                    {props.value ? `> ${roundDuration(props.value)}` : 'any'}
                </span>
                <Chevron open={open()} />
            </button>
            <Show when={open()}>
                <div
                    id={`${id}-panel`}
                    role="dialog"
                    aria-label="Minimum duration"
                    onKeyDown={event => {
                        if (event.key !== 'Escape' && event.key !== 'Enter') return;
                        event.preventDefault();
                        close(true);
                    }}
                    class={cx(POPOVER, 'left-0 z-20 w-80 origin-top-left p-3')}
                >
                    <ThresholdPanel
                        label="Slower than"
                        value={props.value}
                        buckets={durations.answer()?.buckets ?? new Array<number>(THRESHOLD_BUCKETS).fill(0)}
                        kept={durations.answer()?.slower ?? 0}
                        total={durations.answer()?.invocations ?? 0}
                        onChange={props.onChange}
                        track={element => (track = element)}
                    />
                </div>
            </Show>
        </div>
    );
}
