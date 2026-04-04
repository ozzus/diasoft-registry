package com.diasoft.registry.service;

import org.springframework.stereotype.Component;

@Component
public class ActorResolver {
    private final CurrentUserService currentUserService;

    public ActorResolver(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    public String currentActorId() {
        return currentUserService.currentUserResponse().subject();
    }
}
