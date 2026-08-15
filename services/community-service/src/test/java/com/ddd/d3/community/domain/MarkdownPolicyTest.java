package com.ddd.d3.community.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarkdownPolicyTest {

    private final MarkdownPolicy markdownPolicy = new MarkdownPolicy();

    @Test
    void d3Com001ExcludesFencedCodeFromTheProseCharacterCount() {
        String markdown = """
                hello
                ```java
                System.out.println("this source is explicit post content");
                ```
                bye
                """;

        assertEquals(8, markdownPolicy.proseCharacterCount(markdown));
    }

    @Test
    void d3Com001EscapesHtmlWhilePreservingFencedCodeBlocks() {
        String html = markdownPolicy.renderSanitizedHtml("""
                <script>alert(1)</script>
                ```js
                if (a < b) return "&";
                ```
                """);

        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("<pre><code>"));
        assertTrue(html.contains("if (a &lt; b) return &quot;&amp;&quot;"));
    }
}
