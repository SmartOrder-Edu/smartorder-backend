package com.smartorder.edu.learning.service.ppt;

import com.smartorder.edu.learning.config.DocmeeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DocmeeRestPptClientTest {

    private HttpServer server;
    private final List<CapturedRequest> requests = new ArrayList<>();
    private DocmeeRestPptClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/ppt/randomTemplates", this::handleRandomTemplate);
        server.createContext("/api/ppt/v2/createTask", this::handleCreateTask);
        server.createContext("/api/ppt/v2/generatePptx", this::handleGeneratePptx);
        server.start();

        DocmeeProperties properties = new DocmeeProperties();
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setApiKey("test-api-key");
        client = new DocmeeRestPptClient(properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void usesRandomTemplateIdWhenGeneratingMarkdownPpt(CapturedOutput output) {
        DocmeeTemplateRequest templateRequest = new DocmeeTemplateRequest();
        templateRequest.setSize(1);
        templateRequest.setStyle("education");

        DocmeeTemplate template = client.randomTemplate("user-token", templateRequest, "zh-CN");
        DocmeeTask task = client.createMarkdownTask("user-token", "# Operating Systems");
        DocmeePptResult ppt = client.generatePptx("user-token", task.taskId(), "# Operating Systems", template.templateId());

        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).pathAndQuery()).isEqualTo("/api/ppt/randomTemplates?lang=zh-CN");
        assertThat(requests.get(0).body()).contains("\"style\":\"education\"");
        assertThat(requests.get(1).pathAndQuery()).isEqualTo("/api/ppt/v2/createTask");
        assertThat(requests.get(2).pathAndQuery()).isEqualTo("/api/ppt/v2/generatePptx");
        assertThat(requests.get(2).body()).contains("\"id\":\"task-100\"");
        assertThat(requests.get(2).body()).contains("\"templateId\":\"template-100\"");
        assertThat(requests.get(2).body()).contains("\"markdown\":\"# Operating Systems\"");
        assertThat(requests.get(2).body()).doesNotContain("\"taskId\":");
        assertThat(requests.get(2).body()).doesNotContain("\"outlineContentMarkdown\":");
        assertThat(ppt.pptId()).isEqualTo("ppt-100");
        assertThat(output).contains("[Docmee] random template selected: templateId=template-100");
        assertThat(output).contains("[Docmee] markdown task created: taskId=task-100");
        assertThat(output).contains("[Docmee] PPT generated: pptId=ppt-100");
    }

    private void handleRandomTemplate(HttpExchange exchange) throws IOException {
        recordRequest(exchange);
        writeJson(exchange, "{\"code\":0,\"data\":[{\"id\":\"template-100\",\"name\":\"Education\"}]}");
    }

    private void handleCreateTask(HttpExchange exchange) throws IOException {
        recordRequest(exchange);
        writeJson(exchange, "{\"code\":0,\"data\":{\"taskId\":\"task-100\"}}");
    }

    private void handleGeneratePptx(HttpExchange exchange) throws IOException {
        recordRequest(exchange);
        writeJson(exchange, "{\"code\":0,\"data\":{\"pptInfo\":{\"id\":\"ppt-100\",\"coverUrl\":\"https://example.com/ppt-100.png\"}}}");
    }

    private void recordRequest(HttpExchange exchange) throws IOException {
        requests.add(new CapturedRequest(
                exchange.getRequestURI().toString(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
        ));
    }

    private void writeJson(HttpExchange exchange, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(String pathAndQuery, String body) {
    }
}
