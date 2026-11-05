package com.arabicquest.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class PortalRouteController {

    private final String googleClientId;
    private final String adminSecretSlug;

    public PortalRouteController(
            @Value("${GOOGLE_CLIENT_ID:}") String googleClientId,
            @Value("${ADMIN_SECRET_PATH:turath-hq}") String adminSecretPath) {
        this.googleClientId = googleClientId != null ? googleClientId.trim() : "";
        String slug = adminSecretPath != null ? adminSecretPath.trim() : "turath-hq";
        while (slug.startsWith("/")) slug = slug.substring(1);
        while (slug.endsWith("/")) slug = slug.substring(0, slug.length() - 1);
        this.adminSecretSlug = slug.isEmpty() ? "turath-hq" : slug;
    }

    @GetMapping("/api/public/config")
    public ResponseEntity<Map<String, Object>> getPublicConfig() {
        return ResponseEntity.ok(Map.of(
                "googleClientId", googleClientId,
                "googleSignInEnabled", !googleClientId.isEmpty()
        ));
    }

    @GetMapping({"/${ADMIN_SECRET_PATH:turath-hq}", "/${ADMIN_SECRET_PATH:turath-hq}/"})
    public ResponseEntity<String> serveSecretAdminDashboard() throws IOException {
        String html = readResource("private-hq/admin.html")
                .replace("{{HQ_SCRIPT_PATH}}", "/" + adminSecretSlug + ".js")
                .replace("{{HQ_LOGIN_PATH}}", "/" + adminSecretSlug + "-login");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
                .body(html);
    }

    @GetMapping({"/${ADMIN_SECRET_PATH:turath-hq}-login", "/${ADMIN_SECRET_PATH:turath-hq}-login/"})
    public ResponseEntity<String> serveSecretAdminLogin() throws IOException {
        String html = readResource("private-hq/admin-login.html")
                .replace("{{HQ_DASHBOARD_PATH}}", "/" + adminSecretSlug);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
                .body(html);
    }

    @GetMapping("/${ADMIN_SECRET_PATH:turath-hq}.js")
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
