package com.smartorder.edu.learning.service.ppt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.config.DocmeeProperties;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceContent;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.service.ILearningSpaceService;
import com.smartorder.edu.learning.service.IResourceContentService;
import com.smartorder.edu.learning.service.IResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PptGenerationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_LANG = "zh-CN";
    private static final int GENERATE_TOKEN_LIMIT = 1;
    private static final int RENDER_TOKEN_LIMIT = 0;

    private final IResourceService resourceService;
    private final IResourceContentService resourceContentService;
    private final ILearningSpaceService learningSpaceService;
    private final DocmeePptClient docmeePptClient;
    private final PptMarkdownOutlineGenerator outlineGenerator;
    private final DocmeeProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public PptGenerationResult generateFromResource(Long userId, Long sourceResourceId, PptGenerationRequest request) {
        Resource source = resourceService.getById(sourceResourceId);
        if (source == null) {
            throw new BusinessException("资源不存在");
        }
        LearningSpaceVO space = learningSpaceService.getSpaceById(userId, source.getSpaceId());
        ResourceContent sourceContent = resourceContentService.getLatestByResourceId(source.getId());
        PptGenerationRequest safeRequest = request == null ? new PptGenerationRequest() : request;
        if (safeRequest.getSpaceId() != null && !safeRequest.getSpaceId().equals(source.getSpaceId())) {
            throw new BusinessException("资源不属于指定学习空间");
        }
        String topic = firstNonBlank(safeRequest.getTopic(), source.getTitle(), space.getName(), "学习课件");
        String uid = docmeeUid(userId);

        PptOutlineContext outlineContext = new PptOutlineContext(
                space.getName(),
                source.getTitle(),
                topic,
                sourceContent == null ? "" : sourceContent.getContent(),
                safeRequest.getUserIntent(),
                safeRequest.getTargetSlides()
        );
        String markdown = outlineGenerator.generate(outlineContext);
        String lang = firstNonBlank(properties.getLang(), DEFAULT_LANG);
        DocmeeApiToken token = docmeePptClient.createApiToken(uid, tokenLimit(properties.getGenerateTokenLimit(), GENERATE_TOKEN_LIMIT));
        DocmeeTemplateOptions options = docmeePptClient.getTemplateOptions(token.token(), lang);
        DocmeeTemplateRequest templateRequest = buildTemplateRequest(safeRequest, source, options);
        DocmeeTemplate template = docmeePptClient.randomTemplate(token.token(), templateRequest, lang);
        DocmeeTask task = docmeePptClient.createMarkdownTask(token.token(), markdown);
        DocmeePptResult ppt = docmeePptClient.generatePptx(token.token(), task.taskId(), markdown, template.templateId());

        Resource saved = persistPptResource(source, topic, safeRequest, uid, template, ppt);
        resourceContentService.createContent(
                saved.getId(),
                "ppt_embed",
                pptEmbedContent(ppt, template, markdown, uid),
                reviewResult()
        );
        return new PptGenerationResult(saved.getId(), ppt.pptId(), ppt.fileUrl(), template.templateId(), markdown);
    }

    public DocmeeApiToken createRenderToken(Long userId) {
        return docmeePptClient.createApiToken(docmeeUid(userId), tokenLimit(properties.getRenderTokenLimit(), RENDER_TOKEN_LIMIT));
    }

    public DocmeeApiToken createRenderToken(Long userId, Long resourceId, String pptId) {
        Resource resource = resourceService.getById(resourceId);
        if (resource == null) {
            throw new BusinessException("资源不存在");
        }
        learningSpaceService.getSpaceById(userId, resource.getSpaceId());
        if (!"ppt".equals(resource.getResourceType())) {
            throw new BusinessException("该资源不是 PPT 资源");
        }
        ResourceContent content = resourceContentService.getLatestByResourceId(resourceId);
        if (content == null || !"ppt_embed".equals(content.getContentType())) {
            throw new BusinessException("PPT 预览内容不存在");
        }
        String savedPptId = readPptId(content.getContent());
        if (pptId == null || pptId.isBlank() || !pptId.equals(savedPptId)) {
            throw new BusinessException("PPT 预览授权不匹配");
        }
        return createRenderToken(userId);
    }

    private DocmeeTemplateRequest buildTemplateRequest(
            PptGenerationRequest request,
            Resource source,
            DocmeeTemplateOptions options
    ) {
        DocmeeTemplateRequest templateRequest = new DocmeeTemplateRequest();
        templateRequest.setOptions(options);
        templateRequest.setSize(1);
        templateRequest.setStyle(firstNonBlank(request.getTemplateStyle(), inferTemplateStyle(source), "education"));
        templateRequest.setScene("education");
        return templateRequest;
    }

    private Resource persistPptResource(
            Resource source,
            String topic,
            PptGenerationRequest request,
            String uid,
            DocmeeTemplate template,
            DocmeePptResult ppt
    ) {
        Resource resource = new Resource();
        resource.setSpaceId(source.getSpaceId());
        resource.setTaskId(source.getTaskId());
        resource.setPathNodeId(source.getPathNodeId());
        resource.setTitle("演示课件：" + topic);
        resource.setDescription(firstNonBlank(request.getUserIntent(), "基于学习资源生成的 PPT 课件"));
        resource.setResourceType("ppt");
        resource.setUrl(firstNonBlank(ppt.fileUrl(), "/resources/generated/docmee/" + ppt.pptId()));
        resource.setFileFormat("pptx");
        resource.setCoverUrl(firstNonBlank(ppt.coverUrl(), template.coverUrl()));
        resource.setIsAiGenerated(1);
        resource.setSource("ai");
        resource.setStatus("generated");
        resource.setMetadata(toJson(metadata(source, request, uid, template, ppt)));
        resource.setIsDeleted(0);
        return resourceService.batchInsertGenerated(List.of(resource)).getFirst();
    }

    private Map<String, Object> metadata(
            Resource source,
            PptGenerationRequest request,
            String uid,
            DocmeeTemplate template,
            DocmeePptResult ppt
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "docmee");
        metadata.put("docmeeUid", uid);
        metadata.put("sourceResourceId", source.getId());
        metadata.put("pptId", ppt.pptId());
        metadata.put("templateId", template.templateId());
        metadata.put("templateStyle", request.getTemplateStyle());
        metadata.put("fileUrl", ppt.fileUrl());
        metadata.put("coverUrl", firstNonBlank(ppt.coverUrl(), template.coverUrl()));
        metadata.put("agentReason", firstNonBlank(request.getUserIntent(), "Resource Agent 基于当前资源生成 PPT"));
        metadata.put("knowledgePoint", firstNonBlank(request.getTopic(), source.getTitle()));
        metadata.put("studyScenario", "学习");
        metadata.put("generatedBy", "docmee_ppt_generation_service");
        return metadata;
    }

    private String pptEmbedContent(DocmeePptResult ppt, DocmeeTemplate template, String markdown, String uid) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("provider", "docmee");
        content.put("docmeeUid", uid);
        content.put("pptId", ppt.pptId());
        content.put("fileUrl", ppt.fileUrl());
        content.put("coverUrl", firstNonBlank(ppt.coverUrl(), template.coverUrl()));
        content.put("templateId", template.templateId());
        content.put("templateName", template.name());
        content.put("markdown", markdown);
        content.put("status", "generated");
        return toJson(content);
    }

    private String reviewResult() {
        return toJson(Map.of(
                "status", "passed",
                "source", "docmee",
                "checks", List.of("markdown_generated", "template_selected", "ppt_created")
        ));
    }

    private String inferTemplateStyle(Resource source) {
        if ("case_study".equals(source.getResourceType())) {
            return "business";
        }
        if ("mind_map".equals(source.getResourceType()) || "quiz".equals(source.getResourceType())) {
            return "simple";
        }
        return "education";
    }

    private String docmeeUid(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException("用户身份无效");
        }
        return "smartorder-user-" + userId;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("PPT 生成数据序列化失败");
        }
    }
    private String readPptId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            return root.path("pptId").asText("");
        } catch (JsonProcessingException e) {
            throw new BusinessException("PPT 预览内容解析失败");
        }
    }

    private int tokenLimit(Integer value, int fallback) {
        return value == null || value < 0 ? fallback : value;
    }
}
