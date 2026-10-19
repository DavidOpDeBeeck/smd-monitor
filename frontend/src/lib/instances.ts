import type { InstanceState } from './state';
import { ratePerSecond } from './throughput';

// An instance lags when its spans reach the hub after the window settled, so they still change seconds the page already showed; its clock runs
// ahead while the hub refuses spans of it that start too far ahead of the hub's clock.
export interface InstanceRow {
    application: string;
    name: string;
    silent: boolean;
    clockAhead: boolean;
    exportLagMillis: number | null;
    lastReportedAtMillis: number;
    dispatchesPerSecond: number;
    failureRate: number | null;
    p95Millis: number | null;
}

export function instanceRows(instances: readonly InstanceState[], windowSeconds: number): InstanceRow[] {
    return instances.map(instance => {
        const invocations = instance.dispatches.started + instance.handlings.started;
        return {
            application: instance.application,
            name: instance.name,
            silent: instance.silent,
            clockAhead: instance.clockAhead,
            exportLagMillis: instance.exportLagMillis,
            lastReportedAtMillis: instance.lastReportedAtMillis,
            dispatchesPerSecond: ratePerSecond(instance.dispatches.started, windowSeconds),
            failureRate: invocations ? (instance.dispatches.failed + instance.handlings.failed) / invocations : null,
            p95Millis: instance.dispatches.p95Millis,
        };
    });
}

// Whether an instance's spans reach the hub after the window settled.
export function lagging(exportLagMillis: number | null, settleSeconds: number): boolean {
    return exportLagMillis !== null && exportLagMillis > settleSeconds * 1000;
}
