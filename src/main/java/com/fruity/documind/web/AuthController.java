package com.fruity.documind.web;

import com.fruity.documind.entity.User;
import com.fruity.documind.service.AuthService;
import com.fruity.documind.service.TokenService;
import com.fruity.documind.web.AuthDtos.AuthResponse;
import com.fruity.documind.web.AuthDtos.LoginRequest;
import com.fruity.documind.web.AuthDtos.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2 auth endpoints. Public (see {@link com.fruity.documind.config.SecurityConfig}):
 * register mints a VIEWER account, login verifies credentials; both return a JWT the client
 * sends as {@code Authorization: Bearer <token>} on every other request.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;

    public AuthController(AuthService authService, TokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        User user = authService.register(req.email(), req.name(), req.password());
        return AuthResponse.of(tokenService.issue(user), user);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        User user = authService.authenticate(req.email(), req.password());
        return AuthResponse.of(tokenService.issue(user), user);
    }
}
