import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { RecordSalaryRequest, SalarySummary } from '../models/employee.model';

/** FR-3.1 – FR-3.6: Salary history and recording. */
@Injectable({ providedIn: 'root' })
export class SalaryService {
  private static readonly BASE = '/api/v1/employees';

  constructor(private http: HttpClient) {}

  /** FR-3.1 */
  getHistory(employeeId: number): Observable<SalarySummary[]> {
    return this.http.get<SalarySummary[]>(`${SalaryService.BASE}/${employeeId}/salaries`);
  }

  /** FR-3.2 – FR-3.6: Records a salary change, closing the prior record atomically on the backend. */
  record(employeeId: number, req: RecordSalaryRequest): Observable<SalarySummary> {
    return this.http.post<SalarySummary>(`${SalaryService.BASE}/${employeeId}/salaries`, req);
  }
}
