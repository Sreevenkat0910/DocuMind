package com.fruity.documind.service;

import com.fruity.documind.entity.User;
import com.fruity.documind.enums.Role;
import com.fruity.documind.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 2 registration + credential verification. Passwords are BCrypt-hashed; emails are
 * normalised to lowercase so uniqueness and lookup are case-insensitive.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Register a new account. Role is always VIEWER — elevation to EDITOR/ADMIN is an admin
     * action, never self-service, so self-registration can't grant itself privileges.
     */
    public User register(String email, String name, String rawPassword) {
        String normalized = email.strip().toLowerCase();
        if (userRepository.findByEmail(normalized).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = new User();
        user.setEmail(normalized);
        user.setName(name.strip());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(Role.VIEWER);
        return userRepository.save(user);
    }

    /**
     * Verify credentials and return the user. Unknown-email and wrong-password both yield the
     * same 401 (no account enumeration); a disabled account is also rejected.
     */
    public User authenticate(String email, String rawPassword) {
        return userRepository.findByEmail(email.strip().toLowerCase())
                .filter(User::isEnabled)
                .filter(u -> passwordEncoder.matches(rawPassword, u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
    }
}
