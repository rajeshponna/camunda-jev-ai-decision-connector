package io.github.rajeshponna.jev;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.JobContext;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OutboundConnector(
    name = "Jev AI Decision Connector",
    inputVariables = {
      "apiKey", "organizationId", "baseUrl", "timeoutSeconds", "operation", "model", "state", "minConfidence", "questions",
      "instructions", "items", "topN", "groupSize", "finalistsPerGroup",
      "questionsPerCall", "maxConcurrentCalls", "maxItems", "maxTokensPerCall", "threshold", "maxMatches"
    },
    type = "io.github.rajeshponna:jev-ai-decision:1")
public class JevConnectorFunction implements OutboundConnectorFunction {

  private static final Logger LOG = LoggerFactory.getLogger(JevConnectorFunction.class);
  private static final Set<String> TYPES = Set.of("noul", "choice", "score");
  private static final TypeReference<Map<String, Double>> PROBS = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> LEGEND = new TypeReference<>() {};

  @Override
  public Object execute(OutboundConnectorContext context) throws Exception {
    JevRequest req = context.bindVariables(JevRequest.class);
    JevClient client = new JevClient(req.apiKey(), req.baseUrl(), req.timeoutOrDefault());
    String requestId = UUID.randomUUID().toString();

    Instant requestTime = Instant.now();
    long start = System.nanoTime();

    JobContext job = context.getJobContext();
    JevResponse result = switch (req.op()) {
      case "select" -> runSelect(req, client, job, requestId, requestTime, start);
      case "filter" -> runFilter(req, client, job, requestId, requestTime, start);
      case "questions" -> runQuestions(req, client, job, requestId, requestTime, start);
      default -> throw new ConnectorException("JEV_INVALID_INPUT",
          "operation must be select, filter or questions");
    };

    LOG.info("Jev requestId={} op={} pi={} element={} calls={} {}ms -> {}",
        requestId, req.op(),
        result.context().metadata().processInstanceKey(), result.context().metadata().elementId(),
        result.context().metrics().modelCalls(), result.context().metrics().durationMs(),
        result.responseText());
    return result;
  }

  // ---------------------------------------------------------------- select

  private JevResponse runSelect(JevRequest req, JevClient client, JobContext job,
      String requestId, Instant requestTime, long start) throws Exception {

    if (req.instructions() == null || req.instructions().isBlank()) {
      throw new ConnectorException("JEV_INVALID_INPUT", "instructions is required for select");
    }
    List<CatalogSelector.Item> items = CatalogSelector.parseItems(req.items(), req.maxItemsOrDefault());

    CatalogSelector.Outcome o = new CatalogSelector(
            client, req.modelOrDefault(), req.state(), req.instructions(),
            req.groupSizeOrDefault(), req.finalistsOrDefault(),
            req.questionsPerCallOr(4), req.maxConcurrentOrDefault(), req.maxTokensOrDefault())
        .select(items, req.topNOrDefault());

    long durationMs = (System.nanoTime() - start) / 1_000_000;
    JevResponse.Ranked best = o.top().get(0);
    boolean needsReview = o.confidence() != null && o.confidence() < req.minConfidenceOrDefault();

    JevResponse.Selection selection = new JevResponse.Selection(
        best.id(), best.name(), best.type(), best.probability(), o.confidence(),
        o.top(), items.size(), o.rounds());

    String text = "selected " + best.id()
        + (best.name() != null ? " (" + best.name() + ")" : "")
        + " from " + items.size() + " items in " + o.rounds().size() + " round(s)"
        + (o.confidence() != null ? ", confidence " + o.confidence() : "");

    return new JevResponse(
        context(req, job, o.modelCalls(), o.inputTokens(), o.outputTokens(), durationMs,
            new JevResponse.Call(requestId, "select", req.modelOrDefault(), o.modelId(), null,
                o.lastStatus(), requestTime.toString(), Instant.now().toString())),
        null, selection, null, needsReview, text);
  }

  // ---------------------------------------------------------------- filter

  private JevResponse runFilter(JevRequest req, JevClient client, JobContext job,
      String requestId, Instant requestTime, long start) throws Exception {

    if (req.instructions() == null || req.instructions().isBlank()) {
      throw new ConnectorException("JEV_INVALID_INPUT", "instructions is required for filter");
    }
    List<CatalogSelector.Item> items = CatalogSelector.parseItems(req.items(), req.maxItemsOrDefault());
    double threshold = req.thresholdOrDefault();

    CatalogFilter.Outcome o = new CatalogFilter(
            client, req.modelOrDefault(), req.state(), req.instructions(), threshold,
            req.questionsPerCallOr(50), req.maxConcurrentOrDefault(), req.maxTokensOrDefault())
        .filter(items, req.maxMatches());

    long durationMs = (System.nanoTime() - start) / 1_000_000;
    JevResponse.Filter filter =
        new JevResponse.Filter(o.matches(), o.matches().size(), threshold, items.size());

    String text = o.matches().size() + " of " + items.size() + " items match"
        + (o.matches().isEmpty() ? "" : ": " + String.join(", ",
            o.matches().stream().limit(10).map(JevResponse.Match::id).toList())
            + (o.matches().size() > 10 ? ", ..." : ""));

    return new JevResponse(
        context(req, job, o.modelCalls(), o.inputTokens(), o.outputTokens(), durationMs,
            new JevResponse.Call(requestId, "filter", req.modelOrDefault(), o.modelId(), null,
                o.lastStatus(), requestTime.toString(), Instant.now().toString())),
        null, null, filter, o.matches().isEmpty(), text);
  }

