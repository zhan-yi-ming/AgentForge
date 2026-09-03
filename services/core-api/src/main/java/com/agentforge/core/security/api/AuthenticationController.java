package com.agentforge.core.security.api;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agentforge.core.security.application.AuthenticationService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    public AuthenticationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/register")
    ResponseEntity<AuthenticationResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthenticationResponse response = AuthenticationResponse.from(authenticationService.register(
                request.email(),
                request.displayName(),
                request.password()));
        return ResponseEntity.created(URI.create("/api/v1/users/me")).body(response);
    }

    @PostMapping("/login")
    AuthenticationResponse login(@Valid @RequestBody LoginRequest request) {
        return AuthenticationResponse.from(authenticationService.login(request.email(), request.password()));
    }
}
