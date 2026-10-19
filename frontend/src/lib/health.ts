import type { MonitorState } from './state';
import type { HealthReason, Shedding, WireApplicationHealth, WireProduct } from './wire';

// What the hub counted per reason, in the order the panel lists them: what it refused and dropped first, then what it could not link, then what
// it let go of to stay within its memory budget.
export const REASON_LABEL: Record<HealthReason, string> = {
    REFUSED_BY_INSTANCE_LIMIT: 'Refused beyond the instance limit',
    REFUSED_AHEAD_OF_CLOCK: 'Refused, started ahead of the clock',
    QUEUE_FULL: 'Dropped, intake queue full',
    FAILED_BATCH: 'Dropped with a failing batch',
    SHED_UNRETAINED: 'Counted only, not retained',
    SHED_UNJOINED: 'Counted only, not linked',
    BEYOND_HISTORY: 'Started before the history',
    REVISED: 'Revised a settled second',
    DUPLICATE: 'Duplicates',
    OVERFLOW: 'Beyond the caps, counted as other',
    PARENT_NEVER_RECEIVED: 'Parent never received',
    PARENT_AFTER_HORIZON: 'Parent after the horizon',
    FRAGMENT: 'Started a fragment',
    EVICTED: 'Evicted',
};

const REASONS = Object.keys(REASON_LABEL) as HealthReason[];

// Reasons that mean the hub lost or set aside spans; the others describe the spans themselves.
const LOSING: ReadonlySet<HealthReason> = new Set([
    'REFUSED_BY_INSTANCE_LIMIT',
    'REFUSED_AHEAD_OF_CLOCK',
    'QUEUE_FULL',
    'FAILED_BATCH',
    'SHED_UNRETAINED',
    'SHED_UNJOINED',
]);

export const SHEDDING_LABEL: Record<Shedding, string> = {
    NONE: 'None',
    INTERESTING_ONLY: 'Retains failed and slow only',
    UNJOINED: 'Counts only',
};

export interface ProductUse {
    label: string;
    bytes: number;
    limitBytes: number;
}

export interface ReasonCount {
    reason: HealthReason;
    label: string;
    count: number;
    losing: boolean;
}

// An application's share of the hub: what it takes of the join and the specimens against its fair share of each, what its counts take, and how
// the hub degrades it.
export interface ApplicationHealthRow {
    name: string;
    shedding: Shedding;
    join: WireProduct;
    specimens: WireProduct;
    countingBytes: number;
    effectiveHorizonSeconds: number;
    slowestK: number;
    failuresK: number;
    retained: number;
    completeSince: number | null;
    reasons: ReasonCount[];
    degraded: boolean;
}

export interface HubHealth {
    budgetBytes: number;
    usedBytes: number;
    products: ProductUse[];
    maxHeapBytes: number | null;
    historySeconds: number;
    effectiveHistorySeconds: number;
    foldsSooner: boolean;
    linkHorizonSeconds: number;
    refusedByApplicationLimit: number;
    applications: ApplicationHealthRow[];
    // The hub degrades something: it sheds, drops, or keeps less history or fewer traces than configured.
    degraded: boolean;
}

export function hubHealth(state: MonitorState): HubHealth | null {
    const health = state.health;
    if (!health) return null;
    const products = [
        { label: 'Intake queues', ...health.queues },
        { label: 'Counts', ...health.counting },
        { label: 'Linking', ...health.join },
        { label: 'Retained traces', ...health.specimens },
    ];
    const applications = health.applications.map(application => applicationHealthRow(application, health.linkHorizonSeconds));
    const historyShortened = health.effectiveHistorySeconds < health.historySeconds;
    return {
        budgetBytes: health.budgetBytes,
        usedBytes: products.reduce((total, product) => total + product.bytes, 0),
        products,
        maxHeapBytes: state.maxHeapBytes,
        historySeconds: health.historySeconds,
        effectiveHistorySeconds: health.effectiveHistorySeconds,
        foldsSooner: health.foldsSooner,
        linkHorizonSeconds: health.linkHorizonSeconds,
        refusedByApplicationLimit: health.refusedByApplicationLimit,
        applications,
        degraded: health.foldsSooner || historyShortened || health.refusedByApplicationLimit > 0 || applications.some(application => application.degraded),
    };
}

function applicationHealthRow(health: WireApplicationHealth, linkHorizonSeconds: number): ApplicationHealthRow {
    const reasons = REASONS.flatMap(reason => {
        const count = health.reasons[reason] ?? 0;
        return count > 0 ? [{ reason, label: REASON_LABEL[reason], count, losing: LOSING.has(reason) }] : [];
    });
    return {
        name: health.name,
        shedding: health.shedding,
        join: health.join,
        specimens: health.specimens,
        countingBytes: health.countingBytes,
        effectiveHorizonSeconds: health.effectiveHorizonSeconds,
        slowestK: health.slowestK,
        failuresK: health.failuresK,
        retained: health.retained,
        completeSince: health.completeSinceMillis,
        reasons,
        degraded:
            health.shedding !== 'NONE' ||
            health.completeSinceMillis !== null ||
            health.effectiveHorizonSeconds < linkHorizonSeconds ||
            reasons.some(reason => reason.losing),
    };
}

// A share of a limit for a bar, at most whole.
export const filled = (product: { bytes: number; limitBytes: number }) => (product.limitBytes > 0 ? Math.min(1, product.bytes / product.limitBytes) : 0);
