package com.acme.salary.service;

import com.acme.salary.dto.EmployeeDetail.SalarySummary;
import com.acme.salary.model.SalaryRecord;

/** Entity-to-DTO mapping for salary records, shared by the employee detail and the salary service. */
final class SalaryMapper {

    private SalaryMapper() {
    }

    static SalarySummary toSummary(SalaryRecord r) {
        return new SalarySummary(r.getId(), r.getEffectiveFrom(), r.getEffectiveTo(), r.getBaseAmount(),
                r.getCurrencyCode(), r.getPayFrequency(), r.getAnnualisedAmount(), r.getAnnualisedAmountBaseCcy(),
                r.getTargetBonusPct(), r.getChangeReason(), r.getNotes());
    }
}
