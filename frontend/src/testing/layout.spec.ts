import { horizontalOverflowOffenders } from './layout';

// Section 4 (responsive web): the overflow helper is itself a test instrument, so it must be able to fail.
describe('horizontalOverflowOffenders (test helper)', () => {
  let host: HTMLElement;

  beforeEach(() => {
    host = document.createElement('div');
    host.style.cssText = 'width:360px;position:relative';
    document.body.appendChild(host);
  });

  afterEach(() => host.remove());

  const child = (css: string, parent: HTMLElement = host, cls = ''): HTMLElement => {
    const el = document.createElement('div');
    el.style.cssText = css;
    el.className = cls;
    parent.appendChild(el);
    return el;
  };

  it('reports a 500px child inside a 360px host', () => {
    child('width:500px;height:10px');

    expect(horizontalOverflowOffenders(host).length).toBeGreaterThan(0);
  });

  it('reports nothing when everything fits', () => {
    child('width:100%;height:10px');

    expect(horizontalOverflowOffenders(host)).toEqual([]);
  });

  it('skips descendants of a marked scroll container, which scrolls instead of overflowing', () => {
    const scroller = child('overflow-x:auto', host, 'table-scroll');
    child('width:500px;height:10px', scroller);

    expect(horizontalOverflowOffenders(host, { ignore: '.table-scroll' })).toEqual([]);
  });

  it('still reports the scroll container itself when it is wider than the host', () => {
    const scroller = child('overflow-x:auto;width:450px', host, 'table-scroll');
    child('width:500px;height:10px', scroller);

    expect(horizontalOverflowOffenders(host, { ignore: '.table-scroll' }).length).toBeGreaterThan(0);
  });

  it('reports content wider than an unlisted overflow:hidden ancestor, because that content is cut off', () => {
    const clip = child('overflow:hidden;width:200px');
    child('width:300px;height:10px', clip);

    expect(horizontalOverflowOffenders(host, { ignore: '.table-scroll' }).length).toBeGreaterThan(0);
  });

  it('accepts the same content inside a listed scroll container, which scrolls it', () => {
    const scroller = child('overflow-x:auto;width:200px', host, 'table-scroll');
    child('width:300px;height:10px', scroller);

    expect(horizontalOverflowOffenders(host, { ignore: '.table-scroll' })).toEqual([]);
  });

  it('skips a parked inactive tab body, which is not rendered', () => {
    const parked = child('overflow:hidden;visibility:hidden;width:200px');
    child('width:500px;height:10px', parked);

    expect(horizontalOverflowOffenders(host)).toEqual([]);
  });

  it('skips a display:none subtree and a zero-size ancestor', () => {
    const none = child('display:none');
    child('width:500px;height:10px', none);
    const zero = child('width:0;height:0;overflow:hidden');
    child('width:500px;height:10px', zero);

    expect(horizontalOverflowOffenders(host)).toEqual([]);
  });

  it('reports an overflowing child inside an aria-hidden subtree, because aria-hidden does not stop painting', () => {
    const decorative = child('', host);
    decorative.setAttribute('aria-hidden', 'true');
    child('width:500px;height:10px', decorative);

    // The host's own scrollWidth is always reported too; the element-level entry is what aria-hidden used to hide.
    expect(horizontalOverflowOffenders(host).some(o => o.includes('> host right'))).toBe(true);
  });

  it('skips a display:none subtree even when it is also aria-hidden', () => {
    const none = child('display:none');
    none.setAttribute('aria-hidden', 'true');
    child('width:500px;height:10px', none);

    expect(horizontalOverflowOffenders(host)).toEqual([]);
  });

  it('skips content inside a zero-width overflow:hidden ancestor, which paints nothing (Material toggle checkmark)', () => {
    const collapsed = child('width:0;height:18px;overflow:hidden');
    child('width:500px;height:10px', collapsed);

    expect(horizontalOverflowOffenders(host)).toEqual([]);
  });

  it('skips visually hidden elements', () => {
    const hidden = child('position:absolute;width:1px;height:1px;overflow:hidden', host, 'visually-hidden');
    child('width:500px;height:10px', hidden);

    expect(horizontalOverflowOffenders(host)).toEqual([]);
  });
});
