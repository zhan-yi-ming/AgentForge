package com.agentforge.core.security.application;

public record IssuedToken(String value, long expiresInSeconds) {
}
