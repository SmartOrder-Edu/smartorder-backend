package com.smartorder.edu.common.controller;

import com.smartorder.edu.common.model.vo.KnowledgeGraphVO;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.service.INeo4jKnowledgeGraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 文件知识图谱控制器
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/knowledge")
@Tag(name = "文件知识图谱管理", description = "文件知识图谱节点的查询接口")
public class FileKnowledgeGraphController {

    private final INeo4jKnowledgeGraphService neo4jKnowledgeGraphService;

    @GetMapping("/file/{fileId}")
    @Operation(summary = "获取文件的知识图谱", description = "根据文件ID返回该文件的知识图谱节点和边")
    public Result<KnowledgeGraphVO> getFileKnowledgeGraph(@PathVariable Long fileId) {
        KnowledgeGraphVO graph = neo4jKnowledgeGraphService.getFileKnowledgeGraph(fileId);
        return Result.success(graph);
    }
}
