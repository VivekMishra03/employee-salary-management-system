import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/**
 * NFR-2: reject more than `places` decimal places without float arithmetic. `x * 100 % 1` is wrong
 * for values such as 1.15 (114.99999999999999), so the check is made on the number's decimal
 * string instead. Exponent notation (1e-7) never matches the pattern and is rejected too.
 * Empty values pass; `required` owns that case.
 */
export function maxDecimals(places: number, errorKey: string): ValidatorFn {
  const pattern = new RegExp('^-?\\d+(\\.\\d{1,' + places + '})?$');
  return (control: AbstractControl): ValidationErrors | null => {
    const value: unknown = control.value;
    if (value === null || value === undefined || value === '') {
      return null;
    }
    return pattern.test(String(value)) ? null : { [errorKey]: true };
  };
}
