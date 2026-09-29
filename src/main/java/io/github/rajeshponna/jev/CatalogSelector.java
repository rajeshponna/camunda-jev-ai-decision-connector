package io.github.rajeshponna.jev;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.connector.api.error.ConnectorException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Picks the best item(s) from a list of any size.
 *
 * Jev allows max 255 options per choice question, so for big lists this runs a
 * tournament:
 *   round 1: split items into groups (<= groupSize), one choice question per group,
 *            several groups per API call, calls run in parallel;
 *            keep the top K of each group as finalists
 *   repeat until finalists fit in one question, then a final round picks the winner.
 *
 *   500 items   -> 2 groups   -> 6 finalists  -> final           (2 rounds)
 *   2,000 items -> 8 groups   -> 24 finalists -> final           (2 rounds)
 *   20,000      -> 80 groups  -> 240 finalists -> final          (2 rounds)
 *   100,000     -> 400 groups -> 1,200 -> 5 groups -> 15 -> final (3 rounds)
 */
final class CatalogSelector {

  static final int MAX_OPTIONS = 255;

  record Item(String id, String name, String type, String description) {
    int chars() {
      return id.length() + criterion().toString().length() + 8; // quotes, colon, comma
    }

    Object criterion() {
      StringBuilder sb = new StringBuilder(name == null ? id : name);
      if (type != null) sb.append(" [").append(type).append(']');
      if (description != null) sb.append(": ").append(description);
      return sb.toString();
    }
  }

  record Outcome(
      List<JevResponse.Ranked> top,
      Double confidence,
      String modelId,
      List<JevResponse.Round> rounds,
      int modelCalls,
      int inputTokens,
      int outputTokens,
      int lastStatus) {}

  private final JevClient client;
  private final String model;
  private final Object state;
  private final String instructions;
  private final int groupSize;
  private final int finalistsPerGroup;
  private final int questionsPerCall;
  private final int maxConcurrentCalls;
  private final int budgetChars;

  CatalogSelector(
      JevClient client, String model, Object state, String instructions,
      int groupSize, int finalistsPerGroup, int questionsPerCall, int maxConcurrentCalls,
      int maxTokensPerCall) {
    this.client = client;
    this.model = model;
    this.state = state;
    this.instructions = instructions;
    this.groupSize = Math.max(2, Math.min(groupSize, MAX_OPTIONS - 1)); // -1 leaves room to merge a tail of 1
    this.finalistsPerGroup = Math.max(1, finalistsPerGroup);
    this.questionsPerCall = Math.max(1, questionsPerCall);
    this.maxConcurrentCalls = Math.max(1, maxConcurrentCalls);
    this.budgetChars = JevClient.budgetChars(maxTokensPerCall, state, instructions);
  }

  static List<Item> parseItems(List<Map<String, Object>> raw, int maxItems) {
    if (raw == null || raw.isEmpty()) {
      throw new ConnectorException("JEV_INVALID_INPUT", "items is empty");
    }
    if (raw.size() > maxItems) {
      throw new ConnectorException("JEV_INVALID_INPUT",
          "items has " + raw.size() + " entries, maxItems is " + maxItems);
    }
    List<Item> items = new ArrayList<>(raw.size());
    Set<String> seen = new HashSet<>();
    for (Map<String, Object> m : raw) {
      String id = str(m.get("id"));
      if (id == null) throw new ConnectorException("JEV_INVALID_INPUT", "every item needs an id");
      if (!seen.add(id)) throw new ConnectorException("JEV_INVALID_INPUT", "duplicate item id: " + id);
      items.add(new Item(id, str(m.get("name")), str(m.get("type")), str(m.get("description"))));
    }
    // keep same-type items together so groups are coherent
    items.sort(Comparator.comparing(i -> i.type() == null ? "" : i.type()));
    return items;
  }

  Outcome select(List<Item> items, int topN) throws Exception {
    List<Item> candidates = new ArrayList<>(items);
    List<JevResponse.Round> rounds = new ArrayList<>();
    Stats stats = new Stats();

    int round = 0;
    // keep going while the field is too many options OR too many tokens for one call
    while (candidates.size() > MAX_OPTIONS || chars(candidates) > budgetChars) {
      round++;
      List<List<Item>> groups = partition(candidates, groupSize, budgetChars);
      int keep = finalistsPerGroup;
      // make sure every round actually shrinks the field
      if ((long) groups.size() * keep >= candidates.size()) keep = 1;

      List<Item> next = runGroups(groups, keep, stats);
      if (next.size() >= candidates.size()) {
        throw new ConnectorException("JEV_INVALID_INPUT",
            "Items are too large to compare within the token limit. Shorten names/descriptions.");
      }
      rounds.add(new JevResponse.Round(round, candidates.size(), groups.size(), stats.callsLastRound));
      candidates = next;
    }

    // final round: one question over everything that's left
    round++;
    Map<String, Item> byId = new LinkedHashMap<>();
    candidates.forEach(i -> byId.put(i.id(), i));

    if (candidates.size() == 1) {
      Item only = candidates.get(0);
      rounds.add(new JevResponse.Round(round, 1, 0, 0));
      return new Outcome(List.of(new JevResponse.Ranked(only.id(), only.name(), only.type(), 1.0)),
          1.0, null, rounds, stats.calls.get(), stats.in.get(), stats.out.get(), 200);
    }

    JevClient.Result r = client.send(body(Map.of("selection", question(candidates))));
    stats.add(r);
    rounds.add(new JevResponse.Round(round, candidates.size(), 1, 1));

    JsonNode ans = r.json().path("answers").path("selection");
    Map<String, Double> probs = probabilities(ans);
    List<JevResponse.Ranked> top =
        probs.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(Math.max(1, topN))
            .map(e -> {
              Item it = byId.get(e.getKey());
              return new JevResponse.Ranked(e.getKey(), it == null ? null : it.name(),
                  it == null ? null : it.type(), e.getValue());
            })
            .toList();

    if (top.isEmpty()) {
      throw new ConnectorException("JEV_ERROR", "Jev returned no probabilities for the final round");
    }

    Double confidence = ans.hasNonNull("confidence") ? ans.get("confidence").asDouble() : null;
    return new Outcome(top, confidence, r.json().path("model").asText(null), rounds,
        stats.calls.get(), stats.in.get(), stats.out.get(), r.status());
  }

