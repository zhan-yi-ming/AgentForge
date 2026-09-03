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
import com.agentforge.core.user.UserAccount;
import com.agentforge.core.user.UserRole;
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
    void registerNormalizesInputAndKeepsOnlyPasswordHash() {
        when(userRepository.existsByEmail("owner@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserAccount result = userService.register(
                "  Owner@Example.COM ",
                "  Project Owner  ",
                "{bcrypt}encoded-value");

        assertThat(result.email()).isEqualTo("owner@example.com");
        assertThat(result.displayName()).isEqualTo("Project Owner");
        assertThat(result.passwordHash()).isEqualTo("{bcrypt}encoded-value");
        assertThat(result.role()).isEqualTo(UserRole.USER);
        assertThat(result.createdAt()).isEqualTo(NOW);
        verify(userRepository).existsByEmail("owner@example.com");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("owner@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(
                "owner@example.com",
                "Owner",
                "{bcrypt}encoded-value"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("email");
    }

    @Test
    void findByEmailNormalizesLookup() {
        User user = User.register("owner@example.com", "Owner", "{bcrypt}encoded-value", NOW);
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        assertThat(userService.findByEmail(" Owner@Example.com "))
                .get()
                .extracting(UserAccount::email)
                .isEqualTo("owner@example.com");
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
