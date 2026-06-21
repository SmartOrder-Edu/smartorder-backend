package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.ToolResult;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 生成思维导图工具——按树形结构组织节点，发射 {@code agent.mindmap.created} 事件，
 * 前端通过 AntV X6 渲染交互式思维导图（只读 + 缩放/拖拽/导出）。
 * <p>
 * 数据由 Agent LLM 在 tool arguments 中直接给出（nodes + edges），工具负责：
 * <ol>
 *   <li>校验：节点 id 唯一、parentId 引用合法、至少存在一个根节点</li>
 *   <li>规范化：重新分配 id 保证唯一、BFS 补全 level、按 level 分配默认色板</li>
 *   <li>发射专用 SSE 事件携带完整图数据（避免 ToolResultTruncator 截断）</li>
 *   <li>返回精简摘要给 LLM（仅 topic + nodeCount + 提示语）</li>
 * </ol>
 * 该工具为只读、无副作用，归类 UTILITY / READ，无需确认门。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateMindMapTool implements ToolHandler {

    private final TraceEventDispatcher dispatcher;

    /** 按 level 分配的默认色板（root=深色，逐级变浅） */
    private static final String[] LEVEL_COLORS = {
            "#4f46e5", // 根节点：靛蓝
            "#0ea5e9", // 一级：天蓝
            "#10b981", // 二级：翠绿
            "#f59e0b", // 三级：琥珀
            "#ef4444", // 四级：红
            "#8b5cf6"  // 五级及以下：紫
    };

    @Override
    public String name() {
        return "generateMindMap";
    }

    @Override
    public String description() {
        return "为学生生成可视化的思维导图。当用户请求整理章节/概念/知识体系结构、"
                + "要求画出思维导图、脑图或概念图时调用。"
                + "传入按树形组织的 nodes（每个节点必须带 id/label/parentId，根节点 parentId 为 null 或空字符串），"
                + "以及可选的 edges（额外跨分支关联，每条含 source/target/可选 label）。"
                + "nodes 必须构成一棵或多棵树，至少存在一个 parentId 为空的根节点。"
                + "工具会校验并规范化结构，前端自动渲染可交互思维导图。";
    }

    @Override
    public Class<?> requestType() {
        return GenerateMindMapRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.UTILITY;
    }

    @Override
    public ToolCategory.RiskLevel riskLevel() {
        return ToolCategory.RiskLevel.READ;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        GenerateMindMapRequest req = (GenerateMindMapRequest) request;

        // ---- 1. 基础校验 ----
        if (req.getTopic() == null || req.getTopic().isBlank()) {
            return ToolResult.error("topic 不能为空，请提供思维导图的主题");
        }
        List<MindMapNodeDto> rawNodes = req.getNodes();
        if (rawNodes == null || rawNodes.isEmpty()) {
            return ToolResult.error("nodes 不能为空，请提供至少一个节点");
        }

        // ---- 2. 校验节点字段 + 收集原始 id ----
        Set<String> seenOriginalIds = new HashSet<>();
        for (int i = 0; i < rawNodes.size(); i++) {
            MindMapNodeDto n = rawNodes.get(i);
            if (n.getLabel() == null || n.getLabel().isBlank()) {
                return ToolResult.error("第 " + (i + 1) + " 个节点的 label 不能为空");
            }
            if (n.getId() == null || n.getId().isBlank()) {
                // 自动补一个 id
                n.setId("node-" + i);
            }
            if (!seenOriginalIds.add(n.getId())) {
                return ToolResult.error("存在重复的节点 id: " + n.getId() + "，请保证每个节点 id 唯一");
            }
        }

        // 规范化 parentId：空字符串视为 null（根）
        for (MindMapNodeDto n : rawNodes) {
            if (n.getParentId() != null && n.getParentId().isBlank()) {
                n.setParentId(null);
            }
        }

        // 校验 parentId 引用存在
        for (MindMapNodeDto n : rawNodes) {
            if (n.getParentId() != null && !seenOriginalIds.contains(n.getParentId())) {
                return ToolResult.error("节点 " + n.getId() + " 的 parentId=" + n.getParentId()
                        + " 不存在于节点列表中");
            }
        }

        // 至少一个根节点
        boolean hasRoot = rawNodes.stream().anyMatch(n -> n.getParentId() == null);
        if (!hasRoot) {
            return ToolResult.error("nodes 中不存在根节点（parentId 为空的节点），无法构成树形结构");
        }

        // ---- 3. 规范化：重新分配 id，同步 parentId 引用 ----
        Map<String, String> oldToNewId = new HashMap<>();
        List<MindMapNodeDto> normalized = new ArrayList<>(rawNodes.size());
        for (int i = 0; i < rawNodes.size(); i++) {
            MindMapNodeDto src = rawNodes.get(i);
            String newId = "n" + i;
            oldToNewId.put(src.getId(), newId);

            MindMapNodeDto out = new MindMapNodeDto();
            out.setId(newId);
            out.setLabel(src.getLabel());
            // parentId 留待二轮映射（此时新 id 表尚未完全建立）
            out.setParentId(src.getParentId());
            out.setColor(src.getColor());
            out.setNote(src.getNote());
            normalized.add(out);
        }
        // 二轮：映射 parentId
        for (MindMapNodeDto out : normalized) {
            if (out.getParentId() != null) {
                out.setParentId(oldToNewId.get(out.getParentId()));
            }
        }

        // ---- 4. BFS 补全 level + 检测环 ----
        Map<String, Integer> levelMap = new HashMap<>();
        Map<String, List<MindMapNodeDto>> childrenByParent = new HashMap<>();
        List<MindMapNodeDto> roots = new ArrayList<>();
        for (MindMapNodeDto n : normalized) {
            if (n.getParentId() == null) {
                roots.add(n);
                levelMap.put(n.getId(), 0);
            } else {
                childrenByParent.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
            }
        }
        Queue<MindMapNodeDto> queue = new LinkedList<>(roots);
        int visited = 0;
        while (!queue.isEmpty()) {
            MindMapNodeDto cur = queue.poll();
            visited++;
            int childLevel = levelMap.get(cur.getId()) + 1;
            List<MindMapNodeDto> children = childrenByParent.get(cur.getId());
            if (children != null) {
                for (MindMapNodeDto c : children) {
                    if (levelMap.containsKey(c.getId())) {
                        // 已被访问 → 环或被多个父节点引用
                        return ToolResult.error("节点 " + c.getId() + " 存在循环引用或多个父节点，无法构成树");
                    }
                    levelMap.put(c.getId(), childLevel);
                    queue.add(c);
                }
            }
        }
        if (visited != normalized.size()) {
            return ToolResult.error("存在未连通的节点（可能存在环），无法构成完整树形结构");
        }
        // 写回 level + 默认 color
        for (MindMapNodeDto n : normalized) {
            int lvl = levelMap.getOrDefault(n.getId(), 0);
            n.setLevel(lvl);
            if (n.getColor() == null || n.getColor().isBlank()) {
                n.setColor(LEVEL_COLORS[Math.min(lvl, LEVEL_COLORS.length - 1)]);
            }
        }

        // ---- 5. 规范化 edges ----
        List<MindMapEdgeDto> normalizedEdges = new ArrayList<>();
        if (req.getEdges() != null) {
            for (MindMapEdgeDto e : req.getEdges()) {
                if (e.getSource() == null || e.getTarget() == null) {
                    continue;
                }
                String srcNew = oldToNewId.get(e.getSource());
                String tgtNew = oldToNewId.get(e.getTarget());
                if (srcNew == null || tgtNew == null) {
                    continue; // 跳过引用不存在的边
                }
                MindMapEdgeDto out = new MindMapEdgeDto();
                out.setSource(srcNew);
                out.setTarget(tgtNew);
                out.setLabel(e.getLabel());
                normalizedEdges.add(out);
            }
        }

        // ---- 6. 构造与契约一致的 payload ----
        List<Map<String, Object>> nodePayloads = new ArrayList<>(normalized.size());
        for (MindMapNodeDto n : normalized) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("label", n.getLabel());
            m.put("parentId", n.getParentId());
            m.put("level", n.getLevel());
            m.put("color", n.getColor());
            if (n.getNote() != null && !n.getNote().isBlank()) {
                m.put("note", n.getNote());
            }
            nodePayloads.add(m);
        }
        List<Map<String, Object>> edgePayloads = new ArrayList<>(normalizedEdges.size());
        for (MindMapEdgeDto e : normalizedEdges) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", e.getSource());
            m.put("target", e.getTarget());
            if (e.getLabel() != null && !e.getLabel().isBlank()) {
                m.put("label", e.getLabel());
            }
            edgePayloads.add(m);
        }
        Map<String, Object> mindMapData = new LinkedHashMap<>();
        mindMapData.put("topic", req.getTopic());
        mindMapData.put("nodes", nodePayloads);
        mindMapData.put("edges", edgePayloads);
        mindMapData.put("layout", "mindmap");

        log.info("[GenerateMindMap] userId={}, topic={}, nodes={}, edges={}",
                context.getUserId(), req.getTopic(), normalized.size(), normalizedEdges.size());

        // ---- 7. 发射专用 SSE 事件（完整数据，不经过 ToolResultTruncator）----
        if (context.getRuntimeContext() != null && context.getEmitter() != null) {
            dispatcher.emit(context.getEmitter(), context.getRuntimeContext(),
                    AgentEventType.MINDMAP_CREATED, mindMapData);
        }

        // ---- 8. 返回精简摘要给 LLM ----
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("success", true);
        summary.put("topic", req.getTopic());
        summary.put("nodeCount", normalized.size());
        summary.put("edgeCount", normalizedEdges.size());
        summary.put("message", "思维导图已生成并在前端渲染，包含 " + normalized.size()
                + " 个节点。请基于该导图给用户简短说明，不要重复罗列节点。");
        return ToolResult.success(summary);
    }

    // ======================== 请求 DTO（嵌套静态类，沿用 CreateLearningPlanTool 风格）========================

    @Data
    public static class GenerateMindMapRequest {
        @dev.langchain4j.model.output.structured.Description("思维导图主题/根节点标题，如'数据结构-线性表'")
        private String topic;

        @dev.langchain4j.model.output.structured.Description("节点列表，按树形组织。每个节点含 id(唯一)/label(显示文本)/parentId(父节点id，根节点为null或空)/level(可选)/color(可选)/note(可选备注)")
        private List<MindMapNodeDto> nodes;

        @dev.langchain4j.model.output.structured.Description("额外跨分支关联边列表（可选）。每条含 source(源节点id)/target(目标节点id)/label(可选边标签)。父子关系无需在此重复声明")
        private List<MindMapEdgeDto> edges;
    }

    @Data
    public static class MindMapNodeDto {
        @dev.langchain4j.model.output.structured.Description("节点唯一标识，如'n0'、'node-1'")
        private String id;

        @dev.langchain4j.model.output.structured.Description("节点显示文本")
        private String label;

        @dev.langchain4j.model.output.structured.Description("父节点 id；根节点填 null 或空字符串")
        private String parentId;

        @dev.langchain4j.model.output.structured.Description("层级，根=0；可留空，工具会自动补全")
        private Integer level;

        @dev.langchain4j.model.output.structured.Description("自定义颜色（十六进制），可留空由工具按层级分配")
        private String color;

        @dev.langchain4j.model.output.structured.Description("节点备注，可选")
        private String note;
    }

    @Data
    public static class MindMapEdgeDto {
        @dev.langchain4j.model.output.structured.Description("源节点 id")
        private String source;

        @dev.langchain4j.model.output.structured.Description("目标节点 id")
        private String target;

        @dev.langchain4j.model.output.structured.Description("边标签，可选")
        private String label;
    }
}
