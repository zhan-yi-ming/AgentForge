package com.agentforge.core.user.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agentforge.core.user.application.UserService;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse response = UserResponse.from(
                userService.createUser(request.email(), request.displayName()));
        return ResponseEntity
                .created(URI.create("/api/v1/users/" + response.id()))
                .body(response);
    }

    @GetMapping("/{userId}")
    UserResponse getUser(@PathVariable UUID userId) {
        return UserResponse.from(userService.getUser(userId));
    }
}
