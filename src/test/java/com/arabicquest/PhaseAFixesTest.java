package com.arabicquest;

import com.arabicquest.config.ProdStartupValidator;
import com.arabicquest.dto.CreateBalanceAdjustmentRequest;
import com.arabicquest.dto.EarningsSummaryResponse;
import com.arabicquest.dto.VoidPayoutRequest;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.JwtService;
import com.arabicquest.service.PayoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phaseA_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=phaseAadmin",
        "ADMIN_PASSWORD=PhaseAStrongPassword!99",
        "ADMIN_SECRET_PATH=phaseA-secret-vault",
        "JWT_SECRET=phaseA-test-jwt-secret-key-with-well-over-32-chars-entropy"
})
class PhaseAFixesTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private BalanceAdjustmentRepository balanceAdjustmentRepository;

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User adminUser;
    private User studentUser;
    private String adminToken;
    private String studentToken;

    @BeforeEach
    void setUp() {
        adminUser = userRepository.findByUsername("phaseAadmin").orElseGet(() ->
                userRepository.save(new User("phaseAadmin", passwordEncoder.encode("PhaseAStrongPassword!99"), "Admin", Role.ROLE_ADMIN))
        );

        studentUser = userRepository.findByUsername("phaseAstudent").orElseGet(() ->
                userRepository.save(new User("phaseAstudent", passwordEncoder.encode("StudentPass!123"), "Test Student", Role.ROLE_STUDENT))
        );

        adminToken = jwtService.generateToken(adminUser);
        studentToken = jwtService.generateToken(studentUser);
    }

    @Test
    void test1_prodStartupValidatorStrictRules() {
        // Missing all required env vars
        MockEnvironment emptyEnv = new MockEnvironment();
        emptyEnv.setActiveProfiles("prod");
        assertThatThrownBy(() -> ProdStartupValidator.validateProdEnvironment(emptyEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is missing")
                .hasMessageContaining("ADMIN_USERNAME is missing")
                .hasMessageContaining("ADMIN_PASSWORD is missing")
                .hasMessageContaining("ADMIN_SECRET_PATH is missing")
                .hasMessageContaining("DATABASE_URL is missing");

        // Short JWT (< 32 chars)
        MockEnvironment shortJwt = new MockEnvironment();
        shortJwt.setActiveProfiles("prod");
        shortJwt.setProperty("JWT_SECRET", "short-jwt-secret");
        shortJwt.setProperty("ADMIN_USERNAME", "myadmin");
        shortJwt.setProperty("ADMIN_PASSWORD", "SuperStrongPassword!2026");
        shortJwt.setProperty("ADMIN_SECRET_PATH", "secure-secret-slug");
        shortJwt.setProperty("DATABASE_URL", "postgresql://produser:prodpass@ep-cool-db.neon.tech/arabicquest?sslmode=require");
        assertThatThrownBy(() -> ProdStartupValidator.validateProdEnvironment(shortJwt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is too short");

        // Short or weak admin password
        MockEnvironment weakPass = new MockEnvironment();
        weakPass.setActiveProfiles("prod");
        weakPass.setProperty("JWT_SECRET", "this-is-a-valid-32-char-jwt-secret-string-value");
        weakPass.setProperty("ADMIN_USERNAME", "myadmin");
        weakPass.setProperty("ADMIN_PASSWORD", "admin123");
        weakPass.setProperty("ADMIN_SECRET_PATH", "secure-secret-slug");
        weakPass.setProperty("DATABASE_URL", "postgresql://produser:prodpass@ep-cool-db.neon.tech/arabicquest?sslmode=require");
        assertThatThrownBy(() -> ProdStartupValidator.validateProdEnvironment(weakPass))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");

        // Forbidden admin path (e.g., 'turath-hq', 'vault-turathtona', 'admin')
        MockEnvironment forbiddenPath = new MockEnvironment();
        forbiddenPath.setActiveProfiles("prod");
        forbiddenPath.setProperty("JWT_SECRET", "this-is-a-valid-32-char-jwt-secret-string-value");
        forbiddenPath.setProperty("ADMIN_USERNAME", "myadmin");
        forbiddenPath.setProperty("ADMIN_PASSWORD", "SuperStrongPassword!2026");
        forbiddenPath.setProperty("ADMIN_SECRET_PATH", "vault-turathtona");
        forbiddenPath.setProperty("DATABASE_URL", "postgresql://produser:prodpass@ep-cool-db.neon.tech/arabicquest?sslmode=require");
        assertThatThrownBy(() -> ProdStartupValidator.validateProdEnvironment(forbiddenPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_SECRET_PATH must not equal a forbidden default");

        // Valid prod configuration passes cleanly without any exception
        MockEnvironment validEnv = new MockEnvironment();
        validEnv.setActiveProfiles("prod");
        validEnv.setProperty("JWT_SECRET", "a-very-strong-32-byte-secret-key-value-12345");
        validEnv.setProperty("ADMIN_USERNAME", "headmaster");
        validEnv.setProperty("ADMIN_PASSWORD", "ExtremelyComplexPassword#2026");
        validEnv.setProperty("ADMIN_SECRET_PATH", "turath-mgmt-portal-88");
        validEnv.setProperty("DATABASE_URL", "postgresql://user:pass@ep-db.neon.tech/neondb?sslmode=require");
        ProdStartupValidator.validateProdEnvironment(validEnv);
    }

    @Test
    void test2_betaEndpointsAreAdminOnly_studentsBlocked() {
        HttpHeaders studentHeaders = new HttpHeaders();
        studentHeaders.setBearerAuth(studentToken);
        HttpEntity<Void> studentEntity = new HttpEntity<>(studentHeaders);

        // Student attempt to access beta reset-progress must be rejected with 403 Forbidden
        ResponseEntity<Map> studentResp = testRestTemplate.exchange(
                "/api/beta/reset-progress",
                HttpMethod.POST,
                studentEntity,
                Map.class
        );
        assertThat(studentResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Admin call to reset-progress succeeds
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);
        HttpEntity<Void> adminEntity = new HttpEntity<>(adminHeaders);

        ResponseEntity<Map> adminResp = testRestTemplate.exchange(
                "/api/beta/reset-progress",
                HttpMethod.POST,
                adminEntity,
                Map.class
        );
        assertThat(adminResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void test3_resetProgressNeverRemovesPayoutsOrAdjustments() {
        // Create a payout and adjustment for studentUser
        Payout p = payoutService.markAsPaid(studentUser.getId(), new BigDecimal("45.00"), "Regular payout");
        balanceAdjustmentRepository.save(new BalanceAdjustment(studentUser, new BigDecimal("10.00"), "Bonus adjustment", "admin"));

        // Admin triggers beta progress reset
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);
        HttpEntity<Void> adminEntity = new HttpEntity<>(adminHeaders);

        ResponseEntity<Map> resp = testRestTemplate.exchange(
                "/api/beta/reset-progress",
                HttpMethod.POST,
                adminEntity,
                Map.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify payout and adjustment still exist
        assertThat(payoutRepository.findById(p.getId())).isPresent();
        assertThat(balanceAdjustmentRepository.findByStudentIdOrderByTimestampDesc(studentUser.getId())).isNotEmpty();
    }

    @Test
    void test4_balanceAdjustmentsAndVoidingPayouts() {
        User student = userRepository.save(new User("phaseA_math_student", passwordEncoder.encode("Pass!123"), "Math Student", Role.ROLE_STUDENT));
        Long sid = student.getId();

        // 1. Initial earnings: 0 earned, 0 paid, 0 owed
        EarningsSummaryResponse e0 = payoutService.getStudentEarnings(student.getUsername());
        assertThat(e0.getTotalEarned()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(e0.getTotalPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(e0.getBalanceOwed()).isEqualByComparingTo(BigDecimal.ZERO);

        // 2. Admin adds adjustment of +$30.00
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);
        HttpEntity<CreateBalanceAdjustmentRequest> adjEntity = new HttpEntity<>(
                new CreateBalanceAdjustmentRequest(new BigDecimal("30.00"), "Initial legacy balance credit"),
                adminHeaders
        );

        ResponseEntity<Map> adjResp = testRestTemplate.exchange(
                "/api/admin/students/" + sid + "/adjustments",
                HttpMethod.POST,
                adjEntity,
                Map.class
        );
        assertThat(adjResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Check new earnings: Earned $30, Paid $0, Owed $30
        EarningsSummaryResponse e1 = payoutService.getStudentEarnings(student.getUsername());
        assertThat(e1.getTotalEarned()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(e1.getTotalPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(e1.getBalanceOwed()).isEqualByComparingTo(new BigDecimal("30.00"));

        // 3. Admin makes a payout of $20.00
        Payout payout = payoutService.markAsPaid(sid, new BigDecimal("20.00"), "Cash payment");
        EarningsSummaryResponse e2 = payoutService.getStudentEarnings(student.getUsername());
        assertThat(e2.getTotalPaid()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(e2.getBalanceOwed()).isEqualByComparingTo(new BigDecimal("10.00"));

        // 4. Admin voids the payout with an audited reason
        HttpEntity<VoidPayoutRequest> voidEntity = new HttpEntity<>(
                new VoidPayoutRequest("Entered wrong recipient account"),
                adminHeaders
        );
        ResponseEntity<Map> voidResp = testRestTemplate.exchange(
                "/api/admin/payouts/" + payout.getId() + "/void",
                HttpMethod.POST,
                voidEntity,
                Map.class
        );
        assertThat(voidResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Voided payout must be excluded from totalPaid and balanceOwed restored to $30.00
        EarningsSummaryResponse e3 = payoutService.getStudentEarnings(student.getUsername());
        assertThat(e3.getTotalPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(e3.getBalanceOwed()).isEqualByComparingTo(new BigDecimal("30.00"));
    }

    @Test
    void test5_legacyThirtyDollarNegativeBalanceReconciliation_isIdempotent() {
        User brother = userRepository.save(new User("phaseA_brother", passwordEncoder.encode("Pass!123"), "Brother Student", Role.ROLE_STUDENT));
        // Simulate legacy state: Paid $30.00, 0 reward claims, 0 adjustments -> Owed -$30.00
        payoutService.markAsPaid(brother.getId(), new BigDecimal("30.00"), "Old platform cash payout");

        EarningsSummaryResponse before = payoutService.getStudentEarnings(brother.getUsername());
        assertThat(before.getTotalEarned()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(before.getTotalPaid()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(before.getBalanceOwed()).isEqualByComparingTo(new BigDecimal("-30.00"));

        // First run of reconciliation
        payoutService.reconcileLegacyPaidThirtyNegativeBalances("phaseAadmin");

        EarningsSummaryResponse after = payoutService.getStudentEarnings(brother.getUsername());
        assertThat(after.getTotalEarned()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(after.getTotalPaid()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(after.getBalanceOwed()).isEqualByComparingTo(BigDecimal.ZERO);

        // Second run must be completely idempotent (not add another $30)
        payoutService.reconcileLegacyPaidThirtyNegativeBalances("phaseAadmin");
        EarningsSummaryResponse afterSecond = payoutService.getStudentEarnings(brother.getUsername());
        assertThat(afterSecond.getTotalEarned()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(afterSecond.getTotalPaid()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(afterSecond.getBalanceOwed()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
