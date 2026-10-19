import { createEffect, createSignal, on, onCleanup, Show } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { Glyph, HandlingGlyph } from '../../components/Glyph';
import { Tweened } from '../../components/tween';
import { cx } from '../../lib/cx';
import type { CardFigures } from '../../lib/flow-figures';
import { lensApplication, lensName, type LensEdge, type LensKey } from '../../lib/flow-lens';
import {
    count,
    deliveryLabel,
    deliveryTitle,
    duration,
    historySpan,
    isInProcessDelivery,
    KIND_LABEL,
    lastPeriod,
    rate,
    shortName,
    wholeCount,
} from '../../lib/format';
import type { JourneyCard } from '../../lib/journey-layout';
import type { MessageKind } from '../../lib/types';

const FAILING_HIGHLIGHT = 6000;
const FAILING_RING = 'shadow-[0_0_0_3px_color-mix(in_srgb,var(--color-failed)_22%,transparent)]';
const PLACED = 'absolute top-0 left-0 motion-value transition-[transform,opacity]';

export interface CardState {
    card: JourneyCard;
    focus: boolean;
    dimmed: boolean;
    hiddenIn: readonly LensEdge[];
    hiddenOut: readonly LensEdge[];
    onHover: (hovering: boolean) => void;
}

const named = (key: LensKey) => `${lensName(key)} in ${lensApplication(key)}`;
const place = (card: JourneyCard) => ({ transform: `translate(${card.x}px, ${card.y}px)`, width: `${card.width}px`, height: `${card.height}px` });

// Every figure of a card, for its title.
function figuresTitle(figures: CardFigures | null, windowSeconds: number, historySeconds: number) {
    if (!figures) return 'Not reported';
    return [
        `${rate(figures.perSecond)} over the ${lastPeriod(windowSeconds)}`,
        `${wholeCount(figures.started)} in the last ${historySpan(historySeconds)}, p95 ${duration(figures.p95Millis)}, ${count(figures.failed)} failed`,
    ].join('\n');
}

// Its rate, p95, and failures on one quiet line; red only when it failed. A line too long for the card ends in an ellipsis, and its title holds
// every figure.
function Figures(props: { figures: CardFigures | null }) {
    const store = useMonitorApp().store;
    return (
        <span
            title={figuresTitle(props.figures, store.view().windowSeconds, store.view().historySeconds)}
            class="block min-w-0 truncate font-mono text-2xs text-muted"
        >
            <Show when={props.figures} fallback={<span class="text-faint">not reported</span>}>
                {figures => (
                    <>
                        <Tweened value={figures().perSecond} format={rate} />
                        <span class="mx-1 text-faint">·</span>
                        <span>
                            p95{' '}
                            <Show when={figures().p95Millis !== null} fallback="—">
                                <Tweened value={figures().p95Millis!} format={duration} />
                            </Show>
                        </span>
                        <Show when={figures().failed > 0}>
                            <span class="mx-1 text-faint">·</span>
                            <span class="font-semibold text-failed">{count(figures().failed)} failed</span>
                        </Show>
                    </>
                )}
            </Show>
        </span>
    );
}

// The steps of a card the lens does not draw: beyond its hops, or back to a card at an earlier column.
function Stubs(props: { in: readonly LensEdge[]; out: readonly LensEdge[] }) {
    return (
        <>
            <Show when={props.in.length}>
                <span
                    title={`Also follows from ${props.in.map(edge => named(edge.parent)).join(', ')}`}
                    class="absolute top-1 right-full mr-1.5 font-mono text-2xs text-faint"
                >
                    +{props.in.length}
                </span>
            </Show>
            <Show when={props.out.length}>
                <span
                    title={`Also leads to ${props.out.map(edge => named(edge.child)).join(', ')}`}
                    class="absolute top-1 left-full ml-1.5 font-mono text-2xs text-faint"
                >
                    +{props.out.length}
                </span>
            </Show>
        </>
    );
}

