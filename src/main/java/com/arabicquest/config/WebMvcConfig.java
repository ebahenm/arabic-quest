package com.arabicquest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/login").setViewName("forward:/login.html");
        registry.addViewController("/login/").setViewName("forward:/login.html");
        registry.addViewController("/student").setViewName("forward:/index.html");
        registry.addViewController("/student/").setViewName("forward:/index.html");
    }
}
