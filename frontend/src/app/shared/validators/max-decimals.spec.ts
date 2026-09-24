import { FormControl } from '@angular/forms';
import { maxDecimals } from './max-decimals';

// NFR-2: money is validated on its decimal string, never with float arithmetic. The cases below are
// the ones a float-based check gets wrong (1.15, 0.07) or that only the string form exposes
// (exponent notation, binary-float noise such as 0.1 + 0.2).
describe('maxDecimals', () => {
  const twoDecimals = maxDecimals(2, 'twoDecimals');
  const threeDecimals = maxDecimals(3, 'threeDecimals');
  const check = (validator: typeof twoDecimals, value: unknown) => validator(new FormControl(value));

  describe('two places (money amounts, FR-3.3)', () => {
    it('NFR-2: 0.07 and 1.15 are valid although x * 100 is not an integer in binary floating point', () => {
      expect(check(twoDecimals, 0.07)).toBeNull();
      expect(check(twoDecimals, 1.15)).toBeNull();
    });

    it('NFR-2: whole numbers and one decimal place are valid', () => {
      expect(check(twoDecimals, 1000)).toBeNull();
      expect(check(twoDecimals, 1000.5)).toBeNull();
      expect(check(twoDecimals, '1000.50')).toBeNull();
    });

    it('NFR-2: a third decimal place is rejected, however small', () => {
      expect(check(twoDecimals, 1.005)).toEqual({ twoDecimals: true });
      expect(check(twoDecimals, 1000.005)).toEqual({ twoDecimals: true });
      expect(check(twoDecimals, '1.005')).toEqual({ twoDecimals: true });
    });

    it('NFR-2: the largest NUMERIC(15,2) amount, 9999999999999.99, is valid', () => {
      expect(check(twoDecimals, 9999999999999.99)).toBeNull();
    });

    it('NFR-2: exponent notation is rejected in both directions, not read as a number of places', () => {
      expect(check(twoDecimals, 1e-7)).toEqual({ twoDecimals: true });   // String(1e-7) === '1e-7'
      expect(check(twoDecimals, 1e21)).toEqual({ twoDecimals: true });   // String(1e21) === '1e+21'
    });

    it('NFR-2: the result of float arithmetic (0.1 + 0.2 = 0.30000000000000004) is rejected', () => {
      expect(check(twoDecimals, 0.1 + 0.2)).toEqual({ twoDecimals: true });
    });

    it('NFR-2: the sign is not this validator\'s concern; a negative amount with two places passes it', () => {
      expect(check(twoDecimals, -1.5)).toBeNull();
    });

    it('NFR-2: empty values pass so that "required" alone reports a missing value', () => {
      expect(check(twoDecimals, null)).toBeNull();
      expect(check(twoDecimals, undefined)).toBeNull();
      expect(check(twoDecimals, '')).toBeNull();
    });

    it('NFR-2: the error key is the one the caller supplied', () => {
      expect(maxDecimals(2, 'anyKey')(new FormControl(1.005))).toEqual({ anyKey: true });
    });
  });

  describe('three places (FTE ratio, FR-2.1)', () => {
    it('FR-2.1: 0.001, 0.125 and 1 are valid', () => {
      expect(check(threeDecimals, 0.001)).toBeNull();
      expect(check(threeDecimals, 0.125)).toBeNull();
      expect(check(threeDecimals, 1)).toBeNull();
    });

    it('FR-2.1: a fourth decimal place is rejected', () => {
      expect(check(threeDecimals, 0.1234)).toEqual({ threeDecimals: true });
      expect(check(threeDecimals, 0.0001)).toEqual({ threeDecimals: true });
    });

    it('FR-2.1: an empty value passes', () => {
      expect(check(threeDecimals, null)).toBeNull();
    });
  });
});
