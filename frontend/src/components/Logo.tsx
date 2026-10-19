import { For, Show } from 'solid-js';
import { prefersReducedMotion } from './motion';

const SIGNS = [
    { x: 0, y: 0, width: 3, at: 0 },
    { x: 4, y: 0, width: 3, at: 2 },
    { x: 8, y: 0, width: 3, at: 4 },
    { x: 0, y: 4, width: 7, at: 8 },
    { x: 8, y: 4, width: 7, at: 12 },
    { x: 0, y: 8, width: 7, at: 18 },
    { x: 8, y: 8, width: 3, at: 22 },
    { x: 12, y: 8, width: 3, at: 24 },
];

const DRAW_STAGGER = 0.75;
const DRAW_UNITS = 3;
const KEYED_FROM = 9;
const KEYED_FADE_UNITS = 2;
const KEYED_SWELL_UNITS = 2.5;
const KEYED_GROW_UNITS = 3.5;
const CYCLE = 52;
const HOLD_UNTIL = 41;
const BLANK_FROM = 46;
const UNIT_MS = 70;

const keyTimes = (...units: number[]) => units.map(unit => (unit / CYCLE).toFixed(4)).join(';');
const dur = `${CYCLE * UNIT_MS}ms`;

const HOLD = '0 0 1 1';
const EASE_OUT = '0.2 0.6 0.35 1';
const EASE = '0.4 0 0.2 1';

function Fade(props: { at: number; lit: number }) {
    return (
        <animate
            attributeName="opacity"
            dur={dur}
            repeatCount="indefinite"
            values="0;0;1;1;0;0"
            keyTimes={keyTimes(0, props.at, props.at + props.lit, HOLD_UNTIL, BLANK_FROM, CYCLE)}
            calcMode="spline"
            keySplines={[HOLD, EASE_OUT, HOLD, EASE, HOLD].join(';')}
        />
    );
}

function Grow(props: { at: number; grow: number }) {
    return (
        <animate
            attributeName="width"
            dur={dur}
            repeatCount="indefinite"
            values="3;3;7;7"
            keyTimes={keyTimes(0, props.at, props.at + props.grow, CYCLE)}
            calcMode="spline"
            keySplines={[HOLD, EASE, HOLD].join(';')}
        />
    );
}

function Swell(props: { at: number; swell: number }) {
    return (
        <animateTransform
            attributeName="transform"
            type="scale"
            dur={dur}
            repeatCount="indefinite"
            values="0.4;0.4;1;1"
            keyTimes={keyTimes(0, props.at, props.at + props.swell, CYCLE)}
            calcMode="spline"
            keySplines={[HOLD, EASE_OUT, HOLD].join(';')}
        />
    );
}

export function LogoMark(props: { class?: string; transmitting?: boolean }) {
    const animated = () => props.transmitting && !prefersReducedMotion();
    return (
        <svg class={props.class} viewBox="0 0 15 11" aria-hidden="true">
            <Show when={animated()}>
                <g class="fill-current opacity-20">
                    <For each={SIGNS}>
                        {(sign, index) => (
                            <rect x={sign.x} y={sign.y} width={sign.width} height="3" rx="1.5">
                                <Fade at={index() * DRAW_STAGGER} lit={DRAW_UNITS} />
                                <Show when={sign.width === 7}>
                                    <Grow at={index() * DRAW_STAGGER} grow={DRAW_UNITS} />
                                </Show>
                            </rect>
                        )}
                    </For>
                </g>
            </Show>
            <For each={SIGNS}>
                {(sign, index) => (
                    <rect
                        x={sign.x}
                        y={sign.y}
                        width={sign.width}
                        height="3"
                        rx="1.5"
                        class={index() === SIGNS.length - 1 ? 'fill-accent' : 'fill-current'}
                        style={{ 'transform-origin': `${sign.x + 1.5}px ${sign.y + 1.5}px` }}
                    >
                        <Show when={animated()}>
                            <Fade at={KEYED_FROM + sign.at} lit={KEYED_FADE_UNITS} />
                            <Swell at={KEYED_FROM + sign.at} swell={KEYED_SWELL_UNITS} />
                            <Show when={sign.width === 7}>
                                <Grow at={KEYED_FROM + sign.at} grow={KEYED_GROW_UNITS} />
                            </Show>
                        </Show>
                    </rect>
                )}
            </For>
        </svg>
    );
}
