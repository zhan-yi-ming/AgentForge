package com.agentforge.core.user.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.user.UserDirectory;
import com.agentforge.core.user.domain.User;
import com.agentforge.core.user.domain.UserRepository;

@Service
public class UserService implements UserDirectory {

    private final UserRepository userRepository;
    private final Clock clock;

    public UserService(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public UserView createUser(String email, String displayName) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String normalizedDisplayName = displayName.trim();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ConflictException("A user with this email already exists.");
        }

        User user = User.create(normalizedEmail, normalizedDisplayName, Instant.now(clock));
        try {
            return UserView.from(userRepository.save(user));
        }
        catch (DataIntegrityViolationException exception) {
            throw new ConflictException("A user with this email already exists.");
        }
    }

    @Transactional(readOnly = true)
    public UserView getUser(UUID userId) {
        return UserView.from(findUser(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public void requireUserExists(UUID userId) {
        findUser(userId);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }
}
