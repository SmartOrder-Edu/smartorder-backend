package com.smartorder.edu.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 旧版返回码枚举。
 *
 * @deprecated 从外部抽奖项目复制而来的遗留枚举（含 {@code STRATEGY_RULE_WEIGHT_IS_NULL}
 * 等与本项目无关的抽奖策略错误码），全仓无任何引用。项目统一用
 * {@link com.smartorder.edu.common.model.vo.Result} 的 code/message 表达返回结果，
 * 不再维护独立返回码枚举。本类可在确认后删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum ResponseCode {

    SUCCESS("0000", "调用成功"),
    UN_ERROR("0001", "调用失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    STRATEGY_RULE_WEIGHT_IS_NULL("ERR_BIZ_001", "业务异常，策略规则中 rule_weight 权重规则已适用但未配置"),
    UN_ASSEMBLED_STRATEGY_ARMORY("ERR_BIZ_002", "抽奖策略配置未装配，请通过IStrategyArmory完成装配"),
    ;

    private String code;
    private String info;

}
