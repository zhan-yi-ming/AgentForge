package com.agentforge.core.security.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.agentforge.core.shared.error.UnauthorizedException;
import com.agentforge.core.user.UserAccount;
import com.agentforge.core.user.UserAccountDirectory;
import com.agentforge.core.user.UserRole;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock
    private UserAccountDirectory userAccounts;

    @Mock
    private TokenService tokenService;

    private PasswordEncoder passwordEncoder;
    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        authenticationService = new AuthenticationService(userAccounts, passwordEncoder, tokenService);
    }

    @Test
    void registerHashesPasswordWithBcryptBeforePersistence() {
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        UserAccount saved = account("{bcrypt}saved-hash");
        when(userAccounts.register(anyString(), anyString(), anyString())).thenReturn(saved);
        when(tokenService.issue(saved)).thenReturn(new IssuedToken("signed-token", 1800));

        AuthenticationResult result = authenticationService.register(
                "owner@example.com",
                "Owner",
                "correct-horse-battery");

        verify(userAccounts).register(anyString(), anyString(), hashCaptor.capture());
        assertThat(hashCaptor.getValue()).startsWith("{bcrypt}");
        assertThat(hashCaptor.getValue()).isNotEqualTo("correct-horse-battery");
        assertThat(passwordEncoder.matches("correct-horse-battery", hashCaptor.getValue())).isTrue();
        assertThat(result.token().value()).isEqualTo("signed-token");
    }

    @Test
    void loginAcceptsCorrectPassword() {
        String hash = passwordEncoder.encode("correct-horse-battery");
        UserAccount user = account(hash);
        when(userAccounts.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(tokenService.issue(user)).thenReturn(new IssuedToken("signed-token", 1800));

        assertThat(authenticationService.login("owner@example.com", "correct-horse-battery").user())
                .isEqualTo(user);
    }

    @Test
    void loginUsesSameErrorForMissingLegacyAndWrongPasswordAccounts() {
        when(userAccounts.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        when(userAccounts.findByEmail("legacy@example.com")).thenReturn(Optional.of(account(null)));
        when(userAccounts.findByEmail("owner@example.com"))
                .thenReturn(Optional.of(account(passwordEncoder.encode("correct-password"))));

        assertInvalidLogin("missing@example.com", "wrong-password");
        assertInvalidLogin("legacy@example.com", "wrong-password");
        assertInvalidLogin("owner@example.com", "wrong-password");
    }

    private void assertInvalidLogin(String email, String password) {
        assertThatThrownBy(() -> authenticationService.login(email, password))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("The email or password is invalid.");
    }

    private UserAccount account(String passwordHash) {
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        return new UserAccount(
                UUID.randomUUID(),
                "owner@example.com",
                "Owner",
                passwordHash,
                UserRole.USER,
                now,
                now);
    }
}
