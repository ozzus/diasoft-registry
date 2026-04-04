package com.diasoft.registry.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingServiceTest {
    private final MaskingService maskingService = new MaskingService();

    @Test
    void masksEachNamePart() {
        assertThat(maskingService.maskFullName("Ivan Petrov"))
                .isEqualTo("I*** P***");
    }
}
