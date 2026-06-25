package com.ibm.ibmi.mcp.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.ibm.ibmi.mcp.config.ParameterConfig;
import com.ibm.ibmi.mcp.config.SqlToolConfig;

/**
 * Validates/coerces incoming tool arguments against the tool's parameter definitions and
 * converts the {@code :name} placeholders in the YAML statement into a Mapepire
 * parameterized query ({@code ?} markers + ordered values). No values are ever spliced
 * into the SQL text — binding is always parameterized, mirroring the reference server.
 *
 * <p><b>Processing order:</b>
 * <ol>
 *   <li>Apply defaults for missing arguments
 *   <li>Check required parameters
 *   <li>Coerce to declared type (string, integer, float, boolean, array)
 *   <li><b>Validate constraints</b> (min, max, minLength, maxLength, pattern, enum) via
 *       {@link ParameterValidator} — violations throw {@link IllegalArgumentException}
 *       before any SQL binding occurs
 *   <li>Bind as {@code ?} markers in the SQL statement
 * </ol>
 *
 * <p><b>Key behaviors:</b>
 * <ul>
 *   <li>Missing argument: use {@code default} if present; error if required; otherwise
 *       bind an empty string (reference behavior).
 *   <li>{@code boolean} values bind as {@code 1}/{@code 0} for Db2.
 *   <li>An array value expands its placeholder to {@code ?, ?, ...} — one marker per
 *       element — for {@code IN (:list)} clauses.
 *   <li>A {@code :name} with the same name appearing multiple times binds the value once
 *       per occurrence.
 *   <li>Constraint validation runs at call time, matching the reference server's Zod-based
 *       validation. All declared constraints (enum, min/max, minLength/maxLength, pattern)
 *       are enforced before SQL execution.
 * </ul>
 *
 * <p>TODO: the reference server also supports purely positional ({@code ?}) and
 * hybrid statements, plus a "direct substitution" mode where a statement consisting of
 * exactly {@code :param} executes the parameter value as the SQL text (used by
 * {@code execute_sql}). Those modes are not implemented here.
 */
public final class ParameterProcessor {

  private static final Pattern NAMED_PARAM = Pattern.compile(":(\\w+)");

  private ParameterProcessor() {}

  public static BoundStatement prepare(SqlToolConfig tool, Map<String, Object> arguments) {
    Map<String, Object> values = resolveValues(tool, arguments == null ? Map.of() : arguments);

    if (tool.parameters().isEmpty()) {
      // No declared parameters: statement runs as-is (reference behavior).
      return new BoundStatement(tool.statement(), List.of());
    }

    List<Object> bindings = new ArrayList<>();
    Matcher m = NAMED_PARAM.matcher(tool.statement());
    StringBuilder sql = new StringBuilder();
    while (m.find()) {
      String name = m.group(1);
      if (!values.containsKey(name)) {
        // Unknown placeholder: leave it verbatim; the database will report it.
        m.appendReplacement(sql, Matcher.quoteReplacement(m.group(0)));
        continue;
      }
      Object value = values.get(name);
      String markers;
      if (value instanceof List<?> list) {
        markers = list.stream().map(v -> "?").collect(Collectors.joining(", "));
        list.forEach(bindings::add);
      } else {
        markers = "?";
        bindings.add(value);
      }
      m.appendReplacement(sql, Matcher.quoteReplacement(markers));
    }
    m.appendTail(sql);
    return new BoundStatement(sql.toString(), bindings);
  }

  /** Applies defaults, required checks, and type coercion for each declared parameter. */
  private static Map<String, Object> resolveValues(SqlToolConfig tool, Map<String, Object> arguments) {
    return tool.parameters().stream().collect(
        java.util.LinkedHashMap::new,
        (map, param) -> map.put(param.name(), resolveValue(param, arguments.get(param.name()))),
        Map::putAll);
  }

  private static Object resolveValue(ParameterConfig param, Object raw) {
    if (raw == null) {
      if (param.defaultValue() != null) {
        raw = param.defaultValue();
      } else if (param.isRequiredInSchema()) {
        throw new IllegalArgumentException("Missing required parameter: " + param.name());
      } else {
        return ""; // reference behavior: optional with no default binds as empty string
      }
    }
    Object coerced = coerce(param, raw);
    // Validate constraints after coercion but before binding
    ParameterValidator.validate(param, coerced);
    return coerced;
  }

  private static Object coerce(ParameterConfig param, Object value) {
    switch (param.type()) {
      case "integer": {
        if (value instanceof Number n) return n.intValue();
        try {
          return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(
              "Parameter '" + param.name() + "' must be an integer, got: " + value);
        }
      }
      case "float": {
        if (value instanceof Number n) return n.doubleValue();
        try {
          return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(
              "Parameter '" + param.name() + "' must be a number, got: " + value);
        }
      }
      case "boolean":
        return toBoolean(param.name(), value) ? 1 : 0; // Db2 binding convention
      case "array": {
        if (!(value instanceof List<?> list)) {
          throw new IllegalArgumentException(
              "Parameter '" + param.name() + "' must be an array, got: " + value);
        }
        ParameterConfig itemParam = new ParameterConfig(
            param.name() + "[]",
            param.itemType() == null ? "string" : param.itemType(),
            null, null, null, null, null, null, null, null, null, null);
        return list.stream().map(item -> coerce(itemParam, item)).toList();
      }
      default:
        return String.valueOf(value);
    }
  }

  private static boolean toBoolean(String name, Object value) {
    if (value instanceof Boolean b) return b;
    if (value instanceof Number n) return n.doubleValue() != 0;
    String s = String.valueOf(value).trim().toLowerCase();
    if (List.of("true", "1", "yes", "on").contains(s)) return true;
    if (List.of("false", "0", "no", "off").contains(s)) return false;
    throw new IllegalArgumentException("Parameter '" + name + "' must be a boolean, got: " + value);
  }
}
