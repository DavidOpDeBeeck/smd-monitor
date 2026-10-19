import type { DispatchStatus } from './types';

export type StatusSelection = readonly DispatchStatus[];

export const statusMatches = (selection: StatusSelection, status: DispatchStatus) => !selection.length || selection.includes(status);
