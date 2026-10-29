package com.example.manage.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class LoginPasswordVerification {
    private final PasswordEncoder encoder;
    private final String absentAccountHash;

    public LoginPasswordVerification(PasswordEncoder encoder) {
        this.encoder = encoder;
        this.absentAccountHash = encoder.encode(UUID.randomUUID().toString());
    }

    public boolean matches(String rawPassword, String storedHash) {
        boolean matches = encoder.matches(rawPassword,
                storedHash == null ? absentAccountHash : storedHash);
        return storedHash != null && matches;
    }
}
