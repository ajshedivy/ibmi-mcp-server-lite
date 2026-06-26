package com.ibm.ibmi.mcp.format;

/**
 * Fluent builder for markdown tool response documents.
 */
public final class MarkdownBuilder {

  private final StringBuilder sections = new StringBuilder();

  /** Appends a level-2 heading. */
  public MarkdownBuilder h2(String text) {
    sections.append("## ").append(text).append("\n\n");
    return this;
  }

  /** Appends a level-3 heading. */
  public MarkdownBuilder h3(String text) {
    sections.append("### ").append(text).append("\n\n");
    return this;
  }

  /** Appends a GitHub-style alert block (e.g. {@code tip}, {@code note}). */
  public MarkdownBuilder alert(String type, String content) {
    String typeUpper = type.toUpperCase();
    sections.append("> [!").append(typeUpper).append("]\n");
    for (String line : content.split("\n")) {
      sections.append("> ").append(line).append("\n");
    }
    sections.append("\n");
    return this;
  }

  /** Appends a bold key followed by its value. */
  public MarkdownBuilder keyValue(String key, String value) {
    sections.append("**").append(key).append(":** ").append(value).append("\n");
    return this;
  }

  /** Appends a bullet list; no-op when {@code items} is empty. */
  public MarkdownBuilder list(java.util.List<String> items) {
    if (items.isEmpty()) {
      return this;
    }
    for (String item : items) {
      sections.append("- ").append(item).append("\n");
    }
    sections.append("\n");
    return this;
  }

  /** Appends a fenced code block with the given language tag. */
  public MarkdownBuilder codeBlock(String content, String language) {
    sections.append("```").append(language).append("\n")
        .append(content).append("\n")
        .append("```\n\n");
    return this;
  }

  /** Appends a paragraph followed by a blank line. */
  public MarkdownBuilder paragraph(String text) {
    sections.append(text).append("\n\n");
    return this;
  }

  /** Appends markdown verbatim without additional formatting. */
  public MarkdownBuilder raw(String markdown) {
    sections.append(markdown);
    return this;
  }

  /** Appends a single blank line. */
  public MarkdownBuilder blankLine() {
    sections.append("\n");
    return this;
  }

  /** Returns the assembled markdown with leading/trailing whitespace trimmed. */
  public String build() {
    return sections.toString().trim();
  }
}
