CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_app_user_display_name_not_blank CHECK (length(btrim(display_name)) > 0)
);

CREATE TABLE project (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_project_owner FOREIGN KEY (owner_id) REFERENCES app_user (id),
    CONSTRAINT uk_project_owner_name UNIQUE (owner_id, name),
    CONSTRAINT ck_project_name_not_blank CHECK (length(btrim(name)) > 0)
);

CREATE INDEX idx_project_owner_id ON project (owner_id);
