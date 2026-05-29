package com.smartorder.edu.student.controller;

import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.service.IEvaluationQuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/evaluation/questions")
@Tag(name = "测评出题", description = "测评题目的生成接口")
public class EvaluationQuestionController {

    private final IEvaluationQuestionService evaluationQuestionService;

    // 工业级规范：显式指定 produces 为 text/event-stream
    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "生成测评题目", description = "根据课程、知识点、题型和难度生成题目并保存到题库")
    public SseEmitter generateQuestions(@Validated @RequestBody GenerateQuestionDTO dto) {
        // 设置长超时，因为 Agent 思考可能很久----30 minute
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        emitter.onCompletion(emitter::complete);
        emitter.onTimeout(emitter::complete);

        evaluationQuestionService.generateQuestions(dto,emitter);

        return emitter;
    }
}
/*
//致命缺陷：前端断开连接时，AI 还在偷偷烧你的 Token（未处理取消订阅）
// 不支持下面的代码因为代码-本身就是异步实现测试题,切换了,实现什么,用户等不了这么长的时长
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/evaluation/questions")
@Tag(name = "测评出题", description = "测评题目的生成接口")
public class EvaluationQuestionController {

    private final IEvaluationQuestionService evaluationQuestionService;

    // 工业级规范：显式指定 produces 为 text/event-stream
    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "生成测评题目", description = "根据课程、知识点、题型和难度生成题目并保存到题库")
    public SseEmitter generateQuestions(@Validated @RequestBody GenerateQuestionDTO dto) {
        // 1. 设置长超时 (30分钟)
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        // 获取后端的 Agent 响应式流
        Flux<String> questionsStream = evaluationQuestionService.generateQuestions(dto);

        // 2. 关键：切换到弹性线程池执行发送，避免多 Agent 场景下的线程竞争和主线程阻塞
        // 并通过 Disposable 持有订阅关系
        Disposable disposable = questionsStream
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        chunk -> {
                            try {
                                // 标准 SSE 格式，保证前端打字机流畅度
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException e) {
                                log.error("SSE发送失败，可能前端已断开连接: {}", e.getMessage());
                                // 触发异常结束，进入 onError
                                throw new RuntimeException(e);
                            }
                        },
                        error -> {
                            log.error("Agent生成题目过程中发生错误", error);
                            emitter.completeWithError(error);
                        },
                        () -> {
                            log.info("Agent 题目生成完毕，SSE 通道正常关闭");
                            emitter.complete();
                        }
                );

        // 3. 核心精髓：当客户端断开、超时或发生错误时，必须主动销毁（dispose）Flux 流
        // 这行代码能帮你止损由于用户中途关闭页面导致的 Token 浪费！
        Runnable cancelHook = () -> {
            if (!disposable.isDisposed()) {
                log.info("检测到客户端断开连接或超时，正在终止后台 Agent 任务...");
                disposable.dispose(); // 停止 Flux 消费，断开与 AI 的连接
            }
        };

        emitter.onCompletion(cancelHook);
        emitter.onTimeout(cancelHook);
        emitter.onError(ex -> cancelHook.run());

        return emitter;
    }
}*/
