// Checks how the page keeps the lists and the trace it asks the hub for: merging a first page into a list, and keeping what did not change the
// same object. Run by the hub's tests: node scripts/check-lists.ts.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { withFirstPage } from '../src/lib/paging.ts';
import { sameData, sameFields, shareUnchanged } from '../src/lib/sharing.ts';

interface Item {
    id: number;
    label: string;
}

const item = (id: number, label = `item ${id}`): Item => ({ id, label });
const items = (...ids: number[]) => ids.map(id => item(id));
const ids = (list: Item[]) => list.map(each => each.id);
const key = (each: Item) => each.id;

test('withFirstPage_firstPageOverlapsHeld_keepsWhatFollowsItsLastItem', () => {
    const held = { items: items(5, 4, 3, 2, 1), next: 'after-1' };

    const merged = withFirstPage(held, { items: items(7, 6, 5), next: 'after-5' }, key);

    assert.deepEqual(ids(merged.items), [7, 6, 5, 4, 3, 2, 1]);
    assert.equal(merged.next, 'after-1');
});

test('withFirstPage_firstPageEndsWhereHeldEnds_isTheFirstPage', () => {
    const held = { items: items(3, 2, 1), next: 'after-1' };
    const first = { items: items(4, 3, 2, 1), next: 'after-1-now' };

    const merged = withFirstPage(held, first, key);

    assert.equal(merged, first);
});

test('withFirstPage_firstPageLastItemNotHeld_startsOverFromTheFirstPage', () => {
    const held = { items: items(5, 4, 3), next: 'after-3' };
    const first = { items: items(9, 8, 7), next: 'after-7' };

    const merged = withFirstPage(held, first, key);

    assert.equal(merged, first);
});

test('withFirstPage_hubHasNothingAfterFirstPage_dropsWhatTheListHeldBeyondIt', () => {
    const held = { items: items(5, 4, 3, 2, 1), next: 'after-1' };
    const first = { items: items(6, 5, 4), next: null };

    const merged = withFirstPage(held, first, key);

    assert.equal(merged, first);
});

test('withFirstPage_emptyFirstPage_isEmpty', () => {
    const merged = withFirstPage({ items: items(2, 1), next: 'after-1' }, { items: [], next: null }, key);

    assert.deepEqual(merged.items, []);
    assert.equal(merged.next, null);
});

test('withFirstPage_heldItemMovedIntoFirstPage_keepsItOnce', () => {
    const held = { items: items(5, 4, 3, 2, 1), next: 'after-1' };

    const merged = withFirstPage(held, { items: items(2, 5, 4), next: 'after-4' }, key);

    assert.deepEqual(ids(merged.items), [2, 5, 4, 3, 1]);
});

test('shareUnchanged_equalItems_keepsThePreviousObjects', () => {
    const previous = items(2, 1);

    const shared = shareUnchanged(previous, [item(3), item(2), item(1, 'changed')], key);

    assert.equal(shared[1], previous[0]);
    assert.notEqual(shared[2], previous[1]);
    assert.equal(shared[2].label, 'changed');
});

test('shareUnchanged_nothingChanged_isThePreviousArray', () => {
    const previous = items(2, 1);

    const shared = shareUnchanged(previous, items(2, 1), key);

    assert.equal(shared, previous);
});

test('shareUnchanged_sameItemsInAnotherOrder_isANewArrayOfThePreviousObjects', () => {
    const previous = items(2, 1);

    const shared = shareUnchanged(previous, items(1, 2), key);

    assert.notEqual(shared, previous);
    assert.equal(shared[0], previous[1]);
    assert.equal(shared[1], previous[0]);
});

test('shareUnchanged_noPreviousItems_isTheNextArray', () => {
    const next = items(1);

    assert.equal(shareUnchanged([], next, key), next);
});

test('sameData_nestedEqualValues_isTrue', () => {
    assert.equal(sameData({ a: [1, { b: 'c' }], d: null }, { a: [1, { b: 'c' }], d: null }), true);
});

test('sameData_differentNestedValue_isFalse', () => {
    assert.equal(sameData({ a: [1, { b: 'c' }] }, { a: [1, { b: 'd' }] }), false);
});

test('sameData_missingField_isFalse', () => {
    assert.equal(sameData({ a: 1, b: undefined }, { a: 1, c: undefined }), false);
});

test('sameData_arrayAndObject_isFalse', () => {
    assert.equal(sameData([], {}), false);
});

test('sameFields_sameValues_isTrue', () => {
    const shared = { id: 1 };

    assert.equal(sameFields({ item: shared, depth: 1 }, { item: shared, depth: 1 }), true);
});

test('sameFields_equalButOtherObject_isFalse', () => {
    assert.equal(sameFields({ item: { id: 1 } }, { item: { id: 1 } }), false);
});
