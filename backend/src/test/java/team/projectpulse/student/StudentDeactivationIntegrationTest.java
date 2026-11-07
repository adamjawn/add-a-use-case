package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.section.Section;
import team.projectpulse.system.StatusCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-STU-deactivate-student: POST /students/{studentId}/deactivation, its main flow and extensions 6a and 6b
 * (design tests T1-T5, T11). John Smith is in course section 2023-2024 of course 1, on Team1.
 */
@DisplayName("Integration tests for deactivating a student")
class StudentDeactivationIntegrationTest extends AbstractIntegrationTest {

    private static final int NO_SUCH_ID = 999999;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityManager entityManager;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    String adminBingyangToken; // Course admin of course 1, assigned to John's course section
    Integer adminBingyangId;
    String instructorBillToken; // Assigned to John's course section, not a course admin
    String adminTimToken; // Course admin of course 3, another course section entirely
    String studentJohnToken;
    Integer johnId;
    String studentEricToken; // John's teammate


    @BeforeEach
    void setUp() throws Exception {
        JSONObject bingyang = login("b.wei@abc.edu");
        this.adminBingyangToken = "Bearer " + bingyang.getString("token");
        this.adminBingyangId = bingyang.getJSONObject("userInfo").getInt("id");
        this.instructorBillToken = "Bearer " + login("b.gates@abc.edu").getString("token");
        this.adminTimToken = "Bearer " + login("t.cook@abc.edu").getString("token");
        JSONObject john = login("j.smith@abc.edu");
        this.studentJohnToken = "Bearer " + john.getString("token");
        this.johnId = john.getJSONObject("userInfo").getInt("id");
        this.studentEricToken = "Bearer " + login("e.hudson@abc.edu").getString("token");
    }

    private JSONObject login(String email) throws Exception {
        MvcResult mvcResult = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(email, "123456"))).andReturn();
        return new JSONObject(mvcResult.getResponse().getContentAsString()).getJSONObject("data");
    }

    private ResultActions deactivate(Object studentId, String token) throws Exception {
        return this.mockMvc.perform(post(this.baseUrl + "/students/" + studentId + "/deactivation").accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token));
    }

    private Student john() {
        this.entityManager.flush();
        this.entityManager.clear();
        return this.entityManager.find(Student.class, this.johnId);
    }

    private long count(String jpql) {
        return this.entityManager.createQuery(jpql, Long.class).setParameter("id", this.johnId).getSingleResult();
    }

    @Test
    @DisplayName("T1: an instructor of her course section deactivates her; her team, activities and evaluations are kept")
    void instructorOfHerSectionDeactivatesHer() throws Exception {
        Integer teamIdBefore = john().getTeam().getTeamId();
        long activitiesBefore = count("select count(a) from Activity a where a.student.id = :id");
        long givenBefore = count("select count(e) from PeerEvaluation e where e.evaluator.id = :id");
        long receivedBefore = count("select count(e) from PeerEvaluation e where e.evaluatee.id = :id");

        deactivate(this.johnId, this.instructorBillToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.code").value(StatusCode.SUCCESS))
                .andExpect(jsonPath("$.data.id").value(this.johnId))
                .andExpect(jsonPath("$.data.enabled").value(false));

        Student john = john();
        assertThat(john.isEnabled()).isFalse();
        assertThat(john.getTeam().getTeamId()).isEqualTo(teamIdBefore);
        assertThat(count("select count(a) from Activity a where a.student.id = :id")).isEqualTo(activitiesBefore);
        assertThat(count("select count(e) from PeerEvaluation e where e.evaluator.id = :id")).isEqualTo(givenBefore);
        assertThat(count("select count(e) from PeerEvaluation e where e.evaluatee.id = :id")).isEqualTo(receivedBefore);
    }

    @Test
    @DisplayName("T2: the course admin of her course deactivates her though not assigned to her course section")
    void courseAdminNotAssignedToHerSectionDeactivatesHer() throws Exception {
        // The seed assigns Bingyang to every course section of course 1, so take her off John's for this test
        Student john = john();
        Section section = john.getSection();
        Instructor bingyang = this.entityManager.find(Instructor.class, this.adminBingyangId);
        section.removeInstructor(bingyang);
        this.entityManager.flush();
        assertThat(section.getInstructors()).extracting(Instructor::getId).doesNotContain(this.adminBingyangId);

        deactivate(this.johnId, this.adminBingyangToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    @DisplayName("T3 (6a): an instructor of another course section is denied and nothing changes")
    void instructorOfAnotherSectionIsDenied() throws Exception {
        deactivate(this.johnId, this.adminTimToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN))
                .andExpect(jsonPath("$.message").value("No permission."));

        assertThat(john().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("T4 (6a): the student herself and her teammate are denied and nothing changes")
    void studentsAreDenied() throws Exception {
        deactivate(this.johnId, this.studentJohnToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));
        deactivate(this.johnId, this.studentEricToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));

        assertThat(john().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("T5 (6b): deactivating an already deactivated student answers 409 and changes nothing")
    void alreadyDeactivatedIsAConflict() throws Exception {
        deactivate(this.johnId, this.instructorBillToken).andExpect(status().isOk());

        deactivate(this.johnId, this.adminBingyangToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.CONFLICT))
                .andExpect(jsonPath("$.message").value("This student is already deactivated."));

        assertThat(john().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("T11: an id that names no student is denied, not a server error")
    void unknownStudentIsDenied() throws Exception {
        deactivate(NO_SUCH_ID, this.adminBingyangToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));
        deactivate("not-a-number", this.adminBingyangToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));
    }

}
