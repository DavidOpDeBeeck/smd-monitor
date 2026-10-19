import { Key } from '@solid-primitives/keyed';
import { createEffect, createMemo, on } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { deliveryStroke } from '../../components/tones';
import { cx } from '../../lib/cx';
import { lensName, type LensGraph } from '../../lib/flow-lens';
import { deliveryLabel, historySpan, isTypeKey, parseTypeKey, rate } from '../../lib/format';
import { STATION_HEIGHT, STATION_WIDTH, type Journey, type JourneyWire } from '../../lib/journey-layout';
import type { MessageKind } from '../../lib/types';
import { createPulses } from './pulses';

const counted = (graph: LensGraph, wire: JourneyWire) => (wire.counts.type === 'edge' ? graph.edges.get(wire.counts.id) : graph.sends.get(wire.counts.id));

// The steps of the lens as quiet lines with rounded elbows and no heads: every one runs left to right. A line is as thick as its share of the
// busiest step, within a narrow range, and lights up on the path through the hovered card; dots run along it as new steps are counted.
export function JourneyWires(props: { journey: Journey; graph: LensGraph; path: { ends: Set<string>; wires: Set<string> } | null }) {
    const app = useMonitorApp();
    let layer!: SVGGElement;
    const paths = new Map<string, SVGPathElement>();
    const pulses = createPulses(() => layer);
    const busiest = createMemo(() => Math.max(1, ...props.journey.wires.map(wire => counted(props.graph, wire)?.invocations ?? 0)));
    const width = (wire: JourneyWire) => 1.1 + 1.3 * (Math.log1p(counted(props.graph, wire)?.invocations ?? 0) / Math.log1p(busiest()));
    const kindOf = (wire: JourneyWire): MessageKind => (isTypeKey(wire.to) ? parseTypeKey(wire.to).kind : 'EVENT');

    createEffect(
        on(
            () => props.graph,
            graph => {
                if (app.store.paused()) return;
                for (const wire of props.journey.wires) {
                    const path = paths.get(wire.id);
                    const newest = counted(graph, wire)?.lastSecond ?? 0;
                    if (path && newest > 0) pulses.spawn(path, kindOf(wire), Math.min(3, newest));
                }
            },
            { defer: true },
        ),
    );

    const hot = (id: string) => props.path?.wires.has(id) ?? false;
    const dimmed = (id: string) => props.path !== null && !hot(id);
    return (
        <svg aria-hidden="true" class="pointer-events-none absolute top-0 left-0 overflow-visible" width={props.journey.width} height={props.journey.height}>
            <Key each={props.journey.wires} by={wire => wire.id}>
                {wire => (
                    <path
                        ref={element => paths.set(wire().id, element)}
                        d={wire().d}
                        stroke-width={width(wire())}
                        class={cx(
                            'fill-none stroke-line-strong transition-[stroke,opacity] duration-200 [stroke-linecap:round] [stroke-linejoin:round]',
                            wire().delivery !== null && deliveryStroke(wire().delivery!),
                            hot(wire().id) && 'stroke-accent',
                            dimmed(wire().id) && 'opacity-20',
                        )}
                    />
                )}
            </Key>
            <Key each={props.journey.stations} by={placed => placed.station.id}>
                {placed => {
                    const send = () => props.graph.sends.get(placed().station.id);
                    const perSecond = () => (send()?.invocations ?? 0) / app.store.view().historySeconds;
                    return (
                        <g
                            class={cx(
                                'pointer-events-auto transition-opacity duration-200',
                                props.path !== null && !props.path.ends.has(placed().station.id) && 'opacity-30',
                            )}
                            transform={`translate(${placed().x} ${placed().y})`}
                        >
                            <title>
                                {`${lensName(placed().station.event)} into ${deliveryLabel(placed().station.medium)}: ${rate(perSecond())} over the last ${historySpan(app.store.view().historySeconds)}`}
                            </title>
                            <rect width={STATION_WIDTH} height={STATION_HEIGHT} rx={STATION_HEIGHT / 2} class="fill-sunken stroke-muted stroke-[1.2]" />
                            <ellipse
                                cx={STATION_WIDTH - STATION_HEIGHT / 2}
                                cy={STATION_HEIGHT / 2}
                                rx={STATION_HEIGHT / 4}
                                ry={STATION_HEIGHT / 2 - 0.6}
                                class="fill-none stroke-muted stroke-[1.2]"
                            />
                            <text x={STATION_WIDTH + 6} y={STATION_HEIGHT / 2 + 3.5} class="fill-faint font-mono text-2xs">
                                {rate(perSecond())}
                            </text>
                        </g>
                    );
                }}
            </Key>
            <g ref={layer} class="motion-reduce:hidden" />
        </svg>
    );
}
