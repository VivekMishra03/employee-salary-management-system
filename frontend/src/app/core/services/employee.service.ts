import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  CreateEmployeeRequest,
  EmployeeDetail,
  EmployeeListItem,
  Page,
  UpdateEmployeeRequest
} from '../models/employee.model';

/** FR-2.1 – FR-2.6: Employee CRUD and server-side search. */
@Injectable({ providedIn: 'root' })
export class EmployeeService {
  private static readonly BASE = '/api/v1/employees';

  constructor(private http: HttpClient) {}

  /**
   * FR-2.2 – FR-2.4: Server-side paginated, sorted, filtered search.
   * The client never receives the full 10,000-row dataset.
   */
  search(
    filter: {
      q?:              string;
      departmentId?:   number;
      countryCode?:    string;
      status?:         string;
      employmentType?: string;
      jobLevel?:       string;
    },
    page: number,
    size: number,
    sort: string
  ): Observable<Page<EmployeeListItem>> {
    let params = new HttpParams()
      .set('page', page)
      .set('size', size)
      .set('sort', sort);

    if (filter.q)              params = params.set('q',              filter.q);
    if (filter.departmentId)   params = params.set('departmentId',   filter.departmentId);
    if (filter.countryCode)    params = params.set('countryCode',    filter.countryCode);
    if (filter.status)         params = params.set('status',         filter.status);
    if (filter.employmentType) params = params.set('employmentType', filter.employmentType);
    if (filter.jobLevel)       params = params.set('jobLevel',       filter.jobLevel);

    return this.http.get<Page<EmployeeListItem>>(EmployeeService.BASE, { params });
  }

  /** FR-2.5 */
  get(id: number): Observable<EmployeeDetail> {
    return this.http.get<EmployeeDetail>(`${EmployeeService.BASE}/${id}`);
  }

  /** FR-2.1 */
  create(req: CreateEmployeeRequest): Observable<EmployeeDetail> {
    return this.http.post<EmployeeDetail>(EmployeeService.BASE, req);
  }

  /** FR-2.1, FR-2.6: optimistic lock via version in request body */
  update(id: number, req: UpdateEmployeeRequest): Observable<EmployeeDetail> {
    return this.http.put<EmployeeDetail>(`${EmployeeService.BASE}/${id}`, req);
  }

  /** FR-2.1: soft delete — employee becomes TERMINATED */
  deactivate(id: number): Observable<void> {
    return this.http.delete<void>(`${EmployeeService.BASE}/${id}`);
  }
}
