package com.aitravel.planner.auth;

import java.util.UUID;

public class JwtUser {
    private final UUID id;
    private final String email;

    public JwtUser(UUID id, String email) {
        this.id = id;
        this.email = email;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
}

