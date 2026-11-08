package com.arabicquest;

import com.arabicquest.dto.SetUserEnabledRequest;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.PayoutService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
public class PhaseDUserManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private EpisodeRewardClaimRepository episodeRewardClaimRepository;
    @Autowired private PayoutRepository payoutRepository;
    @Autowired private BalanceAdjustmentRepository balanceAdjustmentRepository;
    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private PayoutService payoutService;
    @Autowired private PasswordEncoder passwordEncoder;

    private User admin;
    private User student;
    private Playlist course;
    private Episode ep1;
    private String adminBearer;
    private String studentBearer;

    @BeforeEach
    void setup() throws Exception {
        userRepository.deleteAll();
        playlistRepository.deleteAll();

        admin = new User("admin_mgmt", passwordEncoder.encode("AdminPass#2026!"), "Admin Mgmt", Role.ROLE_ADMIN);
        student = new User("student_mgmt", passwordEncoder.encode("StudentPass#2026!"), "Student Mgmt", Role.ROLE_STUDENT);
        userRepository.saveAll(List.of(admin, student));

        course = new Playlist("PL_MGMT", "Management Course", "Test Course");
        course.setPublished(true);
        playlistRepository.save(course);

        ep1 = new Episode(course, 1, "EP1_MGMT", "Management Ep 1");
        ep1.setEnabled(true);
        episodeRepository.save(ep1);

        String adminLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "admin_mgmt", "password", "AdminPass#2026!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        adminBearer = "Bearer " + objectMapper.readTree(adminLogin).get("token").asText();

        String studentLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "student_mgmt", "password", "StudentPass#2026!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        studentBearer = "Bearer " + objectMapper.readTree(studentLogin).get("token").asText();
    }

    @Test
    @DisplayName("D2: Disabling student blocks login (403) and invalidates existing session via tokenVersion")
    void disableStudentBlocksLoginAndExistingSession() throws Exception {
        // Initially, student can access protected endpoint
        mockMvc.perform(get("/api/student/earnings")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk());

        // Admin disables the student
        mockMvc.perform(put("/api/admin/students/" + student.getId() + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SetUserEnabledRequest(false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // Existing session token is rejected (tokenVersion mismatch or disabled check in filter)
        mockMvc.perform(get("/api/student/earnings")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isUnauthorized());

        // New login attempts are forbidden (403)
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "student_mgmt", "password", "StudentPass#2026!"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Your account has been disabled. Please contact the administrator."));

        // Admin re-enables the student
        mockMvc.perform(put("/api/admin/students/" + student.getId() + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SetUserEnabledRequest(true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        // Student can log in again
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "student_mgmt", "password", "StudentPass#2026!"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("D3: Deleting student is blocked if balance owed is non-zero")
    void deleteStudentBlockedWhenBalanceOwedNonZero() throws Exception {
        // Give student $15.00 earned with no payouts -> balanceOwed = $15.00
        episodeRewardClaimRepository.save(new EpisodeRewardClaim(student, ep1, WatchMode.ARABIC_SUB, new BigDecimal("15.00")));

        var earnings = payoutService.getStudentEarnings(student.getUsername());
        assertThat(earnings.getBalanceOwed()).isEqualByComparingTo("15.00");

        // Attempt deletion -> blocked with 400 Bad Request
        mockMvc.perform(delete("/api/admin/students/" + student.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmUsername\":\"student_mgmt\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("non-zero balance owed")));

        // Student still exists in DB
        assertThat(userRepository.findById(student.getId())).isPresent();
    }

    @Test
    @DisplayName("D3: Deleting student is blocked for admin accounts or self")
    void deleteAdminOrSelfIsBlocked() throws Exception {
        // Admin tries to delete self
        mockMvc.perform(delete("/api/admin/students/" + admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmUsername\":\"admin_mgmt\"}"))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findById(admin.getId())).isPresent();
    }

    @Test
    @DisplayName("D3: Delete permanently removes all rows in single transaction when balance is $0")
    void deleteStudentSucceedsWhenBalanceZero() throws Exception {
        // Student has $15 earned and $15 paid -> balanceOwed = $0.00
        episodeRewardClaimRepository.save(new EpisodeRewardClaim(student, ep1, WatchMode.ARABIC_SUB, new BigDecimal("15.00")));
        Payout p = new Payout(student, new BigDecimal("15.00"), "Final Payout");
        p.setIsPaid(true);
        payoutRepository.save(p);
        quizAttemptRepository.save(new QuizAttempt(student, ep1, WatchMode.ARABIC_SUB, 100.0, true, BigDecimal.ZERO));

        var earnings = payoutService.getStudentEarnings(student.getUsername());
        assertThat(earnings.getBalanceOwed()).isEqualByComparingTo("0.00");

        // Export record before deletion
        mockMvc.perform(get("/api/admin/students/" + student.getId() + "/export")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(jsonPath("$.student.username").value("student_mgmt"))
                .andExpect(jsonPath("$.earnings.totalEarned").value(15.0));

        // Delete student with confirmation username
        mockMvc.perform(delete("/api/admin/students/" + student.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmUsername\":\"student_mgmt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        // Verify all related rows removed
        assertThat(userRepository.findById(student.getId())).isEmpty();
        assertThat(episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), ep1.getId())).isFalse();
        assertThat(payoutRepository.findByStudentIdOrderByCreatedAtDesc(student.getId())).isEmpty();
        assertThat(quizAttemptRepository.findByStudentIdOrderByAttemptedAtDesc(student.getId())).isEmpty();
    }
}
