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
 * </ul>
 *
 * <p>Accepts a single YAML file, a directory of {@code *.yaml}/{@code *.yml} files, or a
 * glob pattern. Multiple files are merged with env-controlled duplicate handling
 * ({@link MergeOptions}). Hot-reload ({@code YAML_AUTO_RELOAD}) is not implemented.
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
      return load(files.get(0));
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

  static List<Path> resolveToolPaths(String toolsPath) {
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
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    Path walkRoot = globWalkRoot(pattern);
    if (!Files.exists(walkRoot)) {
      return List.of();
    }

    try {
      List<Path> files = new ArrayList<>();
      Files.walkFileTree(walkRoot, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (Files.isRegularFile(file) && isYamlFile(file) && matcher.matches(file)) {
            files.add(file);
          }
          return FileVisitResult.CONTINUE;
        }
      });
      return files;
    } catch (IOException e) {
      throw new ConfigException("Cannot resolve tools YAML glob: " + pattern, e);
    }
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
      validatePoolSizes(name, maxSize, startingSize);
      result.put(name, new SourceConfig(
          name,
          requireString(src, "host", "source '" + name + "'"),
          getInt(src, "port", SourceConfig.DEFAULT_MAPEPIRE_PORT),
          requireString(src, "user", "source '" + name + "'"),
          requireString(src, "password", "source '" + name + "'"),
          getBool(src, "ignore-unauthorized", false),
          maxSize,
          startingSize,
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

  private static boolean getBool(Map<String, Object> map, String key, boolean dflt) {
    return map.get(key) instanceof Boolean b ? b : dflt;
  }

  private static int getInt(Map<String, Object> map, String key, int dflt) {
    return map.get(key) instanceof Number n ? n.intValue() : dflt;
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
