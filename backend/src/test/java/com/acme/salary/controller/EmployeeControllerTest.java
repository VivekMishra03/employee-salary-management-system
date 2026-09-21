package com.acme.salary.controller;

import com.acme.salary.model.AppUserRole;
import com.acme.salary.model.Department;
import com.acme.salary.model.JobRole;
import com.acme.salary.model.Location;
import com.acme.salary.repository.DepartmentRepository;
import com.acme.salary.repository.JobRoleRepository;
import com.acme.salary.repository.LocationRepository;
import com.acme.salary.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-2 over HTTP, behind the real security chain. Every request carries a genuine bearer token
 * except where a test is specifically about the absence of one.
 */
@SpringBootTest(properties = "PORT=8080")
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Transactional
class EmployeeControllerTest {

    private static final String EMPLOYEES = "/api/v1/employees";
    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private JobRoleRepository jobRoleRepository;

    private Long deptId;
    private Long salesDeptId;
    private Long austinId;
    private Long berlinId;
    private Long roleId;
    private Long seniorRoleId;
    private String token;

    @BeforeEach
    void seed() {
        deptId = departmentRepository.saveAndFlush(new Department("ENG", "Engineering", null, "CC-1")).getId();
        salesDeptId = departmentRepository.saveAndFlush(new Department("SAL", "Sales", null, "CC-2")).getId();
        austinId = locationRepository.saveAndFlush(new Location("US", "United States", "Austin", "USD")).getId();
        berlinId = locationRepository.saveAndFlush(new Location("DE", "Germany", "Berlin", "EUR")).getId();
        roleId = jobRoleRepository.saveAndFlush(new JobRole("Software Engineer", "Engineering", "L4")).getId();
        seniorRoleId = jobRoleRepository.saveAndFlush(new JobRole("Senior Software Engineer", "Engineering", "L5"))
                .getId();
        token = jwtService.issue(1L, "hr.manager@acme.example", AppUserRole.HR_MANAGER).token();
    }

    // ---- helpers ---------------------------------------------------------------------------

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private Map<String, Object> newEmployee(String code, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employeeCode", code);
        m.put("firstName", "Ada");
        m.put("lastName", "Lovelace");
        m.put("email", email);
        m.put("gender", "FEMALE");
        m.put("hireDate", "2020-01-15");
        m.put("employmentType", "FULL_TIME");
        m.put("departmentId", deptId);
        m.put("jobRoleId", roleId);
        m.put("locationId", austinId);
        return m;
    }

