package com.ddd.d3.community.domain;

public final class MarkdownPolicy {

    public int proseCharacterCount(String markdown) {
        boolean inFence = false;
        int count = 0;
        for (String line : markdown.split("\\R", -1)) {
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inFence) {
                count += line.length();
            }
        }
        return count;
    }

    public String renderSanitizedHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        boolean inFence = false;

        for (String line : markdown.split("\\R", -1)) {
            if (line.stripLeading().startsWith("```")) {
                if (inFence) {
                    html.append("</code></pre>");
                } else {
                    flushParagraph(html, paragraph);
                    html.append("<pre><code>");
                }
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                html.append(escape(line)).append('\n');
            } else if (line.isBlank()) {
                flushParagraph(html, paragraph);
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append("<br>");
                }
                paragraph.append(escape(line));
            }
        }

        if (inFence) {
            html.append("</code></pre>");
        }
        flushParagraph(html, paragraph);
        return html.toString();
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        html.append("<p>").append(paragraph).append("</p>");
        paragraph.setLength(0);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
