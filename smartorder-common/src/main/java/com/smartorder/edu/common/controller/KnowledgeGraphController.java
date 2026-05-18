package com.smartorder.edu.common.controller;

import com.smartorder.edu.common.model.vo.KnowledgeGraphVO;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.service.INeo4jKnowledgeGraphService;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱控制器
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/knowledge")
@Tag(name = "知识图谱管理", description = "知识图谱节点的查询接口")
public class KnowledgeGraphController {


    private final INeo4jKnowledgeGraphService neo4jKnowledgeGraphService;



    @GetMapping("/file/{fileId}")
    @Operation(summary = "获取文件的知识图谱", description = "根据文件ID返回该文件的知识图谱节点和边")
    public Result<KnowledgeGraphVO> getFileKnowledgeGraph(@PathVariable Long fileId) {
        KnowledgeGraphVO graph = neo4jKnowledgeGraphService.getFileKnowledgeGraph(fileId);
        return Result.success(graph);
    }


}
