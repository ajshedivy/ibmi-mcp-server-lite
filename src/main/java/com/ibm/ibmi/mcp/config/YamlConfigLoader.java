package com.ibm.ibmi.mcp.config;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 *   <li>{@code ignore-unauthorized} defaults to {@code false} (verify Mapepire TLS). When
 *       the YAML key is omitted, {@link SourceConfig#ENV_IGNORE_UNAUTHORIZED} applies.
 *       {@code true} logs a warning: it skips certificate-chain and hostname checks.
 * </ul>
 *
 * <p>Accepts a single YAML file, a directory of {@code *.yaml}/{@code *.yml} files, or a
 * glob pattern. Multiple files are merged with env-controlled duplicate handling
 * ({@link MergeOptions}). Hot-reload re-runs {@link #loadAll(String, MergeOptions)} when
 * any resolved file changes ({@code YAML_AUTO_RELOAD}; see
 * {@link com.ibm.ibmi.mcp.server.ToolsYamlWatcher}).
 */
public final class YamlConfigLoader {

  private static final Logger log = LoggerFactory.getLogger(YamlConfigLoader.class);
  private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([^}]+)}");

  private final Map<String, String> env;

  public YamlConfigLoader(Map<String, String> env) {
    this.env = env;
  }

  public ToolsConfig load(Path yamlFile) {
    return parse(readFile(yamlFile), true);
  }

  /** Loads and merges every YAML file resolved from a file, directory, or glob path. */
  public ToolsConfig loadAll(String toolsPath, MergeOptions opts) {
    List<Path> files = resolveToolPaths(toolsPath);
    return loadAll(files, opts);
  }

  /** Loads and merges the given YAML files in order. */
  public ToolsConfig loadAll(List<Path> files, MergeOptions opts) {
    if (files.isEmpty()) {
      throw new ConfigException("No tools YAML files to load");
    }
    if (files.size() == 1) {
      return parse(readFile(files.get(0)), opts.validateMerged());
    }

    log.info("Loading and merging {} YAML files", files.size());
    List<ToolsConfig> configs = new ArrayList<>(files.size());
    for (Path file : files) {
      log.debug("Loading {}", file);
      configs.add(parse(readFile(file), false));
    }
    ToolsConfig merged = merge(configs, opts);
    if (opts.validateMerged()) {
      validateReferences(merged);
    }
    return merged;
  }

  /** Parses YAML text (after env interpolation) into a validated config. */
  public ToolsConfig parse(String yamlText) {
    return parse(yamlText, true);
  }

  /** Parses YAML text; reference validation is optional for multi-file merge. */
  ToolsConfig parse(String yamlText, boolean validateReferences) {
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
    if (validateReferences) {
      validateReferences(config);
    }
    return config;
  }

  public static List<Path> resolveToolPaths(String toolsPath) {
    Path path = Path.of(toolsPath);
    List<Path> resolved;
    if (Files.isRegularFile(path)) {
      log.debug("Resolved tools path as file: {}", path);
      resolved = List.of(path);
    } else if (Files.isDirectory(path)) {
      log.debug("Resolved tools path as directory: {}", path);
      resolved = findYamlFilesInDirectory(path);
      if (resolved.isEmpty()) {
        throw new ConfigException(
            "No YAML files found in directory: " + path.toAbsolutePath());
      }
    } else if (isGlobPattern(toolsPath)) {
      log.debug("Resolved tools path as glob: {}", toolsPath);
      resolved = findYamlFilesMatchingGlob(toolsPath);
      if (resolved.isEmpty()) {
        throw new ConfigException("No files found matching pattern: " + toolsPath);
      }
    } else {
      throw new ConfigException("Tools YAML path not found: " + path.toAbsolutePath());
    }

    List<Path> deduped = new ArrayList<>(new LinkedHashSet<>(
        resolved.stream()
            .map(Path::toAbsolutePath)
            .sorted()
            .toList()));
    log.info("Resolved {} tools YAML file(s): {}", deduped.size(), deduped);
    return deduped;
  }

  private static boolean isGlobPattern(String toolsPath) {
    return toolsPath.indexOf('*') >= 0
        || toolsPath.indexOf('?') >= 0
        || toolsPath.indexOf('[') >= 0
        || toolsPath.indexOf('{') >= 0;
  }

  private static List<Path> findYamlFilesInDirectory(Path dir) {
    try {
      List<Path> files = new ArrayList<>();
      Files.walkFileTree(dir, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (Files.isRegularFile(file) && isYamlFile(file)) {
            files.add(file);
          }
          return FileVisitResult.CONTINUE;
        }
      });
      return files;
    } catch (IOException e) {
      throw new ConfigException("Cannot read tools YAML directory: " + dir, e);
    }
  }

  private static List<Path> findYamlFilesMatchingGlob(String pattern) {
    List<String> patterns = expandGlobPattern(pattern);
    Path walkRoot = globWalkRoot(pattern);
    if (!Files.exists(walkRoot)) {
      return List.of();
    }

    List<PathMatcher> matchers = patterns.stream()
        .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
        .toList();

    try {
      List<Path> files = new ArrayList<>();
      Path normalizedRoot = walkRoot.toAbsolutePath().normalize();
      Files.walkFileTree(walkRoot, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (!Files.isRegularFile(file) || !isYamlFile(file)) {
            return FileVisitResult.CONTINUE;
          }
          for (PathMatcher matcher : matchers) {
            if (matchesGlob(matcher, normalizedRoot, file)) {
              files.add(file);
              break;
            }
          }
          return FileVisitResult.CONTINUE;
        }
      });
      return files;
    } catch (IOException e) {
      throw new ConfigException("Cannot resolve tools YAML glob: " + pattern, e);
    }
  }

  /**
   * Expands {@code {a,b}} brace alternation and adds a flattened variant for each
   * {@code **} directory segment so root-level files match (Java {@link PathMatcher} treats
   * {@code **} as one-or-more directories; the reference server's glob matches zero-or-more).
   */
  static List<String> expandGlobPattern(String pattern) {
    LinkedHashSet<String> expanded = new LinkedHashSet<>();
    for (String braceVariant : expandBraceAlternation(pattern)) {
      expanded.add(braceVariant);
      if (braceVariant.contains("/**/")) {
        expanded.add(braceVariant.replace("/**/", "/"));
      }
    }
    return List.copyOf(expanded);
  }

  /** Recursively expands the first {@code {alt1,alt2,...}} group in a glob pattern. */
  static List<String> expandBraceAlternation(String pattern) {
    int open = pattern.indexOf('{');
    if (open < 0) {
      return List.of(pattern);
    }
    int close = pattern.indexOf('}', open);
    if (close < 0) {
      return List.of(pattern);
    }
    String prefix = pattern.substring(0, open);
    String suffix = pattern.substring(close + 1);
    String[] alternatives = pattern.substring(open + 1, close).split(",");
    List<String> results = new ArrayList<>();
    for (String alt : alternatives) {
      results.addAll(expandBraceAlternation(prefix + alt.trim() + suffix));
    }
    return results;
  }

  private static boolean matchesGlob(PathMatcher matcher, Path walkRoot, Path file) {
    Path normalized = file.toAbsolutePath().normalize();
    if (matcher.matches(normalized) || matcher.matches(file)) {
      return true;
    }
    if (normalized.startsWith(walkRoot)) {
      return matcher.matches(walkRoot.relativize(normalized));
    }
    return false;
  }

  private static Path globWalkRoot(String pattern) {
    int globStart = indexOfFirstGlobChar(pattern);
    if (globStart <= 0) {
      return Path.of(".");
    }
    String beforeGlob = pattern.substring(0, globStart);
    int lastSep = Math.max(beforeGlob.lastIndexOf('/'), beforeGlob.lastIndexOf('\\'));
    if (lastSep < 0) {
      return Path.of(".");
    }
    String rootPart = beforeGlob.substring(0, lastSep);
    return rootPart.isEmpty() ? Path.of(".") : Path.of(rootPart);
  }

  private static int indexOfFirstGlobChar(String pattern) {
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '*' || c == '?' || c == '[' || c == '{') {
        return i;
      }
    }
    return -1;
  }

  private static boolean isYamlFile(Path file) {
    String name = file.getFileName().toString().toLowerCase();
    return name.endsWith(".yaml") || name.endsWith(".yml");
  }

  private String readFile(Path yamlFile) {
    try {
      return Files.readString(yamlFile);
    } catch (IOException e) {
      throw new ConfigException("Cannot read tools YAML file: " + yamlFile, e);
    }
  }

  private ToolsConfig merge(List<ToolsConfig> configs, MergeOptions opts) {
    Map<String, SourceConfig> sources = new LinkedHashMap<>();
    Map<String, SqlToolConfig> tools = new LinkedHashMap<>();
    Map<String, ToolsetConfig> toolsets = new LinkedHashMap<>();

    for (ToolsConfig config : configs) {
      mergeSources(sources, config.sources(), opts);
      mergeTools(tools, config.tools(), opts);
      mergeToolsets(toolsets, config.toolsets(), opts);
    }
    return new ToolsConfig(sources, tools, toolsets);
  }

  private void mergeSources(
      Map<String, SourceConfig> target, Map<String, SourceConfig> incoming, MergeOptions opts) {
    for (SourceConfig source : incoming.values()) {
      if (target.containsKey(source.name())) {
        if (!opts.allowDuplicateSources()) {
          throw new ConfigException(
              "Duplicate source name: " + source.name()
                  + ". To allow duplicate source names, set YAML_ALLOW_DUPLICATE_SOURCES=true");
        }
        log.warn("Overriding duplicate source '{}'", source.name());
      }
      target.put(source.name(), source);
    }
  }

  private void mergeTools(
      Map<String, SqlToolConfig> target, Map<String, SqlToolConfig> incoming, MergeOptions opts) {
    for (SqlToolConfig tool : incoming.values()) {
      if (target.containsKey(tool.name())) {
        if (!opts.allowDuplicateTools()) {
          throw new ConfigException(
              "Duplicate tool name: " + tool.name()
                  + ". To allow duplicate tool names, set YAML_ALLOW_DUPLICATE_TOOLS=true");
        }
        log.warn("Overriding duplicate tool '{}'", tool.name());
      }
      target.put(tool.name(), tool);
    }
  }

  private void mergeToolsets(
      Map<String, ToolsetConfig> target, Map<String, ToolsetConfig> incoming, MergeOptions opts) {
    for (ToolsetConfig toolset : incoming.values()) {
      ToolsetConfig existing = target.get(toolset.name());
      if (existing == null) {
        target.put(toolset.name(), toolset);
        continue;
      }
      if (opts.mergeArrays()) {
        List<String> mergedTools = new ArrayList<>(existing.tools());
        mergedTools.addAll(toolset.tools());
        target.put(toolset.name(), new ToolsetConfig(
            existing.name(), existing.title(), existing.description(), List.copyOf(mergedTools)));
      } else {
        target.put(toolset.name(), toolset);
      }
    }
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
      int maxSize = getInt(src, "max-size", SourceConfig.DEFAULT_MAX_SIZE);
      int startingSize = getInt(src, "starting-size", SourceConfig.DEFAULT_STARTING_SIZE);
      int mcpPoolIdleTimeoutMs = resolvePoolTimeoutMs(
          src, "mcp-pool-idle-timeout-ms", "MCP_POOL_IDLE_TIMEOUT_MS",
          SourceConfig.DEFAULT_MCP_POOL_IDLE_TIMEOUT_MS);
      int mcpPoolQueryTimeoutMs = resolvePoolTimeoutMs(
          src, "mcp-pool-query-timeout-ms", "MCP_POOL_QUERY_TIMEOUT_MS",
          SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS);
      boolean ignoreUnauthorized = resolveIgnoreUnauthorized(src);
      if (ignoreUnauthorized) {
        log.warn(
            "TLS certificate verification is disabled for source '{}'. "
                + "ignore-unauthorized skips Mapepire certificate-chain and hostname checks. "
                + "This is a development override; omit the key (or set false) in production.",
            name);
      }
      validatePoolSizes(name, maxSize, startingSize);
      result.put(name, new SourceConfig(
          name,
          requireString(src, "host", "source '" + name + "'"),
          getInt(src, "port", SourceConfig.DEFAULT_MAPEPIRE_PORT),
          requireString(src, "user", "source '" + name + "'"),
          requireString(src, "password", "source '" + name + "'"),
          ignoreUnauthorized,
          maxSize,
          startingSize,
          mcpPoolIdleTimeoutMs,
          mcpPoolQueryTimeoutMs,
          mergeJdbcOptions(parseYamlJdbcOptions(src, name))));
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
          getString(tool, "tableFormat"),
          tool.get("maxDisplayRows") instanceof Number n ? n.intValue() : null,
          tool.get("annotations") == null ? Map.of() : asMap(tool.get("annotations"), "tool '" + name + "' annotations"),
          parseSecurity(name, tool.get("security")),
          tool.get("rowsToFetch") instanceof Number n ? n.intValue() : null,
          tool.get("fetchAllRows") instanceof Boolean b ? b : null,
          getString(tool, "domain"),
          getString(tool, "category"),
          // No YAML key: an empty result is a valid answer for a query someone wrote by hand.
          null));
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
      // Resource URIs are toolsets://{name}. Blank and '/' collide with the catalog URI
      // (toolsets:// / toolsets:///). {…} is treated as an MCP URI template by the SDK.
      if (name == null || name.isBlank()) {
        throw new ConfigException("Toolset name must not be blank");
      }
      if (name.indexOf('{') >= 0 || name.indexOf('}') >= 0 || name.indexOf('/') >= 0) {
        throw new ConfigException(
            "Toolset '" + name + "' must not contain '{', '}', or '/' "
                + "(URI template / path characters)");
      }
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

  private Map<String, Object> mergeJdbcOptions(Map<String, Object> yamlJdbc) {
    Map<String, Object> envJdbc = JdbcOptionsParser.parse(env.get("DB2i_JDBC_OPTIONS"));
    if (yamlJdbc.isEmpty() && envJdbc.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, Object> merged = new LinkedHashMap<>(yamlJdbc);
    merged.putAll(envJdbc);
    return merged;
  }

  private static Map<String, Object> parseYamlJdbcOptions(Map<String, Object> src, String name) {
    Object jdbcOptionsRaw = src.get("jdbc-options");
    if (jdbcOptionsRaw == null) {
      return Collections.emptyMap();
    }

    Map<String, Object> yamlJdbcOptions = asMap(jdbcOptionsRaw, "jdbc-options for source '" + name + "'");
    Map<String, Object> processedOptions = new LinkedHashMap<>();

    for (Map.Entry<String, Object> opt : yamlJdbcOptions.entrySet()) {
      String key = opt.getKey();
      Object value = opt.getValue();

      if ("libraries".equals(key)) {
        processedOptions.put("libraries", parseLibrariesValue(name, value));
      } else {
        processedOptions.put(key, value);
      }
    }
    return processedOptions;
  }

  private static List<String> parseLibrariesValue(String sourceName, Object value) {
    if (value instanceof List<?> list) {
      return list.stream().map(String::valueOf).collect(Collectors.toList());
    }
    if (value instanceof String csv) {
      return JdbcOptionsParser.parseLibrariesCsv(csv);
    }
    throw new ConfigException(
        "jdbc-options.libraries for source '" + sourceName + "' must be an array or comma-separated string");
  }

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

  /**
   * YAML {@code ignore-unauthorized} if present, else {@link SourceConfig#ENV_IGNORE_UNAUTHORIZED},
   * else {@code false} (verify TLS). YAML wins when the key is set (including quoted strings and
   * numeric 0/1). Unambiguous coercions only — invalid values throw {@link ConfigException}.
   */
  private boolean resolveIgnoreUnauthorized(Map<String, Object> src) {
    if (src.containsKey("ignore-unauthorized")) {
      return parseBooleanConfigValue(
          src.get("ignore-unauthorized"), "source ignore-unauthorized");
    }
    String raw = env.get(SourceConfig.ENV_IGNORE_UNAUTHORIZED);
    if (raw == null || raw.isBlank()) {
      return false;
    }
    return parseBooleanConfigValue(raw.trim(), SourceConfig.ENV_IGNORE_UNAUTHORIZED);
  }

  /**
   * Parses boolean config from YAML-native types and common string/number mistakes.
   * Accepts {@code true}/{@code false}, {@code "true"}/{@code "false"}/{@code "1"}/{@code "0"},
   * and integer {@code 1}/{@code 0}. Rejects null, blank strings, and ambiguous values
   * like {@code "yes"}.
   */
  static boolean parseBooleanConfigValue(Object raw, String fieldName) {
    if (raw == null) {
      throw new ConfigException(fieldName + " must not be blank");
    }
    if (raw instanceof Boolean b) {
      return b;
    }
    if (raw instanceof Number n) {
      int value = n.intValue();
      if (value == 1) {
        return true;
      }
      if (value == 0) {
        return false;
      }
      throw new ConfigException(
          fieldName + " must be a boolean or 0/1 when numeric; got " + value);
    }
    if (raw instanceof String s) {
      String trimmed = s.trim();
      if (trimmed.isEmpty()) {
          throw new ConfigException(fieldName + " must not be blank");
      }
      if ("true".equalsIgnoreCase(trimmed) || "1".equals(trimmed)) {
        return true;
      }
      if ("false".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) {
        return false;
      }
      throw new ConfigException(
          fieldName + " must be true/false or 1/0; got '" + trimmed + "'");
    }
    throw new ConfigException(
        fieldName + " must be a boolean; got " + raw.getClass().getSimpleName());
  }

  private static boolean getBool(Map<String, Object> map, String key, boolean dflt) {
    return map.get(key) instanceof Boolean b ? b : dflt;
  }

  private static int getInt(Map<String, Object> map, String key, int dflt) {
    return map.get(key) instanceof Number n ? n.intValue() : dflt;
  }

  /**
   * YAML key if present, else {@code envKey} from the loader env map, else {@code dflt}.
   * Explicit {@code 0} from YAML or env disables the timeout. Negatives are rejected
   * (Node {@code .nonnegative()} parity) so {@code -1} does not silently disable.
   */
  private int resolvePoolTimeoutMs(
      Map<String, Object> src, String yamlKey, String envKey, int dflt) {
    int value;
    if (src.get(yamlKey) instanceof Number n) {
      value = n.intValue();
    } else {
      String raw = env.get(envKey);
      if (raw != null && !raw.isBlank()) {
        try {
          value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
          throw new ConfigException(
              "Invalid " + envKey + " value '" + raw + "': must be an integer (ms)");
        }
      } else {
        return dflt;
      }
    }
    if (value < 0) {
      throw new ConfigException(
          envKey + " / " + yamlKey + " must be >= 0 (ms); got " + value);
    }
    return value;
  }

  private static void validatePoolSizes(String sourceName, int maxSize, int startingSize) {
    if (maxSize <= 0) {
      throw new ConfigException("Source '" + sourceName + "' max-size must be greater than 0");
    }
    if (startingSize <= 0) {
      throw new ConfigException("Source '" + sourceName + "' starting-size must be greater than 0");
    }
    if (startingSize > maxSize) {
      throw new ConfigException(
          "Source '" + sourceName + "' starting-size must be less than or equal to max-size");
    }
  }
}
