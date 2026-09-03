package com.agentforge.core.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.user.domain.User;
import com.agentforge.core.user.domain.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createUserNormalizesInputAndReturnsSavedUser() {
        when(userRepository.existsByEmail("owner@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserView result = userService.createUser("  Owner@Example.COM ", "  Project Owner  ");

        assertThat(result.email()).isEqualTo("owner@example.com");
        assertThat(result.displayName()).isEqualTo("Project Owner");
        assertThat(result.createdAt()).isEqualTo(NOW);
        verify(userRepository).existsByEmail("owner@example.com");
    }

    @Test
    void createUserRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("owner@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser("owner@example.com", "Owner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("email");
    }

    @Test
    void getUserRejectsUnknownId() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUser(userId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(userId.toString());
    }
}
