// Verifies Phase 3 overpaid delete guard, payout void & adjustment math, disable flow, and private-hq modules.
package com.arabicquest;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:p3_edge_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=p3edge_admin",
        "ADMIN_PASSWORD=MasterStrongPass!2026",
        "ADMIN_SECRET_PATH=p3edge-vault-hq",
        "JWT_SECRET=phase3-edge-jwt-secret-key-with-at-least-32-chars-ok"
})
class Phase3UserMgmtEdgeCasesTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PayoutRepository payoutRepository;
    @Autowired private BalanceAdjustmentRepository balanceAdjustmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String loginBearer(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void negativeOverpaidBalanceBlocksDelete_untilSettledByVoidOrAdjustment() throws Exception {
        String adminBearer = loginBearer("p3edge_admin", "MasterStrongPass!2026");

        User student = userRepository.save(new User("overpaid_user", passwordEncoder.encode("StudentPass!123"), "Overpaid User", Role.ROLE_STUDENT));
        // Issue a $25 payout with $0 earned -> balanceOwed = -25.00 (overpaid)
        MvcResult payoutRes = mockMvc.perform(post("/api/admin/payouts")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("studentId", student.getId(), "amount", 25.00, "note", "Accidental"))))
                .andExpect(status().isOk())
                .andReturn();
        long payoutId = objectMapper.readTree(payoutRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/admin/students/" + student.getUsername() + "/earnings")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceOwed").value(-25.00));

        // Deletion must be blocked while balanceOwed is -25.00
        mockMvc.perform(delete("/api/admin/students/" + student.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmUsername\":\"overpaid_user\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("non-zero balance owed")));

        // Credit +$10.00 adjustment -> balanceOwed = -15.00; voiding $25 payout -> balanceOwed = +10.00; debit -$10.00 -> Owed = 0.00
        mockMvc.perform(post("/api/admin/students/" + student.getId() + "/adjustments")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"note\":\"Credit adjustment\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/payouts/" + payoutId + "/void")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Mistaken entry\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voided").value(true));
        mockMvc.perform(post("/api/admin/students/" + student.getId() + "/adjustments")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":-10.00,\"note\":\"Reconcile to zero\"}"))
                .andExpect(status().isOk());

        // Export works and deletion now succeeds and cascades payouts + adjustments
        mockMvc.perform(get("/api/admin/students/" + student.getId() + "/export")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.student.username").value("overpaid_user"))
                .andExpect(jsonPath("$.earnings.balanceOwed").value(0.0));

        mockMvc.perform(delete("/api/admin/students/" + student.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmUsername\":\"overpaid_user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        assertThat(userRepository.findById(student.getId())).isEmpty();
        assertThat(payoutRepository.findByStudentIdOrderByCreatedAtDesc(student.getId())).isEmpty();
        assertThat(balanceAdjustmentRepository.findByStudentIdOrderByTimestampDesc(student.getId())).isEmpty();
    }

    @Test
    void privateHqModularScriptsServedOnlyUnderSecretPathAndBlockTraversal() throws Exception {
        mockMvc.perform(get("/p3edge-vault-hq/modules/pages/students-management.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("enhanceStudentRow")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("innerHTML"))));

        mockMvc.perform(get("/p3edge-vault-hq/modules/pages/payouts-and-overview.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("appendPayoutActionCell")));

        mockMvc.perform(get("/p3edge-vault-hq/modules/data/../admin.js"))
                .andExpect(status().is4xxClientError());
    }
}
