import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { App } from './app';

// M0 exit criterion (requirements.md section 10): the Angular skeleton builds and renders.
//
// Jasmine + Karma, running in a real Chrome (docs/adr/0004). The app is zoneless -- Angular 21's
// default, and it has no zone.js dependency -- so TestBed needs provideZonelessChangeDetection()
// explicitly. Without it Angular raises NG0908 rather than silently doing the wrong thing.
//
// detectChanges() is load-bearing, not ceremony. Without it the test only proves the constructor
// ran: a component whose template throws on every render still passes. Bootstrapping runs change
// detection, so the test must too, or it does not cover what its name claims.
//
// The scaffold's "should render title" case was removed: it asserted the placeholder text in the
// generated welcome page, which M8 deletes, and pinned scaffold output rather than any specified
// behaviour. NFR-8 requires every test to reference the requirement it serves.
describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideZonelessChangeDetection()],
    }).compileComponents();
  });

  it('M0: the root component bootstraps and renders', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    expect(fixture.componentInstance).toBeTruthy();
  });
});
