package com.arabicquest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Student Public Routes
        registry.addViewController("/login").setViewName("forward:/login.html");
        registry.addViewController("/login/").setViewName("forward:/login.html");
        registry.addViewController("/student").setViewName("forward:/index.html");
        registry.addViewController("/student/").setViewName("forward:/index.html");

        // Secret Admin Portal Routes (Only known to you!)
        registry.addViewController("/turath-hq").setViewName("forward:/admin.html");
        registry.addViewController("/turath-hq/").setViewName("forward:/admin.html");
        registry.addViewController("/turath-hq-login").setViewName("forward:/admin-login.html");
        registry.addViewController("/turath-hq-login/").setViewName("forward:/admin-login.html");
    }
}
