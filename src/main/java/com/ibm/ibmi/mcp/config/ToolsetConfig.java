package com.ibm.ibmi.mcp.config;

import java.util.List;

/**
 * A named grouping of tools from the {@code toolsets:} section. Toolsets are pure
 * groupings — a tool may belong to several, and selecting toolsets at startup
 * (via {@code --toolsets} or {@code SELECTED_TOOLSETS}) filters which tools register.
 */
public record ToolsetConfig(
    String name,
    String title,
    String description,
    List<String> tools) {}
