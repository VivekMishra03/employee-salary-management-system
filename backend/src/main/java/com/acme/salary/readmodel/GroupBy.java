package com.acme.salary.readmodel;

/**
 * FR-4.2: the dimensions a pay comparison can be grouped by. Each constant carries the SQL for its
 * key and its display label; these are fixed constants, never user input, so they are safe to place
 * in SQL text. The user's choice is validated against this closed set before it gets here.
 */
public enum GroupBy {
    DEPARTMENT("CAST(d.id AS VARCHAR)", "d.name", "JOIN department d ON d.id = e.department_id"),
    COUNTRY("l.country_code", "l.country_name", ""),
    JOB_LEVEL("jr.job_level", "jr.job_level", "");

    private final String keySql;
    private final String labelSql;
    private final String extraJoin;

    GroupBy(String keySql, String labelSql, String extraJoin) {
        this.keySql = keySql;
        this.labelSql = labelSql;
        this.extraJoin = extraJoin;
    }

    String keySql() {
        return keySql;
    }

    String labelSql() {
        return labelSql;
    }

    String extraJoin() {
        return extraJoin;
    }
}
