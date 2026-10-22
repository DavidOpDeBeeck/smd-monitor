import { KIND_LABEL, shortName, typeKey } from './format';
import { outdated } from './protocol';
import { selectionInScope, type TypeSelection } from './type-selection';
import type { Activity, DispatchStatus, Linked, ParentRef } from './types';
import type { WireInvocation, WireParent, WireRetainedInvocation } from './wire';

// A query of the hub's retained invocations, relative to the page. Its key names the query, so two requests with one key ask the same.
export interface HubRequest {
    path: string;
    params: URLSearchParams;
}

export const requestKey = (request: HubRequest) => `${request.path}?${request.params}`;

// The headers that name the protocol and build of every answer.
const PROTOCOL_HEADER = 'X-Smd-Monitor-Protocol';
const BUILD_HEADER = 'X-Smd-Monitor-Build';

export function hubRequest(path: string, params: Record<string, string | number | boolean | readonly string[] | null | undefined> = {}): HubRequest {
    const search = new URLSearchParams();
    for (const [name, value] of Object.entries(params)) {
        if (value === null || value === undefined || value === '' || value === false) continue;
        if (Array.isArray(value)) for (const each of value) search.append(name, each);
        else search.append(name, String(value));
    }
    return { path, params: search };
}

export function withParams(request: HubRequest, params: Record<string, string | number>): HubRequest {
    const search = new URLSearchParams(request.params);
    for (const [name, value] of Object.entries(params)) search.set(name, String(value));
    return { path: request.path, params: search };
}

class QueryError extends Error {}

// What the hub answered: an answer with the ETag of its content, when it names one; that the answer whose ETag the page holds did not change; or
// that the hub retains nothing under the path, such as an evicted invocation.
export type Answered<T> = { type: 'answer'; answer: T; eTag: string | null } | { type: 'unchanged' } | { type: 'missing' };

// Asks the hub, which answers unchanged instead when the page holds the answer the ETag names. The page keeps the answers itself, so the
// browser's cache never answers in the hub's place.
export async function askHub<T>(request: HubRequest, options: { signal?: AbortSignal; eTag?: string | null } = {}): Promise<Answered<T>> {
    const url = new URL(`api/${request.path}`, document.baseURI);
    url.search = request.params.toString();
    const headers: Record<string, string> = {};
    if (options.eTag) headers['If-None-Match'] = options.eTag;
    const response = await fetch(url, { signal: options.signal, headers, cache: 'no-store' });
    if (outdated(response.headers.get(PROTOCOL_HEADER), response.headers.get(BUILD_HEADER))) {
        location.reload();
        return { type: 'missing' };
    }
    if (response.status === 304) return { type: 'unchanged' };
    if (response.status === 404) return { type: 'missing' };
    if (!response.ok) throw new QueryError(`Monitor query ${request.path} failed with ${response.status}: ${await response.text()}`);
    return { type: 'answer', answer: (await response.json()) as T, eTag: response.headers.get('ETag') };
}

// The answer, or null when the hub retains nothing under the path.
export async function fetchAnswer<T>(request: HubRequest, signal?: AbortSignal): Promise<T | null> {
    const answered = await askHub<T>(request, { signal });
    return answered.type === 'answer' ? answered.answer : null;
}

// The filters every invocation query shares: the scope, the applications of the system scope, the types and statuses, and the search.
export function invocationParams(
    scope: string | null,
    applications: readonly string[],
    types: TypeSelection,
    statuses: readonly DispatchStatus[],
    search: string,
) {
    const scoped = selectionInScope(types, scope);
    return {
        scope,
        application: scope === null ? applications : [],
        kind: scoped.kinds,
        type: scoped.types,
        status: statuses,
        search: search.trim(),
    };
}

export function activity(invocation: WireInvocation): Activity {
    return {
        sequence: invocation.sequence,
        application: invocation.application,
        messageId: invocation.messageId,
        hasParent: invocation.hasParent,
        typeKey: typeKey(invocation.application, invocation.kind, invocation.type),
        kind: invocation.kind,
        messageType: invocation.type,
        handling: invocation.handling,
        processingGroup: invocation.processingGroup,
        startedAtMillis: invocation.startedAtMillis,
        threadName: invocation.threadName,
        instance: invocation.instance,
        outcome: invocation.outcome,
    };
}

function parentRef(parent: WireParent): ParentRef {
    return {
        sequence: parent.sequence,
        application: parent.application,
        messageId: parent.messageId,
        typeKey: typeKey(parent.application, parent.kind, parent.type),
        kind: parent.kind,
        messageType: parent.type,
        handling: parent.handling,
        processingGroup: parent.processingGroup,
        delivered: parent.delivered,
        retained: parent.retained,
    };
}

export const linked = (retained: WireRetainedInvocation): Linked => ({
    ...activity(retained.invocation),
    parent: retained.parent && parentRef(retained.parent),
});

// What a list says of a parent it cannot link to: the parent's type and, in another application, where it ran, from what the hub kept of it once
// it found it; or that the hub found no parent.
export function parentNote(message: Linked): string | null {
    const parent = message.parent;
    if (parent?.retained) return null;
    if (!parent) return message.hasParent ? 'Parent not found' : null;
    const named = parent.processingGroup
        ? `${parent.processingGroup} handling ${shortName(parent.messageType)}`
        : `${shortName(parent.messageType)} ${parent.handling ? 'handling' : KIND_LABEL[parent.kind].toLowerCase()}`;
    if (parent.application !== message.application)
        return parent.delivered ? `Delivered from ${named} in ${parent.application}` : `Parent ${named} in ${parent.application}`;
    return `Parent ${named}, not retained`;
}
