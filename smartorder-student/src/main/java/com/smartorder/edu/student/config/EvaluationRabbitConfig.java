package com.smartorder.edu.student.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 测评模块 RabbitMQ 配置
 *
 * <p>拓扑结构：
 * <pre>
 *                     evaluation.grading.direct (DirectExchange)
 *                    ┌─────────────────────────────────────┐
 *                    │                                     │
 *            subjective.key                        code.key
 *                    │                                     │
 *                    ▼                                     ▼
 *    subjective.queue (DLX→evaluation.grading.dlx)   code.queue (DLX→evaluation.grading.dlx)
 *                    │                                     │
 *                    ▼                                     ▼
 *         SubjectiveGradingConsumer              CodeGradingConsumer
 *                    │                                     │
 *                    └────────── grading.completed ────────┘
 *                                   │
 *                    evaluation.report.direct
 *                                   │
 *                            report.key
 *                                   │
 *                                   ▼
 *                      evaluation.report.generate.queue
 *                                   │
 *                                   ▼
 *                       ReportGenerateConsumer
 * </pre>
 * </p>
 *
 * @author SmartOrder
 * @since 2026-07-11
 */
@Configuration
public class EvaluationRabbitConfig {

    // ==================== 交换机 ====================

    public static final String GRADING_EXCHANGE = "evaluation.grading.direct";
    public static final String REPORT_EXCHANGE = "evaluation.report.direct";
    public static final String DLX_EXCHANGE = "evaluation.grading.dlx";

    // ==================== 路由键 ====================

    public static final String RK_SUBJECTIVE = "evaluation.grading.subjective.key";
    public static final String RK_CODE = "evaluation.grading.code.key";
    public static final String RK_REPORT = "evaluation.report.generate.key";

    // ==================== 队列名 ====================

    public static final String Q_SUBJECTIVE = "evaluation.grading.subjective.queue";
    public static final String Q_CODE = "evaluation.grading.code.queue";
    public static final String Q_REPORT = "evaluation.report.generate.queue";
    public static final String Q_SUBJECTIVE_DLQ = "evaluation.grading.subjective.dlq";
    public static final String Q_CODE_DLQ = "evaluation.grading.code.dlq";

    // ==================== Exchange Bean ====================

    @Bean
    public DirectExchange evaluationGradingExchange() {
        return new DirectExchange(GRADING_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange evaluationReportExchange() {
        return new DirectExchange(REPORT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange evaluationGradingDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    // ==================== 主观题判分队列（含 DLX） ====================

    @Bean
    public Queue subjectiveGradingQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", Q_SUBJECTIVE_DLQ);
        return QueueBuilder.durable(Q_SUBJECTIVE)
                .withArguments(args)
                .build();
    }

    @Bean
    public Binding subjectiveGradingBinding() {
        return BindingBuilder.bind(subjectiveGradingQueue())
                .to(evaluationGradingExchange())
                .with(RK_SUBJECTIVE);
    }

    @Bean
    public Queue subjectiveDlq() {
        return QueueBuilder.durable(Q_SUBJECTIVE_DLQ).build();
    }

    @Bean
    public Binding subjectiveDlqBinding() {
        return BindingBuilder.bind(subjectiveDlq())
                .to(evaluationGradingDlxExchange())
                .with(Q_SUBJECTIVE_DLQ);
    }

    // ==================== 代码题判分队列（含 DLX） ====================

    @Bean
    public Queue codeGradingQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", Q_CODE_DLQ);
        return QueueBuilder.durable(Q_CODE)
                .withArguments(args)
                .build();
    }

    @Bean
    public Binding codeGradingBinding() {
        return BindingBuilder.bind(codeGradingQueue())
                .to(evaluationGradingExchange())
                .with(RK_CODE);
    }

    @Bean
    public Queue codeDlq() {
        return QueueBuilder.durable(Q_CODE_DLQ).build();
    }

    @Bean
    public Binding codeDlqBinding() {
        return BindingBuilder.bind(codeDlq())
                .to(evaluationGradingDlxExchange())
                .with(Q_CODE_DLQ);
    }

    // ==================== 报告生成队列 ====================

    @Bean
    public Queue reportGenerateQueue() {
        return QueueBuilder.durable(Q_REPORT).build();
    }

    @Bean
    public Binding reportGenerateBinding() {
        return BindingBuilder.bind(reportGenerateQueue())
                .to(evaluationReportExchange())
                .with(RK_REPORT);
    }
}
