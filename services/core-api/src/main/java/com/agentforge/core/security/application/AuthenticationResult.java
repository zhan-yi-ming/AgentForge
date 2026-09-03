package com.agentforge.core.security.application;

import com.agentforge.core.user.UserAccount;

public record AuthenticationResult(UserAccount user, IssuedToken token) {
}