  /** Sends groups in batches, in parallel, returns the top `keep` of every group. */
  private List<Item> runGroups(List<List<Item>> groups, int keep, Stats stats) throws Exception {
    // pack groups into calls: max questionsPerCall per call AND within the token budget
    List<List<Integer>> batches = new ArrayList<>();
    List<Integer> cur = new ArrayList<>();
    int curChars = 0;
    for (int g = 0; g < groups.size(); g++) {
      int c = chars(groups.get(g)) + instructions.length() + 60;
      if (!cur.isEmpty() && (cur.size() >= questionsPerCall || curChars + c > budgetChars)) {
        batches.add(cur);
        cur = new ArrayList<>();
        curChars = 0;
      }
      cur.add(g);
      curChars += c;
    }
    if (!cur.isEmpty()) batches.add(cur);

    Semaphore limit = new Semaphore(maxConcurrentCalls);
    List<Future<List<Item>>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (List<Integer> batch : batches) {
        futures.add(pool.submit(() -> {
          limit.acquire();
          try {
            Map<String, Object> questions = new LinkedHashMap<>();
            for (int g : batch) questions.put("g" + g, question(groups.get(g)));

            JevClient.Result r = client.send(body(questions));
            stats.add(r);

            List<Item> winners = new ArrayList<>();
            for (int g : batch) {
              Map<String, Item> byId = new LinkedHashMap<>();
              groups.get(g).forEach(it -> byId.put(it.id(), it));
              probabilities(r.json().path("answers").path("g" + g)).entrySet().stream()
                  .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                  .limit(keep)
                  .map(e -> byId.get(e.getKey()))
                  .filter(java.util.Objects::nonNull)
                  .forEach(winners::add);
            }
            return winners;
          } finally {
            limit.release();
          }
        }));
      }

      List<Item> next = new ArrayList<>();
      for (Future<List<Item>> f : futures) {
        try {
          next.addAll(f.get());
        } catch (java.util.concurrent.ExecutionException e) {
          Throwable c = e.getCause();
          if (c instanceof RuntimeException re) throw re; // keeps ConnectorException codes
          throw new RuntimeException(c);
        }
      }
      stats.callsLastRound = batches.size();
      return next;
    }
  }

  private Map<String, Object> question(List<Item> group) {
    Map<String, Object> criteria = new LinkedHashMap<>();
    group.forEach(i -> criteria.put(i.id(), i.criterion()));
    return Map.of("type", "choice", "instructions", instructions, "criteria", criteria);
  }

  private Map<String, Object> body(Map<String, Object> questions) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("model", model);
    b.put("state", state);
    b.put("questions", questions);
    return b;
  }

  private static Map<String, Double> probabilities(JsonNode answer) {
    Map<String, Double> p = new LinkedHashMap<>();
    answer.path("probabilities").fields().forEachRemaining(e -> p.put(e.getKey(), e.getValue().asDouble()));
    if (p.isEmpty() && answer.hasNonNull("choice")) p.put(answer.get("choice").asText(), 1.0);
    return p;
  }

  private static int chars(List<Item> items) {
    int n = 0;
    for (Item i : items) n += i.chars();
    return n;
  }

  /** Groups of max `size` items that also fit the token budget. */
  private static List<List<Item>> partition(List<Item> list, int size, int budgetChars) {
    List<List<Item>> out = new ArrayList<>();
    List<Item> cur = new ArrayList<>();
    int curChars = 0;
    for (Item it : list) {
      if (!cur.isEmpty() && (cur.size() >= size || curChars + it.chars() > budgetChars)) {
        out.add(cur);
        cur = new ArrayList<>();
        curChars = 0;
      }
      cur.add(it);
      curChars += it.chars();
    }
    if (!cur.isEmpty()) out.add(cur);
    // a choice needs 2+ options: fold a lone last item into the previous group
    if (out.size() > 1 && out.get(out.size() - 1).size() == 1) {
      out.get(out.size() - 2).addAll(out.remove(out.size() - 1));
    }
    return out;
  }

  private static String str(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private static final class Stats {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger in = new AtomicInteger();
    final AtomicInteger out = new AtomicInteger();
    volatile int callsLastRound;

    void add(JevClient.Result r) {
      calls.incrementAndGet();
      in.addAndGet(r.inputTokens());
      out.addAndGet(r.outputTokens());
    }
  }
}
