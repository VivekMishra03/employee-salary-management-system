import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { EmployeeDetailComponent } from './employee-detail.component';
import { detail } from '../../../../testing/fixtures';
import { expectKeyboardScrollRegion, expectNoHorizontalOverflow, setHostWidth } from '../../../../testing/layout';

// Section 4 (responsive web), FR-2.5, FR-3.1: the employee page and its salary history on a phone.
describe('EmployeeDetailComponent layout at phone, tablet and desktop widths', () => {
  let fixture: ComponentFixture<EmployeeDetailComponent>;

  const el = (): HTMLElement => fixture.nativeElement;
  const q = <T extends HTMLElement>(selector: string): T => el().querySelector(selector) as T;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EmployeeDetailComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '42' }) } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(EmployeeDetailComponent);
    await fixture.whenStable();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/employees/42')
      .flush(detail({ email: 'ada.byron.with.a.very.long.address@subdomain.example-company.com' }));
    await fixture.whenStable();
  });

  const openHistory = async (): Promise<void> => {
    (el().querySelectorAll('.mat-mdc-tab')[1] as HTMLElement).click();
    await fixture.whenStable();
  };

  for (const width of [360, 768]) {
    it(`at ${width}px the profile tab does not scroll sideways`, () => {
      expectNoHorizontalOverflow(setHostWidth(fixture, width), { ignore: '.table-scroll' });
    });

    it(`at ${width}px the salary history tab does not scroll sideways: only the table box scrolls`, async () => {
      await openHistory();

      expectNoHorizontalOverflow(setHostWidth(fixture, width), { ignore: '.table-scroll' });
    });
  }

  it('at 360px the salary history sits in a box that scrolls sideways', async () => {
    await openHistory();
    setHostWidth(fixture, 360);
    const box = q('.table-scroll');

    expect(box.contains(q('table.salary-history'))).toBeTrue();
    expect(getComputedStyle(box).overflowX).toBe('auto');
    expect(box.scrollWidth).toBeGreaterThan(box.clientWidth);
  });

  it('at 1280px the salary history fits its box', async () => {
    await openHistory();
    setHostWidth(fixture, 1280);
    const box = q('.table-scroll');

    expect(box.scrollWidth).toBeLessThanOrEqual(box.clientWidth + 1);
  });

  it('at 360px the four actions stay inside the header card and are at least 44px tall', () => {
    setHostWidth(fixture, 360);
    const card = q('.header-card').getBoundingClientRect();
    const buttons = Array.from(el().querySelectorAll('.header-card mat-card-actions button')) as HTMLElement[];

    expect(buttons.length).toBe(4);
    for (const button of buttons) {
      const rect = button.getBoundingClientRect();
      expect(rect.right).toBeLessThanOrEqual(card.right);
      expect(rect.left).toBeGreaterThanOrEqual(card.left);
      expect(rect.height).toBeGreaterThanOrEqual(44);
    }
  });

  it('at 360px the profile labels sit above their values, and beside them at 1280px', () => {
    setHostWidth(fixture, 360);
    const dt = q('.profile dt');
    const dd = q('.profile dd');
    expect(dd.getBoundingClientRect().left).toBe(dt.getBoundingClientRect().left);
    expect(dd.getBoundingClientRect().top).toBeGreaterThanOrEqual(dt.getBoundingClientRect().bottom);

    setHostWidth(fixture, 1280);
    expect(dd.getBoundingClientRect().left).toBeGreaterThan(dt.getBoundingClientRect().left + 100);
  });

  it('the salary history box is a focusable, labelled region with a visible focus outline', async () => {
    await openHistory();
    setHostWidth(fixture, 360);

    expectKeyboardScrollRegion(q('.table-scroll'));
    expect(q('.table-scroll').getAttribute('aria-label')).toBe('Salary history table, scrolls horizontally');
  });
});
