package com.smartorder.edu.common.util;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;

/**
 * 阿里云OSS工具类
 * @author 谢光湘
 * @since 2026/2/26
 */
@Component
public class AliOSSUtils {
    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.accessKeyId}")
    private String accessKeyId;

    @Value("${aliyun.oss.accessKeySecret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucketName}")
    private String bucketName;

    /**
     * 全局OSS客户端实例，实现连接复用
     */
    private OSS ossClient;

    /**
     * Bean初始化时创建OSS客户端（仅创建一次）
     * @PostConstruct 注解确保在Spring注入完属性后执行
     */
    @PostConstruct
    public void initOssClient(){
        this.ossClient = new OSSClientBuilder().build(endpoint,accessKeyId,accessKeySecret);
    }


    /**
     * 实现通过文件流上传到OSS
     * @param inputStream 文件输入流（例如从网络请求或本地读取的流）
     * @param originalFilename 原始文件名称（必须包含后缀，例如 "test.pptx"）
     * @param filePath 文件存储路径（例如："system/docs/"）
     * @return 上传到OSS的文件访问路径
     * @throws IOException 文件上传或流关闭异常
     */
    public String uploadStream(InputStream inputStream, String originalFilename, String filePath) throws IOException {
        // 1. 校验文件名和后缀
        if (originalFilename == null || !originalFilename.contains(".")) {
            throw new IllegalArgumentException("文件名称不合法，缺少后缀");
        }

        // 2. 黑名单校验
        String extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        List<String> blackList = Arrays.asList(".jsp", ".php", ".exe", ".sh", ".bat");
        if (blackList.contains(extension)) {
            throw new IllegalArgumentException("不允许上传此类型文件");
        }

        // 3. 生成新的唯一文件名
        String fileName = UUID.randomUUID() + extension;

        // 确保目录路径以 '/' 结尾，防止路径拼接错误
        if (filePath != null && !filePath.isEmpty() && !filePath.endsWith("/")) {
            filePath += "/";
        }
        String objectName = (filePath == null ? "" : filePath) + fileName;
        String url;

        // 4. 上传并确保流被正确关闭
        try {
            // 上传文件流到 OSS
            ossClient.putObject(bucketName, objectName, inputStream);

            // 拼接文件访问路径
            url = endpoint.split("//")[0] + "//" + bucketName + "." + endpoint.split("//")[1] + "/" + objectName;
        } finally {
            // 无论上传是否成功，最后都要关闭传入的流，防止内存或连接泄漏
            if (inputStream != null) {
                inputStream.close();
            }
        }

        return url;
    }

    /**
     * 实现上传图片到OSS
     * @param multipartFile 前端传来的文件对象
     * @param filePath 文件路径（例如：user/avatar/）
     * @return 上传到OSS的文件路径
     * @throws IOException 文件上传异常
     */
    public String upload(MultipartFile multipartFile,String filePath) throws IOException {
        return uploadStream(multipartFile.getInputStream(), multipartFile.getOriginalFilename(), filePath);
    }


    // ==================== 分片上传核心功能 ====================

    /**
     * 初始化分片上传任务
     * @param objectKey OSS存储路径+文件名（例如：user/files/xxx.pdf）
     * @return UploadId 分片上传唯一标识
     */
    public String initiateMultipartUpload(String objectKey) {
        InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(bucketName, objectKey);
        InitiateMultipartUploadResult result = ossClient.initiateMultipartUpload(request);
        return result.getUploadId();
    }

    /**
     * 上传单个分片（后端直传方式）
     * @param objectKey OSS存储路径+文件名
     * @param uploadId 分片上传ID
     * @param partNumber 分片编号（从1开始，最大10000）
     * @param inputStream 分片数据流
     * @return PartETag 分片ETag，用于最后合并
     */
    public PartETag uploadPart(String objectKey, String uploadId, int partNumber, InputStream inputStream) {
        UploadPartRequest uploadPartRequest = new UploadPartRequest();
        uploadPartRequest.setBucketName(bucketName);
        uploadPartRequest.setKey(objectKey);
        uploadPartRequest.setUploadId(uploadId);
        uploadPartRequest.setPartNumber(partNumber);
        uploadPartRequest.setInputStream(inputStream);

        // 设置分片大小（可选，OSS会自动计算）
        // uploadPartRequest.setPartSize(2 * 1024 * 1024); // 2MB

        UploadPartResult result = ossClient.uploadPart(uploadPartRequest);
        return new PartETag(partNumber, result.getETag());
    }

    /**
     * 上传单个分片（字节数组方式）
     * @param objectKey OSS存储路径+文件名
     * @param uploadId 分片上传ID
     * @param partNumber 分片编号
     * @param data 分片字节数据
     * @return PartETag 分片ETag
     */
    public PartETag uploadPart(String objectKey, String uploadId, int partNumber, byte[] data) {
        try (InputStream inputStream = new ByteArrayInputStream(data)) {
            return uploadPart(objectKey, uploadId, partNumber, inputStream);
        } catch (IOException e) {
            throw new RuntimeException("分片上传失败", e);
        }
    }

    /**
     * 合并所有分片，完成上传
     * @param objectKey OSS存储路径+文件名
     * @param uploadId 分片上传ID
     * @param partETags 所有分片的ETag列表（必须按partNumber排序）
     * @return 文件访问URL
     */
    public String completeMultipartUpload(String objectKey, String uploadId, List<PartETag> partETags) {
        // 按分片号排序（OSS要求必须有序）
        partETags.sort(Comparator.comparingInt(PartETag::getPartNumber));

        CompleteMultipartUploadRequest request = new CompleteMultipartUploadRequest(
                bucketName, objectKey, uploadId, partETags
        );
        ossClient.completeMultipartUpload(request);

        // 返回文件访问URL
        return endpoint.split("//")[0] + "//" + bucketName + "." + endpoint.split("//")[1] + "/" + objectKey;
    }

    /**
     * 查询已上传的分片列表（用于断点续传）
     * @param objectKey OSS存储路径+文件名
     * @param uploadId 分片上传ID
     * @return 已上传的分片编号列表
     */
    public List<Integer> listUploadedParts(String objectKey, String uploadId) {
        ListPartsRequest request = new ListPartsRequest(bucketName, objectKey, uploadId);
        PartListing partListing = ossClient.listParts(request);

        List<Integer> uploadedPartNumbers = new ArrayList<>();
        for (PartSummary part : partListing.getParts()) {
            uploadedPartNumbers.add(part.getPartNumber());
        }
        return uploadedPartNumbers;
    }

    /**
     * 取消/放弃分片上传任务（清理临时分片）
     * @param objectKey OSS存储路径+文件名
     * @param uploadId 分片上传ID
     */
    public void abortMultipartUpload(String objectKey, String uploadId) {
        AbortMultipartUploadRequest request = new AbortMultipartUploadRequest(
                bucketName, objectKey, uploadId
        );
        ossClient.abortMultipartUpload(request);
    }

    /**
     * 生成预签名URL（用于前端直传OSS分片）
     * @param objectKey OSS存储路径+文件名
     * @param uploadId 分片上传ID
     * @param partNumber 分片编号
     * @param expirationSeconds URL有效期（秒）
     * @return 预签名上传URL
     */
    public String generatePresignedUrl(String objectKey, String uploadId, int partNumber, long expirationSeconds) {
        Date expiration = new Date(System.currentTimeMillis() + expirationSeconds * 1000);

        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                bucketName, objectKey, HttpMethod.PUT
        );
        request.setExpiration(expiration);
        request.addQueryParameter("uploadId", uploadId);
        request.addQueryParameter("partNumber", String.valueOf(partNumber));

        URL url = ossClient.generatePresignedUrl(request);
        return url.toString();
    }

    /**
     * 分片上传完整流程（后端直传方式）
     * @param inputStream 文件输入流
     * @param objectKey OSS存储路径+文件名
     * @param chunkSize 分片大小（字节），建议2MB-5MB
     * @return 文件访问URL
     * @throws IOException 上传异常
     */
    public String multipartUpload(InputStream inputStream, String objectKey, long chunkSize) throws IOException {
        // 1. 初始化分片上传
        String uploadId = initiateMultipartUpload(objectKey);
        List<PartETag> partETags = new ArrayList<>();
        int partNumber = 1;

        try {
            byte[] buffer = new byte[(int) chunkSize];
            int bytesRead;

            // 2. 循环读取并上传分片
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                if (bytesRead == buffer.length) {
                    // 完整分片
                    PartETag partETag = uploadPart(objectKey, uploadId, partNumber,
                            new ByteArrayInputStream(buffer));
                    partETags.add(partETag);
                } else {
                    // 最后一个分片（不足chunkSize）
                    byte[] lastChunk = Arrays.copyOf(buffer, bytesRead);
                    PartETag partETag = uploadPart(objectKey, uploadId, partNumber,
                            new ByteArrayInputStream(lastChunk));
                    partETags.add(partETag);
                }
                partNumber++;
            }

            // 3. 合并分片
            return completeMultipartUpload(objectKey, uploadId, partETags);

        } catch (Exception e) {
            // 上传失败，取消分片上传任务
            abortMultipartUpload(objectKey, uploadId);
            throw new IOException("分片上传失败", e);
        }
    }

    /**
     * 分片上传（从MultipartFile）
     * @param multipartFile 文件对象
     * @param filePath 文件路径（例如：user/files/）
     * @param chunkSize 分片大小（字节）
     * @return 文件访问URL
     * @throws IOException 上传异常
     */
    public String multipartUpload(MultipartFile multipartFile, String filePath, long chunkSize) throws IOException {
        String originalFilename = multipartFile.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            throw new IllegalArgumentException("文件名称不合法，缺少后缀");
        }

        String fileName = UUID.randomUUID() + originalFilename.substring(originalFilename.lastIndexOf("."));
        String objectKey = filePath + fileName;

        try (InputStream inputStream = multipartFile.getInputStream()) {
            return multipartUpload(inputStream, objectKey, chunkSize);
        }
    }


    /**
     * 删除OSS中的文件
     *
     @param fileUrl 文件URL，例如：https://bucket.oss-cn-beijing.aliyuncs.com/teacher/resources/1001/test.pdf
     @throws Exception 删除异常
     */
    public void deleteFile(String fileUrl) {
        String objectName = parseOssFileNameFromUrl(fileUrl);
        ossClient.deleteObject(bucketName, objectName);
    }

    /**
     * 从OSS URL中解析出存储文件名（含路径）
     * 示例URL：https://bucket.oss-cn-beijing.aliyuncs.com/teacher/resources/1001/test.pdf
     * 解析结果：teacher/resource/1001/test.pdf
     * @param fileUrl OSS文件访问URL
     * @return OSS存储路径+文件名
     */
    public String parseOssFileNameFromUrl(String fileUrl) {
        // 如果已经是相对路径（objectKey），直接返回
        String domain = bucketName + "." + endpoint.split("//")[1];
        int domainIndex = fileUrl.indexOf(domain);
        if (domainIndex == -1) {
            // 不包含域名，说明已经是 objectKey
            return fileUrl;
        }
        // 包含域名，截取域名后的部分
        return fileUrl.substring(domainIndex + domain.length() + 1);
    }

    /**
     * Bean销毁时关闭oss客户端（应用停止时执行）
     * @PreDestroy 注解确保在Soring容器销毁Bean之前执行
     */
    @PreDestroy
    public void destroyOssClient(){
        if(this.ossClient != null){
            this.ossClient.shutdown();
        }
    }


    /**
     * 从OSS下载文件，返回输入流
     * @param fileUrl OSS文件访问URL，例如：https://bucket.oss-cn-beijing.aliyuncs.com/teacher/resources/1001/test.pdf
     * @return 文件输入流
     */
    public InputStream downloadFile(String fileUrl) {
        String objectName = parseOssFileNameFromUrl(fileUrl);
        return ossClient.getObject(bucketName, objectName).getObjectContent();
    }

    /**
     * 从OSS下载文件到本地
     * @param fileUrl OSS文件访问URL
     * @param localPath 本地保存路径（包含文件名）
     */
    public void downloadFileToLocal(String fileUrl, String localPath) {
        String objectName = parseOssFileNameFromUrl(fileUrl);
        ossClient.getObject(new GetObjectRequest(bucketName, objectName), new File(localPath));
    }

    /**
     * 上传音频二进制数据到 OSS
     * @param audioBytes 音频二进制数据
     * @param originalFilename 原始文件名(包括文件扩展名)
     * @param filePath 文件保存路径(格式：xxx/xxx/)
     */
    public String uploadAudio(byte[] audioBytes, String originalFilename, String filePath) throws IOException {
        // 1. 将 byte[] 包装为 InputStream (OSS putObject 需要 InputStream)
        InputStream inputStream = new ByteArrayInputStream(audioBytes);
        // 2. 生成文件名
        String fileName = UUID.randomUUID() + "-" + originalFilename;
        // 3. 创建 OSS 客户端
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        ossClient.putObject(bucketName,filePath + fileName, inputStream);
        //拼接文件访问路径
        String url = endpoint.split("//")[0] + "//" + bucketName + "." + endpoint.split("//")[1] + "/" + filePath + fileName;

        // 4. 关闭 OSS 客户端
        ossClient.shutdown();

        // 5. 返回文件访问路径
        return url;
    }

    /**
     * 获取带5分钟签名的URL
     * @param fileUrl OSS文件访问URL，例如：https://bucket.oss-cn-beijing.aliyuncs.com/teacher/resources/1001/test.pdf
     * @param expiryTime URL有效期（毫秒），例如：5分钟 = 5 * 60 * 1000
     * @return 带5分钟签名的URL
     */
    public String getSignedUrlWithExpiry(String fileUrl, long expiryTime) {
        // 例如把 https://xxx.aliyuncs.com/teacher/xxx.xlsx 截取出 teacher/xxx.xlsx
        String objectKey = fileUrl;
        if (fileUrl.contains(".com/")) {
            objectKey = fileUrl.substring(fileUrl.lastIndexOf(".com/") + 5);
        }
        // 设置URL过期时间为5分钟
        Date expiration = new Date(System.currentTimeMillis() + expiryTime);
        
        // 创建预签名URL请求
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                bucketName, objectKey, HttpMethod.GET
        );
        request.setExpiration(expiration);
        
        // 生成预签名URL
        URL url = ossClient.generatePresignedUrl(request);
        return url.toString();
    }

    /**
     * 通过文件URL获取带5分钟签名的URL
     * @param fileUrl OSS文件访问URL，例如：https://bucket.oss-cn-beijing.aliyuncs.com/teacher/resources/1001/test.pdf
     * @param expiryTime URL有效期（毫秒），例如：5分钟 = 5 * 60 * 1000
     * @return 带5分钟签名的URL
     */
    public String getSignedUrlWith5MinExpiryFromUrl(String fileUrl, long expiryTime) {
        String objectKey = parseOssFileNameFromUrl(fileUrl);
        return getSignedUrlWithExpiry(objectKey, expiryTime);
    }

    /**
     * 从 OSS 获取该 uploadId 下所有已经上传成功的 PartETag
     */
    public List<PartETag> listAllPartETags(String objectKey, String uploadId) {
        // 创建 ListPartsRequest 请求
        ListPartsRequest request = new ListPartsRequest(bucketName, objectKey, uploadId);
        PartListing partListing = ossClient.listParts(request);

        // 遍历所有已上传的 part，组装成 PartETag 列表
        List<PartETag> partETags = new ArrayList<>();
        for (PartSummary part : partListing.getParts()) {
            partETags.add(new PartETag(part.getPartNumber(), part.getETag()));
        }
        return partETags;
    }

}