package com.diasoft.registry.api;

import com.diasoft.registry.api.dto.DiplomaResponse;
import com.diasoft.registry.api.dto.RegistryDiplomaListResponse;
import com.diasoft.registry.api.dto.RevokeDiplomaRequest;
import com.diasoft.registry.service.DiplomaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/university/diplomas")
public class UniversityDiplomaController {
    private final DiplomaService diplomaService;

    public UniversityDiplomaController(DiplomaService diplomaService) {
        this.diplomaService = diplomaService;
    }

    @GetMapping
    public RegistryDiplomaListResponse listDiplomas(
            @RequestParam(value = "universityId", required = false) UUID universityId,
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page
    ) {
        return diplomaService.listUniversityDiplomas(universityId, page, 20);
    }

    @GetMapping("/{id}")
    public DiplomaResponse getDiploma(@PathVariable("id") UUID diplomaId) {
        return diplomaService.getUniversityDiploma(diplomaId);
    }

    @PostMapping("/{id}/revoke")
    public DiplomaResponse revokeDiploma(
            @PathVariable("id") UUID diplomaId,
            @Valid @RequestBody RevokeDiplomaRequest request
    ) {
        return diplomaService.revoke(diplomaId, request.reason());
    }
}
