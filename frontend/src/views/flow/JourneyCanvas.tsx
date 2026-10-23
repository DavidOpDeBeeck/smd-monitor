import { Key } from '@solid-primitives/keyed';
import { createEffect, createMemo, createSignal, For, Match, on, onMount, Show, Switch } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { MediumGlyph } from '../../components/Glyph';
import { prefersReducedMotion } from '../../components/motion';
import { cx } from '../../lib/cx';
import { groupFigures, typeFigures } from '../../lib/flow-figures';
import { lensApplication, type Lens, type LensGraph, type LensKey } from '../../lib/flow-lens';
import { deliveryLabel, deliveryTitle, groupKey, isTypeKey, mediumShape, parseGroupKey, parseTypeKey, plural } from '../../lib/format';
import { JOURNEY_HEAD, journeyPath, type Journey, type JourneyLane } from '../../lib/journey-layout';
import type { GroupKey, TypeKey } from '../../lib/types';
import { GroupCard, MessageCard } from './JourneyCards';
import { JourneyWires } from './JourneyWires';

const LABEL_WIDTH = 168;

// The lens on a board that scrolls natively: the lanes' names stay at its left edge, each application's lane in the surface tone and each
// medium's sunken below the application writing to it first.
const NO_FAILURES: readonly number[] = [];

