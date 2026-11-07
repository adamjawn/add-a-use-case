package team.projectpulse.security;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.system.StatusCode;
import team.projectpulse.user.PeerEvaluationUser;
import team.projectpulse.user.UserRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-SEC-active-account: a deactivated account is refused on its next request, even with a token issued before the
 * deactivation, and cannot log in again (UC-STU-deactivate-student, "Session in progress"; tests T6 and T6b).
 */
@DisplayName("Integration tests for the per-request active-account check")
class ActiveAccountIntegrationTest extends AbstractIntegrationTest {

    private static final int NO_SUCH_ID = 999999;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtEncoder jwtEncoder;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    String johnToken;
    Integer johnId;
    String billToken;
    Integer billId;


    @BeforeEach
    void setUp() throws Exception {
        JSONObject john = login("j.smith@abc.edu");
        this.johnToken = "Bearer " + john.getString("token");
        this.johnId = john.getJSONObject("userInfo").getInt("id");
        JSONObject bill = login("b.gates@abc.edu");
        this.billToken = "Bearer " + bill.getString("token");
        this.billId = bill.getJSONObject("userInfo").getInt("id");
    }

    private JSONObject login(String email) throws Exception {
        MvcResult mvcResult = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(email, "123456"))).andReturn();
        return new JSONObject(mvcResult.getResponse().getContentAsString()).getJSONObject("data");
    }

    private void deactivate(Integer userId) {
        PeerEvaluationUser user = this.userRepository.findById(userId).orElseThrow();
        user.setEnabled(false);
        this.userRepository.saveAndFlush(user);
    }

    @Test
    @DisplayName("T6: an active student's token is accepted")
    void activeStudentTokenIsAccepted() throws Exception {
        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.johnId).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, this.johnToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true));
    }

    @Test
    @DisplayName("T6: a token issued before the student's deactivation is refused on her next request")
    void deactivatedStudentTokenIsRefused() throws Exception {
        deactivate(this.johnId);

        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.johnId).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, this.johnToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("T6: a deactivated instructor's token is refused too")
    void deactivatedInstructorTokenIsRefused() throws Exception {
        deactivate(this.billId);

        this.mockMvc.perform(get(this.baseUrl + "/students/" + this.johnId).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, this.billToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("T6: a token whose userId names no user is refused")
    void tokenOfAUserWhoNoLongerExistsIsRefused() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("self")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject("gone@abc.edu")
                .claim("userId", NO_SUCH_ID)
                .claim("authorities", "ROLE_student")
                .build();
        String token = "Bearer " + this.jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        this.mockMvc.perform(get(this.baseUrl + "/students/" + NO_SUCH_ID).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("T6b: a deactivated student cannot log in with her correct credentials")
    void deactivatedStudentCannotLogIn() throws Exception {
        deactivate(this.johnId);

        this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic("j.smith@abc.edu", "123456")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

}
