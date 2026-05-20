package com.smartorder.edu.common.rag;

import com.smartorder.edu.common.model.rag.MarkdownChunkResult;
import com.smartorder.edu.common.model.rag.MarkdownSection;
import com.smartorder.edu.common.model.rag.RagChildChunk;
import com.smartorder.edu.common.model.rag.RagParentChunk;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MarkdownChunkService {

    /**
     * 子块最大长度。
     *
     * 子块一般用于向量检索，不建议太长。
     * 太长会导致 embedding 语义变稀释，检索精度下降。
     */
    private static final int MAX_CHILD_LENGTH = 1200;

    /**
     * 子块之间的重叠长度。
     *
     * 作用：
     * 1. 防止句子、概念、代码块被硬切断；
     * 2. 保证上下文连续；
     * 3. 提高向量检索命中率。
     */
    private static final int OVERLAP = 150;

    /**
     * Markdown 分块入口方法。
     *
     * 整体流程：
     * 1. 解析 Markdown 标题结构，得到 MarkdownSection；
     * 2. 根据一级、二级标题构建父块；
     * 3. 根据三级及以下标题构建子块；
     * 4. 对过长子块做滑窗切分；
     * 5. 返回父块、子块、文档摘要。
     */
    public MarkdownChunkResult split(
            String markdown,
            Long fileId,
            Long knowledgeBaseId,
            String fileName,
            String fileType,
            Integer totalPages  // 新增参数：文件总页数
    ) {
        // 1. 按 Markdown 标题解析文档结构
        List<MarkdownSection> sections = parseSections(markdown, fileName);

        // 父块列表：用于保存大章节上下文
        List<RagParentChunk> parents = new ArrayList<>();

        // 子块列表：用于向量数据库检索
        List<RagChildChunk> children = new ArrayList<>();

        // 父块排序索引
        int parentIndex = 0;

        // 子块排序索引
        int childIndex = 0;

        // 2. 找出父块，一般是 # 和 ## 级别的章节
        List<MarkdownSection> parentSections = buildParentSections(sections, fileName);

        // 3. 遍历每一个父章节，生成父块和对应的子块
        for (MarkdownSection parentSection : parentSections) {

            // 每个父块生成一个唯一 key，子块通过它关联父块
            String parentKey = UUID.randomUUID().toString();

            // 父块内容
            String parentContent = parentSection.getContent();

            // 4. 构建父块
            RagParentChunk parent = RagParentChunk.builder()
                    .parentChunkKey(parentKey)
                    .title(parentSection.getTitle())
                    .headingPath(parentSection.getHeadingPath())
                    .content(parentContent)

                    // 简单摘要：目前只是截断，后续可以替换成 AI 摘要
                    .summary(simpleSummary(parentContent, 300))

                    .chunkIndex(parentIndex++)
                    .sourceLocator(fileType)

                    // 父块元数据，方便后续根据文件、知识库、来源类型过滤
                    .metadata(Map.of(
                            "fileId", fileId,
                            "knowledgeBaseId", knowledgeBaseId,
                            "fileName", fileName,
                            "sourceType", fileType,
                            "chunkRole", "parent"
                    ))
                    .build();

            parents.add(parent);

            // 5. 找出当前父块下面的子章节
            // 一般是 ### 及以下标题
            List<MarkdownSection> childSections = findChildSections(sections, parentSection);

            // 如果当前父块下面没有三级标题，则直接把父块自身当成子块使用
            // 这样可以避免某些文档只有 # 或 ## 时无法生成子块
            if (childSections.isEmpty()) {
                childSections = List.of(parentSection);
            }

            // 6. 遍历子章节，生成子块
            for (MarkdownSection childSection : childSections) {

                // 如果子章节内容过长，就切成多个小块
                List<String> pieces = splitLongText(
                        childSection.getContent(),
                        MAX_CHILD_LENGTH,
                        OVERLAP
                );

                // 7. 每个 piece 都是一个真正要进入向量数据库的检索块
                for (String piece : pieces) {

                    // 子块元数据
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("fileId", fileId);
                    metadata.put("knowledgeBaseId", knowledgeBaseId);
                    metadata.put("fileName", fileName);
                    metadata.put("sourceType", fileType);

                    // 关键字段：子块关联父块
                    metadata.put("parentChunkKey", parentKey);

                    // 当前子块所在的 Markdown 标题路径
                    metadata.put("headingPath", childSection.getHeadingPath());

                    // 当前子块所属章节标题
                    metadata.put("sectionTitle", childSection.getTitle());

                    // 当前子块所属标题层级
                    metadata.put("sectionLevel", childSection.getLevel());

                    // 标记这是子块
                    metadata.put("chunkRole", "child");

                    children.add(RagChildChunk.builder()

                            // 子块唯一 key
                            .chunkKey(UUID.randomUUID().toString())

                            // 关联父块 key
                            .parentChunkKey(parentKey)

                            .title(childSection.getTitle())
                            .headingPath(childSection.getHeadingPath())

                            // 真正进入向量库 embedding 的文本内容
                            .content(piece)

                            // 简单摘要，目前是截断
                            .summary(simpleSummary(piece, 260))

                            // 关键词暂时为空，后续可以接 AI 提取关键词
                            .keywords(Collections.emptyList())

                            .sourceType(fileType)
                            .chunkIndex(childIndex++)
                            .sectionLevel(childSection.getLevel())

                            // 设置文件总页数
                            .pageNumber(totalPages != null ? totalPages : 1)

                            // 根据文件类型判断子块类型：text / table / image_ocr
                            .chunkType(resolveChunkType(fileType))

                            .metadata(metadata)
                            .build());
                }
            }
        }

        // 8. 返回完整分块结果
        return MarkdownChunkResult.builder()
                .documentSummary(simpleSummary(markdown, 600))
                .parentChunks(parents)
                .childChunks(children)
                .build();
    }

    /**
     * 解析 Markdown 文档结构。
     *
     * 作用：
     * 把 Markdown 按标题切成多个 MarkdownSection。
     *
     * 例如：
     * # 操作系统
     * ## 进程管理
     * ### 进程调度
     *
     * 会解析出多个章节，并给每个章节生成 headingPath。
     */
    private List<MarkdownSection> parseSections(String markdown, String fileName) {
        List<MarkdownSection> sections = new ArrayList<>();

        // 如果 markdown 为空，则生成一个默认章节，避免后续分块失败
        String safeMarkdown = markdown == null || markdown.isBlank()
                ? "# " + fileName + "\n\n## 默认章节\n\n暂无有效内容。"
                : markdown;

        // 按行切分，兼容不同操作系统换行符
        String[] lines = safeMarkdown.split("\\R");

        // 用于记录当前标题路径
        String h1 = "";
        String h2 = "";
        String h3 = "";

        // 当前正在处理的章节
        MarkdownSection current = null;

        // 当前章节的正文缓存
        StringBuilder buffer = new StringBuilder();

        for (String line : lines) {

            // 判断当前行是否是 Markdown 标题
            if (line.matches("^#{1,6}\\s+.*")) {

                // 如果已经有当前章节，说明遇到了新标题
                // 需要先把上一个章节保存起来
                if (current != null) {
                    current.setContent(buffer.toString().trim());
                    sections.add(current);
                    buffer.setLength(0);
                }

                // 统计标题等级，例如 # 是 1，## 是 2
                int level = countHeadingLevel(line);

                // 提取标题文本
                String title = line.replaceFirst("^#{1,6}\\s+", "").trim();

                // 维护当前标题路径状态
                if (level == 1) {
                    h1 = title;
                    h2 = "";
                    h3 = "";
                } else if (level == 2) {
                    h2 = title;
                    h3 = "";
                } else if (level == 3) {
                    h3 = title;
                }

                // 创建新的章节对象
                current = MarkdownSection.builder()
                        .level(level)
                        .title(title)
                        .headingPath(buildHeadingPath(h1, h2, h3, title, level))
                        .build();

                // 把标题行本身也放入章节内容中
                buffer.append(line).append("\n");
            } else {
                // 普通正文行，直接放入当前章节缓存
                buffer.append(line).append("\n");
            }
        }

        // 保存最后一个章节
        if (current != null) {
            current.setContent(buffer.toString().trim());
            sections.add(current);
        }

        // 如果整篇文档没有任何 Markdown 标题，则创建默认章节
        if (sections.isEmpty()) {
            sections.add(MarkdownSection.builder()
                    .level(2)
                    .title("默认章节")
                    .headingPath(fileName + "/默认章节")
                    .content(safeMarkdown)
                    .build());
        }

        return sections;
    }

    /**
     * 构建父块章节。
     *
     * 当前规则：
     * level <= 2 的章节作为父块。
     *
     * 也就是：
     * # 一级标题
     * ## 二级标题
     *
     * 都会成为父块。
     */
    private List<MarkdownSection> buildParentSections(List<MarkdownSection> sections, String fileName) {
        List<MarkdownSection> parents = new ArrayList<>();

        for (MarkdownSection section : sections) {
            if (section.getLevel() <= 2) {
                parents.add(section);
            }
        }

        // 如果没有一级、二级标题，则把所有内容合并成一个父块
        if (parents.isEmpty()) {
            parents.add(MarkdownSection.builder()
                    .level(2)
                    .title(fileName)
                    .headingPath(fileName)
                    .content(joinSectionContent(sections))
                    .build());
        }

        return parents;
    }

    /**
     * 查找某个父块下面的子章节。
     *
     * 当前规则：
     * 1. 从 parent 后面开始找；
     * 2. 遇到同级或更高级标题时停止；
     * 3. parent 范围内 level >= 3 的标题作为子块。
     */
    private List<MarkdownSection> findChildSections(List<MarkdownSection> sections, MarkdownSection parent) {
        List<MarkdownSection> children = new ArrayList<>();

        // 是否已经进入当前父块范围
        boolean inParent = false;

        // 父块标题等级
        int parentLevel = parent.getLevel();

        for (MarkdownSection section : sections) {

            // 找到当前父块，从它后面开始收集子章节
            if (section == parent) {
                inParent = true;
                continue;
            }

            // 如果已经进入父块范围，又遇到同级或更高级标题，说明当前父块结束
            if (inParent && section.getLevel() <= parentLevel) {
                break;
            }

            // 当前父块范围内，三级及以下标题作为子块
            if (inParent && section.getLevel() >= 3) {
                children.add(section);
            }
        }

        return children;
    }

    /**
     * 合并多个章节内容。
     *
     * 当文档没有合适的父块时使用。
     */
    private String joinSectionContent(List<MarkdownSection> sections) {
        StringBuilder sb = new StringBuilder();

        for (MarkdownSection section : sections) {
            sb.append(section.getContent()).append("\n\n");
        }

        return sb.toString().trim();
    }

    /**
     * 长文本滑窗切分。
     *
     * 例如：
     * maxLength = 1200
     * overlap = 150
     *
     * 切分结果类似：
     * 第 1 块：0    - 1200
     * 第 2 块：1050 - 2250
     * 第 3 块：2100 - 3300
     *
     * 这样相邻块之间会共享 150 个字符上下文。
     */
    private List<String> splitLongText(String text, int maxLength, int overlap) {
        List<String> result = new ArrayList<>();

        // 空文本不生成块
        if (text == null || text.isBlank()) return result;

        // 如果文本长度没超过最大长度，直接作为一个块
        if (text.length() <= maxLength) {
            result.add(text);
            return result;
        }

        int start = 0;

        while (start < text.length()) {

            // 当前块结束位置
            int end = Math.min(start + maxLength, text.length());

            // 截取当前块
            result.add(text.substring(start, end));

            // 如果已经切到文本末尾，结束循环
            if (end == text.length()) break;

            // 下一个块从 end - overlap 开始，实现上下文重叠
            start = Math.max(0, end - overlap);
        }

        return result;
    }

    /**
     * 统计 Markdown 标题等级。
     *
     * #      -> 1
     * ##     -> 2
     * ###    -> 3
     */
    private int countHeadingLevel(String line) {
        int count = 0;

        while (count < line.length() && line.charAt(count) == '#') {
            count++;
        }

        return count;
    }

    /**
     * 构建标题路径。
     *
     * 例如：
     * # 操作系统
     * ## 进程管理
     * ### 进程调度
     *
     * headingPath = 操作系统/进程管理/进程调度
     */
    private String buildHeadingPath(String h1, String h2, String h3, String title, int level) {
        List<String> path = new ArrayList<>();

        if (h1 != null && !h1.isBlank()) {
            path.add(h1);
        }

        if (level >= 2 && h2 != null && !h2.isBlank()) {
            path.add(h2);
        }

        if (level >= 3 && h3 != null && !h3.isBlank()) {
            path.add(h3);
        }

        // 如果没有任何路径，则至少放入当前标题
        if (path.isEmpty()) {
            path.add(title);
        }

        return String.join("/", path);
    }

    /**
     * 简单摘要方法。
     *
     * 当前实现：
     * 1. 去掉多余空白；
     * 2. 如果超过 maxLen，就截断并加 ...
     *
     * 注意：
     * 这不是真正的 AI 摘要。
     * 后续可以替换为大模型摘要。
     */
    private String simpleSummary(String text, int maxLen) {
        if (text == null) return "";

        String clean = text.replaceAll("\\s+", " ").trim();

        return clean.length() <= maxLen
                ? clean
                : clean.substring(0, maxLen) + "...";
    }

    /**
     * 根据文件类型判断子块类型。
     *
     * image     -> image_ocr
     * excel     -> table
     * sheet     -> table
     * 其他       -> text
     */
    private String resolveChunkType(String fileType) {
        if (fileType == null) return "text";

        if (fileType.contains("image")) {
            return "image_ocr";
        }

        if (fileType.contains("sheet") || fileType.contains("excel")) {
            return "table";
        }

        return "text";
    }
}