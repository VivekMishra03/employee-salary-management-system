import { MoneyPipe } from './money.pipe';

// NFR-2: the API sends NUMERIC(15,2) values; the UI only formats them and never does arithmetic.
// Formatting must show exactly two decimals so 1500 and 1500.5 read as money, not as counts.
describe('MoneyPipe', () => {
  const pipe = new MoneyPipe();

  it('NFR-2: renders two decimals with thousands grouping', () => {
    expect(pipe.transform(1234567.5)).toBe('1,234,567.50');
  });

  it('NFR-2: a whole number still shows .00', () => {
    expect(pipe.transform(85000)).toBe('85,000.00');
  });

  it('NFR-2: appends the currency code when given', () => {
    expect(pipe.transform(85000, 'EUR')).toBe('85,000.00 EUR');
  });

  it('NFR-2: a missing amount (no salary record) renders a dash, not "null"', () => {
    expect(pipe.transform(null)).toBe('—');
    expect(pipe.transform(undefined)).toBe('—');
  });
});
