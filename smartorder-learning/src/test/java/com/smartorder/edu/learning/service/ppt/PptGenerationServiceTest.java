package com.smartorder.edu.learning.service.ppt;

import com.smartorder.edu.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.learning.config.DocmeeProperties;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceContent;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.service.ILearningSpaceService;
import com.smartorder.edu.learning.service.IResourceContentService;
import com.smartorder.edu.learning.service.IResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PptGenerationServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private IResourceService resourceService;
    private IResourceContentService resourceContentService;
    private ILearningSpaceService learningSpaceService;
    private DocmeePptClient docmeePptClient;
    private PptMarkdownOutlineGenerator outlineGenerator;
    private DocmeeProperties properties;
    private PptGenerationService service;

    @BeforeEach
    void setUp() {
        resourceService = mock(IResourceService.class);
        resourceContentService = mock(IResourceContentService.class);
        learningSpaceService = mock(ILearningSpaceService.class);
        docmeePptClient = mock(DocmeePptClient.class);
        outlineGenerator = mock(PptMarkdownOutlineGenerator.class);
        properties = new DocmeeProperties();
        service = new PptGenerationService(
                resourceService,
                resourceContentService,
                learningSpaceService,
                docmeePptClient,
                outlineGenerator,
                properties
        );
    }

    @Test
    void generateFromResourceUsesTemplateOptionsBeforeRandomTemplateAndPersistsPptEmbedResource() throws Exception {
        Resource source = new Resource();
        source.setId(88L);
        source.setSpaceId(10L);
        source.setTaskId(20L);
        source.setPathNodeId(30L);
        source.setTitle("进程调度讲义");
        source.setDescription("操作系统学习资源");
        source.setResourceType("doc");
        when(resourceService.getById(88L)).thenReturn(source);

        LearningSpaceVO space = new LearningSpaceVO();
        space.setId(10L);
        space.setName("操作系统");
        when(learningSpaceService.getSpaceById(7L, 10L)).thenReturn(space);

        ResourceContent sourceContent = new ResourceContent();
        sourceContent.setContentType("markdown");
        sourceContent.setContent("# 进程调度\n\n## FCFS");
        when(resourceContentService.getLatestByResourceId(88L)).thenReturn(sourceContent);

        PptGenerationRequest request = new PptGenerationRequest();
        request.setTopic("进程调度算法");
        request.setTemplateStyle("education");
        request.setTargetSlides(8);
        request.setUserIntent("做成课堂讲义");

        String markdown = "# 进程调度算法\n\n## 目录\n\n## FCFS";
        when(outlineGenerator.generate(any(PptOutlineContext.class))).thenReturn(markdown);

        DocmeeApiToken token = new DocmeeApiToken("docmee-token", "smartorder-user-7");
        DocmeeTemplateOptions options = new DocmeeTemplateOptions(Map.of("styles", List.of("education")));
        DocmeeTemplate template = new DocmeeTemplate("tpl-100", "教育模板", "https://cover.example/tpl.png");
        DocmeeTask task = new DocmeeTask("task-200");
        DocmeePptResult pptResult = new DocmeePptResult(
                "ppt-300",
                "https://file.example/ppt-300.pptx",
                "https://cover.example/ppt-300.png"
        );
        when(docmeePptClient.createApiToken("smartorder-user-7", 1)).thenReturn(token);
        when(docmeePptClient.getTemplateOptions("docmee-token", "zh-CN")).thenReturn(options);
        when(docmeePptClient.randomTemplate(eq("docmee-token"), any(DocmeeTemplateRequest.class), eq("zh-CN")))
                .thenReturn(template);
        when(docmeePptClient.createMarkdownTask("docmee-token", markdown)).thenReturn(task);
        when(docmeePptClient.generatePptx("docmee-token", "task-200", markdown, "tpl-100"))
                .thenReturn(pptResult);
        when(resourceService.batchInsertGenerated(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Resource> resources = invocation.getArgument(0, List.class);
            resources.getFirst().setId(99L);
            return resources;
        });
        ResourceContent created = new ResourceContent();
        created.setId(100L);
        created.setResourceId(99L);
        created.setContentType("ppt_embed");
        when(resourceContentService.createContent(eq(99L), eq("ppt_embed"), any(), any())).thenReturn(created);

        PptGenerationResult result = service.generateFromResource(7L, 88L, request);

        InOrder inOrder = inOrder(docmeePptClient);
        inOrder.verify(docmeePptClient).createApiToken("smartorder-user-7", 1);
        inOrder.verify(docmeePptClient).getTemplateOptions("docmee-token", "zh-CN");
        inOrder.verify(docmeePptClient).randomTemplate(eq("docmee-token"), any(DocmeeTemplateRequest.class), eq("zh-CN"));
        inOrder.verify(docmeePptClient).createMarkdownTask("docmee-token", markdown);
        inOrder.verify(docmeePptClient).generatePptx("docmee-token", "task-200", markdown, "tpl-100");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Resource>> resourceCaptor = ArgumentCaptor.forClass(List.class);
        verify(resourceService).batchInsertGenerated(resourceCaptor.capture());
        Resource saved = resourceCaptor.getValue().getFirst();
        assertThat(saved.getSpaceId()).isEqualTo(10L);
        assertThat(saved.getTaskId()).isEqualTo(20L);
        assertThat(saved.getPathNodeId()).isEqualTo(30L);
        assertThat(saved.getResourceType()).isEqualTo("ppt");
        assertThat(saved.getFileFormat()).isEqualTo("pptx");
        assertThat(saved.getUrl()).isEqualTo("https://file.example/ppt-300.pptx");
        assertThat(saved.getCoverUrl()).isEqualTo("https://cover.example/ppt-300.png");
        assertThat(saved.getMetadata()).contains("docmee", "ppt-300", "tpl-100", "smartorder-user-7");

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceContentService).createContent(eq(99L), eq("ppt_embed"), contentCaptor.capture(), any());
        JsonNode contentJson = OBJECT_MAPPER.readTree(contentCaptor.getValue());
        assertThat(contentJson.get("pptId").asText()).isEqualTo("ppt-300");
        assertThat(contentJson.get("templateId").asText()).isEqualTo("tpl-100");
        assertThat(contentJson.get("markdown").asText()).isEqualTo(markdown);
        assertThat(contentJson.get("fileUrl").asText()).isEqualTo("https://file.example/ppt-300.pptx");

        assertThat(result.resourceId()).isEqualTo(99L);
        assertThat(result.pptId()).isEqualTo("ppt-300");
        assertThat(result.templateId()).isEqualTo("tpl-100");
        assertThat(result.markdown()).isEqualTo(markdown);
    }

    @Test
    void generateFromResourceRejectsMissingSourceBeforeCallingDocmee() {
        when(resourceService.getById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.generateFromResource(7L, 404L, new PptGenerationRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("资源不存在");

        verify(docmeePptClient, never()).createApiToken(any(), any());
        verify(resourceService, never()).batchInsertGenerated(any());
    }

    @Test
    void createRenderTokenRequiresOwnedPptResourceAndMatchingPptId() {
        Resource pptResource = new Resource();
        pptResource.setId(99L);
        pptResource.setSpaceId(10L);
        pptResource.setResourceType("ppt");
        when(resourceService.getById(99L)).thenReturn(pptResource);

        LearningSpaceVO space = new LearningSpaceVO();
        space.setId(10L);
        when(learningSpaceService.getSpaceById(7L, 10L)).thenReturn(space);

        ResourceContent content = new ResourceContent();
        content.setContentType("ppt_embed");
        content.setContent("{\"pptId\":\"ppt-300\"}");
        when(resourceContentService.getLatestByResourceId(99L)).thenReturn(content);
        when(docmeePptClient.createApiToken("smartorder-user-7", 0))
                .thenReturn(new DocmeeApiToken("render-token", "smartorder-user-7"));

        DocmeeApiToken token = service.createRenderToken(7L, 99L, "ppt-300");

        assertThat(token.token()).isEqualTo("render-token");
        verify(docmeePptClient).createApiToken("smartorder-user-7", 0);
    }
}
