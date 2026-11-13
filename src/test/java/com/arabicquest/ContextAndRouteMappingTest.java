// Verifies the Spring application context starts cleanly and every controller method+path is unique.
package com.arabicquest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ContextAndRouteMappingTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void applicationContextLoadsAndHandlerMappingExists() {
        assertThat(handlerMapping).isNotNull();
        assertThat(handlerMapping.getHandlerMethods()).isNotEmpty();
    }

    @Test
    void everyHttpMethodAndPathIsMappedExactlyOnce() {
        Map<String, String> seen = new HashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.arabicquest")) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (methods.isEmpty()) {
                methods = Set.of(RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);
            }
            Set<String> patterns = info.getPatternValues();
            String handlerLabel = handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
            for (RequestMethod method : methods) {
                for (String pattern : patterns) {
                    String normalizedPath = pattern.replaceAll("\\{[^/]+\\}", "{param}");
                    String key = method.name() + " " + normalizedPath;
                    String previous = seen.putIfAbsent(key, handlerLabel + " (" + pattern + ")");
                    assertThat(previous)
                            .as("Duplicate endpoint mapping detected for %s", key)
                            .isNull();
                }
            }
        }
    }
}
