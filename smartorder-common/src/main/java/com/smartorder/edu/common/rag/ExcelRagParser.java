package com.smartorder.edu.common.rag;

import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.model.rag.RagChildChunk;
import com.smartorder.edu.common.model.rag.RagParentChunk;
import com.smartorder.edu.common.model.rag.RagParseResult;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

/**
 * Excel 文件 RAG 解析器
 *
 * 设计思想：
 * 1. 一个 Excel 文件整体作为一个父块 ParentChunk
 * 2. Excel 中的每个 Sheet 工作表按行分批切成多个子块 ChildChunk
 * 3. 每个子块保留 sheetName、行范围、父块 key 等元信息，方便后续检索、溯源和展示
 */
@Service
public class ExcelRagParser {

    /**
     * 每 20 行切成一个子块
     *
     * 例如：
     * 第 1-20 行 -> 一个 child chunk
     * 第 21-40 行 -> 一个 child chunk
     */
    private static final int ROW_BATCH_SIZE = 20;

    /**
     * 解析 Excel 文件，生成 RAG 解析结果
     *
     * @param file       实际上传后的 Excel 文件
     * @param fileEntity 数据库中的知识库文件实体，包含 fileId、userId、knowledgeBaseId、原始文件名等信息
     * @return RagParseResult，包含 markdown、父块、子块等数据
     */
    public RagParseResult parse(File file, KnowledgeFile fileEntity) throws Exception {

        // 当前 Excel 文件对应的父块唯一标识
        // 所有子块都会通过 parentChunkKey 关联到这个父块
        String parentKey = UUID.randomUUID().toString();

        // 原始文件名，例如：学生成绩表.xlsx
        String fileName = fileEntity.getOriginalName();

        // 父块集合：当前实现中，一个 Excel 文件只生成一个父块
        List<RagParentChunk> parents = new ArrayList<>();

        // 子块集合：每个 Sheet 按 20 行分批生成多个子块
        List<RagChildChunk> children = new ArrayList<>();

        // 用来拼接整个 Excel 文件的 markdown 文本
        StringBuilder wholeMarkdown = new StringBuilder();

        // 使用 try-with-resources，自动关闭文件流和 workbook，避免资源泄漏
        try (InputStream is = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(is)) {

            /*
             * 创建父块：
             * 父块代表整个 Excel 文件，不存放具体每一行数据，
             * 主要存放文件级别的信息和摘要。
             */
            RagParentChunk parent = RagParentChunk.builder()
                    .parentChunkKey(parentKey)
                    .title(fileName)
                    .headingPath(fileName)
                    .content("Excel 文件：" + fileName)
                    .summary("Excel 文件，共 " + workbook.getNumberOfSheets() + " 个工作表")
                    .chunkIndex(0)
                    .sourceLocator("excel")
                    .metadata(Map.of(
                            "sourceType", "excel",
                            "fileId", fileEntity.getId(),
                            "knowledgeBaseId", fileEntity.getKnowledgeBaseId(),
                            "chunkRole", "parent"
                    ))
                    .build();

            parents.add(parent);

            // 子块下标，用于记录 child chunk 的顺序
            int childIndex = 0;

            /*
             * 遍历 Excel 中的所有工作表 Sheet
             */
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {

                // 获取当前工作表
                Sheet sheet = workbook.getSheetAt(sheetIndex);

                // 当前工作表名称
                String sheetName = sheet.getSheetName();

                /*
                 * 读取当前 Sheet 的所有非空行
                 * 返回结构：
                 * [
                 *   ["姓名", "年龄", "成绩"],
                 *   ["张三", "18", "90"],
                 *   ["李四", "19", "85"]
                 * ]
                 */
                List<List<String>> rows = readSheetRows(sheet);

                // 如果当前 Sheet 没有有效数据，直接跳过
                if (rows.isEmpty()) continue;

                // 当前工作表的简要说明
                String sheetSummary = "工作表 " + sheetName + "，共 " + rows.size() + " 行数据";

                /*
                 * 把整个 Sheet 追加到完整 markdown 中
                 * 这里是给 rawText / markdown 使用的完整文本
                 */
                wholeMarkdown.append("\n\n## 工作表：").append(sheetName).append("\n\n")
                        .append(sheetRowsToMarkdown(sheetName, rows, 1, rows.size()));

                /*
                 * 按 ROW_BATCH_SIZE 分批切分行数据
                 *
                 * 例如 rows.size() = 45，ROW_BATCH_SIZE = 20：
                 * 第一次：start=0,  end=20  -> 第 1-20 行
                 * 第二次：start=20, end=40  -> 第 21-40 行
                 * 第三次：start=40, end=45  -> 第 41-45 行
                 */
                for (int start = 0; start < rows.size(); start += ROW_BATCH_SIZE) {

                    // 当前批次结束位置，防止超过总行数
                    int end = Math.min(start + ROW_BATCH_SIZE, rows.size());

                    // 当前批次的数据
                    List<List<String>> part = rows.subList(start, end);

                    // 把当前批次行数据转成 markdown 文本，作为子块内容
                    String content = sheetRowsToMarkdown(sheetName, part, start + 1, end);

                    /*
                     * 子块元信息：
                     * 这些字段非常重要，后续向量检索命中后，
                     * 可以知道答案来自哪个文件、哪个 Sheet、哪几行。
                     */
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("sourceType", "excel");
                    metadata.put("fileName", fileName);
                    metadata.put("sheetName", sheetName);
                    metadata.put("sheetIndex", sheetIndex);
                    metadata.put("rowStart", start + 1);
                    metadata.put("rowEnd", end);
                    metadata.put("sheetSummary", sheetSummary);
                    metadata.put("parentChunkKey", parentKey);

                    /*
                     * 创建子块：
                     * 一个子块对应某个 Sheet 中的一段行数据。
                     */
                    children.add(RagChildChunk.builder()
                            .chunkKey(UUID.randomUUID().toString())
                            .parentChunkKey(parentKey)
                            .title("工作表：" + sheetName + "，第 " + (start + 1) + "-" + end + " 行")
                            .headingPath(fileName + "/" + sheetName)
                            .content(content)
                            .summary(sheetSummary)
                            .keywords(Collections.emptyList())
                            .sourceType("excel")
                            .chunkIndex(childIndex++)
                            .sectionLevel(2)
                            .chunkType("table")
                            .metadata(metadata)
                            .build());
                }
            }
        }

        // 去掉前后空白，得到最终 markdown
        String markdown = wholeMarkdown.toString().trim();

        /*
         * 返回最终解析结果
         * 后续你可以把：
         * 1. markdown 存 MySQL
         * 2. parentChunks 存父块表
         * 3. childChunks 向量化后入向量数据库
         */
        return RagParseResult.builder()
                .fileId(fileEntity.getId())
                .userId(fileEntity.getUserId())
                .knowledgeBaseId(fileEntity.getKnowledgeBaseId())
                .fileName(fileName)
                .fileType("excel")
                .rawText(markdown)
                .markdown(markdown)
                .documentSummary("Excel 表格解析完成")
                .parentChunks(parents)
                .childChunks(children)
                .build();
    }

