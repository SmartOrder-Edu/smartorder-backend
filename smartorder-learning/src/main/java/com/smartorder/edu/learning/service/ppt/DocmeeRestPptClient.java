package com.smartorder.edu.learning.service.ppt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.config.DocmeeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class DocmeeRestPptClient implements DocmeePptClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DocmeeProperties properties;
    private final RestClient restClient;

    public DocmeeRestPptClient(DocmeeProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(safeTimeout(properties.getConnectTimeoutMillis(), 10000));
        requestFactory.setReadTimeout(safeTimeout(properties.getReadTimeoutMillis(), 120000));
        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public DocmeeApiToken createApiToken(String uid, Integer limit) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new BusinessException("Docmee API-Key 未配置");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uid", uid);
        if (limit != null) {
            body.put("limit", limit);
        }
        JsonNode data = dataNode(restClient.post()
                .uri("/api/user/createApiToken")
                .header("Api-Key", properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
        return new DocmeeApiToken(requiredText(data, "token"), uid);
    }

    @Override
    public DocmeeTemplateOptions getTemplateOptions(String token, String lang) {
        JsonNode data = dataNode(restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ppt/template/options")
                        .queryParam("lang", lang)
                        .build())
                .header("token", token)
                .retrieve()
                .body(String.class));
        return new DocmeeTemplateOptions(toMap(data));
    }

    @Override
    public DocmeeTemplate randomTemplate(String token, DocmeeTemplateRequest request, String lang) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", request.getSize() == null ? 1 : request.getSize());
        if (request.getStyle() != null && !request.getStyle().isBlank()) {
            body.put("style", request.getStyle());
        }
        if (request.getScene() != null && !request.getScene().isBlank()) {
            body.put("scene", request.getScene());
        }
        log.info("[Docmee] requesting random template: style={}, scene={}, lang={}",
                request.getStyle(), request.getScene(), lang);
        JsonNode data = dataNode(restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ppt/randomTemplates")
                        .queryParam("lang", lang)
                        .build())
                .header("token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
        JsonNode templateNode = firstObject(data);
        DocmeeTemplate template = new DocmeeTemplate(
                requiredText(templateNode, "id", "templateId"),
                firstText(templateNode, "name", "title"),
                firstText(templateNode, "coverUrl", "cover", "imageUrl")
        );
        log.info("[Docmee] random template selected: templateId={}", template.templateId());
        return template;
    }

    @Override
    public DocmeeTask createMarkdownTask(String token, String markdown) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("type", "7");
        body.add("content", markdown);
        log.info("[Docmee] creating markdown task: markdownLength={}", markdown.length());
        JsonNode data = dataNode(restClient.post()
                .uri("/api/ppt/v2/createTask")
                .header("token", token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class));
        DocmeeTask task = new DocmeeTask(requiredText(data, "taskId", "id"));
        log.info("[Docmee] markdown task created: taskId={}", task.taskId());
        return task;
    }

    @Override
    public DocmeePptResult generatePptx(String token, String taskId, String markdown, String templateId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", taskId);
        body.put("markdown", markdown);
        body.put("templateId", templateId);
        log.info("[Docmee] generating PPT: taskId={}, templateId={}, markdownLength={}",
                taskId, templateId, markdown.length());
        JsonNode data = dataNode(restClient.post()
                .uri("/api/ppt/v2/generatePptx")
                .header("token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
        DocmeePptResult result = new DocmeePptResult(
                requiredText(data, "pptId", "id"),
                firstText(data, "fileUrl", "url"),
                firstText(data, "coverUrl", "cover", "imageUrl")
        );
        log.info("[Docmee] PPT generated: pptId={}", result.pptId());
        return result;
    }

    private JsonNode dataNode(String body) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body == null ? "{}" : body);
            int code = root.path("code").asInt(200);
            if (code != 0 && code != 200) {
                throw new BusinessException("Docmee API 调用失败：" + root.path("message").asText(root.path("msg").asText()));
            }
            JsonNode data = root.get("data");
            return data == null || data.isNull() ? root : data;
        } catch (JsonProcessingException e) {
            log.warn("[Docmee] invalid json response: {}", body);
            throw new BusinessException("Docmee API 响应解析失败");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        return OBJECT_MAPPER.convertValue(node, Map.class);
    }

    private String requiredText(JsonNode node, String... names) {
        String value = firstText(node, names);
        if (value == null || value.isBlank()) {
            throw new BusinessException("Docmee API 响应缺少必要字段：" + String.join("/", names));
        }
        return value;
    }

    private String firstText(JsonNode node, String... names) {
        if (node == null || node.isNull()) {
            return "";
        }
        for (String name : names) {
            JsonNode found = findField(node, name);
            if (found != null && !found.isNull()) {
                return found.asText("");
            }
        }
        return "";
    }

    private JsonNode firstObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        if (node.isArray()) {
            return node.isEmpty() ? OBJECT_MAPPER.createObjectNode() : node.get(0);
        }
        if (node.isObject()) {
            JsonNode records = node.get("records");
            if (records != null && records.isArray() && !records.isEmpty()) {
                return records.get(0);
            }
            JsonNode list = node.get("list");
            if (list != null && list.isArray() && !list.isEmpty()) {
                return list.get(0);
            }
            return node;
        }
        return OBJECT_MAPPER.createObjectNode();
    }

    private JsonNode findField(JsonNode node, String name) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode direct = node.get(name);
        if (direct != null) {
            return direct;
        }
        if (node.isObject()) {
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                JsonNode found = findField(values.next(), name);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findField(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String trimTrailingSlash(String value) {
        String baseUrl = value == null || value.isBlank() ? "https://open.docmee.cn" : value.trim();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static int safeTimeout(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
