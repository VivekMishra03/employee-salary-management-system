package com.acme.salary.service;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.model.Employee;
import com.acme.salary.model.JobRole;
import com.acme.salary.model.Location;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FR-2.3 / FR-2.4: turns an {@link EmployeeFilter} into a database query. Every restriction is
 * optional and they combine with AND, so each additional filter can only narrow the result.
 *
 * <p>{@link Employee} maps its foreign keys as plain {@code Long} columns rather than
 * associations (see its javadoc), so the country and job-level filters are expressed as {@code
 * IN (subquery)} on the referenced table instead of a join.
 *
 * <p>All user text reaches the database as a bound parameter, never concatenated into SQL (NFR-4).
 */
public final class EmployeeSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private EmployeeSpecifications() {
    }

    public static Specification<Employee> matching(EmployeeFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.departmentId() != null) {
                predicates.add(cb.equal(root.get("departmentId"), filter.departmentId()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("employmentStatus"), filter.status()));
            }
            if (filter.employmentType() != null) {
                predicates.add(cb.equal(root.get("employmentType"), filter.employmentType()));
            }
            if (hasText(filter.countryCode())) {
                Subquery<Long> locations = query.subquery(Long.class);
                Root<Location> location = locations.from(Location.class);
                locations.select(location.get("id"))
                        .where(cb.equal(location.get("countryCode"), filter.countryCode().trim().toUpperCase(Locale.ROOT)));
                predicates.add(root.get("locationId").in(locations));
            }
            if (hasText(filter.jobLevel())) {
                Subquery<Long> roles = query.subquery(Long.class);
                Root<JobRole> role = roles.from(JobRole.class);
                roles.select(role.get("id"))
                        .where(cb.equal(role.get("jobLevel"), filter.jobLevel().trim().toUpperCase(Locale.ROOT)));
                predicates.add(root.get("jobRoleId").in(roles));
            }
            if (hasText(filter.q())) {
                Expression<String> searchable = searchableText(root, cb);
                for (String token : filter.q().trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                    predicates.add(cb.like(searchable, "%" + escapeLike(token) + "%", LIKE_ESCAPE));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * The text FR-2.3 searches: first name, last name, employee code and email, space-separated and
     * lower-cased. Each whitespace-separated word of the query must appear somewhere in it, in any
     * order, so "byron ada" finds "Ada Byron".
     */
    private static Expression<String> searchableText(Root<Employee> root, CriteriaBuilder cb) {
        Expression<String> text = root.get("firstName");
        for (String column : new String[]{"lastName", "employeeCode", "email"}) {
            text = cb.concat(cb.concat(text, " "), root.<String>get(column));
        }
        return cb.lower(text);
    }

    /** Makes {@code %}, {@code _} and the escape character itself match literally rather than as wildcards. */
    private static String escapeLike(String token) {
        return token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
