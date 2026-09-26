import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { LineChartComponent, LinePoint } from './line-chart.component';

describe('LineChartComponent', () => {
  let fixture: ComponentFixture<LineChartComponent>;

  async function render(points: LinePoint[]): Promise<HTMLElement> {
    fixture.componentRef.setInput('points', points);
    fixture.componentRef.setInput('caption', 'Total payroll by month');
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const paths = (el: HTMLElement): SVGPathElement[] => Array.from(el.querySelectorAll<SVGPathElement>('path.series'));
  const dots = (el: HTMLElement): SVGCircleElement[] => Array.from(el.querySelectorAll<SVGCircleElement>('circle.dot'));
  const xLabels = (el: HTMLElement): string[] =>
    Array.from(el.querySelectorAll('text.x-label')).map(t => t.textContent?.trim() ?? '');

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LineChartComponent],
      providers: [provideZonelessChangeDetection()],
    }).compileComponents();
    fixture = TestBed.createComponent(LineChartComponent);
  });

  it('FR-4.6: draws one line through the points and one dot per point, higher values higher on the chart', async () => {
    const el = await render([
      { label: '2026-01', value: 100 },
      { label: '2026-02', value: 200 },
      { label: '2026-03', value: 300 },
    ]);

    expect(paths(el).length).toBe(1);
    const cy = dots(el).map(d => Number(d.getAttribute('cy')));
    expect(cy.length).toBe(3);
    expect(cy[0]).toBeGreaterThan(cy[1]); // SVG y grows downwards: a bigger value has a smaller cy
    expect(cy[1]).toBeGreaterThan(cy[2]);
    const cx = dots(el).map(d => Number(d.getAttribute('cx')));
    expect(cx[0]).toBeLessThan(cx[1]);
    expect(cx[1]).toBeLessThan(cx[2]);
  });

  it('FR-4.6: labels the first and last period on the x axis', async () => {
    const el = await render([
      { label: '2026-01', value: 1 }, { label: '2026-02', value: 2 }, { label: '2026-03', value: 3 },
    ]);

    expect(xLabels(el)).toEqual(['2026-01', '2026-03']);
  });

  it('FR-4.6: labels the y axis with several round ticks', async () => {
    const el = await render([{ label: 'a', value: 0 }, { label: 'b', value: 100 }]);

    const ticks = Array.from(el.querySelectorAll('text.y-label')).map(t => t.textContent?.trim());
    expect(ticks).toEqual(['0', '20', '40', '60', '80', '100']);
  });

  it('FR-4.6: a null value is a gap: the line breaks there and no dot is drawn for it', async () => {
    const el = await render([
      { label: 'a', value: 1 }, { label: 'b', value: 2 }, { label: 'c', value: null },
      { label: 'd', value: 4 }, { label: 'e', value: 5 },
    ]);

    expect(paths(el).length).toBe(2);
    expect(dots(el).length).toBe(4);
  });

  it('FR-4.6: each dot has a title with the period and the formatted value, for hover', async () => {
    const el = await render([{ label: '2026-03', value: 1234.5, valueText: '1,234.50' }, { label: '2026-04', value: 5 }]);

    expect(dots(el)[0].querySelector('title')?.textContent).toBe('2026-03: 1,234.50');
  });

  it('NFR-2: a single point draws one dot in the middle with no NaN and one x label', async () => {
    const el = await render([{ label: '2026', value: 5000 }]);

    expect(dots(el).length).toBe(1);
    expect(el.innerHTML).not.toContain('NaN');
    expect(xLabels(el)).toEqual(['2026']);
    const cx = Number(dots(el)[0].getAttribute('cx'));
    expect(Number.isFinite(cx)).toBeTrue();
  });

  it('NFR-2: a flat series draws a horizontal line with no NaN', async () => {
    const el = await render([{ label: 'a', value: 7 }, { label: 'b', value: 7 }, { label: 'c', value: 7 }]);

    const cy = new Set(dots(el).map(d => d.getAttribute('cy')));
    expect(cy.size).toBe(1);
    expect(el.innerHTML).not.toContain('NaN');
  });

  it('NFR-2: an all-zero series draws without NaN', async () => {
    const el = await render([{ label: 'a', value: 0 }, { label: 'b', value: 0 }]);

    expect(dots(el).length).toBe(2);
    expect(el.innerHTML).not.toContain('NaN');
  });

  it('NFR-2: very large payroll values draw without NaN or Infinity', async () => {
    const el = await render([{ label: 'a', value: 9_000_000_000_000 }, { label: 'b', value: 9_500_000_000_000 }]);

    expect(el.innerHTML).not.toContain('NaN');
    expect(el.innerHTML).not.toContain('Infinity');
    expect(dots(el).length).toBe(2);
  });

  it('FR-4.6: no points, or only nulls, shows a message instead of an empty axis', async () => {
    let el = await render([]);
    expect(el.textContent).toContain('No data to chart');
    expect(el.querySelector('svg')).toBeNull();

    el = await render([{ label: 'a', value: null }, { label: 'b', value: null }]);
    expect(el.textContent).toContain('No data to chart');
    expect(el.querySelector('svg')).toBeNull();
  });

  it('FR-4.6: offers a text alternative, a table with every period, a dash for a gap, and the caption', async () => {
    const el = await render([
      { label: 'a', value: 1, valueText: '1.00' }, { label: 'b', value: null }, { label: 'c', value: 3, valueText: '3.00' },
    ]);

    const table = el.querySelector('table.visually-hidden') as HTMLTableElement;
    expect(table.caption?.textContent).toBe('Total payroll by month');
    const rows = Array.from(table.querySelectorAll('tbody tr'))
      .map(r => Array.from(r.children).map(c => c.textContent?.trim()));
    expect(rows).toEqual([['a', '1.00'], ['b', '—'], ['c', '3.00']]);
    expect(el.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
  });
});
