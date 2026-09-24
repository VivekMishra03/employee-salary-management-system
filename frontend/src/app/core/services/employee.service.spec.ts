import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { EmployeeService } from './employee.service';
import { CreateEmployeeRequest, Page, EmployeeListItem, UpdateEmployeeRequest } from '../models/employee.model';

describe('EmployeeService', () => {
  let service: EmployeeService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(EmployeeService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('FR-2.2: search asks the server for one page, with page, size and sort as query parameters', () => {
    service.search({}, 3, 25, 'lastName,asc').subscribe();

    const req = http.expectOne(r => r.url === '/api/v1/employees');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('3');
    expect(req.request.params.get('size')).toBe('25');
    expect(req.request.params.get('sort')).toBe('lastName,asc');
    expect(req.request.params.keys()).toEqual(['page', 'size', 'sort']);
    req.flush({ content: [], page: 3, size: 25, totalElements: 0, totalPages: 0 });
  });

  it('FR-2.3 / FR-2.4: search passes the free-text query and every combinable filter', () => {
    service.search(
      { q: 'ada byron', departmentId: 4, countryCode: 'DE', status: 'ACTIVE', employmentType: 'FULL_TIME', jobLevel: 'L3' },
      0, 20, 'employeeCode,desc',
    ).subscribe();

    const p = http.expectOne(r => r.url === '/api/v1/employees').request.params;
    expect(p.get('q')).toBe('ada byron');
    expect(p.get('departmentId')).toBe('4');
    expect(p.get('countryCode')).toBe('DE');
    expect(p.get('status')).toBe('ACTIVE');
    expect(p.get('employmentType')).toBe('FULL_TIME');
    expect(p.get('jobLevel')).toBe('L3');
  });

  it('FR-2.2: the page envelope mirrors PageResponse (content, page, size, totalElements, totalPages)', () => {
    let page: Page<EmployeeListItem> | undefined;
    service.search({}, 0, 20, 'lastName,asc').subscribe(p => (page = p));
    http.expectOne(r => r.url === '/api/v1/employees')
      .flush({ content: [], page: 0, size: 20, totalElements: 10000, totalPages: 500 });

    expect(page?.totalElements).toBe(10000);
    expect(page?.totalPages).toBe(500);
    expect(page?.page).toBe(0);
  });

  it('FR-2.5: get fetches one employee by id', () => {
    service.get(42).subscribe();
    const req = http.expectOne('/api/v1/employees/42');
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('FR-2.1: create posts the new employee', () => {
    const body: CreateEmployeeRequest = {
      employeeCode: 'E10001', firstName: 'Ada', lastName: 'Byron', email: 'ada@acme.com', gender: null,
      hireDate: '2026-10-01', employmentType: 'FULL_TIME', fteRatio: 1, departmentId: 2, jobRoleId: 5,
      locationId: 3, managerId: null,
    };
    service.create(body).subscribe();
    const req = http.expectOne('/api/v1/employees');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('FR-2.6: update sends the version the client read, so a stale write can be refused', () => {
    const body: UpdateEmployeeRequest = {
      version: 7, firstName: 'Ada', lastName: 'Lovelace', email: 'ada@acme.com', gender: 'FEMALE',
      employmentType: 'FULL_TIME', fteRatio: 0.8, departmentId: 2, jobRoleId: 5, locationId: 3,
      managerId: 9, employmentStatus: 'ACTIVE',
    };
    service.update(42, body).subscribe();
    const req = http.expectOne('/api/v1/employees/42');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.version).toBe(7);
    req.flush({});
  });

  it('FR-2.1: deactivate is a DELETE (soft delete on the server)', () => {
    service.deactivate(42).subscribe();
    const req = http.expectOne('/api/v1/employees/42');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });
});
