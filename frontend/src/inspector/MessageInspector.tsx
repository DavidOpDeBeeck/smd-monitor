import { Key } from '@solid-primitives/keyed';
import { createMemo, Show, type JSX } from 'solid-js';
import { useMonitorApp } from '../app/monitor-app';
import { AsyncChip, GroupChip, HueBadge, InstanceChip, ThreadChip } from '../components/chips';
import { LinkButton, TextButton } from '../components/controls';
import { Empty, EmptyLine } from '../components/empty';
import { Loading } from '../components/loading';
import { ActivityGlyph } from '../components/Glyph';
import { cx } from '../lib/cx';
import { activityName, ago, calendarDay, clock, duration, threadLabel } from '../lib/format';
import { activity, hubRequest, linked, parentNote } from '../lib/api';
import { endOf, failureTypeOf, parentMessageIdOf } from '../lib/model';
import type { Activity } from '../lib/types';
import type { WireMessageDetail } from '../lib/wire';
import { createHubQuery } from '../store/hub-query';
import { CopyableId, InspectorHeading, InspectorSection, MessageRow } from './parts';

function Fact(props: { label: string; children: JSX.Element }) {
    return (
        <>
            <dt class="text-sm text-muted">{props.label}</dt>
            <dd class="min-w-0 [overflow-wrap:anywhere]">{props.children}</dd>
        </>
    );
}

function MessageLink(props: { message: Activity }) {
    const app = useMonitorApp();
    return (
        <LinkButton onClick={() => app.select({ type: 'message', sequence: props.message.sequence })}>
            <ActivityGlyph message={props.message} />
            {activityName(props.message)}
        </LinkButton>
    );
}

