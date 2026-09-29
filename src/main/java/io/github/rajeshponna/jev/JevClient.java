package io.github.rajeshponna.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.api.error.ConnectorException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Thin HTTP client for Jev with exponential backoff on 429 / 529 / 5xx. */
final class JevClient {

  static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
  private static final String PATH = "/v1/systemone";
  static final ObjectMapper MAPPER = new ObjectMapper();

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final int MAX_ATTEMPTS = 4;

  /** Jev 1.13 context window is 32k tokens; stay well below it. */
  static final int DEFAULT_MAX_TOKENS_PER_CALL = 20_000;
  private static final int CHARS_PER_TOKEN = 3; // conservative (ids, JSON punctuation)

  /** Character budget left for questions after the state and request overhead. */
  static int budgetChars(int maxTokensPerCall, Object state, Object instructions) {
    int total = Math.max(1_000, maxTokensPerCall) * CHARS_PER_TOKEN;
    int used = jsonLength(state) + 400;
    int left = total - used;
    if (left < 2_000) {
      throw new ConnectorException("JEV_INVALID_INPUT",
          "State is too large for one Jev call (" + used / CHARS_PER_TOKEN + " tokens est.)");
    }
    return left;
  }

  static int jsonLength(Object o) {
    try {
      return MAPPER.writeValueAsString(o).length();
    } catch (IOException e) {
      return String.valueOf(o).length();
    }
  }

  record Result(JsonNode json, int status, int inputTokens, int outputTokens) {}

  private final String apiKey;
  private final URI endpoint;
  private final Duration timeout;

  JevClient(String apiKey, String baseUrl, int timeoutSeconds) {
    this.apiKey = apiKey;
    String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    this.endpoint = URI.create(base + PATH);
    this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
  }

  Result send(Object body) {
    String payload;
    try {
      payload = MAPPER.writeValueAsString(body);
    } catch (IOException e) {
      throw new ConnectorException("JEV_INVALID_INPUT", "Cannot serialize request: " + e.getMessage());
    }

    HttpRequest req =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

    for (int attempt = 1; ; attempt++) {
      boolean last = attempt == MAX_ATTEMPTS;
      HttpResponse<String> resp;
      try {
        resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      } catch (IOException e) {
        if (last) throw new RuntimeException("Jev unreachable: " + e.getMessage(), e);
        sleep(backoff(attempt, null));
        continue;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted", e);
      }

      int s = resp.statusCode();
      if (s < 400) {
        try {
          JsonNode json = MAPPER.readTree(resp.body());
          return new Result(
              json,
              s,
              json.path("usage").path("input_tokens").asInt(),
              json.path("usage").path("output_tokens").asInt());
        } catch (IOException e) {
          throw new ConnectorException("JEV_ERROR", "Unreadable response: " + e.getMessage());
        }
      }

      String detail = s + ": " + truncate(resp.body());
      if (s == 401) throw new ConnectorException("JEV_UNAUTHORIZED", detail);
      if (s == 422) throw new ConnectorException("JEV_INVALID_REQUEST", detail);
      if (s == 429 || s == 529 || s >= 500) {
        // after in-call retries, fail the job so Camunda retries with the template backoff
        if (last) throw new RuntimeException("Jev busy after " + attempt + " attempts. " + detail);
        sleep(backoff(attempt, resp.headers().firstValue("retry-after").orElse(null)));
        continue;
      }
      throw new ConnectorException("JEV_ERROR", detail);
    }
  }

  private static long backoff(int attempt, String retryAfter) {
    if (retryAfter != null) {
      try {
        return Long.parseLong(retryAfter.trim()) * 1000;
      } catch (NumberFormatException ignored) {
        // fall through to exponential
      }
    }
    long base = 1000L << (attempt - 1); // 1s, 2s, 4s
    return base + ThreadLocalRandom.current().nextLong(250);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted", e);
    }
  }

  private static String truncate(String s) {
    return s == null ? "" : s.length() > 500 ? s.substring(0, 500) : s;
  }
}
