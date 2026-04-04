package com.diasoft.registry.api;

import com.diasoft.registry.api.dto.DiplomaResponse;
import com.diasoft.registry.api.dto.ShareLinkRequest;
import com.diasoft.registry.api.dto.ShareLinkResponse;
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
@RequestMapping("/api/v1/student/diplomas")
public class StudentDiplomaController {
    private final DiplomaService diplomaService;

    public StudentDiplomaController(DiplomaService diplomaService) {
        this.diplomaService = diplomaService;
    }

    @GetMapping
    public List<DiplomaResponse> listDiplomas() {
        return diplomaService.listStudentDiplomas();
    }

    @GetMapping("/{id}")
    public DiplomaResponse getDiploma(@PathVariable("id") UUID diplomaId) {
        return diplomaService.getStudentDiploma(diplomaId);
    }

    @PostMapping("/{id}/share-links")
    public ShareLinkResponse createShareLink(
            @PathVariable("id") UUID diplomaId,
            @Valid @RequestBody(required = false) ShareLinkRequest request
    ) {
        Integer maxViews = request == null ? null : request.maxViews();
        return diplomaService.createShareLink(diplomaId, maxViews);
    }
}
