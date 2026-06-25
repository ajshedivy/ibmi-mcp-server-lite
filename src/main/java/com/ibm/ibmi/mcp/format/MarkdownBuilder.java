package com.ibm.ibmi.mcp.format;

/**
 * Fluent builder for markdown tool response documents.
 */
public final class MarkdownBuilder {

  private final StringBuilder sections = new StringBuilder();

  public MarkdownBuilder h2(String text) {
    sections.append("## ").append(text).append("\n\n");
    return this;
  }

  public MarkdownBuilder h3(String text) {
    sections.append("### ").append(text).append("\n\n");
    return this;
  }

  public MarkdownBuilder alert(String type, String content) {
    String typeUpper = type.toUpperCase();
    sections.append("> [!").append(typeUpper).append("]\n");
    for (String line : content.split("\n")) {
      sections.append("> ").append(line).append("\n");
    }
    sections.append("\n");
    return this;
  }

  public MarkdownBuilder keyValue(String key, String value) {
    sections.append("**").append(key).append(":** ").append(value).append("\n");
    return this;
  }

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

  public MarkdownBuilder codeBlock(String content, String language) {
    sections.append("```").append(language).append("\n")
        .append(content).append("\n")
        .append("```\n\n");
    return this;
  }

  public MarkdownBuilder paragraph(String text) {
    sections.append(text).append("\n\n");
    return this;
  }

  public MarkdownBuilder raw(String markdown) {
    sections.append(markdown);
    return this;
  }

  public MarkdownBuilder blankLine() {
    sections.append("\n");
    return this;
  }

  public String build() {
    return sections.toString().trim();
  }
}
