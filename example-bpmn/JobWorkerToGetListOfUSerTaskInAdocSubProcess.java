package io.github.rajeshponna.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;

@Component
public class JobWorkerToGetListOfUSerTaskInAdocSubProcess {

  private final CamundaClient client;
  // books per process version: BPMN is read once, then reused
  private final Map<Long, List<Map<String, String>>> cache = new ConcurrentHashMap<>();

  public JobWorkerToGetListOfUSerTaskInAdocSubProcess(CamundaClient client) {
    this.client = client;
  }

  @JobWorker(type = "fetch-adhoc-tasks")
  public Map<String, Object> fetchAdHocTasks(final ActivatedJob job) throws Exception {

    List<Map<String, String>> books = cache.computeIfAbsent(job.getProcessDefinitionKey(), key -> {
      String xml = client.newProcessDefinitionGetXmlRequest(key).send().join();
      return readAdHocTasks(xml);
    });

    if (books.isEmpty()) {
      throw new IllegalStateException("No tasks found inside an ad-hoc sub-process");
    }
    System.out.println("Books: " + books);
    return Map.of("books", books);
  }

  private static List<Map<String, String>> readAdHocTasks(String xml) {
    try {
      String ns = "http://www.omg.org/spec/BPMN/20100524/MODEL";
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      Document doc = f.newDocumentBuilder()
              .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

      List<Map<String, String>> books = new ArrayList<>();
      NodeList adHocs = doc.getElementsByTagNameNS(ns, "adHocSubProcess");
      for (int i = 0; i < adHocs.getLength(); i++) {
        NodeList children = adHocs.item(i).getChildNodes();
        for (int j = 0; j < children.getLength(); j++) {
          if (!(children.item(j) instanceof Element task)) continue;
          if (!"userTask".equals(task.getLocalName())) continue;

          String elementId   = task.getAttribute("id");          // Book_01
          String elementName = task.getAttribute("name");        // genre
          NodeList docs = task.getElementsByTagNameNS(ns, "documentation");
          String documentation = docs.getLength() > 0
                  ? docs.item(0).getTextContent().trim() : elementId; // book title

          Map<String, String> book = new HashMap<>();
          book.put("id", elementId);
          book.put("name", documentation);
          book.put("type", elementName);
          books.add(book);
        }
      }
      return books;
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read BPMN: " + e.getMessage(), e);
    }
  }
}