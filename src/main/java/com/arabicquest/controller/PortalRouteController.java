// Serves public pages and secret-path Admin HQ HTML, script, and modular script resources.
package com.arabicquest.controller;

import com.arabicquest.config.BrandConstants;
import com.arabicquest.config.ProdStartupValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

@RestController
public class PortalRouteController {

    private final String googleClientId;
    private final String adminSecretSlug;
    private final String brandNameLatin;

    public PortalRouteController(
            @Value("${GOOGLE_CLIENT_ID:}") String googleClientId,
            @Value("${ADMIN_SECRET_PATH}") String adminSecretPath,
            @Value("${app.brand.name:" + BrandConstants.BRAND_NAME_LATIN + "}") String brandNameLatin) {
        this.googleClientId = googleClientId != null ? googleClientId.trim() : "";
        String slug = ProdStartupValidator.normalizeSlug(adminSecretPath);
        if (slug == null) {
            throw new IllegalStateException("ADMIN_SECRET_PATH must be configured and non-blank");
        }
        this.adminSecretSlug = slug;
        this.brandNameLatin = (brandNameLatin == null || brandNameLatin.isBlank())
                ? BrandConstants.BRAND_NAME_LATIN
                : brandNameLatin.trim();
    }

    @GetMapping("/api/public/config")
    public ResponseEntity<Map<String, Object>> getPublicConfig() {
        return ResponseEntity.ok(Map.of(
                "brandNameLatin", brandNameLatin,
                "brandNameArabic", BrandConstants.BRAND_NAME_ARABIC,
                "tagline", BrandConstants.TAGLINE,
                "googleClientId", googleClientId,
                "googleSignInEnabled", !googleClientId.isEmpty()
        ));
    }

    @GetMapping({"/${ADMIN_SECRET_PATH}", "/${ADMIN_SECRET_PATH}/"})
    public ResponseEntity<String> serveSecretAdminDashboard() throws IOException {
        String html = readResource("private-hq/admin.html")
                .replace("{{HQ_BASE_PATH}}", "/" + adminSecretSlug)
                .replace("{{HQ_SCRIPT_PATH}}", "/" + adminSecretSlug + ".js")
                .replace("{{HQ_LOGIN_PATH}}", "/" + adminSecretSlug + "-login");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
                .body(html);
    }

    @GetMapping({"/turath-hq", "/turath-hq/", "/turath-hq-login", "/turath-hq-login/"})
    public ResponseEntity<Void> legacyAdminRedirect() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "/" + adminSecretSlug + "-login")
                .build();
    }

    @GetMapping({"/${ADMIN_SECRET_PATH}-login", "/${ADMIN_SECRET_PATH}-login/"})
    public ResponseEntity<String> serveSecretAdminLogin() throws IOException {
        String html = readResource("private-hq/admin-login.html")
                .replace("{{HQ_DASHBOARD_PATH}}", "/" + adminSecretSlug);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
                .body(html);
    }

    @GetMapping("/${ADMIN_SECRET_PATH}/modules/{folder}/{file}")
    public ResponseEntity<String> serveSecretAdminModule(
            @PathVariable String folder,
            @PathVariable String file) throws IOException {
        if (!Set.of("data", "components", "pages").contains(folder) || !file.matches("^[a-z0-9-]+\\.js$")) {
            return ResponseEntity.notFound().build();
        }
        ClassPathResource resource = new ClassPathResource("private-hq/" + folder + "/" + file);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        String js = readResource("private-hq/" + folder + "/" + file)
                .replace("{{HQ_LOGIN_PATH}}", "/" + adminSecretSlug + "-login");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.valueOf("application/javascript;charset=UTF-8"))
                .body(js);
    }

    @GetMapping("/${ADMIN_SECRET_PATH}.js")
    public ResponseEntity<String> serveSecretAdminScript() throws IOException {
        String js = readResource("private-hq/admin.js")
                .replace("{{HQ_LOGIN_PATH}}", "/" + adminSecretSlug + "-login");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.valueOf("application/javascript;charset=UTF-8"))
                .body(js);
    }

    private String readResource(String classpathLocation) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        try (InputStream is = resource.getInputStream()) {
            return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
        }
    }
}
