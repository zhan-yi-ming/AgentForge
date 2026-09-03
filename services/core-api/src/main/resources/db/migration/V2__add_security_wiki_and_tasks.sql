ALTER TABLE app_user
    ADD COLUMN password_hash VARCHAR(255),
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT ck_app_user_role CHECK (role IN ('USER', 'ADMIN'));

CREATE TABLE wiki_page (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_wiki_page_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT uk_wiki_page_project_title UNIQUE (project_id, title),
    CONSTRAINT ck_wiki_page_title_not_blank CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_wiki_page_content_length CHECK (length(content) <= 100000),
    CONSTRAINT ck_wiki_page_version_non_negative CHECK (version >= 0)
);

CREATE INDEX idx_wiki_page_project_updated
    ON wiki_page (project_id, updated_at DESC);

CREATE TABLE task_item (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(10000),
    status VARCHAR(20) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_task_item_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT ck_task_item_title_not_blank CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_task_item_status CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    CONSTRAINT ck_task_item_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_task_item_version_non_negative CHECK (version >= 0)
);

CREATE INDEX idx_task_item_project_updated
    ON task_item (project_id, updated_at DESC);
