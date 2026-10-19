import type { Activity, Linked } from './types';

// Known once the hub found the parent, also after it evicted it; a handling's parent message is its own.
export const parentMessageIdOf = (message: Linked) => message.parent?.messageId ?? null;

export const failureTypeOf = (message: Activity) => (message.outcome.status === 'FAILED' ? message.outcome.failureType : null);

export const endOf = (message: Activity) => message.startedAtMillis + message.outcome.durationMillis;
