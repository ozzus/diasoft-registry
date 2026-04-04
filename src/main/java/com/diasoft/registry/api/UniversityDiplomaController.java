package com.diasoft.registry.api;

import com.diasoft.registry.api.dto.DiplomaResponse;
import com.diasoft.registry.api.dto.RevokeDiplomaRequest;
import com.diasoft.registry.service.DiplomaService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/university/diplomas")
public class UniversityDiplomaController {
    private final DiplomaService diplomaService;

    public UniversityDiplomaController(DiplomaService diplomaService) {
        this.diplomaService = diplomaService;
    }

    @GetMapping
    public List<DiplomaResponse> listDiplomas() {
        return diplomaService.listUniversityDiplomas();
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