// A message type: its kind's glyph, its name, and its figures in a quiet outline.
export function MessageCard(
    props: CardState & {
        kind: MessageKind;
        name: string;
        application: string;
        entryPoint: boolean;
        figures: CardFigures | null;
        // The newest failures of the type, one a second.
        failedPerSecond: readonly number[];
        onSelect: () => void;
    },
) {
    const store = useMonitorApp().store;
    const [failing, setFailing] = createSignal(false);
    let failingTimer: ReturnType<typeof setTimeout> | undefined;
    onCleanup(() => clearTimeout(failingTimer));
    // Each frame moves the series on by a second; a failure in its newest second rings the card.
    createEffect(
        on(
            () => props.failedPerSecond,
            current => {
                if (store.paused() || !current.at(-1)) return;
                setFailing(true);
                clearTimeout(failingTimer);
                failingTimer = setTimeout(() => setFailing(false), FAILING_HIGHLIGHT);
            },
        ),
    );
    const hover = () => props.onHover(true);
    const leave = () => props.onHover(false);
    return (
        <div role="listitem" class={cx(PLACED, props.dimmed && 'opacity-30')} style={place(props.card)}>
            <Show when={props.entryPoint}>
                <span aria-hidden="true" class="absolute top-1/2 right-full mr-1 flex -translate-y-1/2 items-center" title="Entry point">
                    <span class="size-[5px] rounded-full border border-faint" />
                    <span class="h-px w-2 bg-faint" />
                </span>
            </Show>
            <button
                type="button"
                data-card
                onClick={() => props.onSelect()}
                onPointerEnter={hover}
                onPointerLeave={leave}
                onFocus={hover}
                onBlur={leave}
                aria-current={props.focus ? 'true' : undefined}
                aria-label={`${KIND_LABEL[props.kind]} ${shortName(props.name)} in ${props.application}`}
                title={`${KIND_LABEL[props.kind]} ${props.name} in ${props.application}`}
                class={cx(
                    'grid size-full cursor-pointer content-center gap-0.5 rounded-lg border bg-surface px-3 text-left transition-[border-color,box-shadow] duration-200',
                    props.focus
                        ? 'border-accent shadow-[0_0_0_3px_color-mix(in_srgb,var(--color-accent)_22%,transparent)]'
                        : 'border-line-strong hover:border-faint',
                    failing() && !props.focus && FAILING_RING,
                )}
            >
                <span class="flex min-w-0 items-center gap-2">
                    <Glyph kind={props.kind} />
                    <span class="truncate text-sm font-semibold">{shortName(props.name)}</span>
                </span>
                <span class="block min-w-0 pl-[17px]">
                    <Figures figures={props.figures} />
                </span>
            </button>
            <Stubs in={props.hiddenIn} out={props.hiddenOut} />
        </div>
    );
}

// A processing group: a compact pill between the events it handles and what it dispatches, lighter than a message card. It shows its rate, its
// failures when it has any, and how the event bus hands it its events when it does so within the publication; its p95 is in its title.
export function GroupCard(
    props: CardState & { name: string; application: string; delivery: string | null; figures: CardFigures | null; onSelect: () => void },
) {
    const store = useMonitorApp().store;
    const hover = () => props.onHover(true);
    const leave = () => props.onHover(false);
    return (
        <div role="listitem" class={cx(PLACED, props.dimmed && 'opacity-30')} style={place(props.card)}>
            <button
                type="button"
                data-card
                onClick={() => props.onSelect()}
                onPointerEnter={hover}
                onPointerLeave={leave}
                onFocus={hover}
                onBlur={leave}
                aria-current={props.focus ? 'true' : undefined}
                aria-label={`Processing group ${props.name} in ${props.application}`}
                title={`Processing group ${props.name} in ${props.application}\n${figuresTitle(props.figures, store.view().windowSeconds, store.view().historySeconds)}`}
                class={cx(
                    'flex size-full cursor-pointer items-center gap-2 rounded-full border bg-surface-2 pr-2 pl-3 text-left transition-[border-color,box-shadow] duration-200',
                    props.focus
                        ? 'border-accent shadow-[0_0_0_3px_color-mix(in_srgb,var(--color-accent)_22%,transparent)]'
                        : 'border-line-strong hover:border-faint',
                )}
            >
                <HandlingGlyph />
                <span class="min-w-0 flex-1 truncate text-sm font-medium text-muted">{props.name}</span>
                <Show when={props.figures} fallback={<span class="flex-none font-mono text-2xs text-faint">not reported</span>}>
                    {figures => (
                        <>
                            <Show when={figures().failed > 0}>
                                <span title={`${count(figures().failed)} failed`} class="flex-none font-mono text-2xs font-semibold text-failed">
                                    ✕ {count(figures().failed)}
                                </span>
                            </Show>
                            <span class="flex-none font-mono text-2xs text-faint">
                                <Tweened value={figures().perSecond} format={rate} />
                            </span>
                        </>
                    )}
                </Show>
                <Show when={props.delivery !== null && isInProcessDelivery(props.delivery) && props.delivery}>
                    {delivery => (
                        <span
                            title={deliveryTitle(delivery())}
                            class="flex-none rounded-full bg-sunken px-1.5 text-2xs leading-4 font-semibold text-muted uppercase"
                        >
                            {deliveryLabel(delivery())}
                        </span>
                    )}
                </Show>
            </button>
            <Stubs in={props.hiddenIn} out={props.hiddenOut} />
        </div>
    );
}
