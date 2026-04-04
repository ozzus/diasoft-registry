package com.diasoft.registry.service;

import com.diasoft.registry.api.dto.UniversityResponse;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class UniversityService {
    private final JdbcClient jdbcClient;
    private final CurrentUserService currentUserService;

    public UniversityService(JdbcClient jdbcClient, CurrentUserService currentUserService) {
        this.jdbcClient = jdbcClient;
        this.currentUserService = currentUserService;
    }

    public List<UniversityResponse> listUniversities() {
        if (!currentUserService.isSuperAdmin()) {
            return jdbcClient.sql("""
                    select id, code, name, created_at
                    from universities
                    where id = :id
                    order by name asc
                    """)
                    .param("id", currentUserService.requireUniversityScope())
                    .query((rs, rowNum) -> new UniversityResponse(
                            rs.getObject("id", java.util.UUID.class),
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getTimestamp("created_at").toInstant()
                    ))
                    .list();
        }

        return jdbcClient.sql("""
                select id, code, name, created_at
                from universities
                order by name asc
                """)
                .query((rs, rowNum) -> new UniversityResponse(
                        rs.getObject("id", java.util.UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }
}
