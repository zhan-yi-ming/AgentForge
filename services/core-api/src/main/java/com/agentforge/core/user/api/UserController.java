package com.agentforge.core.user.api;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agentforge.core.user.application.UserService;
import com.agentforge.core.security.AuthenticatedActor;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    UserResponse getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(userService.getUser(AuthenticatedActor.from(jwt).userId()));
    }
}
