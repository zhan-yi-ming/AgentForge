package com.agentforge.core.user;

import java.util.UUID;

public interface UserDirectory {

    void requireUserExists(UUID userId);
}
