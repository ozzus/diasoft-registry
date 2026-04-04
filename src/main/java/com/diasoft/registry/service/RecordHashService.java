package com.diasoft.registry.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class RecordHashService {
    public String compute(String universityCode, String diplomaNumber, String fullName, String programName, Integer graduationYear) {
        String canonical = String.join("|",
                universityCode == null ? "" : universityCode.trim().toUpperCase(),
                diplomaNumber == null ? "" : diplomaNumber.trim(),
                fullName == null ? "" : fullName.trim(),
                programName == null ? "" : programName.trim(),
                graduationYear == null ? "" : graduationYear.toString()
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to compute record hash", ex);
        }
    }
}
