import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { LoginComponent } from './login.component';
import { expectNoHorizontalOverflow, setHostWidth } from '../../../testing/layout';

// Section 4 (responsive web), FR-1.1: the sign-in card fits a phone with a gutter either side.
describe('LoginComponent layout at phone, tablet and desktop widths', () => {
  let fixture: ComponentFixture<LoginComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    fixture = TestBed.createComponent(LoginComponent);
    await fixture.whenStable();
  });

  const card = (): HTMLElement => fixture.nativeElement.querySelector('.login-card');

  for (const width of [360, 768, 1280]) {
    it(`at ${width}px the page does not scroll sideways`, () => {
      expectNoHorizontalOverflow(setHostWidth(fixture, width));
    });
  }

  it('at 360px the card leaves a 16px gutter on each side', () => {
    const host = setHostWidth(fixture, 360);

    expect(card().getBoundingClientRect().width).toBeCloseTo(328, 0);
    expect(card().getBoundingClientRect().left - host.getBoundingClientRect().left).toBeCloseTo(16, 0);
  });

  it('at 1280px the card keeps its 380px width', () => {
    setHostWidth(fixture, 1280);

    expect(card().getBoundingClientRect().width).toBeCloseTo(380, 0);
  });

  it('at 360px each field spans the form', () => {
    setHostWidth(fixture, 360);
    const form = fixture.nativeElement.querySelector('.login-form') as HTMLElement;

    for (const field of Array.from(fixture.nativeElement.querySelectorAll('mat-form-field')) as HTMLElement[]) {
      expect(field.getBoundingClientRect().width).toBeCloseTo(form.getBoundingClientRect().width, 0);
    }
  });
});
