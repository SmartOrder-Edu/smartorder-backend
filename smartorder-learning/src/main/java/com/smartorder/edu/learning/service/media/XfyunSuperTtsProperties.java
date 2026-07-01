package com.smartorder.edu.learning.service.media;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "xfyun.super-tts")
public class XfyunSuperTtsProperties {
    private boolean enabled = false;
    private String appId = "";
    private String apiPassword = "";
    private String websocketUrl = "wss://cbm01.cn-huabei-1.xf-yun.com/v1/private/mcd9m97e6";
    private String voice = "x5_lingfeiyi_flow";
    private Integer speed = 50;
    private Integer volume = 50;
    private Integer pitch = 50;
    private Integer timeoutSeconds = 90;
}
