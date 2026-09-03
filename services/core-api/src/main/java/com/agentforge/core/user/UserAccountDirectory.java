package com.agentforge.core.user;

import java.util.Optional;

public interface UserAccountDirectory {

    UserAccount register(String email, String displayName, String passwordHash);

    Optional<UserAccount> findByEmail(String email);
}
