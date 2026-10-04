[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$provision = Join-Path $root "infra/postgres/provision-roles.sh"
$migration = Join-Path $root "services/core-api/src/main/resources/db/migration/V16__separate_database_service_roles.sql"
$snapshotMigration = Join-Path $root "services/core-api/src/main/resources/db/migration/V17__rag_snapshot_generation.sql"
$lexicalMigration = Join-Path $root "services/core-api/src/main/resources/db/migration/V18__rag_lexical_search.sql"
foreach ($path in $provision, $migration, $snapshotMigration, $lexicalMigration) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required role boundary file is missing: $path" }
}

$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 10)
$container = "agentforge-r10-role-$suffix"
$adminPassword = "r10_admin_test_$suffix"
$corePassword = "r10_core_test_$suffix"
$agentPassword = "r10_agent_test_$suffix"
$database = "agentforge_r10"
$created = $false

function Invoke-Docker([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker command failed with exit code $LASTEXITCODE" }
}

function Invoke-Psql([string]$User, [string]$Password, [string]$Sql) {
    & docker exec -e "PGPASSWORD=$Password" $container psql --no-psqlrc --set ON_ERROR_STOP=1 `
        --host 127.0.0.1 --username $User --dbname $database --command $Sql
    if ($LASTEXITCODE -ne 0) { throw "psql command failed for role $User" }
}

function Assert-PsqlDenied([string]$Sql) {
    $output = & docker exec -e "PGPASSWORD=$agentPassword" $container psql --no-psqlrc --set ON_ERROR_STOP=1 `
        --host 127.0.0.1 --username agentforge_agent --dbname $database --command $Sql 2>&1
    if ($LASTEXITCODE -eq 0) { throw "Agent role unexpectedly executed forbidden SQL: $Sql" }
    if (($output -join "`n") -notmatch "permission denied") {
        throw "Agent role failed for an unexpected reason while checking a forbidden operation."
    }
}

try {
    Invoke-Docker @("run", "--detach", "--name", $container,
        "-e", "POSTGRES_DB=$database", "-e", "POSTGRES_USER=agentforge_admin",
        "-e", "POSTGRES_PASSWORD=$adminPassword", "pgvector/pgvector:pg17")
    $created = $true
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & docker exec $container pg_isready --host 127.0.0.1 --username agentforge_admin --dbname $database *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "PostgreSQL did not become ready." }

    Invoke-Psql "agentforge_admin" $adminPassword "CREATE ROLE agentforge_core LOGIN BYPASSRLS; CREATE ROLE agentforge_agent LOGIN BYPASSRLS;"

    Invoke-Docker @("cp", $provision, "${container}:/tmp/provision-roles.sh")
    Invoke-Docker @("cp", $migration, "${container}:/tmp/V16.sql")
    Invoke-Docker @("cp", $snapshotMigration, "${container}:/tmp/V17.sql")
    Invoke-Docker @("cp", $lexicalMigration, "${container}:/tmp/V18.sql")
    Invoke-Docker @("exec", "-e", "PGHOST=127.0.0.1", "-e", "POSTGRES_DB=$database",
        "-e", "POSTGRES_USER=agentforge_admin", "-e", "POSTGRES_PASSWORD=$adminPassword",
        "-e", "AGENTFORGE_CORE_DB_PASSWORD=$corePassword",
        "-e", "AGENTFORGE_AGENT_DB_PASSWORD=$agentPassword",
        $container, "bash", "/tmp/provision-roles.sh")

    Invoke-Psql "agentforge_admin" $adminPassword @"
CREATE TABLE app_user(id uuid PRIMARY KEY, marker text NOT NULL);
CREATE TABLE project(id uuid PRIMARY KEY);
CREATE TABLE wiki_page(id uuid PRIMARY KEY, project_id uuid, marker text NOT NULL);
CREATE TABLE task_item(id uuid PRIMARY KEY, project_id uuid, marker text NOT NULL);
CREATE TABLE agent_task_action(id uuid PRIMARY KEY, marker text NOT NULL);
CREATE EXTENSION vector;
CREATE TABLE rag_chunk(
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    source_type varchar(16) NOT NULL,
    source_id uuid NOT NULL,
    source_version bigint NOT NULL,
    chunk_index integer NOT NULL,
    title varchar(200) NOT NULL,
    content text NOT NULL,
    embedding vector(384) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE(source_type,source_id,source_version,chunk_index)
);
CREATE SCHEMA agent_checkpoint;
CREATE TABLE agent_checkpoint.checkpoints(id uuid PRIMARY KEY, marker text NOT NULL);
INSERT INTO app_user VALUES ('00000000-0000-0000-0000-000000000001', 'initial');
INSERT INTO task_item(id,marker) VALUES ('00000000-0000-0000-0000-000000000002', 'initial');
INSERT INTO agent_task_action VALUES ('00000000-0000-0000-0000-000000000003', 'initial');
INSERT INTO project VALUES ('00000000-0000-0000-0000-000000000006');
"@
    Invoke-Docker @("exec", "-e", "PGPASSWORD=$adminPassword", $container, "psql", "--no-psqlrc",
        "--set", "ON_ERROR_STOP=1", "--host", "127.0.0.1", "--username", "agentforge_admin",
        "--dbname", $database, "--file", "/tmp/V16.sql")
    Invoke-Docker @("exec", "-e", "PGPASSWORD=$adminPassword", $container, "psql", "--no-psqlrc",
        "--set", "ON_ERROR_STOP=1", "--host", "127.0.0.1", "--username", "agentforge_admin",
        "--dbname", $database, "--file", "/tmp/V17.sql")
    Invoke-Docker @("exec", "-e", "PGPASSWORD=$adminPassword", $container, "psql", "--no-psqlrc",
        "--set", "ON_ERROR_STOP=1", "--host", "127.0.0.1", "--username", "agentforge_admin",
        "--dbname", $database, "--file", "/tmp/V18.sql")

    Invoke-Psql "agentforge_core" $corePassword "UPDATE app_user SET marker='core-ok';"
    Invoke-Psql "agentforge_core" $corePassword "INSERT INTO wiki_page VALUES ('00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000006','core-source'); SELECT generation FROM rag_source_generation WHERE project_id='00000000-0000-0000-0000-000000000006';"
    Invoke-Psql "agentforge_agent" $agentPassword "INSERT INTO rag_chunk(id,project_id,source_type,source_id,source_version,chunk_index,title,content,embedding,created_at) VALUES ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000006','WIKI','00000000-0000-0000-0000-000000000007',0,0,'Agent','agent lexical marker',array_fill(0::real,ARRAY[384])::vector,now()); SELECT search_document FROM rag_chunk WHERE id='00000000-0000-0000-0000-000000000004';"
    Invoke-Psql "agentforge_agent" $agentPassword "INSERT INTO rag_project_snapshot(project_id,snapshot_version) VALUES ('00000000-0000-0000-0000-000000000006',42);"
    Invoke-Psql "agentforge_agent" $agentPassword "ALTER TABLE agent_checkpoint.checkpoints ADD COLUMN agent_upgrade_marker text;"
    Invoke-Psql "agentforge_agent" $agentPassword "CREATE TABLE agent_checkpoint.writes(id uuid PRIMARY KEY); INSERT INTO agent_checkpoint.writes VALUES ('00000000-0000-0000-0000-000000000005');"
    Assert-PsqlDenied "UPDATE app_user SET marker='forbidden';"
    Assert-PsqlDenied "UPDATE task_item SET marker='forbidden';"
    Assert-PsqlDenied "UPDATE agent_task_action SET marker='forbidden';"
    Assert-PsqlDenied "DELETE FROM project;"
    Assert-PsqlDenied "SELECT * FROM rag_source_generation;"
    Assert-PsqlDenied "CREATE TABLE public.agent_escape(id integer);"

    Invoke-Psql "agentforge_admin" $adminPassword "DO `$check`$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('agentforge_core','agentforge_agent') AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN RAISE EXCEPTION 'service role is privileged'; END IF; END `$check`$;"
    Write-Host "Database role boundary passed: Core business DML allowed; Agent RAG chunk/snapshot and checkpoint allowed; Agent business writes and public DDL denied."
} finally {
    if ($created) {
        & docker rm --force $container *> $null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Failed to remove temporary container $container" }
    }
}
