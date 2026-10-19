import { hubRequest, invocationParams, type HubRequest } from './api';
import { statusMatches, type StatusSelection } from './status-selection';
import { matchesSearch } from './traces';
import { everyType, selectionInScope, selectionMatches, selectsEveryType, type TypeSelection } from './type-selection';
import type { Activity, MessageKind, TypeKey } from './types';

export type LiveVisual = 'lanes' | 'scatter' | 'tape';

export interface LiveFilters {
    search: string;
    types: TypeSelection;
    // Applies in the system scope only; an application's scope keeps it for the system scope.
    applications: readonly string[];
    statuses: StatusSelection;
    minDuration: number;
}

export const noLiveFilters = (): LiveFilters => ({ search: '', types: everyType(), applications: [], statuses: [], minDuration: 0 });

export const liveFiltersActive = (filters: LiveFilters, scope: string | null) =>
    filters.search.trim() !== '' ||
    !selectsEveryType(selectionInScope(filters.types, scope)) ||
    (scope === null && filters.applications.length > 0) ||
    filters.statuses.length > 0 ||
    filters.minDuration > 0;

// The type filter and the application chips of the scope: they choose the lanes, and which types a selection may stay on.
export function liveTypeFilter(filters: LiveFilters, scope: string | null): (application: string, kind: MessageKind, key: TypeKey) => boolean {
    const types = selectionInScope({ kinds: [...filters.types.kinds], types: [...filters.types.types] }, scope);
    const applications = scope === null ? [...filters.applications] : [];
    return (application, kind, key) => (!applications.length || applications.includes(application)) && selectionMatches(types, kind, key);
}

function liveMatcher(filters: LiveFilters, scope: string | null): (message: Activity) => boolean {
    const keepsType = liveTypeFilter(filters, scope);
    const search = filters.search.trim().toLowerCase();
    const statuses = [...filters.statuses];
    return message =>
        statusMatches(statuses, message.outcome.status) &&
        keepsType(message.application, message.kind, message.typeKey) &&
        (!search || matchesSearch(message, search));
}

// The tape of the filters: every retained invocation they keep, newest first.
export const tapeRequest = (filters: LiveFilters, scope: string | null): HubRequest =>
    hubRequest('tape', { ...liveParams(filters, scope), minDurationMillis: filters.minDuration });

// How long the invocations the filters keep took, leaving out the minimum duration, and how many took at least it.
export const durationsRequest = (filters: LiveFilters, scope: string | null, buckets: number): HubRequest =>
    hubRequest('tape/durations', { ...liveParams(filters, scope), minDurationMillis: filters.minDuration, buckets });

const liveParams = (filters: LiveFilters, scope: string | null) =>
    invocationParams(scope, filters.applications, filters.types, filters.statuses, filters.search);

// The dispatches of the time span the filters keep, the latest started first, at most the limit.
export const timelineRequest = (filters: LiveFilters, scope: string | null, span: number, limit: number): HubRequest =>
    hubRequest('timeline', { ...liveParams(filters, scope), minDurationMillis: filters.minDuration, spanMillis: span, limit });

export const liveKeeps = (message: Activity, filters: LiveFilters, scope: string | null) =>
    liveMatcher(filters, scope)(message) && message.outcome.durationMillis >= filters.minDuration;
