package com.acme.salary.controller;

import com.acme.salary.dto.LoginRequest;
import com.acme.salary.dto.LoginResponse;
import com.acme.salary.service.AuthService;
import com.acme.salary.service.JwtService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-1.1: {@code POST /api/v1/auth/login}. Thin by design -- all logic lives in {@link AuthService}. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        JwtService.IssuedToken issued = authService.login(request.email(), request.password());
        return LoginResponse.bearer(issued.token(), issued.expiresAt());
    }
}
