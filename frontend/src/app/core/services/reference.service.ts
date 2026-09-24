import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, shareReplay } from 'rxjs';
import { Department, JobRole, Location } from '../models/reference.model';

/**
 * Reference data for filter/form dropdowns (FR-2.3, FR-2.4).
 * Results are shared-replayed so every component shares one HTTP call
 * per app session without re-fetching on re-subscribe.
 */
@Injectable({ providedIn: 'root' })
export class ReferenceService {
  private static readonly BASE = '/api/v1/reference';

  private departments$?: Observable<Department[]>;
  private locations$?:   Observable<Location[]>;
  private jobRoles$?:    Observable<JobRole[]>;

  constructor(private http: HttpClient) {}

  getDepartments(): Observable<Department[]> {
    if (!this.departments$) {
      this.departments$ = this.http
        .get<Department[]>(`${ReferenceService.BASE}/departments`)
        .pipe(shareReplay(1));
    }
    return this.departments$;
  }

  getLocations(): Observable<Location[]> {
    if (!this.locations$) {
      this.locations$ = this.http
        .get<Location[]>(`${ReferenceService.BASE}/locations`)
        .pipe(shareReplay(1));
    }
    return this.locations$;
  }

  getJobRoles(): Observable<JobRole[]> {
    if (!this.jobRoles$) {
      this.jobRoles$ = this.http
        .get<JobRole[]>(`${ReferenceService.BASE}/job-roles`)
        .pipe(shareReplay(1));
    }
    return this.jobRoles$;
  }
}
