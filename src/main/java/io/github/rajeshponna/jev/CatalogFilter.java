package io.github.rajeshponna.jev;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.connector.api.error.ConnectorException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Returns ALL items that match the request (not a top N).
 *
 * One yes/no (noul) question per item. The item is passed as data inside the
 * question's instructions, the user's prompt is the state:
 *
 *   state:    "I want health books"
 *   question: { item: {id, name, type, description}, question: "Does `item` match ...?" }
 *   answer:   noul 0..1  -> match when >= threshold
 *
 * Items are batched (questionsPerCall per request) and requests run in parallel.
 */
final class CatalogFilter {

  record Outcome(
      List<JevResponse.Match> matches,
      int modelCalls,
      int inputTokens,
      int outputTokens,
      String modelId,
      int lastStatus) {}

  private final JevClient client;
  private final String model;
  private final Object state;
  private final String instructions;
  private final double threshold;
  private final int questionsPerCall;
  private final int maxConcurrentCalls;
  private final int maxTokensPerCall;

  CatalogFilter(JevClient client, String model, Object state, String instructions,
      double threshold, int questionsPerCall, int maxConcurrentCalls, int maxTokensPerCall) {
    this.client = client;
    this.model = model;
    this.state = state;
    this.instructions = instructions;
    this.threshold = threshold;
    this.questionsPerCall = Math.max(1, questionsPerCall);
    this.maxConcurrentCalls = Math.max(1, maxConcurrentCalls);
    this.maxTokensPerCall = maxTokensPerCall;
  }

  Outcome filter(List<CatalogSelector.Item> items, Integer maxMatches) throws Exception {
    // pack items into calls: max questionsPerCall per call AND within the token budget
    int budget = JevClient.budgetChars(maxTokensPerCall, state, instructions);
    List<List<CatalogSelector.Item>> batches = new ArrayList<>();
    List<CatalogSelector.Item> cur = new ArrayList<>();
    int curChars = 0;
    for (CatalogSelector.Item it : items) {
      int c = JevClient.jsonLength(question(it)) + 20;
      if (c > budget) {
        throw new ConnectorException("JEV_INVALID_INPUT", "Item " + it.id() + " is too large for one Jev call");
      }
      if (!cur.isEmpty() && (cur.size() >= questionsPerCall || curChars + c > budget)) {
        batches.add(cur);
        cur = new ArrayList<>();
        curChars = 0;
      }
      cur.add(it);
      curChars += c;
    }
    if (!cur.isEmpty()) batches.add(cur);

    AtomicInteger calls = new AtomicInteger();
    AtomicInteger in = new AtomicInteger();
    AtomicInteger out = new AtomicInteger();
    String[] modelId = new String[1];
    int[] status = {200};

    Semaphore limit = new Semaphore(maxConcurrentCalls);
    List<Future<List<JevResponse.Match>>> futures = new ArrayList<>();

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (List<CatalogSelector.Item> batch : batches) {
        futures.add(pool.submit(() -> {
          limit.acquire();
          try {
            Map<String, Object> questions = new LinkedHashMap<>();
            for (int i = 0; i < batch.size(); i++) questions.put("i" + i, question(batch.get(i)));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("state", state);
            body.put("questions", questions);

            JevClient.Result r = client.send(body);
            calls.incrementAndGet();
            in.addAndGet(r.inputTokens());
            out.addAndGet(r.outputTokens());
            modelId[0] = r.json().path("model").asText(null);
            status[0] = r.status();

            List<JevResponse.Match> found = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
              JsonNode a = r.json().path("answers").path("i" + i);
              double score = a.path("noul").asDouble(0.0);
              if (score >= threshold) {
                CatalogSelector.Item it = batch.get(i);
                found.add(new JevResponse.Match(it.id(), it.name(), it.type(), score));
              }
            }
            return found;
          } finally {
            limit.release();
          }
        }));
      }

      List<JevResponse.Match> matches = new ArrayList<>();
      for (Future<List<JevResponse.Match>> f : futures) {
        try {
          matches.addAll(f.get());
        } catch (java.util.concurrent.ExecutionException e) {
          if (e.getCause() instanceof RuntimeException re) throw re;
          throw new RuntimeException(e.getCause());
        }
      }

      matches.sort(Comparator.comparingDouble(JevResponse.Match::score).reversed());
      if (maxMatches != null && maxMatches > 0 && matches.size() > maxMatches) {
        matches = new ArrayList<>(matches.subList(0, maxMatches));
      }
      return new Outcome(matches, calls.get(), in.get(), out.get(), modelId[0], status[0]);
    }
  }

  private Map<String, Object> question(CatalogSelector.Item it) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", it.id());
    if (it.name() != null) item.put("name", it.name());
    if (it.type() != null) item.put("type", it.type());
    if (it.description() != null) item.put("description", it.description());

    Map<String, Object> instr = new LinkedHashMap<>();
    instr.put("item", item);
    instr.put("question", instructions);

    return Map.of(
        "type", "noul",
        "instructions", instr,
        "criteria", Map.of(
            "true", "The item clearly fits what the user is asking for",
            "false", "The item does not fit the request"));
  }
}
