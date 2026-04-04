package com.diasoft.registry.service;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class MaskingService {
    public String maskFullName(String fullName) {
        return Arrays.stream(fullName.trim().split("\\s+"))
                .filter(part -> !part.isBlank())
                .map(this::maskPart)
                .collect(Collectors.joining(" "));
    }

    private String maskPart(String value) {
        if (value.length() <= 1) {
            return "*";
        }
        return value.substring(0, 1) + "***";
    }
}
