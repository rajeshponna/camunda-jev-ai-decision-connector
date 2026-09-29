package io.github.rajeshponna.jev;

import java.util.List;
import java.util.Map;

/** Connector result, shaped like the Camunda AI Agent response. */
public record JevResponse(
    Context context,
    Map<String, Answer> answers,
    Selection selection,
    Filter filter,
    boolean needsReview,
    String responseText) {

  public record Context(int schemaVersion, String state, Metadata metadata, Metrics metrics, Call call) {}

  public record Metadata(
      String organizationId,
      long processDefinitionKey,
      long processInstanceKey,
      String bpmnProcessId,
      int processDefinitionVersion,
      String elementId,
      long elementInstanceKey) {}

  public record Metrics(int modelCalls, TokenUsage tokenUsage, long durationMs) {}

  public record TokenUsage(int inputTokenCount, int outputTokenCount, int totalTokenCount) {}

  public record Call(
      String requestId,
      String operation,
      String requestedModel,
      String modelId,
      List<String> questionIds,
      int httpStatus,
      String requestTimestamp,
      String responseTimestamp) {}

  /** questions mode. value: choice -> option, noul -> 0..1, score -> weighted level. */
  public record Answer(
      String type,
      Object value,
      Double confidence,
      Map<String, Double> probabilities,
      Map<String, String> legend,
      boolean needsReview) {}

  /** select mode. id = winner; top = ranked finalists (size topN). */
  public record Selection(
      String id,
      String name,
      String type,
      Double probability,
      Double confidence,
      List<Ranked> top,
      int itemsTotal,
      List<Round> rounds) {}

  /** filter mode. matches = every item scoring >= threshold, best first. */
  public record Filter(
      List<Match> matches,
      int matchCount,
      double threshold,
      int itemsTotal) {}

  public record Match(String id, String name, String type, double score) {}

  public record Ranked(String id, String name, String type, Double probability) {}

  public record Round(int round, int candidates, int groups, int calls) {}
}
