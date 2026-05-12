package com.smartorder.edu.common.util;

import com.alibaba.fastjson.JSONObject;
import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阿里云文件转写（FileTrans）工具类
 * 适配长音频/大文件的语音识别（替代实时ASR）
 */
@Slf4j
@Component
public class AliyunFileTransUtil {
    // 从配置文件读取（建议替换为application.yml配置）
    @Value("${aliyun.asr.accessKeyId}")
    private String accessKeyId;
    @Value("${aliyun.asr.accessKeySecret}")
    private String accessKeySecret;
    @Value("${aliyun.asr.appKey}")
    private String appKey;

    // 阿里云文件转写固定配置
    private static final String REGION_ID = "cn-shanghai";
    private static final String ENDPOINT_NAME = "cn-shanghai";
    private static final String PRODUCT = "nls-filetrans";
    private static final String DOMAIN = "filetrans.cn-shanghai.aliyuncs.com";
    private static final String API_VERSION = "2018-08-17";
    private static final String ACTION_SUBMIT_TASK = "SubmitTask";
    private static final String ACTION_GET_RESULT = "GetTaskResult";

    // 状态常量
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_QUEUEING = "QUEUEING";
    private static final String STATUS_SUCCESS_NO_FRAGMENT = "SUCCESS_WITH_NO_VALID_FRAGMENT";
    /**
     * 初始化阿里云客户端
     */
    private IAcsClient initAcsClient() {
        try {
            DefaultProfile.addEndpoint(ENDPOINT_NAME, REGION_ID, PRODUCT, DOMAIN);
        } catch (ClientException e) {
            log.error("初始化阿里云Endpoint失败", e);
            throw new RuntimeException(e);
        }
        DefaultProfile profile = DefaultProfile.getProfile(REGION_ID, accessKeyId, accessKeySecret);
        return new DefaultAcsClient(profile);
    }

    /**
     * 提交文件转写任务
     * @param fileLink 音频文件的OSS/公网URL
     * @return 任务ID（用于查询结果）
     */
    public String submitTransTask(String fileLink) {
        IAcsClient client = initAcsClient();
        CommonRequest postRequest = new CommonRequest();
        // 设置请求参数
        postRequest.setDomain(DOMAIN);
        postRequest.setVersion(API_VERSION);
        postRequest.setAction(ACTION_SUBMIT_TASK);
        postRequest.setProduct(PRODUCT);
        postRequest.setMethod(MethodType.POST);

        // 构造任务JSON
        JSONObject taskObject = new JSONObject();
        taskObject.put("appkey", appKey);          // 应用appKey
        taskObject.put("file_link", fileLink);     // 音频文件URL
        taskObject.put("version", "4.0");          // 4.0版本（支持更多特性）
        taskObject.put("enable_words", true);      // 输出词级别信息
        postRequest.putBodyParameter("Task", taskObject.toJSONString());

        // 提交请求
        try {
            CommonResponse response = client.getCommonResponse(postRequest);
            if (response.getHttpStatus() != 200) {
                log.error("提交转写任务失败，HTTP状态码：{}", response.getHttpStatus());
                return null;
            }
            JSONObject result = JSONObject.parseObject(response.getData());
            if (STATUS_SUCCESS.equals(result.getString("StatusText"))) {
                String taskId = result.getString("TaskId");
                log.info("转写任务提交成功，TaskId：{}", taskId);
                return taskId;
            } else {
                log.error("提交转写任务失败：{}", result.toJSONString());
                return null;
            }
        } catch (ClientException e) {
            log.error("提交转写任务异常", e);
            return null;
        }
    }

    /**
     * 轮询查询转写结果
     * @param taskId 任务ID
     * @return 转写文本（空表示失败/无结果）
     */
    public String getTransResult(String taskId) {
        IAcsClient client = initAcsClient();
        CommonRequest getRequest = new CommonRequest();
        // 设置查询参数
        getRequest.setDomain(DOMAIN);
        getRequest.setVersion(API_VERSION);
        getRequest.setAction(ACTION_GET_RESULT);
        getRequest.setProduct(PRODUCT);
        getRequest.setMethod(MethodType.GET);
        getRequest.putQueryParameter("TaskId", taskId);

        // 轮询查询（间隔10秒，建议设置超时时间）
        int maxRetry = 60; // 最大轮询次数（10秒*60=10分钟）
        int retryCount = 0;
        while (retryCount < maxRetry) {
            try {
                CommonResponse response = client.getCommonResponse(getRequest);
                if (response.getHttpStatus() != 200) {
                    log.error("查询转写结果失败，HTTP状态码：{}", response.getHttpStatus());
                    break;
                }
                JSONObject result = JSONObject.parseObject(response.getData());
                String statusText = result.getString("StatusText");
                switch (statusText) {
                    case STATUS_RUNNING:
                    case STATUS_QUEUEING:
                        // 任务中，继续轮询
                        retryCount++;
                        log.info("转写任务中（{}），第{}次轮询...", statusText, retryCount);
                        Thread.sleep(10000); // 10秒间隔
                        break;
                    case STATUS_SUCCESS:
                        // 转写成功，解析结果
                        String transResult = result.getString("Result");
                        if (transResult == null || transResult.isEmpty()) {
                            log.warn("转写成功但无结果（音频可能为空/静音）");
                            return "";
                        }
                        // 解析JSON格式的结果，提取纯文本
                        JSONObject resultJson = JSONObject.parseObject(transResult);
                        StringBuilder text = new StringBuilder();
                        resultJson.getJSONArray("Sentences").forEach(sentence -> {
                            text.append(((JSONObject) sentence).getString("Text")).append(" ");
                        });
                        return text.toString().trim();
                    case STATUS_SUCCESS_NO_FRAGMENT:
                        // 成功但无有效片段，返回空字符串（而非null）
                        log.warn("转写任务完成：音频无有效可识别的语音片段（TaskId：{}）", taskId);
                        return "";
                    default:
                        // 任务真失败
                        log.error("转写任务失败：{}", result.toJSONString());
                        break;
                }
            } catch (ClientException e) {
                log.error("查询转写结果异常", e);
                break;
            } catch (InterruptedException e) {
                log.error("轮询线程被中断", e);
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.error("转写任务超时（超过{}次轮询）", maxRetry);
        return null;
    }

    /**
     * 快捷方法：提交任务并获取转写结果（同步）
     * @param fileLink 音频文件URL
     * @return 转写文本
     */
    public String transcribe(String fileLink) {
        String taskId = submitTransTask(fileLink);
        if (taskId == null) {
            return null;
        }
        return getTransResult(taskId);
    }
}