export function JourneyCanvas(props: { lens: Lens; journey: Journey; graph: LensGraph }) {
    const app = useMonitorApp();
    let scroller!: HTMLDivElement;
    const [hovered, setHovered] = createSignal<string | null>(null);
    const path = createMemo(() => {
        const key = hovered();
        return key === null || !props.journey.positions.has(key as LensKey) ? null : journeyPath(props.journey, key);
    });
    const groups = createMemo(() => new Map(app.system().groups.map(group => [groupKey(group.application, group.processingGroup), group])));
    const windowSeconds = () => app.store.view().windowSeconds;
    const hover = (key: LensKey) => (hovering: boolean) => setHovered(current => (hovering ? key : current === key ? null : current));
    const dimmed = (key: LensKey) => {
        const current = path();
        return current !== null && !current.ends.has(key);
    };
    const cardsIn = (lane: JourneyLane) => props.journey.cards.filter(card => card.lane === lane.id);

    // From the first cause when everything up to the focus fits, or else with the focus a third of the way in, so what caused it stays in sight.
    const reveal = (smooth: boolean) => {
        const card = props.journey.positions.get(props.lens.focus);
        if (!card) return;
        const visible = scroller.clientWidth - LABEL_WIDTH;
        scroller.scrollTo({
            left: card.x + card.width + 48 <= visible ? 0 : Math.max(0, card.x - visible / 3),
            top: Math.max(0, card.y - scroller.clientHeight / 3),
            behavior: smooth && !prefersReducedMotion() ? 'smooth' : 'auto',
        });
    };
    onMount(() => reveal(false));
    createEffect(
        on(
            () => props.lens.focus,
            () => reveal(true),
            { defer: true },
        ),
    );
    createEffect(on(app.flow.reveal, () => reveal(true), { defer: true }));

    // Dragging the board's empty space with a mouse or pen moves it, like a map; cards, lane names, and other controls keep their own clicks, and
    // touch keeps its native scrolling.
    const [dragging, setDragging] = createSignal(false);
    let drag: { pointer: number; x: number; y: number; left: number; top: number; moved: boolean } | null = null;
    const press = (event: PointerEvent) => {
        if (event.button !== 0 || event.pointerType === 'touch' || (event.target as Element).closest('button, a, input, [data-card]')) return;
        event.preventDefault();
        drag = { pointer: event.pointerId, x: event.clientX, y: event.clientY, left: scroller.scrollLeft, top: scroller.scrollTop, moved: false };
    };
    const move = (event: PointerEvent) => {
        if (drag === null || event.pointerId !== drag.pointer) return;
        const dx = event.clientX - drag.x;
        const dy = event.clientY - drag.y;
        if (!drag.moved) {
            if (Math.hypot(dx, dy) < 4) return;
            drag.moved = true;
            scroller.setPointerCapture(drag.pointer);
            setDragging(true);
        }
        scroller.scrollLeft = drag.left - dx;
        scroller.scrollTop = drag.top - dy;
    };
    const release = (event: PointerEvent) => {
        if (drag === null || event.pointerId !== drag.pointer) return;
        drag = null;
        setDragging(false);
    };

    return (
        <div
            ref={scroller}
            onPointerDown={press}
            onPointerMove={move}
            onPointerUp={release}
            onPointerCancel={release}
            class={cx('relative min-h-0 flex-1 overflow-auto bg-bg', dragging() ? 'cursor-grabbing select-none' : 'cursor-grab')}
            style={{ 'scroll-padding-left': `${LABEL_WIDTH}px` }}
        >
            <div class="relative flex min-w-max" style={{ height: `${props.journey.height}px` }}>
                <div class="sticky left-0 z-2 flex-none border-r bg-bg" style={{ width: `${LABEL_WIDTH}px` }}>
                    <div style={{ height: `${JOURNEY_HEAD}px` }} />
                    <Key each={props.journey.lanes} by={lane => lane.id}>
                        {lane => (
                            <div
                                class={cx(
                                    'flex flex-col justify-center gap-0.5 overflow-hidden border-t px-3.5',
                                    lane().medium === null ? 'bg-surface' : 'bg-sunken',
                                    lane().application !== null && lane().application === app.scope() && 'shadow-[inset_3px_0_0_var(--color-accent)]',
                                )}
                                style={{ height: `${lane().height}px` }}
                            >
                                <Show
                                    when={lane().application}
                                    fallback={
                                        <span class="flex min-w-0 items-center gap-1.5 text-sm text-muted" title={deliveryTitle(lane().medium!)}>
                                            <MediumGlyph shape={mediumShape(lane().medium!)} class="stroke-muted" />
                                            <span class="truncate">{deliveryLabel(lane().medium!)}</span>
                                        </span>
                                    }
                                >
                                    {application => (
                                        <>
                                            <button
                                                type="button"
                                                onClick={() => app.select({ type: 'application', name: application() })}
                                                class="cursor-pointer truncate text-left text-sm font-semibold hover:underline"
                                                title={`Inspect ${application()}`}
                                            >
                                                {application()}
                                            </button>
                                            <span class="text-2xs text-faint">
                                                {cardsIn(lane()).length} {plural(cardsIn(lane()).length, 'card')} in this flow
                                            </span>
                                        </>
                                    )}
                                </Show>
                            </div>
                        )}
                    </Key>
                </div>
                <div
                    role="list"
                    aria-label="Flow"
                    class="relative flex-none"
                    style={{ width: `${props.journey.width}px`, height: `${props.journey.height}px` }}
                >
                    <For each={props.journey.lanes}>
                        {lane => (
                            <div
                                aria-hidden="true"
                                class={cx('absolute inset-x-0 border-t', lane.medium === null ? 'bg-surface' : 'bg-sunken')}
                                style={{ top: `${lane.top}px`, height: `${lane.height}px` }}
                            />
                        )}
                    </For>
                    <For each={props.journey.heads}>
                        {head => (
                            <span
                                aria-hidden="true"
                                class={cx('absolute top-3 text-2xs font-semibold tracking-[.08em] uppercase', head.selected ? 'text-accent' : 'text-faint')}
                                style={{ left: `${head.x}px` }}
                            >
                                {head.label}
                            </span>
                        )}
                    </For>
                    <JourneyWires journey={props.journey} graph={props.graph} path={path()} />
                    <Key each={props.journey.cards} by={card => card.key}>
                        {card => {
                            const key = () => card().key;
                            const state = () => ({
                                card: card(),
                                focus: key() === props.lens.focus,
                                dimmed: dimmed(key()),
                                hiddenIn: props.lens.hiddenIn.get(key()) ?? [],
                                hiddenOut: props.lens.hiddenOut.get(key()) ?? [],
                                onHover: hover(key()),
                            });
                            return (
                                <Switch>
                                    <Match when={isTypeKey(key()) && (key() as TypeKey)}>
                                        {typeKey => {
                                            const type = () => app.system().types.get(typeKey());
                                            const figures = () => app.flow.types().get(typeKey());
                                            const parsed = () => parseTypeKey(typeKey());
                                            return (
                                                <MessageCard
                                                    {...state()}
                                                    kind={parsed().kind}
                                                    name={parsed().messageType}
                                                    application={parsed().application}
                                                    entryPoint={type()?.entryPoint ?? false}
                                                    figures={figures() ? typeFigures(figures()!, windowSeconds()) : null}
                                                    failedPerSecond={app.flow.failing().get(typeKey()) ?? NO_FAILURES}
                                                    onSelect={() => {
                                                        app.flow.focusOn(typeKey());
                                                        app.select({ type: 'type', key: typeKey() });
                                                    }}
                                                />
                                            );
                                        }}
                                    </Match>
                                    <Match when={!isTypeKey(key()) && (key() as GroupKey)}>
                                        {group => {
                                            const statistics = () => groups().get(group());
                                            return (
                                                <GroupCard
                                                    {...state()}
                                                    name={parseGroupKey(group()).processingGroup}
                                                    application={lensApplication(group())}
                                                    delivery={statistics()?.delivery ?? null}
                                                    figures={statistics() ? groupFigures(statistics()!, app.handlingFigures(), windowSeconds()) : null}
                                                    onSelect={() => app.flow.focusOn(group())}
                                                />
                                            );
                                        }}
                                    </Match>
                                </Switch>
                            );
                        }}
                    </Key>
                </div>
            </div>
        </div>
    );
}
