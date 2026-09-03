package com.agentforge.core.security.application;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agentforge.core.shared.error.UnauthorizedException;
import com.agentforge.core.user.UserAccount;
import com.agentforge.core.user.UserAccountDirectory;

@Service
public class AuthenticationService {

    private static final String DUMMY_PASSWORD_HASH =
            "{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";

    private final UserAccountDirectory userAccounts;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthenticationService(
            UserAccountDirectory userAccounts,
            PasswordEncoder passwordEncoder,
            TokenService tokenService) {
        this.userAccounts = userAccounts;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Transactional
    public AuthenticationResult register(String email, String displayName, String password) {
        String passwordHash = passwordEncoder.encode(password);
        UserAccount user = userAccounts.register(email, displayName, passwordHash);
        return new AuthenticationResult(user, tokenService.issue(user));
    }

    @Transactional(readOnly = true)
    public AuthenticationResult login(String email, String password) {
        UserAccount user = userAccounts.findByEmail(email).orElse(null);
        String storedHash = user == null || user.passwordHash() == null
                ? DUMMY_PASSWORD_HASH
                : user.passwordHash();
        boolean matches = passwordEncoder.matches(password, storedHash);
        if (user == null || user.passwordHash() == null || !matches) {
            throw new UnauthorizedException("The email or password is invalid.");
        }
        return new AuthenticationResult(user, tokenService.issue(user));
    }
}
