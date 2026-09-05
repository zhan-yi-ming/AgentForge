from dataclasses import dataclass
import re
from uuid import UUID

from .schemas import RagSource


@dataclass(frozen=True)
class Chunk:
    project_id: UUID
    source_type: str
    source_id: UUID
    source_version: int
    chunk_index: int
    title: str
    content: str


def chunk_source(
    project_id: UUID,
    source: RagSource,
    max_chars: int = 800,
    overlap_chars: int = 120,
) -> list[Chunk]:
    if max_chars <= overlap_chars or overlap_chars < 0:
        raise ValueError("max_chars must be greater than overlap_chars")

    normalized = source.content.strip()
    if not normalized:
        return []

    blocks = [block.strip() for block in re.split(r"\n\s*\n", normalized) if block.strip()]
    segments: list[str] = []
    current = ""
    for block in blocks:
        if len(block) > max_chars:
            if current:
                segments.append(current)
                current = ""
            step = max_chars - overlap_chars
            segments.extend(block[start : start + max_chars] for start in range(0, len(block), step))
            continue
        candidate = f"{current}\n\n{block}" if current else block
        if len(candidate) <= max_chars:
            current = candidate
        else:
            segments.append(current)
            prefix = current[-overlap_chars:] if overlap_chars else ""
            current = f"{prefix}\n\n{block}".strip()
    if current:
        segments.append(current)

    return [
        Chunk(
            project_id=project_id,
            source_type=source.source_type,
            source_id=source.source_id,
            source_version=source.version,
            chunk_index=index,
            title=source.title,
            content=f"{source.title}\n{segment}" if source.title not in segment else segment,
        )
        for index, segment in enumerate(segments)
        if segment.strip()
    ]
