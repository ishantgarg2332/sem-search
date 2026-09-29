package com.dev.semsearch.search.auth;

import java.util.List;

public class AuthDtos {

    public record LoginRequest(
            String username,
            String password
    ) {}

    public record LoginResponse(
            String token,
            UserSummary user
    ) {}

    public record SignupRequest(
            String username,
            String password,
            String firstName,
            String lastName,
            String email
    ) {}

    public record UserSummary(
            String username,
            String firstName,
            String lastName,
            String email,
            List<String> roles,
            boolean isAdmin
    ) {}

    public record MessageResponse(
            String message
    ) {}
}
