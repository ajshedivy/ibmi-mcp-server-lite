package com.ibm.ibmi.mcp.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads a tools YAML file into a validated {@link ToolsConfig}.
 *
 * <p>Behavior mirrors the reference Node.js implementation:
 *
 * <ul>
 *   <li>{@code ${VAR}} placeholders are substituted from the environment in the raw file
 *       text <em>before</em> YAML parsing; unknown variables are left verbatim (debug-logged),
 *       not treated as errors.
 *   <li>Every tool's {@code source} must name an entry in {@code sources}; every toolset
 *       member must name an entry in {@code tools}; every enabled tool needs a non-empty
 *       {@code statement}.
 * </ul>
 *
 * <p>INTERN TODO: the reference server also accepts a directory or glob of YAML files and
 * merges them (env-controlled duplicate handling), and hot-reloads on change
 * ({@code YAML_AUTO_RELOAD}). This MVP loads a single file only.
 */
public final class YamlConfigLoader {

  private static final Logger log = LoggerFactory.getLogger(YamlConfigLoader.class);
  private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([^}]+)}");

  private final Map<String, String> env;

  public YamlConfigLoader(Map<String, String> env) {
    this.env = env;
  }

  public ToolsConfig load(Path yamlFile) {
    String text;
    try {
      text = Files.readString(yamlFile);
    } catch (IOException e) {
      throw new ConfigException("Cannot read tools YAML file: " + yamlFile, e);
    }
    return parse(text);
  }

  /** Parses YAML text (after env interpolation) into a validated config. */
  public ToolsConfig parse(String yamlText) {
    String interpolated = interpolateEnvVars(yamlText);
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object root = yaml.load(interpolated);
    if (!(root instanceof Map)) {
      throw new ConfigException("Tools YAML must be a mapping with sources/tools/toolsets sections");
    }
    Map<String, Object> doc = asMap(root, "document root");

    Map<String, SourceConfig> sources = parseSources(asMap(doc.getOrDefault("sources", Map.of()), "sources"));
    Map<String, SqlToolConfig> tools = parseTools(asMap(doc.getOrDefault("tools", Map.of()), "tools"));
    Map<String, ToolsetConfig> toolsets = parseToolsets(asMap(doc.getOrDefault("toolsets", Map.of()), "toolsets"));

    if (sources.isEmpty() && tools.isEmpty() && toolsets.isEmpty()) {
      throw new ConfigException(
          "YAML file must contain at least one section: sources, tools, or toolsets");
    }

    ToolsConfig config = new ToolsConfig(sources, tools, toolsets);
    validateReferences(config);
    return config;
  }

  /** {@code ${VAR}} → value from environment; unknown variables stay verbatim. */
  String interpolateEnvVars(String text) {
    Matcher m = ENV_VAR.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String name = m.group(1);
      String value = env.get(name);
      if (value == null) {
        log.debug("Environment variable not found, keeping placeholder: ${{}}", name);
        value = m.group(0);
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(value));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private Map<String, SourceConfig> parseSources(Map<String, Object> section) {
    Map<String, SourceConfig> result = new LinkedHashMap<>();
    for (var entry : section.entrySet()) {
      String name = entry.getKey();
      Map<String, Object> src = asMap(entry.getValue(), "source '" + name + "'");
      result.put(name, new SourceConfig(
          name,
          requireString(src, "host", "source '" + name + "'"),
          getInt(src, "port", SourceConfig.DEFAULT_MAPEPIRE_PORT),
          requireString(src, "user", "source '" + name + "'"),
          requireString(src, "password", "source '" + name + "'"),
          getBool(src, "ignore-unauthorized", false)));
    }
    return result;
  }

  private Map<String, SqlToolConfig> parseTools(Map<String, Object> section) {
    Map<String, SqlToolConfig> result = new LinkedHashMap<>();
    for (var entry : section.entrySet()) {
      String name = entry.getKey();
      Map<String, Object> tool = asMap(entry.getValue(), "tool '" + name + "'");
      result.put(name, new SqlToolConfig(
          name,
          getBool(tool, "enabled", true),
          requireString(tool, "source", "tool '" + name + "'"),
          requireString(tool, "description", "tool '" + name + "'"),
          getString(tool, "statement"),
          parseParameters(name, tool.get("parameters")),
          getString(tool, "responseFormat"),
          tool.get("annotations") == null ? Map.of() : asMap(tool.get("annotations"), "tool '" + name + "' annotations"),
          parseSecurity(name, tool.get("security")),
          tool.get("rowsToFetch") instanceof Number n ? n.intValue() : null,
          getString(tool, "domain"),
          getString(tool, "category")));
    }
    return result;
  }

  private List<ParameterConfig> parseParameters(String toolName, Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new ConfigException("Tool '" + toolName + "' parameters must be a list");
    }
    List<ParameterConfig> params = new ArrayList<>();
    for (Object item : list) {
      Map<String, Object> p = asMap(item, "tool '" + toolName + "' parameter");
      String pName = requireString(p, "name", "tool '" + toolName + "' parameter");
      String type = requireString(p, "type", "parameter '" + pName + "'");
      if (!ParameterConfig.TYPES.contains(type)) {
        throw new ConfigException("Parameter '" + pName + "' of tool '" + toolName
            + "' has unsupported type '" + type + "'. Supported: " + ParameterConfig.TYPES);
      }
      List<Object> enumValues = null;
      if (p.get("enum") instanceof List<?> e) {
        enumValues = new ArrayList<>(e);
      }
      params.add(new ParameterConfig(
          pName,
          type,
          getString(p, "description"),
          p.get("default"),
          p.get("required") instanceof Boolean b ? b : null,
          getString(p, "itemType"),
          p.get("min") instanceof Number n ? n : null,
          p.get("max") instanceof Number n ? n : null,
          p.get("minLength") instanceof Number n ? n.intValue() : null,
          p.get("maxLength") instanceof Number n ? n.intValue() : null,
          enumValues,
          getString(p, "pattern")));
    }
    return params;
  }

  private SecurityConfig parseSecurity(String toolName, Object raw) {
    if (raw == null) {
      return SecurityConfig.DEFAULTS;
    }
    Map<String, Object> s = asMap(raw, "tool '" + toolName + "' security");
    List<String> forbidden = null;
    if (s.get("forbiddenKeywords") instanceof List<?> list) {
      forbidden = list.stream().map(String::valueOf).toList();
    }
    return new SecurityConfig(
        s.get("readOnly") instanceof Boolean b ? b : null,
        s.get("maxQueryLength") instanceof Number n ? n.intValue() : null,
        forbidden);
  }

  private Map<String, ToolsetConfig> parseToolsets(Map<String, Object> section) {
    Map<String, ToolsetConfig> result = new LinkedHashMap<>();
    for (var entry : section.entrySet()) {
      String name = entry.getKey();
      Map<String, Object> ts = asMap(entry.getValue(), "toolset '" + name + "'");
      if (!(ts.get("tools") instanceof List<?> list) || list.isEmpty()) {
        throw new ConfigException("Toolset '" + name + "' must contain at least one tool");
      }
      result.put(name, new ToolsetConfig(
          name,
          getString(ts, "title"),
          getString(ts, "description"),
          list.stream().map(String::valueOf).toList()));
    }
    return result;
  }

  private void validateReferences(ToolsConfig config) {
    for (SqlToolConfig tool : config.tools().values()) {
      if (!config.sources().containsKey(tool.source())) {
        throw new ConfigException(
            "Tool '" + tool.name() + "' references unknown source '" + tool.source() + "'");
      }
      if (tool.enabled() && (tool.statement() == null || tool.statement().isBlank())) {
        throw new ConfigException("Tool '" + tool.name() + "' must have a non-empty statement field");
      }
    }
    for (ToolsetConfig toolset : config.toolsets().values()) {
      for (String toolName : toolset.tools()) {
        if (!config.tools().containsKey(toolName)) {
          throw new ConfigException(
              "Toolset '" + toolset.name() + "' references unknown tool '" + toolName + "'");
        }
      }
    }
  }

  // -- small extraction helpers -------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value, String what) {
    if (!(value instanceof Map)) {
      throw new ConfigException(what + " must be a YAML mapping");
    }
    return (Map<String, Object>) value;
  }

  private static String getString(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static String requireString(Map<String, Object> map, String key, String owner) {
    String value = getString(map, key);
    if (value == null || value.isBlank()) {
      throw new ConfigException(owner + " is missing required field '" + key + "'");
    }
    return value;
  }

  private static boolean getBool(Map<String, Object> map, String key, boolean dflt) {
    return map.get(key) instanceof Boolean b ? b : dflt;
  }

  private static int getInt(Map<String, Object> map, String key, int dflt) {
    return map.get(key) instanceof Number n ? n.intValue() : dflt;
  }
}
