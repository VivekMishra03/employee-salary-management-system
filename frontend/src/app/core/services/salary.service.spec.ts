import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { SalaryService } from './salary.service';
import { RecordSalaryRequest } from '../models/employee.model';

describe('SalaryService', () => {
  let service: SalaryService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SalaryService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('FR-3.1: history reads /employees/{id}/salaries', () => {
    service.getHistory(42).subscribe();
    const req = http.expectOne('/api/v1/employees/42/salaries');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('FR-3.2 – FR-3.4: record posts the change without a currency (the server takes it from the location)', () => {
    const body: RecordSalaryRequest = {
      effectiveFrom: '2026-10-01', baseAmount: 95000, payFrequency: 'ANNUAL', targetBonusPct: 10,
      changeReason: 'MERIT_INCREASE', notes: null,
    };
    service.record(42, body).subscribe();
    const req = http.expectOne('/api/v1/employees/42/salaries');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    expect('currencyCode' in req.request.body).toBeFalse();
    req.flush({});
  });
});
