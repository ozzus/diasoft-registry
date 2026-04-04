package com.diasoft.registry.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    @ConditionalOnProperty(name = "app.security.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain insecureFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.enabled", havingValue = "true")
    SecurityFilterChain secureFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/ping", "/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/livez", "/readyz").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/universities").hasAnyAuthority("ROLE_super_admin", "ROLE_university_admin", "ROLE_university_operator")
                        .requestMatchers(HttpMethod.POST, "/api/v1/universities/*/imports").hasAnyAuthority("ROLE_super_admin", "ROLE_university_admin", "ROLE_university_operator")
                        .requestMatchers(HttpMethod.GET, "/api/v1/universities/*/imports").hasAnyAuthority("ROLE_super_admin", "ROLE_university_admin", "ROLE_university_operator")
                        .requestMatchers(HttpMethod.GET, "/api/v1/me").hasAnyAuthority("ROLE_super_admin", "ROLE_university_admin", "ROLE_university_operator", "ROLE_student")
                        .requestMatchers("/api/v1/imports/**", "/api/v1/university/**").hasAnyAuthority("ROLE_super_admin", "ROLE_university_admin", "ROLE_university_operator")
                        .requestMatchers("/api/v1/student/**").hasAnyAuthority("ROLE_super_admin", "ROLE_student")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
        return converter;
    }

    static class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
        @Override
        public Collection<GrantedAuthority> convert(Jwt source) {
            List<GrantedAuthority> authorities = new ArrayList<>();

            Object scopeClaim = source.getClaims().get("scope");
            if (scopeClaim instanceof String scopeValue) {
                for (String scope : scopeValue.split(" ")) {
                    if (!scope.isBlank()) {
                        authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
                    }
                }
            }

            Object realmAccess = source.getClaims().get("realm_access");
            if (realmAccess instanceof Map<?, ?> realmAccessMap) {
                Object roles = realmAccessMap.get("roles");
                if (roles instanceof Collection<?> roleItems) {
                    for (Object roleItem : roleItems) {
                        if (roleItem instanceof String role && !role.isBlank()) {
                            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                        }
                    }
                }
            }

            return authorities;
        }
    }
}
