CREATE TABLE ai_usage_daily (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    usage_date DATE NOT NULL,
    request_count INTEGER NOT NULL,
    PRIMARY KEY (user_id, usage_date),
    CONSTRAINT ai_usage_daily_count_check CHECK (request_count > 0)
);