  // ------------------------------------------------------------- questions

  private JevResponse runQuestions(JevRequest req, JevClient client, JobContext job,
      String requestId, Instant requestTime, long start) {

    validateQuestions(req.questions());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", req.modelOrDefault());
    body.put("state", req.state());
    body.put("questions", req.questions());

    JevClient.Result r = client.send(body);
    long durationMs = (System.nanoTime() - start) / 1_000_000;

    double min = req.minConfidenceOrDefault();
    Map<String, JevResponse.Answer> answers = new LinkedHashMap<>();
    r.json().path("answers").fields()
        .forEachRemaining(e -> answers.put(e.getKey(), toAnswer(e.getValue(), min)));
    boolean needsReview = answers.values().stream().anyMatch(JevResponse.Answer::needsReview);

    return new JevResponse(
        context(req, job, 1, r.inputTokens(), r.outputTokens(), durationMs,
            new JevResponse.Call(requestId, "questions", req.modelOrDefault(),
                r.json().path("model").asText(null), new ArrayList<>(req.questions().keySet()),
                r.status(), requestTime.toString(), Instant.now().toString())),
        answers, null, null, needsReview, summary(answers));
  }

  private static void validateQuestions(Map<String, Map<String, Object>> questions) {
    if (questions == null || questions.isEmpty()) {
      throw new ConnectorException("JEV_INVALID_INPUT", "questions is required");
    }
    questions.forEach((id, q) -> {
      Object type = q.get("type");
      if (!(type instanceof String t) || !TYPES.contains(t)) {
        throw new ConnectorException("JEV_INVALID_INPUT",
            "Question '" + id + "': type must be noul, choice or score");
      }
      if (q.get("instructions") == null) {
        throw new ConnectorException("JEV_INVALID_INPUT", "Question '" + id + "': instructions required");
      }
      if (!"noul".equals(type) && q.get("criteria") == null) {
        throw new ConnectorException("JEV_INVALID_INPUT", "Question '" + id + "': criteria required");
      }
      if ("choice".equals(type) && q.get("criteria") instanceof Map<?, ?> c
          && c.size() > CatalogSelector.MAX_OPTIONS) {
        throw new ConnectorException("JEV_INVALID_INPUT",
            "Question '" + id + "' has " + c.size() + " options (max 255). Use operation 'select' for big lists.");
      }
      if ("choice".equals(type) && !(q.get("criteria") instanceof Map<?, ?>)) {
        throw new ConnectorException("JEV_INVALID_INPUT",
            "Question '" + id + "': choice criteria must be a map of option -> description");
      }
      if ("score".equals(type)) {
        if (!(q.get("criteria") instanceof java.util.List<?> levels) || levels.size() < 2 || levels.size() > 10) {
          throw new ConnectorException("JEV_INVALID_INPUT",
              "Question '" + id + "': score criteria must be a list of 2 to 10 levels");
        }
      }
    });
  }

  private static JevResponse.Answer toAnswer(JsonNode a, double min) {
    String type = a.path("type").asText();
    Double confidence = a.hasNonNull("confidence") ? a.get("confidence").asDouble() : null;
    Map<String, Double> probs =
        a.has("probabilities") ? JevClient.MAPPER.convertValue(a.get("probabilities"), PROBS) : null;
    Map<String, String> legend =
        a.has("legend") ? JevClient.MAPPER.convertValue(a.get("legend"), LEGEND) : null;

    Object value = switch (type) {
      case "choice" -> a.path("choice").asText();
      case "noul" -> a.path("noul").asDouble();
      case "score" -> a.path("score").asDouble();
      default -> JevClient.MAPPER.convertValue(a, Object.class);
    };
    return new JevResponse.Answer(type, value, confidence, probs, legend,
        confidence != null && confidence < min);
  }

  private static String summary(Map<String, JevResponse.Answer> answers) {
    StringJoiner sj = new StringJoiner("; ");
    answers.forEach((id, a) -> sj.add(
        id + " = " + a.value() + (a.confidence() != null ? " (confidence " + a.confidence() + ")" : "")));
    return sj.toString();
  }

  // ---------------------------------------------------------------- shared

  private static JevResponse.Context context(JevRequest req, JobContext job, int calls, int in, int out,
      long durationMs, JevResponse.Call call) {
    return new JevResponse.Context(
        1,
        "COMPLETED",
        new JevResponse.Metadata(
            req.organizationId(),
            job.getProcessDefinitionKey(),
            job.getProcessInstanceKey(),
            job.getBpmnProcessId(),
            job.getProcessDefinitionVersion(),
            job.getElementId(),
            job.getElementInstanceKey()),
        new JevResponse.Metrics(calls, new JevResponse.TokenUsage(in, out, in + out), durationMs),
        call);
  }
}
