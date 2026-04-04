package com.diasoft.registry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public class GatewayInternalAuthFilter extends OncePerRequestFilter {
    private static final String INTERNAL_PATTERN = "/internal/gateway/**";

    private final String expectedBearerToken;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public GatewayInternalAuthFilter(String expectedBearerToken, ObjectMapper objectMapper) {
        this.expectedBearerToken = expectedBearerToken == null ? "" : expectedBearerToken.trim();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !pathMatcher.match(INTERNAL_PATTERN, request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String expectedValue = "Bearer " + expectedBearerToken;
        if (!expectedBearerToken.isBlank() && expectedValue.equals(authorization)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("error", "unauthorized"));
    }
}
