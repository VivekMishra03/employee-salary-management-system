import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { RecordSalaryDialogComponent } from './record-salary-dialog.component';
import { expectNoHorizontalOverflow, setHostWidth } from '../../../../testing/layout';

// Section 4 (responsive web), FR-3.2: recording a salary change on a phone.
describe('RecordSalaryDialogComponent layout at phone, tablet and desktop widths', () => {
  let fixture: ComponentFixture<RecordSalaryDialogComponent>;

  const el = (): HTMLElement => fixture.nativeElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RecordSalaryDialogComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { employeeId: 42, employeeName: 'Ada Byron' } },
        { provide: MatDialogRef, useValue: jasmine.createSpyObj('MatDialogRef', ['close']) },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(RecordSalaryDialogComponent);
    await fixture.whenStable();
  });

  for (const width of [360, 768]) {
    it(`at ${width}px the form does not scroll sideways`, () => {
      expectNoHorizontalOverflow(setHostWidth(fixture, width));
    });
  }

  it('at 360px each field spans the form', () => {
    setHostWidth(fixture, 360);
    const form = el().querySelector('.salary-form') as HTMLElement;

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

  it('at 1280px the action buttons stay on one row at the right', () => {
    setHostWidth(fixture, 1280);
    const buttons = Array.from(el().querySelectorAll('mat-dialog-actions button')) as HTMLElement[];

    expect(buttons[1].getBoundingClientRect().top).toBeLessThan(buttons[0].getBoundingClientRect().bottom);
  });
});
