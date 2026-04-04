package com.diasoft.registry.api;

import com.diasoft.registry.api.dto.CurrentUserResponse;
import com.diasoft.registry.service.CurrentUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final CurrentUserService currentUserService;

    public MeController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public CurrentUserResponse me() {
        return currentUserService.currentUserResponse();
    }
}