    private ResultActions postEmployee(Map<String, Object> body) throws Exception {
        return mockMvc.perform(authed(post(EMPLOYEES)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String createAndReturnBody(Map<String, Object> body) throws Exception {
        return postEmployee(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private long idOf(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private Map<String, Object> updateBodyFrom(String detailJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", JsonPath.read(detailJson, "$.version"));
        m.put("firstName", JsonPath.read(detailJson, "$.firstName"));
        m.put("lastName", JsonPath.read(detailJson, "$.lastName"));
        m.put("email", JsonPath.read(detailJson, "$.email"));
        m.put("gender", JsonPath.read(detailJson, "$.gender"));
        m.put("employmentType", JsonPath.read(detailJson, "$.employmentType"));
        m.put("fteRatio", JsonPath.read(detailJson, "$.fteRatio"));
        m.put("departmentId", JsonPath.read(detailJson, "$.departmentId"));
        m.put("jobRoleId", JsonPath.read(detailJson, "$.jobRoleId"));
        m.put("locationId", JsonPath.read(detailJson, "$.locationId"));
        m.put("employmentStatus", JsonPath.read(detailJson, "$.employmentStatus"));
        return m;
    }

    private ResultActions putEmployee(long id, Map<String, Object> body) throws Exception {
        return mockMvc.perform(authed(put(EMPLOYEES + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    // ---- security --------------------------------------------------------------------------

    @Test
    @DisplayName("FR-1.2: every employee endpoint rejects a request without a token")
    void everyEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(EMPLOYEES)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(EMPLOYEES + "/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(EMPLOYEES).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(EMPLOYEES + "/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(EMPLOYEES + "/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reference/departments")).andExpect(status().isUnauthorized());
    }

    // ---- create ----------------------------------------------------------------------------

    @Test
    @DisplayName("FR-2.1: POST creates an employee: 201, a Location header and the new detail")
    void create_returns201WithLocationAndDetail() throws Exception {
        String body = postEmployee(newEmployee("ACME-000001", "ada@acme.example"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeCode").value("ACME-000001"))
                .andExpect(jsonPath("$.employmentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.departmentName").value("Engineering"))
                .andExpect(jsonPath("$.fteRatio").value(1.0))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(authed(get(EMPLOYEES + "/" + idOf(body))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada@acme.example"));
    }

    @Test
    @DisplayName("FR-2.1: the Location header points at the new resource")
    void create_setsTheLocationHeader() throws Exception {
        var response = postEmployee(newEmployee("ACME-000001", "ada@acme.example"))
                .andExpect(status().isCreated())
                .andReturn().getResponse();

        long id = idOf(response.getContentAsString());
        assertThat(response.getHeader("Location")).endsWith("/api/v1/employees/" + id);
    }

    @Test
    @DisplayName("NFR-4: an invalid body is a 400 naming every bad field, never echoing values")
    void create_withInvalidBody_returns400WithFieldErrors() throws Exception {
        Map<String, Object> bad = newEmployee("", "not-an-email");
        bad.put("firstName", "");
        bad.put("fteRatio", 2);

        postEmployee(bad)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='employeeCode')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='firstName')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='fteRatio')]").exists())
                .andExpect(content().string(not(containsString("not-an-email"))));
    }

    @Test
    @DisplayName("requirements.md 6.2: a duplicate employee code is a 409 with a stable code")
    void create_withDuplicateCode_returns409() throws Exception {
        createAndReturnBody(newEmployee("ACME-000001", "ada@acme.example"));

        postEmployee(newEmployee("ACME-000001", "different@acme.example"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMPLOYEE_CODE"));
    }

    @Test
    @DisplayName("requirements.md 6.2: a duplicate email is a 409 with a stable code")
    void create_withDuplicateEmail_returns409() throws Exception {
        createAndReturnBody(newEmployee("ACME-000001", "ada@acme.example"));

        postEmployee(newEmployee("ACME-000002", "ada@acme.example"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    @DisplayName("FR-2.1: an unknown department is a 400 that names the field")
    void create_withUnknownDepartment_returns400() throws Exception {
        Map<String, Object> body = newEmployee("ACME-000001", "ada@acme.example");
        body.put("departmentId", 999999);

        postEmployee(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REFERENCE"))
                .andExpect(jsonPath("$.errors[0].field").value("departmentId"));
    }

    // ---- read ------------------------------------------------------------------------------

    @Test
    @DisplayName("FR-2.5: an unknown employee is a 404 problem+json")
    void get_withUnknownId_returns404() throws Exception {
        mockMvc.perform(authed(get(EMPLOYEES + "/999999")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("FR-2.5: the detail exposes current compensation and salary history fields (empty for a new hire)")
    void get_returnsTheDetailShape() throws Exception {
        long id = idOf(createAndReturnBody(newEmployee("ACME-000001", "ada@acme.example")));

        mockMvc.perform(authed(get(EMPLOYEES + "/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.manager").doesNotExist())
                .andExpect(jsonPath("$.directReports", hasSize(0)))
                .andExpect(jsonPath("$.currentSalary").doesNotExist())
                .andExpect(jsonPath("$.salaryHistory", hasSize(0)));
    }

    // ---- list ------------------------------------------------------------------------------

    private void seedThree() throws Exception {
        createAndReturnBody(newEmployee("ACME-000001", "ada@acme.example"));
        Map<String, Object> grace = newEmployee("ACME-000002", "grace@acme.example");
        grace.put("firstName", "Grace");
        grace.put("lastName", "Hopper");
        grace.put("locationId", berlinId);
        grace.put("jobRoleId", seniorRoleId);
        createAndReturnBody(grace);
        Map<String, Object> sam = newEmployee("ACME-000003", "sam@acme.example");
        sam.put("firstName", "Sam");
        sam.put("lastName", "Seller");
        sam.put("departmentId", salesDeptId);
        sam.put("employmentType", "CONTRACT");
        createAndReturnBody(sam);
    }

    @Test
    @DisplayName("FR-2.2: the list is a page object with the total, and never includes gender")
    void list_returnsAPageWithoutGender() throws Exception {
        seedThree();

        mockMvc.perform(authed(get(EMPLOYEES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].departmentName").exists())
                // requirements.md assumption 3: gender is never shown on the directory list.
                .andExpect(jsonPath("$.content[0].gender").doesNotExist());
    }

    @Test
    @DisplayName("FR-2.2: page, size and sort are honoured on the server")
    void list_pagesAndSorts() throws Exception {
        seedThree();

        mockMvc.perform(authed(get(EMPLOYEES).param("size", "2").param("page", "0").param("sort", "lastName,desc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].lastName").value("Seller"))
                .andExpect(jsonPath("$.content[1].lastName").value("Lovelace"))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @DisplayName("FR-2.2: the page size is capped, so a client can never request the full dataset")
    void list_capsThePageSize() throws Exception {
        seedThree();

        mockMvc.perform(authed(get(EMPLOYEES).param("size", "100000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    @DisplayName("FR-2.4: the department, country, status, type and level filters apply through the query string")
    void list_appliesFilters() throws Exception {
        seedThree();

        mockMvc.perform(authed(get(EMPLOYEES).param("departmentId", salesDeptId.toString())))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].employeeCode").value("ACME-000003"));
        mockMvc.perform(authed(get(EMPLOYEES).param("countryCode", "DE")))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].employeeCode").value("ACME-000002"));
        mockMvc.perform(authed(get(EMPLOYEES).param("employmentType", "CONTRACT")))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mockMvc.perform(authed(get(EMPLOYEES).param("jobLevel", "L5")))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].lastName").value("Hopper"));
        mockMvc.perform(authed(get(EMPLOYEES).param("status", "TERMINATED")))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("FR-2.3: the q parameter searches name, code and email")
    void list_appliesFreeTextSearch() throws Exception {
        seedThree();

        mockMvc.perform(authed(get(EMPLOYEES).param("q", "hopper")))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].employeeCode").value("ACME-000002"));
        mockMvc.perform(authed(get(EMPLOYEES).param("q", "sam@acme")))
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @DisplayName("FR-2.2: sorting by a field that is not part of the API is a 400, not a 500")
    void list_withUnknownSortField_returns400() throws Exception {
        mockMvc.perform(authed(get(EMPLOYEES).param("sort", "passwordHash")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));
    }

    @Test
    @DisplayName("FR-2.4: an unrecognised enum filter value is a 400 naming the field, without echoing it")
    void list_withUnknownStatus_returns400() throws Exception {
        mockMvc.perform(authed(get(EMPLOYEES).param("status", "BANANA")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='status')]").exists())
                // Spring's own message quotes the rejected value and the internal enum class name.
                .andExpect(content().string(not(containsString("BANANA"))))
                .andExpect(content().string(not(containsString("EmploymentStatus"))));
    }

    // ---- update / optimistic locking -------------------------------------------------------

    @Test
    @DisplayName("FR-2.1: PUT with the current version updates the employee and returns the new version")
    void update_withCurrentVersion_returns200() throws Exception {
        String created = createAndReturnBody(newEmployee("ACME-000001", "ada@acme.example"));
        Map<String, Object> body = updateBodyFrom(created);
        body.put("lastName", "Byron");

        putEmployee(idOf(created), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Byron"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("FR-2.6 (M3 exit criterion): a second writer holding a stale version gets a 409")
    void update_withStaleVersion_returns409AndKeepsTheFirstWrite() throws Exception {
        String created = createAndReturnBody(newEmployee("ACME-000001", "ada@acme.example"));
        long id = idOf(created);

        // Two HR users open the same employee: both read version 0.
        Map<String, Object> userA = updateBodyFrom(created);
        userA.put("lastName", "Byron");
        Map<String, Object> userB = updateBodyFrom(created);
        userB.put("lastName", "Overwritten");

        putEmployee(id, userA).andExpect(status().isOk());
        putEmployee(id, userB)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));

        mockMvc.perform(authed(get(EMPLOYEES + "/" + id)))
                .andExpect(jsonPath("$.lastName").value("Byron"));
    }

    @Test
    @DisplayName("FR-2.6: a PUT without a version is rejected, so a blind overwrite is impossible")
    void update_withoutVersion_returns400() throws Exception {
        String created = createAndReturnBody(newEmployee("ACME-000001", "ada@acme.example"));
        Map<String, Object> body = updateBodyFrom(created);
        body.remove("version");

        putEmployee(idOf(created), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='version')]").exists());
    }

    @Test
    @DisplayName("FR-2.1: updating an unknown employee is a 404")
    void update_withUnknownId_returns404() throws Exception {
        String created = createAndReturnBody(newEmployee("ACME-000001", "ada@acme.example"));

        putEmployee(999999, updateBodyFrom(created)).andExpect(status().isNotFound());
    }

    // ---- soft delete -----------------------------------------------------------------------

    @Test
    @DisplayName("FR-2.1: DELETE is a soft delete -- 204, and the employee is still readable as TERMINATED")
    void delete_softDeletesTheEmployee() throws Exception {
        long id = idOf(createAndReturnBody(newEmployee("ACME-000001", "ada@acme.example")));

        mockMvc.perform(authed(delete(EMPLOYEES + "/" + id))).andExpect(status().isNoContent());

        mockMvc.perform(authed(get(EMPLOYEES + "/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employmentStatus").value("TERMINATED"))
                .andExpect(jsonPath("$.terminationDate").isNotEmpty());
    }

    @Test
    @DisplayName("FR-2.1: deleting an unknown employee is a 404")
    void delete_withUnknownId_returns404() throws Exception {
        mockMvc.perform(authed(delete(EMPLOYEES + "/999999"))).andExpect(status().isNotFound());
    }

    // ---- reference data --------------------------------------------------------------------

    @Test
    @DisplayName("reference lists feed the UI filters and forms")
    void reference_returnsDepartmentsLocationsAndJobRoles() throws Exception {
        mockMvc.perform(authed(get("/api/v1/reference/departments")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.code=='ENG')].name").value("Engineering"));
        mockMvc.perform(authed(get("/api/v1/reference/locations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.city=='Berlin')].currencyCode").value("EUR"));
        mockMvc.perform(authed(get("/api/v1/reference/job-roles")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.title=='Senior Software Engineer')].jobLevel").value("L5"));
    }
}
