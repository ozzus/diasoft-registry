package com.diasoft.registry.api;

import com.diasoft.registry.api.dto.UniversityResponse;
import com.diasoft.registry.service.UniversityService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/universities")
public class UniversityController {
    private final UniversityService universityService;

    public UniversityController(UniversityService universityService) {
        this.universityService = universityService;
    }

    @GetMapping
    public List<UniversityResponse> listUniversities() {
        return universityService.listUniversities();
    }
}
