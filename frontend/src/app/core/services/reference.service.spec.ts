import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ReferenceService } from './reference.service';
import { Department } from '../models/reference.model';

describe('ReferenceService', () => {
  let service: ReferenceService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ReferenceService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('FR-2.4: departments mirror DepartmentDto and are fetched once per session, however many subscribers', () => {
    const first: Department[][] = [];
    const second: Department[][] = [];
    service.getDepartments().subscribe(d => first.push(d));
    service.getDepartments().subscribe(d => second.push(d));

    const req = http.expectOne('/api/v1/reference/departments');
    req.flush([{ id: 1, code: 'ENG', name: 'Engineering', parentDepartmentId: null }]);
    http.expectNone('/api/v1/reference/departments');

    expect(first[0][0].parentDepartmentId).toBeNull();
    expect(second[0]).toEqual(first[0]);
  });

  it('FR-2.4: locations and job roles come from their own endpoints', () => {
    const locations: unknown[][] = [];
    const roles: unknown[][] = [];
    service.getLocations().subscribe(l => locations.push(l));
    service.getJobRoles().subscribe(r => roles.push(r));
    http.expectOne('/api/v1/reference/locations').flush([{ id: 1 }]);
    http.expectOne('/api/v1/reference/job-roles').flush([{ id: 2 }, { id: 3 }]);

    expect(locations[0].length).toBe(1);
    expect(roles[0].length).toBe(2);
  });
});
