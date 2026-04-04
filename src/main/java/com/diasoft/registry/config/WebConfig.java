package com.diasoft.registry.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AppProperties properties;

    public WebConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var mapping = registry.addMapping("/api/**")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")
                .allowedHeaders("*")
                .maxAge(3600);

        if (properties.http().allowedOrigins().stream().anyMatch("*"::equals)) {
            mapping.allowedOriginPatterns("*");
        } else {
            mapping.allowedOrigins(properties.http().allowedOrigins().toArray(String[]::new))
                    .allowCredentials(true);
        }
    }
}
