CREATE TABLE rag_project_snapshot (
    project_id UUID PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    snapshot_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_rag_project_snapshot_version CHECK (snapshot_version >= 0)
);

CREATE TABLE rag_source_generation (
    project_id UUID PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    generation BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_rag_source_generation_non_negative CHECK (generation >= 0)
);

INSERT INTO rag_source_generation(project_id)
SELECT id FROM project;

CREATE FUNCTION initialize_rag_source_generation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
BEGIN
    INSERT INTO public.rag_source_generation(project_id) VALUES (NEW.id)
    ON CONFLICT (project_id) DO NOTHING;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_project_rag_source_generation
AFTER INSERT ON project
FOR EACH ROW EXECUTE FUNCTION initialize_rag_source_generation();

CREATE FUNCTION advance_rag_source_generation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        UPDATE public.rag_source_generation
        SET generation = generation + 1
        WHERE project_id = OLD.project_id;
        RETURN OLD;
    END IF;

    UPDATE public.rag_source_generation
    SET generation = generation + 1
    WHERE project_id = NEW.project_id;
    IF TG_OP = 'UPDATE' AND OLD.project_id IS DISTINCT FROM NEW.project_id THEN
        UPDATE public.rag_source_generation
        SET generation = generation + 1
        WHERE project_id = OLD.project_id;
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_wiki_rag_source_generation
AFTER INSERT OR UPDATE OR DELETE ON wiki_page
FOR EACH ROW EXECUTE FUNCTION advance_rag_source_generation();

CREATE TRIGGER trg_task_rag_source_generation
AFTER INSERT OR UPDATE OR DELETE ON task_item
FOR EACH ROW EXECUTE FUNCTION advance_rag_source_generation();

DO $grant_agent$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentforge_agent') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE rag_project_snapshot TO agentforge_agent;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentforge_core') THEN
        GRANT SELECT ON TABLE rag_source_generation TO agentforge_core;
    END IF;
END
$grant_agent$;
