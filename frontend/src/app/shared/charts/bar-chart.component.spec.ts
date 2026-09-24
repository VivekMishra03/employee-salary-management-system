import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { BarChartComponent, BarDatum } from './bar-chart.component';

describe('BarChartComponent', () => {
  let fixture: ComponentFixture<BarChartComponent>;

  async function render(bars: BarDatum[]): Promise<HTMLElement> {
    fixture.componentRef.setInput('bars', bars);
    fixture.componentRef.setInput('caption', 'Median pay by department');
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const bars = (el: HTMLElement): HTMLElement[] => Array.from(el.querySelectorAll<HTMLElement>('.bar'));

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BarChartComponent],
      providers: [provideZonelessChangeDetection()],
    }).compileComponents();
    fixture = TestBed.createComponent(BarChartComponent);
  });

  it('FR-4.2: draws one bar per datum, with the largest filling the track and the others in proportion', async () => {
    const el = await render([
      { label: 'Engineering', value: 200000, title: 'Engineering: 200,000.00' },
      { label: 'Sales', value: 100000, title: 'Sales: 100,000.00' },
    ]);

    expect(bars(el).map(b => b.style.width)).toEqual(['100%', '50%']);
    expect(el.textContent).toContain('Engineering');
    expect(el.textContent).toContain('Sales');
  });

  it('FR-4.2: each bar carries its title, so the exact figure is on hover', async () => {
    const el = await render([{ label: 'Sales', value: 1, title: 'Sales: 12 employees' }]);

    expect(el.querySelector('.bar-row')?.getAttribute('title')).toBe('Sales: 12 employees');
  });

  it('FR-4.2: the value text shown beside a bar is the pre-formatted text, not the raw number', async () => {
    const el = await render([{ label: 'Sales', value: 1234.5, title: 't', valueText: '1,234.50 (12)' }]);

    expect(el.querySelector('.bar-value')?.textContent?.trim()).toBe('1,234.50 (12)');
  });

  it('FR-4.2: offers a screen-reader alternative, a table of every label and value with the caption', async () => {
    const el = await render([
      { label: 'Engineering', value: 200000, title: 't1', valueText: '200,000.00' },
      { label: 'Sales', value: 100000, title: 't2', valueText: '100,000.00' },
    ]);

    const table = el.querySelector('table.visually-hidden') as HTMLTableElement;
    expect(table.caption?.textContent).toBe('Median pay by department');
    const rows = Array.from(table.querySelectorAll('tbody tr'))
      .map(r => Array.from(r.children).map(c => c.textContent?.trim()));
    expect(rows).toEqual([['Engineering', '200,000.00'], ['Sales', '100,000.00']]);
    expect(el.querySelector('.bars')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('NFR-2: no data shows a message and no bars', async () => {
    const el = await render([]);

    expect(bars(el).length).toBe(0);
    expect(el.textContent).toContain('No data to chart');
  });

  it('NFR-2: all-zero data draws zero-width bars and no NaN anywhere in the markup', async () => {
    const el = await render([
      { label: 'A', value: 0, title: 'A' },
      { label: 'B', value: 0, title: 'B' },
    ]);

    expect(bars(el).map(b => b.style.width)).toEqual(['0%', '0%']);
    expect(el.innerHTML).not.toContain('NaN');
  });

  it('NFR-2: a value that is not a finite number is drawn empty, not as NaN', async () => {
    const el = await render([{ label: 'A', value: Number.NaN, title: 'A' }, { label: 'B', value: 5, title: 'B' }]);

    expect(bars(el).map(b => b.style.width)).toEqual(['0%', '100%']);
    expect(el.innerHTML).not.toContain('NaN');
  });
});
