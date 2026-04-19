"""`context_schema` passed into `create_deep_agent(...)`.

Available inside tools via `get_config()["configurable"]` / the runtime
context hook. Carries the request-scoped inputs that the orchestrator prompt
references and that sub-agents may want to read (e.g. `work_dir` so they
know where on disk they're allowed to poke).
"""

from __future__ import annotations

from typing_extensions import TypedDict


class RubberDuckContext(TypedDict, total=False):
    language: str
    current_file_path: str | None
    current_code: str
    context_files: list[dict]
    allow_edits: bool
    work_dir: str
