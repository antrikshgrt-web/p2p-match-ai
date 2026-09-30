package com.p2pmatchai.engine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Tiny Anthropic Messages API client built on java.net.http (JDK only, no libraries).
 * Optional: the prototype runs fully offline when no API key is configured.
 */
final class LlmClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-5";

    private LlmClient() {
    }

    static String rephrase(String apiKey, String prompt) throws Exception {
        String body = "{"
                + "\"model\":\"" + MODEL + "\","
                + "\"max_tokens\":400,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + escape(prompt) + "\"}]"
                + "}";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Anthropic API returned HTTP " + response.statusCode());
        }
        return firstTextBlock(response.body());
    }

    /**
     * Pulls the first "text" value out of the response without a JSON library.
     * Sufficient for a single-text-block reply; swap in a parser for production.
     */
    private static String firstTextBlock(String json) {
        int marker = json.indexOf("\"text\"");
        if (marker < 0) {
            return null;
        }
        int start = json.indexOf('"', json.indexOf(':', marker) + 1);
        if (start < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(next);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
