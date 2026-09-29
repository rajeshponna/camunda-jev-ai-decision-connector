package io.github.rajeshponna.jev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * operation "questions": send your own questions map (noul / choice / score).
 * operation "select":    pick the best match(es) from a list of any size.
 * operation "filter":    return ALL items that match, from a list of any size.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JevRequest(
    @NotBlank String apiKey,
    String organizationId,
    String baseUrl,
    Integer timeoutSeconds,
    String operation,
    String model,
    @NotNull Object state,
    @DecimalMin("0.0") @DecimalMax("1.0") Double minConfidence,

    // questions mode
    Map<String, Map<String, Object>> questions,

    // select mode
    String instructions,
    List<Map<String, Object>> items,
    Integer topN,
    Integer groupSize,
    Integer finalistsPerGroup,
    Integer questionsPerCall,
    Integer maxConcurrentCalls,
    Integer maxItems,
    Integer maxTokensPerCall,

    // filter mode
    @DecimalMin("0.0") @DecimalMax("1.0") Double threshold,
    Integer maxMatches) {

  public String op() {
    return operation == null || operation.isBlank() ? "questions" : operation.toLowerCase();
  }

  public String modelOrDefault() {
    return model == null || model.isBlank() ? "jev-latest" : model;
  }

  public double minConfidenceOrDefault() {
    return minConfidence == null ? 0.0 : minConfidence;
  }

  public int timeoutOrDefault() { return timeoutSeconds == null ? 30 : timeoutSeconds; }
  public int topNOrDefault() { return topN == null ? 1 : topN; }
  public int groupSizeOrDefault() { return groupSize == null ? 250 : groupSize; }
  public int finalistsOrDefault() { return finalistsPerGroup == null ? Math.max(3, topNOrDefault()) : finalistsPerGroup; }
  public int questionsPerCallOr(int def) { return questionsPerCall == null ? def : questionsPerCall; }
  public double thresholdOrDefault() { return threshold == null ? 0.5 : threshold; }
  public int maxConcurrentOrDefault() { return maxConcurrentCalls == null ? 4 : maxConcurrentCalls; }
  public int maxTokensOrDefault() {
    return maxTokensPerCall == null ? JevClient.DEFAULT_MAX_TOKENS_PER_CALL : maxTokensPerCall;
  }
  public int maxItemsOrDefault() { return maxItems == null ? 50_000 : maxItems; }
}
