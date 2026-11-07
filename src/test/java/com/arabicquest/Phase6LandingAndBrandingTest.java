package com.arabicquest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase6_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=phase6admin",
        "ADMIN_PASSWORD=Phase6AdminPass!2026",
        "ADMIN_SECRET_PATH=phase6-vault",
        "JWT_SECRET=phase6-jwt-secret-key-with-plenty-of-entropy-over-32-chars"
})
class Phase6LandingAndBrandingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void unauthenticatedLandingPage_hasHeroPicture_arabicRtl_signInCta_noSignup_andRequiredHeadTags() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/login.html"));

        MvcResult rootRes = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn();

        String html = rootRes.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertRequiredHeadTags(html);

        assertThat(html).contains("/images/hero-800.webp");
        assertThat(html).contains("/images/hero-1200.webp");
        assertThat(html).contains("fetchpriority=\"high\"");
        assertThat(html).contains("width=\"1200\"");
        assertThat(html).contains("height=\"1802\"");
        assertThat(html).contains("/images/emblem.png");
        assertThat(html).contains("تراثُنا");
        assertThat(html).contains("lang=\"ar\"");
        assertThat(html).contains("dir=\"rtl\"");
        assertThat(html).contains("Turathtona");
        assertThat(html).contains("Know your history. Stand tall.");
        assertThat(html).contains("href=\"/login\"");
        assertThat(html).contains("TODO(artwork-credit)");
        assertThat(html.toLowerCase()).doesNotContain("/register");
        assertThat(html.toLowerCase()).doesNotContain("sign up");

        // Verify login.html, private-hq/admin.html, and private-hq/admin-login.html also carry required head tags
        String loginHtml = mockMvc.perform(get("/login.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertRequiredHeadTags(loginHtml);

        String adminHtml = mockMvc.perform(get("/phase6-vault"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertRequiredHeadTags(adminHtml);

        String adminLoginHtml = mockMvc.perform(get("/phase6-vault-login"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertRequiredHeadTags(adminLoginHtml);
    }

    @Test
    void icons_manifest_images_andSelfHostedArabicWoff2Fonts_arePublicBeforeLogin() throws Exception {
        List<String> publicBinaryAssets = List.of(
                "/favicon.ico",
                "/favicon-16x16.png",
                "/favicon-32x32.png",
                "/apple-touch-icon.png",
                "/icon-192.png",
                "/icon-512.png",
                "/images/hero-800.webp",
                "/images/hero-1200.webp",
                "/images/emblem.png",
                "/images/logo-lockup.jpg"
        );

        for (String assetPath : publicBinaryAssets) {
            byte[] bytes = mockMvc.perform(get(assetPath))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray();
            assertThat(bytes).as("Asset " + assetPath + " should be non-empty").isNotEmpty();
        }

        // Verify self-hosted WOFF2 fonts have genuine 'wOF2' header and are permitted before login
        for (String fontPath : List.of("/fonts/amiri-arabic-700.woff2", "/fonts/cairo-arabic.woff2")) {
            byte[] fontBytes = mockMvc.perform(get(fontPath))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray();
            assertThat(fontBytes.length).isGreaterThan(1000);
            assertThat(new String(fontBytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("wOF2");
        }

        // Verify site.webmanifest
        String manifestRaw = mockMvc.perform(get("/site.webmanifest"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode manifest = objectMapper.readTree(manifestRaw);
        assertThat(manifest.get("short_name").asText()).isEqualTo("Turathtona");
        assertThat(manifest.get("display").asText()).isEqualTo("standalone");
        assertThat(manifest.get("theme_color").asText()).isEqualTo("#0b1f33");
        assertThat(manifest.get("background_color").asText()).isEqualTo("#0b1f33");
        assertThat(manifest.get("icons")).hasSize(2);

        // Verify style.css uses self-hosted fonts (no Google Fonts tracking) and full brand palette + reduced motion
        String css = mockMvc.perform(get("/style.css"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(css).doesNotContain("fonts.googleapis.com");
        assertThat(css).contains("/fonts/amiri-arabic-700.woff2");
        assertThat(css).contains("#0b1f33");
        assertThat(css).contains("#045b62");
        assertThat(css).contains("#c9a24d");
        assertThat(css).contains("#e0a84f");
        assertThat(css).contains("#f5ecd6");
        assertThat(css).contains("object-position: 50% 55%");
        assertThat(css).contains("prefers-reduced-motion: reduce");
    }

    private static void assertRequiredHeadTags(String html) {
        assertThat(html).contains("<link rel=\"icon\" href=\"/favicon.ico\" sizes=\"any\">");
        assertThat(html).contains("<link rel=\"icon\" type=\"image/png\" sizes=\"32x32\" href=\"/favicon-32x32.png\">");
        assertThat(html).contains("<link rel=\"icon\" type=\"image/png\" sizes=\"16x16\" href=\"/favicon-16x16.png\">");
        assertThat(html).contains("<link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">");
        assertThat(html).contains("<link rel=\"manifest\" href=\"/site.webmanifest\">");
        assertThat(html).contains("<meta name=\"theme-color\" content=\"#0b1f33\">");
    }
}
