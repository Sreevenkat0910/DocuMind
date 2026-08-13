package com.fruity.documind.web;

import com.fruity.documind.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request/response payloads for the {@code /auth} endpoints. */
public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank String name,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {}

    /** The signed access token plus a little user context so the client needn't decode the JWT. */
    public record AuthResponse(String token, String email, String name, String role) {
        static AuthResponse of(String token, User user) {
            return new AuthResponse(token, user.getEmail(), user.getName(), user.getRole().name());
        }
    }
}
