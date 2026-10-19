import { activity } from './api';
import { failureKey, typeKey } from './format';
import type { ScopedState } from './scope';
import type { Activity, FailureCount, MessageKind, TypeKey } from './types';
import type { WireFailureCause, WireFailureStep } from './wire';

interface ChainStep {
    key: string;
    application: string;
    kind: MessageKind;
    messageType: string;
    typeKey: TypeKey;
    handling: boolean;
    processingGroup: string | null;
    failureType: string;
}

// The failures of one chain, from the entry point the failure reached to the invocation that raised it. The cause is the step at causeAt; it
// raised the failure itself when it is the last step, and propagated it from the steps after it otherwise.
export interface FailureChain {
    key: string;
    steps: ChainStep[];
    causeAt: number;
    occurrences: number;
}

// What the hub retains of a failure cause: how many occurrences, the latest newest first, and the most frequent chains.
export interface CauseExamples {
    key: string;
    retained: number;
    latest: Activity[];
    chains: FailureChain[];
}

const stepOf = (step: WireFailureStep): ChainStep => {
    const type = typeKey(step.application, step.kind, step.type);
    return {
        key: failureKey(type, step.handling, step.processingGroup, step.failureType),
        application: step.application,
        kind: step.kind,
        messageType: step.type,
        typeKey: type,
        handling: step.handling,
        processingGroup: step.processingGroup,
        failureType: step.failureType,
    };
};

export function causeExamples(cause: WireFailureCause): CauseExamples {
    const key = stepOf(cause.cause).key;
    return {
        key,
        retained: cause.retained,
        latest: cause.latest.map(activity),
        chains: cause.chains.map(chain => {
            const steps = chain.steps.map(stepOf);
            const causeAt = steps.map(step => step.key).lastIndexOf(key);
            return { key: steps.map(step => step.key).join('>'), steps, causeAt, occurrences: chain.occurrences };
        }),
    };
}

// Whether the cause raised the failure itself in a chain, by the heuristic that a failed parent propagated its failed child's failure.
export const raisedHere = (chain: FailureChain) => chain.causeAt === chain.steps.length - 1;

// The causes that failed something over the history, the most frequent in the window first, then over the history.
export function rankedCauses(failures: readonly FailureCount[]): FailureCount[] {
    return failures.filter(failure => failure.history > 0).sort((a, b) => b.window - a.window || b.history - a.history || a.key.localeCompare(b.key));
}

// The causes that failed something in the window, the most frequent first.
export function recentCauses(failures: readonly FailureCount[]): FailureCount[] {
    return failures.filter(failure => failure.window > 0).sort((a, b) => b.window - a.window || a.key.localeCompare(b.key));
}

export interface ApplicationFailures {
    application: string;
    causes: FailureCount[];
}

// The causes by the application whose stage failed: the application in scope first, then the others by name, each keeping the given order.
export function causesByApplication(causes: readonly FailureCount[], scope: string | null): ApplicationFailures[] {
    const byApplication = new Map<string, FailureCount[]>();
    for (const cause of causes) {
        const own = byApplication.get(cause.application);
        if (own) own.push(cause);
        else byApplication.set(cause.application, [cause]);
    }
    return [...byApplication]
        .map(([application, causes]) => ({ application, causes }))
        .sort((a, b) => Number(b.application === scope) - Number(a.application === scope) || a.application.localeCompare(b.application));
}

// How many invocations of the cause's stage started over the history: its handlings, or the dispatches of its type, as a command or query has
// one handling per dispatch.
export function stageStarted(cause: FailureCount, scoped: ScopedState): number {
    if (cause.handling && cause.processingGroup !== null) {
        const handling = [...scoped.handlings.values()].find(each => each.typeKey === cause.typeKey && each.processingGroup === cause.processingGroup);
        return handling?.history.started ?? 0;
    }
    return scoped.types.get(cause.typeKey)?.history.started ?? 0;
}

// When the latest second of the window with an occurrence started, or null when none did; the series ends at the end.
export function lastOccurrence(cause: FailureCount, end: number): number | null {
    const series = cause.windowPerSecond;
    for (let index = series.length - 1; index >= 0; index--) {
        if (series[index] > 0) return end - (series.length - index) * 1000;
    }
    return null;
}
