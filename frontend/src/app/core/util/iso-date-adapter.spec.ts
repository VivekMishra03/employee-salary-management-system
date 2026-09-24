import { TestBed } from '@angular/core/testing';
import { MAT_DATE_LOCALE } from '@angular/material/core';
import { IsoDateAdapter } from './iso-date-adapter';

// FR-3.6: a typed effective date is a calendar day, not an instant. Native Date.parse reads the
// date-only ISO form as UTC midnight, which is the previous calendar day in any zone west of UTC
// and 05:30 (not midnight) in Asia/Calcutta. "Local midnight" is therefore the assertion: it fails
// in every zone except UTC when the adapter regresses, and CI runs the suite in two non-UTC zones.
describe('IsoDateAdapter.parse', () => {
  let adapter: IsoDateAdapter;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [IsoDateAdapter, { provide: MAT_DATE_LOCALE, useValue: 'en-US' }],
    });
    adapter = TestBed.inject(IsoDateAdapter);
  });

  it('FR-3.6: a typed yyyy-MM-dd is that calendar day at local midnight', () => {
    const parsed = adapter.parse('2026-10-01') as Date;

    expect(parsed.getFullYear()).toBe(2026);
    expect(parsed.getMonth()).toBe(9);
    expect(parsed.getDate()).toBe(1);
    expect(parsed.getHours()).toBe(0);
    expect(parsed.getMinutes()).toBe(0);
    expect(parsed.getTime()).toBe(new Date(2026, 9, 1).getTime());
  });

  it('FR-3.6: 29 February is accepted in a leap year and rejected in any other', () => {
    expect(adapter.isValid(adapter.parse('2028-02-29') as Date)).toBeTrue();
    expect(adapter.isValid(adapter.parse('2027-02-29') as Date)).toBeFalse();
  });

  it('FR-3.6: an impossible calendar date is invalid rather than rolled into the next month', () => {
    const parsed = adapter.parse('2026-02-30') as Date;

    expect(adapter.isValid(parsed)).toBeFalse();
    expect(adapter.isValid(adapter.parse('2026-13-01') as Date)).toBeFalse();
    expect(adapter.isValid(adapter.parse('2026-04-31') as Date)).toBeFalse();
  });

  it('FR-3.6: any other format is still parsed by the base adapter, as a local date', () => {
    const parsed = adapter.parse('10/1/2026') as Date;

    expect(parsed.getFullYear()).toBe(2026);
    expect(parsed.getMonth()).toBe(9);
    expect(parsed.getDate()).toBe(1);
  });

  it('FR-3.6: empty input is null and unparseable text is an invalid date', () => {
    expect(adapter.parse('')).toBeNull();
    expect(adapter.parse(null)).toBeNull();
    expect(adapter.isValid(adapter.parse('not a date') as Date)).toBeFalse();
  });
});
