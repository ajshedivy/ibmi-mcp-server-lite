package com.ibm.ibmi.mcp.server;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches resolved tools YAML files and triggers hot-reload when any changes.
 *
 * <p>Registers each file's parent directory with {@link WatchService} (JVM limitation:
 * individual files cannot be watched directly). Bursts of filesystem events from a
 * single save are coalesced into one reload via a short debounce window.
 */
public final class ToolsYamlWatcher implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ToolsYamlWatcher.class);

  static final long DEFAULT_DEBOUNCE_MS = 250;

  @FunctionalInterface
  interface ReloadAction {
    void run();
  }

  private final List<Path> watchedFiles;
  private final Map<Path, Set<String>> directoryFilenames;
  private final Map<WatchKey, Path> keyToDirectory;
  private final long debounceMs;
  private final ReloadAction reloadAction;
  private final WatchService watchService;
  private final Thread thread;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicBoolean reloadPending = new AtomicBoolean(false);

  private ToolsYamlWatcher(
      List<Path> watchedFiles,
      long debounceMs,
      ReloadAction reloadAction,
      WatchService watchService,
      Map<Path, Set<String>> directoryFilenames,
      Map<WatchKey, Path> keyToDirectory) {
    this.watchedFiles = watchedFiles;
    this.debounceMs = debounceMs;
    this.reloadAction = reloadAction;
    this.watchService = watchService;
    this.directoryFilenames = directoryFilenames;
    this.keyToDirectory = keyToDirectory;
    this.thread = new Thread(this::watchLoop, "tools-yaml-watcher");
    this.thread.setDaemon(true);
  }

  static ToolsYamlWatcher start(
      List<Path> yamlFiles,
      long debounceMs,
      ReloadAction reloadAction) throws IOException {
    List<Path> absolute = yamlFiles.stream()
        .map(path -> path.toAbsolutePath().normalize())
        .distinct()
        .toList();

    Map<Path, Set<String>> directoryFilenames = new LinkedHashMap<>();
    for (Path file : absolute) {
      Path directory = file.getParent();
      if (directory == null) {
        throw new IllegalArgumentException("Tools YAML has no parent directory: " + file);
      }
      directoryFilenames
          .computeIfAbsent(directory, ignored -> new LinkedHashSet<>())
          .add(file.getFileName().toString());
    }

    WatchService watchService = FileSystems.getDefault().newWatchService();
    try {
      Map<WatchKey, Path> keyToDirectory = new HashMap<>();
      for (Path directory : directoryFilenames.keySet()) {
        WatchKey watchKey = directory.register(
            watchService,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_CREATE);
        keyToDirectory.put(watchKey, directory);
      }

      ToolsYamlWatcher watcher = new ToolsYamlWatcher(
          absolute, debounceMs, reloadAction, watchService, directoryFilenames, keyToDirectory);
      watcher.thread.start();

      log.info("Watching {} tools YAML file(s) for changes: {}", absolute.size(), absolute);
      return watcher;
    } catch (Exception e) {
      try {
        watchService.close();
      } catch (IOException closeEx) {
        e.addSuppressed(closeEx);
      }
      throw e;
    }
  }

  private void watchLoop() {
    try {
      while (running.get()) {
        WatchKey key;
        try {
          key = watchService.take();
        } catch (InterruptedException e) {
          if (!running.get()) {
            return;
          }
          Thread.currentThread().interrupt();
          return;
        } catch (java.nio.file.ClosedWatchServiceException e) {
          return;
        }

        if (!keyToDirectory.containsKey(key)) {
          key.reset();
          continue;
        }

        if (pollRelevantEvent(key) && reloadPending.compareAndSet(false, true)) {
          try {
            while (running.get()) {
              debounceAndReload();
              if (!drainPendingEvents()) {
                break;
              }
            }
          } finally {
            reloadPending.set(false);
          }
        }
        key.reset();
      }
    } catch (RuntimeException e) {
      if (running.get()) {
        log.error("Tools YAML watcher stopped unexpectedly: {}", e.getMessage());
        log.debug("Tools YAML watcher failure", e);
      }
    }
  }

  private void debounceAndReload() {
    waitUntilQuiet();
    if (!running.get()) {
      return;
    }
    log.info("YAML file(s) changed: {}", watchedFiles);
    try {
      reloadAction.run();
    } catch (Exception e) {
      log.error("YAML reload callback failed: {}", e.getMessage());
      log.debug("YAML reload callback failure", e);
    }
    // Linux may deliver MODIFY events for the same burst after the first quiet window.
    waitUntilQuiet();
  }

  private void waitUntilQuiet() {
    long quietDeadline = System.currentTimeMillis() + debounceMs;
    while (running.get()) {
      while (drainPendingEvents()) {
        quietDeadline = System.currentTimeMillis() + debounceMs;
      }
      long remaining = quietDeadline - System.currentTimeMillis();
      if (remaining <= 0) {
        if (!drainPendingEvents()) {
          return;
        }
        quietDeadline = System.currentTimeMillis() + debounceMs;
        continue;
      }
      if (!sleepQuietly(Math.min(remaining, 25))) {
        return;
      }
    }
  }

  boolean drainPendingEvents() {
    boolean relevant = false;
    WatchKey key;
    while ((key = watchService.poll()) != null) {
      relevant |= pollRelevantEvent(key);
      key.reset();
    }
    return relevant;
  }

  boolean pollRelevantEvent(WatchKey key) {
    Path directory = keyToDirectory.get(key);
    if (directory == null) {
      return false;
    }
    Set<String> watchedNames = directoryFilenames.get(directory);
    if (watchedNames == null || watchedNames.isEmpty()) {
      return false;
    }

    boolean relevant = false;
    for (WatchEvent<?> event : key.pollEvents()) {
      WatchEvent.Kind<?> kind = event.kind();
      if (kind == StandardWatchEventKinds.OVERFLOW) {
        relevant = true;
        continue;
      }
      if (!(event.context() instanceof Path changed)) {
        continue;
      }
      if (watchedNames.contains(changed.getFileName().toString())) {
        relevant = true;
      }
    }
    return relevant;
  }

  private boolean sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void close() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    thread.interrupt();
    try {
      watchService.close();
    } catch (IOException e) {
      log.warn("Error closing tools YAML watcher: {}", e.getMessage());
    }
    try {
      thread.join(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    log.debug("Tools YAML watcher stopped for {}", watchedFiles);
  }
}
