package com.agentforge.core.agent.infrastructure;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.agentforge.core.agent.application.AiUsageQuota;
import com.agentforge.core.shared.error.RateLimitExceededException;

@Component
class JdbcAiUsageQuota implements AiUsageQuota {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final int dailyLimit;

    JdbcAiUsageQuota(
            JdbcTemplate jdbcTemplate,
            Clock clock,
            @Value("${agentforge.ai.daily-limit:0}") int dailyLimit) {
        if (dailyLimit < 0) {
            throw new IllegalArgumentException("AGENTFORGE_AI_DAILY_LIMIT cannot be negative.");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.dailyLimit = dailyLimit;
    }

    @Override
    public void consume(UUID userId) {
        if (dailyLimit == 0) {
            return;
        }
        int updated = jdbcTemplate.update("""
                INSERT INTO ai_usage_daily (user_id, usage_date, request_count)
                VALUES (?, ?, 1)
                ON CONFLICT (user_id, usage_date) DO UPDATE
                SET request_count = ai_usage_daily.request_count + 1
                WHERE ai_usage_daily.request_count < ?
                """, userId, LocalDate.now(clock), dailyLimit);
        if (updated == 0) {
            throw new RateLimitExceededException("Daily AI request limit reached; retry after 00:00 UTC.");
        }
    }
}
