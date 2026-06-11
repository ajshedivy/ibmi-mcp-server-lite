package com.ibm.ibmi.mcp.sql;

import java.util.List;

/**
 * A SQL statement converted to positional form: every {@code :name} placeholder replaced
 * with {@code ?} and the corresponding values collected in execution order.
 */
public record BoundStatement(String sql, List<Object> parameters) {}
