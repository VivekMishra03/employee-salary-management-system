import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  AnalyticsFilter,
  AnalyticsSummary,
  DistributionBucket,
  GenderGapGroupBy,
  GenderGapStat,
  GroupBy,
  GroupStat,
  PayBandAdherence,
  PayBandReport,
  TrendInterval,
  TrendPoint,
} from '../models/analytics.model';

/**
 * FR-4.1 - FR-4.7: one method per analytics endpoint. Every endpoint takes the same filter, under the
 * same parameter names as GET /employees, so the dashboard and the directory describe the same people.
 * All figures are computed by the server; nothing here aggregates.
 */
@Injectable({ providedIn: 'root' })
export class AnalyticsService {
  private static readonly BASE = '/api/v1/analytics';
  private readonly http = inject(HttpClient);

  /** FR-4.1 */
  summary(filter: AnalyticsFilter): Observable<AnalyticsSummary> {
    return this.http.get<AnalyticsSummary>(`${AnalyticsService.BASE}/summary`, { params: filterParams(filter) });
  }

  /** FR-4.2 */
  byGroup(groupBy: GroupBy, filter: AnalyticsFilter): Observable<GroupStat[]> {
    return this.http.get<GroupStat[]>(`${AnalyticsService.BASE}/by-group`, {
      params: filterParams(filter).set('groupBy', groupBy),
    });
  }

  /** FR-4.3: `buckets` is 2 to 50 on the server. */
  distribution(buckets: number, filter: AnalyticsFilter): Observable<DistributionBucket[]> {
    return this.http.get<DistributionBucket[]>(`${AnalyticsService.BASE}/distribution`, {
      params: filterParams(filter).set('buckets', buckets),
    });
  }

  /** FR-4.4: the four counts for the whole slice plus one server page; `adherence` narrows the page only. */
  payBands(
    filter: AnalyticsFilter,
    paging: { page: number; size: number; adherence?: PayBandAdherence },
  ): Observable<PayBandReport> {
    let params = filterParams(filter).set('page', paging.page).set('size', paging.size);
    if (paging.adherence) {
      params = params.set('adherence', paging.adherence);
    }
    return this.http.get<PayBandReport>(`${AnalyticsService.BASE}/pay-bands`, { params });
  }

  /** FR-4.5 */
  genderGap(groupBy: GenderGapGroupBy, filter: AnalyticsFilter): Observable<GenderGapStat[]> {
    return this.http.get<GenderGapStat[]>(`${AnalyticsService.BASE}/gender-gap`, {
      params: filterParams(filter).set('groupBy', groupBy),
    });
  }

  /** FR-4.6: `from` and `to` are ISO dates (yyyy-MM-dd). */
  trend(filter: AnalyticsFilter, from: string, to: string, interval: TrendInterval): Observable<TrendPoint[]> {
    return this.http.get<TrendPoint[]>(`${AnalyticsService.BASE}/trend`, {
      params: filterParams(filter).set('from', from).set('to', to).set('interval', interval),
    });
  }
}

const FILTER_KEYS = ['q', 'departmentId', 'countryCode', 'status', 'employmentType', 'jobLevel'] as const;

/** FR-4.7: only constraints that are set become parameters; an empty value would read as a filter on "". */
function filterParams(filter: AnalyticsFilter): HttpParams {
  let params = new HttpParams();
  for (const key of FILTER_KEYS) {
    const value = filter[key];
    if (value !== undefined && value !== null && value !== '') {
      params = params.set(key, value);
    }
  }
  return params;
}
