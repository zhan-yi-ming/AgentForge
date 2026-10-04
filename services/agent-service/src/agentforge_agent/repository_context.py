"""Bounded, read-only project repository context from committed Git objects."""

from dataclasses import dataclass
import os
from pathlib import Path
import re
import subprocess
import threading
from uuid import UUID, NAMESPACE_URL, uuid5


@dataclass(frozen=True)
class RepositoryMatch:
    source_type: str
    source_id: UUID
    title: str
    content: str
    revision: str


class RepositoryContextProvider:
    def __init__(self, repositories: dict[UUID, Path]) -> None:
        self.repositories = {project_id: Path(path) for project_id, path in repositories.items()}

    @classmethod
    def from_settings(cls, settings) -> "RepositoryContextProvider":
        return cls({binding.project_id: binding.path for binding in settings.repositories})

    def retrieve(self, project_id: UUID, query: str) -> list[RepositoryMatch]:
        configured = self.repositories.get(project_id)
        if configured is None:
            return []
        try:
            root = configured.resolve(strict=True)
            if not root.is_dir() or not (root / ".git").is_dir() or (root / ".git").is_symlink():
                return []
            actual = Path(self._git(root, "rev-parse", "--show-toplevel").decode().strip()).resolve()
            if actual != root:
                return []
            revision = self._git(root, "rev-parse", "HEAD").decode().strip()
            tree = self._git(root, "ls-tree", "-r", "-z", revision,
                             max_bytes=262_144, allow_truncate=True)
            rows = tree.split(b"\0")
            if not tree.endswith(b"\0"):
                rows.pop()
            matches: list[RepositoryMatch] = []
            directories: set[str] = set()
            for row in rows[:1000]:
                if not row:
                    continue
                metadata, raw_path = row.split(b"\t", 1)
                mode, kind, oid = metadata.decode("ascii").split()
                path = raw_path.decode("utf-8")
                if mode != "100644" or kind != "blob" or not _allowed(path):
                    continue
                try:
                    raw_content = self._git(root, "cat-file", "blob", oid,
                                            max_bytes=16_384, allow_truncate=True)
                except ValueError:
                    continue
                if b"\0" in raw_content:
                    continue
                content = raw_content.decode("utf-8", errors="ignore")
                if _sensitive(content):
                    continue
                if "/" in path:
                    directories.add(path.rsplit("/", 1)[0])
                matches.append(RepositoryMatch(
                    "REPOSITORY", uuid5(NAMESPACE_URL, f"{project_id}:{revision}:{path}"),
                    path, content, revision,
                ))
                if len(matches) >= 20:
                    break
            if directories:
                tree = "\n".join(sorted(directories)[:20])
                matches.append(RepositoryMatch(
                    "REPOSITORY", uuid5(NAMESPACE_URL, f"{project_id}:{revision}:tree"),
                    "Directory structure", tree, revision,
                ))
            commits = self._git(root, "log", "-n", "8", "--format=%h%x09%s", revision,
                                max_bytes=4096).decode("utf-8")
            for line in commits.splitlines()[:8]:
                short_id, separator, subject = line.partition("\t")
                if not separator or not re.fullmatch(r"[0-9a-f]{7,40}", short_id):
                    continue
                subject = subject[:160].strip()
                if not subject or _sensitive(subject):
                    continue
                matches.append(RepositoryMatch(
                    "REPOSITORY", uuid5(NAMESPACE_URL, f"{project_id}:{revision}:commit:{short_id}"),
                    f"Commit {short_id}", f"{short_id} {subject}", revision,
                ))
            return matches[:40]
        except (OSError, ValueError, UnicodeError, subprocess.SubprocessError):
            return []

    @staticmethod
    def _git(root: Path, *args: str, max_bytes: int = 4096,
             allow_truncate: bool = False) -> bytes:
        environment = dict(os.environ)
        environment.update({
            "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_NO_REPLACE_OBJECTS": "1", "GIT_OPTIONAL_LOCKS": "0",
            "GIT_TERMINAL_PROMPT": "0", "GIT_PAGER": "cat",
        })
        process = subprocess.Popen(
            ["git", "-C", str(root), "-c", f"safe.directory={root}", *args],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=environment,
        )
        timer = threading.Timer(5, process.kill)
        timer.daemon = True
        timer.start()
        try:
            if process.stdout is None:
                raise ValueError("Repository command stdout is unavailable.")
            result = process.stdout.read(max_bytes + 1)
            if len(result) > max_bytes:
                process.kill()
            code = process.wait()
            if len(result) > max_bytes and allow_truncate:
                return result[:max_bytes]
            if code != 0 or len(result) > max_bytes:
                raise ValueError("Repository command failed or exceeded output limit.")
            return result
        finally:
            timer.cancel()


def _allowed(path: str) -> bool:
    parts = path.lower().split("/")
    name = parts[-1]
    if any(marker in part for part in parts
           for marker in ("secret", "credential", "private", "token")):
        return False
    if any(part.startswith(".") or part in {"node_modules", "vendor"} for part in parts):
        return False
    if name.endswith((".pem", ".key", ".p12", ".pfx")):
        return False
    if "/" not in path:
        return name.startswith("readme") and name.endswith(".md") or name in {
            "pom.xml", "pyproject.toml", "package.json",
        }
    return path.lower().startswith("docs/04-api/") and name.endswith(".md")


def _sensitive(content: str) -> bool:
    return any(re.search(pattern, content) for pattern in (
        r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
        r"AKIA[0-9A-Z]{16}",
        r"sk-[A-Za-z0-9_-]{20,}",
        r"gh[pousr]_[A-Za-z0-9_]{30,}",
        r"github_pat_[A-Za-z0-9_]{20,}",
        r"(?i)(?:password|api[_-]?key|access[_-]?token)\s*[:=]\s*['\"]?[^\s'\"]{12,}",
    ))