    /**
     * 读取一个 Sheet 中的所有有效行
     *
     * 处理逻辑：
     * 1. 遍历 Sheet 中的每一行
     * 2. 遍历当前行中的每个单元格
     * 3. 使用 DataFormatter 把单元格内容统一转成字符串
     * 4. 跳过整行为空的数据
     *
     * 注意：
     * 这里并不是严格意义上的“数据清洗”，
     * 更准确地说是“数据读取 + 空行过滤 + 单元格格式统一”。
     */
    private List<List<String>> readSheetRows(Sheet sheet) {

        // 每一行是 List<String>，整个 Sheet 是 List<List<String>>
        List<List<String>> rows = new ArrayList<>();

        /*
         * DataFormatter 的作用：
         * 保留 Excel 中用户看到的显示效果。
         *
         * 例如：
         * 日期单元格 -> 转成日期字符串
         * 百分比单元格 -> 转成百分比字符串
         * 数字单元格 -> 转成显示格式的字符串
         */
        DataFormatter formatter = new DataFormatter();

        // 遍历 Sheet 中所有有记录的行
        for (Row row : sheet) {

            List<String> cells = new ArrayList<>();

            // 当前行最后一个单元格的列号
            short lastCellNum = row.getLastCellNum();

            // 如果当前行没有任何单元格，跳过
            if (lastCellNum < 0) continue;

            // 从第 0 列遍历到最后一列
            for (int i = 0; i < lastCellNum; i++) {

                Cell cell = row.getCell(i);

                // 如果单元格为空，使用空字符串占位，保证列位置不乱
                cells.add(cell == null ? "" : formatter.formatCellValue(cell));
            }

            // 判断当前行是否至少有一个非空单元格
            boolean notEmpty = cells.stream().anyMatch(s -> s != null && !s.isBlank());

            // 只保留非空行
            if (notEmpty) rows.add(cells);
        }

        return rows;
    }

    /**
     * 把多行表格数据转换成 markdown 文本
     *
     * 当前格式：
     * ### Sheet1 第 1-20 行
     *
     * - 第 1 行：列1=姓名；列2=年龄；列3=成绩；
     * - 第 2 行：列1=张三；列2=18；列3=90；
     */
    private String sheetRowsToMarkdown(String sheetName, List<List<String>> rows, int rowStart, int rowEnd) {

        StringBuilder sb = new StringBuilder();

        // 当前 markdown 小标题，说明属于哪个 Sheet、哪几行
        sb.append("### ").append(sheetName)
                .append(" 第 ").append(rowStart)
                .append("-").append(rowEnd)
                .append(" 行\n\n");

        // 遍历当前批次中的每一行
        for (int i = 0; i < rows.size(); i++) {

            // 实际 Excel 行号 = 当前批次起始行号 + 当前下标
            sb.append("- 第 ").append(rowStart + i).append(" 行：");

            List<String> row = rows.get(i);

            // 遍历当前行中的每一列
            for (int j = 0; j < row.size(); j++) {
                sb.append("列").append(j + 1)
                        .append("=")
                        .append(row.get(j))
                        .append("；");
            }

            sb.append("\n");
        }

        return sb.toString();
    }
}