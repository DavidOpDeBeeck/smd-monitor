import { Key } from '@solid-primitives/keyed';
import { createMemo, Show, type JSX } from 'solid-js';
import { useMonitorApp } from '../../app/monitor-app';
import { Glyph, HandlingGlyph } from '../../components/Glyph';
import { Panel } from '../../components/Panel';
import { SECTION_TITLE } from '../../components/text';
import { cx } from '../../lib/cx';
import { hubRequest } from '../../lib/api';
import { count, percent, shortHistorySpan, shortName } from '../../lib/format';
import { typeFigures } from '../../lib/state';
import type { TypeKey } from '../../lib/types';
import type { WireTypeFigures } from '../../lib/wire';
import { createHubQuery } from '../../store/hub-query';

// The types that failed most, as the hub ranks them.
const MOST_FAILED = 20;

function FailureRateRow(props: { typeKey: TypeKey; failures: number; completed: number; glyph: JSX.Element; title?: string; children: JSX.Element }) {
    const app = useMonitorApp();
    return (
        <button
            type="button"
            title={props.title}
            onClick={() => app.select({ type: 'type', key: props.typeKey })}
            class="grid w-full grid-cols-[10px_minmax(0,1fr)_auto_44px] items-center gap-x-[9px] px-1 py-[7px] text-left hover:bg-surface-2"
        >
            {props.glyph}
            <span class="flex min-w-0 items-baseline gap-1.5">{props.children}</span>
            <span class="font-mono text-sm text-muted">
                {count(props.failures)} / {count(props.completed)}
            </span>
            <strong class="text-right font-mono text-sm text-failed">{percent(props.failures, props.completed)}</strong>
            <span class="col-start-2 col-end-5 mt-1 h-1 overflow-hidden rounded-[3px] bg-sunken">
                <span
                    class="block h-full bg-failed transition-[width] motion-value"
                    style={{ width: `${Math.max(2, (props.failures / Math.max(1, props.completed)) * 100)}%` }}
                />
            </span>
        </button>
    );
}

export function FailureLeaderboard() {
    const app = useMonitorApp();
    // The system scope names the application of each row, since type and group names repeat across applications.
    const named = () => app.scope() === null && app.system().applications.length > 1;
    const ranked = createHubQuery<WireTypeFigures>(app.store, () => hubRequest('type-figures', { scope: app.scope(), sort: 'failures', limit: MOST_FAILED }), {
        kept: true,
    });
    const failedTypes = createMemo(() => typeFigures(ranked.answer()).types.filter(type => type.history.failed > 0));
    // What each handling counted comes with the handlings query, which the failures view keeps asking while it is open.
    const failedHandlings = createMemo(() =>
        [...app.scoped().handlings.values()]
            .flatMap(handling => {
                const figures = app.handlingFigures().get(handling.key);
                return figures && figures.history.failed > 0 ? [{ ...handling, history: figures.history }] : [];
            })
            .sort((a, b) => b.history.failed - a.history.failed),
    );
    return (
        <Panel title="Failed invocations per message type" note={`last ${shortHistorySpan(app.store.view().historySeconds)}`} class="self-start">
            <Key each={failedTypes()} by="key">
                {type => (
                    <FailureRateRow
                        typeKey={type().key}
                        failures={type().history.failed}
                        completed={type().history.completed}
                        glyph={<Glyph kind={type().kind} />}
                    >
                        <span class="truncate font-semibold" title={type().messageType}>
                            {shortName(type().messageType)}
                        </span>
                        <Show when={named()}>
                            <span class="truncate text-xs text-faint">{type().application}</span>
                        </Show>
                    </FailureRateRow>
                )}
            </Key>
            <Show when={failedHandlings().length > 0}>
                <h3 class={cx('mt-4', SECTION_TITLE)}>Failed handlings per processing group</h3>
                <Key each={failedHandlings()} by="key">
                    {handling => (
                        <FailureRateRow
                            typeKey={handling().typeKey}
                            failures={handling().history.failed}
                            completed={handling().history.completed}
                            glyph={<HandlingGlyph />}
                            title={`Processing group ${handling().processingGroup} handling ${handling().messageType}`}
                        >
                            <span class="truncate font-mono font-semibold">{handling().processingGroup}</span>
                            <span class="truncate text-xs text-faint">{shortName(handling().messageType)}</span>
                            <Show when={named()}>
                                <span class="truncate text-xs text-faint">{handling().application}</span>
                            </Show>
                        </FailureRateRow>
                    )}
                </Key>
            </Show>
        </Panel>
    );
}
