import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { EmployeeFormDialogComponent } from './employee-form-dialog.component';
import { DEPARTMENTS, JOB_ROLES, LOCATIONS } from '../../../../testing/fixtures';
import { expectNoHorizontalOverflow, setHostWidth } from '../../../../testing/layout';

// Section 4 (responsive web), FR-2.1: the create form is two columns in a desktop dialog and one on a phone.
describe('EmployeeFormDialogComponent layout at phone, tablet and desktop widths', () => {
  let fixture: ComponentFixture<EmployeeFormDialogComponent>;

  const el = (): HTMLElement => fixture.nativeElement;
  const left = (selector: string): number => (el().querySelector(selector) as HTMLElement).getBoundingClientRect().left;

  const dialogRef = (): jasmine.SpyObj<MatDialogRef<EmployeeFormDialogComponent>> => {
    const ref = jasmine.createSpyObj('MatDialogRef', ['close', 'backdropClick', 'keydownEvents']);
    ref.backdropClick.and.returnValue(new Subject());
    ref.keydownEvents.and.returnValue(new Subject());
    return ref;
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EmployeeFormDialogComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { employee: null } },
        { provide: MatDialogRef, useValue: dialogRef() },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(EmployeeFormDialogComponent);
    await fixture.whenStable();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/reference/departments').flush(DEPARTMENTS);
    http.expectOne('/api/v1/reference/locations').flush(LOCATIONS);
    http.expectOne('/api/v1/reference/job-roles').flush(JOB_ROLES);
    await fixture.whenStable();
  });

  for (const width of [360, 768]) {
    it(`at ${width}px the form does not scroll sideways`, () => {
      expectNoHorizontalOverflow(setHostWidth(fixture, width));
    });
  }

  for (const width of [360, 480]) {
    it(`at ${width}px the fields of a row are stacked in one column`, () => {
      setHostWidth(fixture, width);

      expect(left('#emp-first-name')).toBe(left('#emp-last-name'));
      expect(left('#emp-code')).toBe(left('#emp-hire-date'));
    });
  }

  for (const width of [640, 1280]) {
    it(`at ${width}px the fields of a row sit side by side`, () => {
      setHostWidth(fixture, width);

      expect(left('#emp-last-name')).toBeGreaterThan(left('#emp-first-name') + 100);
    });
  }

  it('at 360px each field spans the form', () => {
    setHostWidth(fixture, 360);
    const form = el().querySelector('.employee-form') as HTMLElement;

    for (const field of Array.from(el().querySelectorAll('mat-form-field')) as HTMLElement[]) {
      expect(field.getBoundingClientRect().width).toBeCloseTo(form.getBoundingClientRect().width, 0);
    }
  });

  it('at 360px the action buttons are stacked, full width and at least 44px tall', () => {
    setHostWidth(fixture, 360);
    const buttons = Array.from(el().querySelectorAll('mat-dialog-actions button')) as HTMLElement[];

    expect(buttons.length).toBe(2);
    expect(Math.abs(buttons[1].getBoundingClientRect().top - buttons[0].getBoundingClientRect().top)).toBeGreaterThanOrEqual(44);
    expect(buttons[1].getBoundingClientRect().width).toBeCloseTo(buttons[0].getBoundingClientRect().width, 0);
    for (const button of buttons) {
      expect(button.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
    }
  });
});
