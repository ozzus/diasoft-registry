package com.diasoft.registry.service;

import com.diasoft.registry.api.dto.CurrentUserResponse;
import com.diasoft.registry.config.AppProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
    private final AppProperties properties;

    public CurrentUserService(AppProperties properties) {
        this.properties = properties;
    }

    public CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!properties.security().enabled()) {
            return currentOrDevIdentity(authentication);
        }
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AccessDeniedException("authentication required");
        }
        return fromJwt(authentication, jwt);
    }

    public CurrentUserResponse currentUserResponse() {
        CurrentUser user = currentUser();
        return new CurrentUserResponse(user.subject(), user.roles(), user.universityId(), user.studentExternalId());
    }

    public UUID requireUniversityScope() {
        CurrentUser user = currentUser();
        if (user.hasRole("super_admin")) {
            throw new AccessDeniedException("super_admin requires an explicit university resource");
        }
        if (!user.hasAnyRole("university_admin", "university_operator") || user.universityId() == null) {
            throw new AccessDeniedException("university scope is required");
        }
        return user.universityId();
    }

    public void assertUniversityAccess(UUID universityId) {
        CurrentUser user = currentUser();
        if (user.hasRole("super_admin")) {
            return;
        }
        if (!user.hasAnyRole("university_admin", "university_operator") || user.universityId() == null) {
            throw new AccessDeniedException("university scope is required");
        }
        if (!user.universityId().equals(universityId)) {
            throw new AccessDeniedException("university scope mismatch");
        }
    }

    public String requireStudentScope() {
        CurrentUser user = currentUser();
        if (user.hasRole("super_admin")) {
            throw new AccessDeniedException("super_admin requires an explicit student resource");
        }
        if (!user.hasRole("student") || user.studentExternalId() == null || user.studentExternalId().isBlank()) {
            throw new AccessDeniedException("student scope is required");
        }
        return user.studentExternalId();
    }

    public boolean isSuperAdmin() {
        return currentUser().hasRole("super_admin");
    }

    private CurrentUser currentOrDevIdentity(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return fromJwt(authentication, jwt);
        }

        AppProperties.DevIdentity devIdentity = properties.security().devIdentity();
        UUID universityId = parseUuid(devIdentity.universityId(), "app.security.dev-identity.university-id");
        String studentExternalId = blankToNull(devIdentity.studentExternalId());
        List<String> roles = sanitizeRoles(devIdentity.roles());
        return new CurrentUser(devIdentity.subject(), roles, universityId, studentExternalId);
    }

    private CurrentUser fromJwt(Authentication authentication, Jwt jwt) {
        List<String> roles = rolesFromAuthentication(authentication, jwt);
        UUID universityId = parseUuid(jwt.getClaimAsString("university_id"), "university_id");
        String studentExternalId = blankToNull(jwt.getClaimAsString("student_external_id"));
        String subject = blankToFallback(jwt.getSubject(), authentication.getName(), "jwt-user");
        return new CurrentUser(subject, roles, universityId, studentExternalId);
    }

    private List<String> rolesFromAuthentication(Authentication authentication, Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String value = authority.getAuthority();
            if (value != null && value.startsWith("ROLE_") && value.length() > 5) {
                roles.add(value.substring(5));
            }
        }

        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> realmAccessMap) {
            Object roleItems = realmAccessMap.get("roles");
            if (roleItems instanceof List<?> rawRoles) {
                for (Object rawRole : rawRoles) {
                    if (rawRole instanceof String role && !role.isBlank()) {
                        roles.add(role);
                    }
                }
            }
        }

        return new ArrayList<>(roles);
    }

    private List<String> sanitizeRoles(List<String> roles) {
        List<String> sanitized = new ArrayList<>();
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                sanitized.add(role.trim());
            }
        }
        if (sanitized.isEmpty()) {
            throw new IllegalStateException("app.security.dev-identity.roles must contain at least one role");
        }
        return sanitized;
    }

    private UUID parseUuid(String rawValue, String claimName) {
        String value = blankToNull(rawValue);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new AccessDeniedException("invalid UUID in " + claimName);
        }
    }

    private String blankToFallback(String primary, String secondary, String fallback) {
        String primaryValue = blankToNull(primary);
        if (primaryValue != null) {
            return primaryValue;
        }
        String secondaryValue = blankToNull(secondary);
        if (secondaryValue != null) {
            return secondaryValue;
        }
        return fallback;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    public record CurrentUser(
            String subject,
            List<String> roles,
            UUID universityId,
            String studentExternalId
    ) {
        public boolean hasRole(String role) {
            return roles.contains(role);
        }

        public boolean hasAnyRole(String... candidateRoles) {
            for (String candidateRole : candidateRoles) {
                if (roles.contains(candidateRole)) {
                    return true;
                }
            }
            return false;
        }
    }
}
