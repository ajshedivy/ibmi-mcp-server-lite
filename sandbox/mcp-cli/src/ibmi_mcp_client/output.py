"""Helpers for reading the server's ``StandardSqlToolOutput`` results.

Every successful tool call returns ``{success, data, metadata}`` both as a JSON
text block and as MCP ``structuredContent``. These helpers pull that payload out
of a FastMCP ``CallToolResult`` regardless of which channel carried it.
"""

from __future__ import annotations

import json
from typing import Any


def parse_output(result: Any) -> dict[str, Any]:
    """Return the ``{success, data, metadata}`` dict from a tool result.

    Prefers MCP ``structuredContent``; falls back to parsing the JSON text block.
    """
    structured = getattr(result, "structured_content", None)
    if isinstance(structured, dict) and "success" in structured:
        return structured
    text = text_content(result)
    if text:
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            pass
    return structured if isinstance(structured, dict) else {}


def text_content(result: Any) -> str:
    """Return the first text content block of a tool result, or an empty string."""
    for block in getattr(result, "content", None) or []:
        text = getattr(block, "text", None)
        if text:
            return text
    return ""