export function MessageInspector(props: { sequence: number }) {
    const app = useMonitorApp();
    const store = app.store;
    const query = createHubQuery<WireMessageDetail>(store, () => hubRequest(`invocations/${props.sequence}`));
    const detail = createMemo(() => {
        const answer = query.answer();
        if (!answer) return null;
        return {
            message: linked(answer.invocation),
            ancestors: answer.ancestors.map(linked),
            children: answer.children.map(activity),
            childCount: answer.childCount,
            propagatedFrom: answer.propagatedFrom && activity(answer.propagatedFrom),
        };
    });

    return (
        <Show
            when={detail()}
            fallback={
                <Show when={query.missing()} fallback={<Loading title="Loading the invocation" class="h-full" />}>
                    <Empty class="h-full" title="No longer retained">
                        This invocation was evicted. Pause the view to hold history while you inspect it, or pin its trace.
                    </Empty>
                </Show>
            }
        >
            {detail => {
                const message = () => detail().message;
                const parent = () => (message().parent?.retained ? (detail().ancestors.at(-1) ?? null) : null);
                const children = () => detail().children;
                const lineage = (): Activity[] => [...detail().ancestors, message()];
                const asyncGap = () => {
                    const current = parent();
                    const gap = current ? message().startedAtMillis - endOf(current) : -1;
                    return gap >= 0 ? gap : null;
                };
                return (
                    <>
                        <InspectorHeading
                            kind={message().kind}
                            messageType={message().messageType}
                            application={message().application}
                            status={message().outcome.status}
                        />
                        <Show when={lineage().length > 1}>
                            <nav aria-label="Lineage" class="mt-3.5 rounded-md bg-surface-2 p-1.5">
                                <ol>
                                    <Key each={lineage()} by="sequence">
                                        {item => {
                                            const step = () => lineage().indexOf(item());
                                            const parent = () => (step() > 0 ? lineage()[step() - 1] : undefined);
                                            return (
                                                <li
                                                    class={cx(
                                                        'relative',
                                                        // One rail runs through the glyphs, from the first message to the inspected one.
                                                        step() > 0 &&
                                                            'before:absolute before:top-0 before:left-[11.5px] before:h-1/2 before:border-l before:border-line-strong',
                                                        step() < lineage().length - 1 &&
                                                            'after:absolute after:bottom-0 after:left-[11.5px] after:h-1/2 after:border-l after:border-line-strong',
                                                    )}
                                                >
                                                    <button
                                                        type="button"
                                                        aria-current={item().sequence === props.sequence ? 'true' : undefined}
                                                        onClick={() => app.select({ type: 'message', sequence: item().sequence })}
                                                        class="group/step relative z-1 flex w-full min-w-0 items-center gap-2 rounded-sm py-[3px] pr-1 pl-1.5 text-left text-sm hover:bg-sunken aria-[current]:font-bold"
                                                    >
                                                        <span class="grid w-3 flex-none place-items-center bg-surface-2 py-0.5 group-hover/step:bg-sunken">
                                                            <ActivityGlyph message={item()} />
                                                        </span>
                                                        <span class="min-w-0 flex-1 truncate">{activityName(item())}</span>
                                                        <HueBadge
                                                            value={item().application}
                                                            hue={app.applicationHue(item().application)}
                                                            parent={parent()?.application}
                                                            label={item().application}
                                                            title={`Application ${item().application}`}
                                                            class="flex-none text-xs font-normal"
                                                        />
                                                    </button>
                                                </li>
                                            );
                                        }}
                                    </Key>
                                </ol>
                            </nav>
                        </Show>
                        <dl class="mt-4 grid grid-cols-[92px_minmax(0,1fr)] gap-x-3 gap-y-[9px]">
                            <Show when={message().handling}>
                                <Fact label="Handled by">
                                    <Show when={message().processingGroup}>
                                        {group => (
                                            <>
                                                <GroupChip name={group()} />
                                                <span class="text-muted"> · processing group</span>
                                            </>
                                        )}
                                    </Show>
                                </Fact>
                            </Show>
                            <Fact label="Started">
                                <Show when={calendarDay(message().startedAtMillis, store.now())}>{day => `${day()}, `}</Show>
                                <span class="font-mono">{clock(message().startedAtMillis)}</span>
                                <span class="text-muted"> · {ago(store.now() - message().startedAtMillis)}</span>
                            </Fact>
                            <Fact label="Duration">{duration(message().outcome.durationMillis)}</Fact>
                            <Fact label="Instance">
                                <InstanceChip name={message().instance} />
                                <Show when={parent() && parent()!.instance !== message().instance}>
                                    <span class="text-muted"> · parent ran on {parent()!.instance}</span>
                                </Show>
                            </Fact>
                            <Fact label="Thread">
                                <ThreadChip name={message().threadName} />
                                <Show when={parent() && parent()!.threadName !== message().threadName}>
                                    <span class="text-muted"> · parent ran on {threadLabel(parent()!.threadName)}</span>
                                </Show>
                            </Fact>
                            <Fact label="Message ID">
                                <CopyableId value={message().messageId} title="Copy message ID" />
                            </Fact>
                            <Show
                                when={message().hasParent || parent()}
                                fallback={
                                    <Fact label="Parent">
                                        <span class="text-muted">{message().handling ? 'None' : 'None · entry point'}</span>
                                    </Fact>
                                }
                            >
                                <Fact label="Parent">
                                    <Show
                                        when={parent()}
                                        fallback={<Show when={parentMessageIdOf(message())}>{id => <span class="font-mono text-sm">{id()}</span>}</Show>}
                                    >
                                        {parent => (
                                            <>
                                                <MessageLink message={parent()} />
                                                <Show when={parent().application !== message().application}>
                                                    <div class="text-muted">
                                                        {message().parent?.delivered ? 'Delivered from' : 'In'} {parent().application}
                                                    </div>
                                                </Show>
                                            </>
                                        )}
                                    </Show>
                                    <Show when={parentNote(message())}>{note => <div class="text-muted">{note()}</div>}</Show>
                                </Fact>
                                <Show when={asyncGap() !== null}>
                                    <Fact label={message().handling ? 'Handling' : 'Dispatch'}>
                                        <AsyncChip /> started {duration(asyncGap())} after its parent returned
                                    </Fact>
                                </Show>
                            </Show>
                            <Show when={failureTypeOf(message())}>
                                {failure => (
                                    <Fact label="Failure">
                                        <span class="font-mono text-sm text-failed">{failure()}</span>
                                        <Show when={detail().propagatedFrom} fallback={<div class="text-muted">Raised here</div>}>
                                            {source => (
                                                <div class="text-muted">
                                                    Likely propagated from <MessageLink message={source()} />
                                                </div>
                                            )}
                                        </Show>
                                    </Fact>
                                )}
                            </Show>
                            <Fact label="Invocation">#{message().sequence.toLocaleString()}</Fact>
                        </dl>
                        <div class="mt-4 flex gap-2">
                            <TextButton onClick={() => app.openTrace(props.sequence)}>Open trace</TextButton>
                            <TextButton onClick={() => app.select({ type: 'type', key: message().typeKey })}>Type statistics</TextButton>
                        </div>
                        <InspectorSection
                            title="Children"
                            note={
                                detail().childCount > children().length
                                    ? `first ${children().length} of ${detail().childCount} retained`
                                    : `${detail().childCount} retained`
                            }
                        >
                            <Key
                                each={children()}
                                by="sequence"
                                fallback={
                                    <EmptyLine class="text-left">
                                        {message().handling
                                            ? 'Its handlers dispatched nothing.'
                                            : message().kind !== 'EVENT'
                                              ? 'Its handler dispatched nothing.'
                                              : 'Nothing was dispatched or handled with this message as parent.'}
                                    </EmptyLine>
                                }
                            >
                                {child => <MessageRow message={child()} />}
                            </Key>
                        </InspectorSection>
                    </>
                );
            }}
        </Show>
    );
}
