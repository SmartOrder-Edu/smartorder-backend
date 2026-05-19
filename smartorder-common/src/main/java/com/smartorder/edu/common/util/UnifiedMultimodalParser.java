package com.smartorder.edu.common.util;

import com.baidu.aip.ocr.AipOcr;
import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.entity.FileParseRecord;
import com.smartorder.edu.common.model.vo.Result;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import ws.schild.jave.Encoder;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;
import ws.schild.jave.info.MultimediaInfo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;


/**
 * 统一多模态文件解析工具类
 * 核心功能：支持文本(TXT)、PDF、Word(DOC/DOCX)、音频(MP3)、视频(MP4)、图片(JPG/PNG)的解析
 * 解析能力：
 *  1. 文本/文档：提取纯文本内容 + 生成摘要
 *  2. 音频：转写为文字（阿里云ASR）
 *  3. 视频：提取音频转写 + 抽关键帧(Base64) + 生成摘要
 *  4. 图片：提取元信息 + OCR文字识别（百度OCR） + Base64编码
 * 设计原则：
 *  1. 统一入口：parseWithResult对外提供标准化Result响应
 *  2. 异常分层：细分不同文件类型的异常码，便于前端精准提示
 *  3. 资源清理：自动清理视频解析产生的临时音频文件
 *  4. 容错设计：OCR/ASR识别失败不中断整体解析流程
 *  ppt
 *  execl
 */
@Component // 新增注解，让Spring管理该类
@Slf4j
public class UnifiedMultimodalParser {
    // ===================== 全局配置项（需替换为实际值） =====================


    /** 百度文字识别(OCR)应用ID - 可复用ASR的APP_ID */
    @Value("${baidu.ocr.appId}")
     String BAIDU_OCR_APP_ID ;
    /** 百度文字识别(OCR)API密钥 - 可复用ASR的API_KEY */
    @Value("${baidu.ocr.apiKey}")
     String BAIDU_OCR_API_KEY ;
    /** 百度文字识别(OCR)密钥 - 可复用ASR的SECRET_KEY */
    @Value("${baidu.ocr.secretKey}")
     String BAIDU_OCR_SECRET_KEY ;

    /** 视频解析：最大抽帧数量 - 避免抽帧过多占用内存 */
    private static final int MAX_VIDEO_FRAMES = 2;
    /** 视频解析：抽帧时间间隔（秒） - 均匀抽取关键帧 */
    private static final int VIDEO_FRAME_INTERVAL = 10;
    /** 内容摘要：最大长度限制 - 避免摘要过长影响展示 */
    private static final int SUMMARY_MAX_LENGTH = 600;

    // ===================== 百度OCR客户端初始化 =====================
    /**
     * 获取百度OCR客户端实例（单例模式，每次调用创建新实例保证线程安全）
     * @return AipOcr OCR客户端对象
     */

    @Autowired // 补充Autowired注解（若缺失）
    private AliyunFileTransUtil aliyunFileTransUtil;

    // 注入已有的OSS工具类
    @Autowired // 补充Autowired注解（若缺失）
    private   AliOSSUtils aliOSSUtils;


    private static final String MIME_TEXT = "text/plain";
    private static final String MIME_MARKDOWN = "text/markdown";
    private static final String MIME_PDF = "application/pdf";
    private static final String MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String MIME_DOC = "application/msword";
    private static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String MIME_XLS = "application/vnd.ms-excel";
    private static final String MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String MIME_PPT = "application/vnd.ms-powerpoint";
    /**
     * 获取百度OCR客户端实例（单例模式，每次调用创建新实例保证线程安全）
     * @return AipOcr OCR客户端对象
     */
    private AipOcr getOcrClient() {
        // 初始化OCR客户端，传入配置的密钥信息
        AipOcr client = new AipOcr(BAIDU_OCR_APP_ID, BAIDU_OCR_API_KEY, BAIDU_OCR_SECRET_KEY);
        // 设置连接超时时间（毫秒）- 3秒超时
        client.setConnectionTimeoutInMillis(3000);
        // 设置socket超时时间（毫秒）- 60秒超时，适配大图片识别
        client.setSocketTimeoutInMillis(60000);
        return client;
    }


    // ===================== 新增：多模态上传+解析统合入口 =====================
    /**
     * 接收MultipartFile，先上传OSS，再转存临时文件进行解析，解析后立即清理
     */
    public Result<FileParseRecord> parseAndUpload(MultipartFile multipartFile) {
        File tempFile = null;
        try {
            // 1. 上传原始文件到 OSS
            String ossPath = "multimodal/upload/" + System.currentTimeMillis() + "/";
            String ossUrl = aliOSSUtils.upload(multipartFile, ossPath);

            // 2. 转存为本地临时文件供解析内核使用（JAVE/PDFBox 等库必须依赖物理文件）
            tempFile = File.createTempFile("parse_", "_" + multipartFile.getOriginalFilename());
            multipartFile.transferTo(tempFile);

            // 3. 执行核心解析逻辑
            FileParseRecord record = parse(tempFile);

            // 4. 将 OSS URL 注入到解析记录中
            record.setOssUrl(ossUrl);

            return Result.success(record, "文件解析并上传成功");

        } catch (Exception e) {
            // 细分异常捕获
            if (e.getMessage().contains("PDF文件已加密")) return Result.fail(ResultCode.PDF_ENCRYPTED_ERROR, e.getMessage());
            if (e.getMessage().contains("音频文件超过")) return Result.fail(ResultCode.AUDIO_SIZE_EXCEED_LIMIT, e.getMessage());
            return Result.fail(ResultCode.SYSTEM_ERROR, "文件处理失败：" + e.getMessage());
        } finally {
            // 5. 强制清理本地临时文件，做到“用完即焚”
            if (tempFile != null) {
                cleanTempFile(tempFile);
            }
        }
    }


    // ===================== 对外暴露的核心方法 =====================
    /**
     * 统一文件解析入口（对外提供标准化Result响应）
     * @param file 待解析的文件对象
     * @return Result<FileParseRecord> 解析结果（包含成功/失败状态+解析数据）
     */
    public Result<FileParseRecord> parseWithResult(File file) {
        try {
            // 调用核心解析方法
            FileParseRecord record = parse(file);
            // 解析成功：返回成功结果+解析数据
            return Result.success(record, "文件解析成功");
        } catch (FileNotFoundException e) {
            // 文件不存在异常：返回对应错误码
            return Result.fail(ResultCode.FILE_NOT_FOUND, e.getMessage());
        } catch (UnsupportedOperationException e) {
            // 不支持的文件类型异常：返回对应错误码
            return Result.fail(ResultCode.UNSUPPORTED_FILE_TYPE, e.getMessage());
        } catch (Exception e) {
            // 通用异常：细分不同场景的错误提示
            if (e.getMessage().contains("PDF文件已加密")) {
                return Result.fail(ResultCode.PDF_ENCRYPTED_ERROR, e.getMessage());
            } else if (e.getMessage().contains("音频文件超过5MB限制")) {
                return Result.fail(ResultCode.AUDIO_SIZE_EXCEED_LIMIT, e.getMessage());
            } else if (e.getMessage().contains("音频转写失败")) {
                return Result.fail(ResultCode.ASR_TRANSCRIBE_ERROR, e.getMessage());
            } else if (e.getMessage().contains("视频解析")) {
                return Result.fail(ResultCode.VIDEO_PARSE_ERROR, e.getMessage());
            } else if (e.getMessage().contains("图片解析") || e.getMessage().contains("OCR识别")) {
                return Result.fail(ResultCode.SYSTEM_ERROR, "图片解析/OCR识别失败：" + e.getMessage());
            } else if (e.getMessage().contains("音频转写失败")) {
                return Result.fail(ResultCode.ASR_TRANSCRIBE_ERROR, e.getMessage());
            }
            else {
                // 其他未知异常：返回通用系统错误
                return Result.fail(ResultCode.SYSTEM_ERROR, "文件解析失败：" + e.getMessage());
            }
        }
    }

    // ===================== 内部核心解析逻辑 =====================
    /**
     * 内部文件解析核心方法（处理具体解析逻辑）
     * @param file 待解析的文件对象
     * @return FileParseRecord 解析结果封装对象
     * @throws Exception 抛出各类解析异常（由外层parseWithResult统一捕获）
     */
    public FileParseRecord parse(File file) throws Exception {
        if (!file.exists() || !file.isFile()) {
            throw new FileNotFoundException("文件不存在或不是有效文件：" + file.getAbsolutePath());
        }

        String fileType = detectFileType(file);
        String fileName = file.getName();
        String rawContent = "";

        // 根据文件类型分发到对应解析方法
        switch (fileType) {
            case "text/plain":
                rawContent = parseText(file);
                break;
            case "text/markdown":
                rawContent = parseMarkdown(file);
                break;
            case "application/pdf":
                rawContent = parsePdf(file);
                break;
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
            case "application/msword":
                rawContent = parseWord(file);
                break;
            case "audio/mpeg":
                rawContent = parseAudio(file);
                break;
            case "video/mp4":
                // 剔除了Base64，现在只需返回视频的摘要文本
                rawContent = parseVideo(file);
                break;

            case "image/jpeg":
            case "image/png":
                // 剔除了Base64，现在只需返回图片元信息+OCR结果
                rawContent = parseImage(file);
                break;
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
            case "application/vnd.ms-excel":
                rawContent = parseExcel(file);
                break;
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
            case "application/ms-powerpoint":
                rawContent = parsePpt(file);
                break;
            default:
                throw new UnsupportedOperationException("不支持的文件类型：" + fileType);
        }

        String contentSummary = generateContentSummary(rawContent, fileType);
        // 注意：构造函数移除了 frameBase64，这里直接使用全参数构造或 setter (OSS URL由上层填充)
        return new FileParseRecord(fileName, fileType, contentSummary, rawContent, null);
    }

    // ===================== 基础工具方法 =====================
    /**
     * 检测文件类型（后缀名+文件头双重检测，避免后缀伪装）
     * @param file 待检测文件
     * @return String 文件MIME类型
     * @throws Exception 文件读取异常
     */
    private String detectFileType(File file) throws Exception {
        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".txt")) return MIME_TEXT;
        if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) return MIME_MARKDOWN;

        if (fileName.endsWith(".pdf")) return MIME_PDF;

        if (fileName.endsWith(".docx")) return MIME_DOCX;
        if (fileName.endsWith(".doc")) return MIME_DOC;

        if (fileName.endsWith(".xlsx")) return MIME_XLSX;
        if (fileName.endsWith(".xls")) return MIME_XLS;

        if (fileName.endsWith(".pptx")) return MIME_PPTX;
        if (fileName.endsWith(".ppt")) return MIME_PPT;

        if (fileName.endsWith(".mp3") || fileName.endsWith(".wav")) return "audio/mpeg";

        // webm 不建议直接判成 audio，webm 可能是音频也可能是视频
        if (fileName.endsWith(".mp4") || fileName.endsWith(".mkv") || fileName.endsWith(".webm")) {
            return "video/mp4";
        }

        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
        if (fileName.endsWith(".png")) return "image/png";
        if (fileName.endsWith(".bmp")) return "image/bmp";
        if (fileName.endsWith(".gif")) return "image/gif";

        byte[] header = new byte[4];
        try (FileInputStream fis = new FileInputStream(file)) {
            int len = fis.read(header);
            if (len <= 0) return "unknown";
        }

        String hexHeader = bytesToHex(header).toUpperCase();

        if (hexHeader.startsWith("25504446")) return MIME_PDF; // %PDF
        if (hexHeader.startsWith("FFD8")) return "image/jpeg";
        if (hexHeader.startsWith("89504E47")) return "image/png";
        if (hexHeader.startsWith("424D")) return "image/bmp";
        if (hexHeader.startsWith("47494638")) return "image/gif";

        // Office OpenXML: docx/xlsx/pptx 本质是 zip，文件头一般是 PK
        if (hexHeader.startsWith("504B0304")) {
            if (fileName.endsWith(".docx")) return MIME_DOCX;
            if (fileName.endsWith(".xlsx")) return MIME_XLSX;
            if (fileName.endsWith(".pptx")) return MIME_PPTX;
        }

        return "unknown";
    }
    /**
     * 字节数组转16进制字符串（用于文件头检测）
     * @param bytes 待转换的字节数组
     * @return String 16进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            // 格式化每个字节为2位16进制
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }


    /**
     使用 POI 的 SlideShowExtractor 可以方便地提取幻灯片中的文字
     */
    private String parsePpt(File file) throws Exception {
        try (InputStream is = new FileInputStream(file);
             org.apache.poi.sl.usermodel.SlideShow<?, ?> ss = org.apache.poi.sl.usermodel.SlideShowFactory.create(is)) {

            org.apache.poi.sl.extractor.SlideShowExtractor<?, ?> extractor = new org.apache.poi.sl.extractor.SlideShowExtractor<>(ss);
            return extractor.getText().trim();
        }
    }

    /**
     Excel 解析通常需要遍历工作表（Sheet）和行（Row），将单元格内容拼接成文本
     */
    private String parseExcel(File file) throws Exception {
        StringBuilder content = new StringBuilder();
        try (InputStream is = new FileInputStream(file);
             org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(is)) {

            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(i);
                content.append("工作表: ").append(sheet.getSheetName()).append("\n");
                for (org.apache.poi.ss.usermodel.Row row : sheet) {
                    for (org.apache.poi.ss.usermodel.Cell cell : row) {
                        content.append(cell.toString()).append("\t");
                    }
                    content.append("\n");
                }
            }
        }
        return content.toString().trim();
    }
    /**
     * 生成内容摘要（根据文件类型适配不同的摘要规则）
     * @param rawContent 原始解析内容
     * @param fileType 文件类型
     * @return String 格式化后的摘要
     */
    private String generateContentSummary(String rawContent, String fileType) {
        // 空内容处理
        if (rawContent == null || rawContent.isEmpty()) {
            return "无有效内容";
        }
        // 针对表格和幻灯片，逻辑与文本类相似，但可以更严格地限制长度
        if (fileType.contains("spreadsheetml") || fileType.contains("presentationml")) {
            return rawContent.length() > SUMMARY_MAX_LENGTH
                    ? rawContent.substring(0, SUMMARY_MAX_LENGTH) + "..."
                    : rawContent;
        }
        // 视频/图片：直接截断（避免摘要过长）
        if (fileType.contains("video") || fileType.contains("image")) {
            return rawContent.length() > SUMMARY_MAX_LENGTH ? rawContent.substring(0, SUMMARY_MAX_LENGTH) + "..." : rawContent;
        }
        // 文本类文件：保留开头500+结尾100字符，中间省略
        if (rawContent.length() <= SUMMARY_MAX_LENGTH) {
            return rawContent;
        }
        return rawContent.substring(0, 500) + "...（省略中间内容）..." + rawContent.substring(rawContent.length() - 100);
    }

    /**
     * 清理临时文件（如视频解析生成的临时音频）
     * @param tempFile 临时文件对象
     */
    private void cleanTempFile(File tempFile) {
        if (tempFile.exists()) {
            boolean deleted = tempFile.delete();
            // 清理失败：打印错误日志（不抛出异常，避免影响主流程）
            if (!deleted) {
                System.err.println("临时文件清理失败：" + tempFile.getAbsolutePath());
            }
        }
    }

    // ===================== 各类型文件解析实现 =====================
    /**
     * 解析文本文件（TXT）
     * @param file 文本文件
     * @return String 清洗后的纯文本内容
     * @throws Exception 文件读取异常
     */
    private String parseText(File file) throws Exception {
        // 读取文件内容（UTF-8编码）
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        // 内容清洗：去空行、全角空格、多余空格/换行
        return content.replaceAll("\\n\\s*\\n", "\n")
                .replaceAll("　", " ") // 全角空格转半角
                .replaceAll("\\s+", " ") // 多个空格合并为一个
                .trim(); // 首尾去空格
    }

    /**
     * 解析PDF文件（适配PDFBox 2.0.29版本）
     * @param file PDF文件
     * @return String 提取并清洗后的文本内容
     * @throws Exception PDF读取/解析异常
     */

    private String parsePdf(File file) throws Exception {
        String text = parsePdfText(file);

        if (text != null && text.trim().length() >= 50) {
            return text;
        }

        log.warn("PDF 文本内容较少，尝试 OCR 解析: {}", file.getName());
        return parsePdfByOcr(file);
    }
    private String parsePdfByOcr(File file) throws Exception {
        StringBuilder result = new StringBuilder();

        try (PDDocument document = PDDocument.load(file)) {
            if (document.isEncrypted()) {
                throw new Exception("PDF文件已加密，无法解析");
            }

            PDFRenderer renderer = new PDFRenderer(document);

            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, 200);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "png", baos);

                String ocrText = recognizeImageText(baos.toByteArray());

                result.append("\n\n## 第 ")
                        .append(pageIndex + 1)
                        .append(" 页\n\n")
                        .append(ocrText == null || ocrText.isBlank() ? "本页未识别到有效文字。" : ocrText);
            }
        }

        return result.toString().trim();
    }
    private String parsePdfText(File file) throws Exception {
        try (PDDocument document = PDDocument.load(file)) {
            if (document.isEncrypted()) {
                throw new Exception("PDF文件已加密，无法解析");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(document.getNumberOfPages());

            String text = stripper.getText(document);

            return text.replaceAll("\\n{3,}", "\n\n")
                    .replaceAll("[ \\t]{2,}", " ")
                    .trim();
        }
    }
//    private String parsePdf(File file) throws Exception {
//        // 加载PDF文档（try-with-resources自动关闭资源）
//        try (PDDocument document = PDDocument.load(file)) {
//            // 校验：PDF是否加密
//            if (document.isEncrypted()) {
//                throw new Exception("PDF文件已加密，无法解析");
//            }
//            // 创建PDF文本提取器
//            PDFTextStripper stripper = new PDFTextStripper();
//            // 设置提取页码范围：从第1页到最后一页
//            stripper.setStartPage(1);
//            stripper.setEndPage(document.getNumberOfPages());
//            // 提取文本
//            String text = stripper.getText(document);
//            // 内容清洗：合并多余换行和空格
//            return text.replaceAll("\\n+", "\n")
//                    .replaceAll("\\s{2,}", " ")
//                    .trim();
//        }
//    }

    /**
     * 解析Word文件（兼容DOC和DOCX格式）
     * @param file Word文件
     * @return String 提取并清洗后的文本内容
     * @throws Exception Word读取/解析异常
     */
    private String parseWord(File file) throws Exception {
        String fileName = file.getName().toLowerCase();
        String content;

        // 区分DOCX和DOC格式
        if (fileName.endsWith(".docx")) {
            // 解析DOCX文件（Office 2007+）
            try (XWPFDocument doc = new XWPFDocument(new FileInputStream(file))) {
                XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
                content = extractor.getText();
            }
        } else if (fileName.endsWith(".doc")) {
            // 解析DOC文件（Office 2003-）
            try (HWPFDocument doc = new HWPFDocument(new FileInputStream(file))) {
                WordExtractor extractor = new WordExtractor(doc);
                content = extractor.getText();
            }
        } else {
            throw new Exception("非Word文件：" + fileName);
        }

        // 内容清洗：合并多余换行和空格
        return content.replaceAll("\\n+", "\n")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    /**
     * 解析音频文件（MP3/WAV）- 调用阿里云文件转写（适配长音频）
     * @param file 本地音频文件
     * @return String 转写后的文字内容
     * @throws Exception 音频上传/转写异常
     */
    private String parseAudio(File file) throws Exception {
        long fileSize = file.length();
        // 1. 校验文件大小（最小1KB，避免空文件）
        if (fileSize > 512 * 1024 * 1024) {
            throw new Exception("音频文件超过512MB限制，无法转写");
        }
        if (fileSize < 1024) {
            throw new Exception("音频文件过小（小于1KB），无有效音频内容");
        }

        // 新增：WEBM格式转换为MP3
        File audioFile = file;
        if (file.getName().toLowerCase().endsWith(".webm")) {
            audioFile = convertWebmToMp3(file);
        }

        // 2. （可选）校验音频格式/编码（依赖JAVE工具）
        try {
            MultimediaObject multimediaObject = new MultimediaObject(audioFile);
            MultimediaInfo info = multimediaObject.getInfo();
            if (info.getAudio() == null) {
                throw new Exception("音频文件无音频流（可能是视频伪装/文件损坏）");
            }
            // 校验采样率（阿里云ASR推荐16000Hz）
            int sampleRate = info.getAudio().getSamplingRate();
            if (sampleRate != 16000 && sampleRate != 8000) {
                log.warn("音频采样率{}Hz非推荐值（16000/8000Hz），可能影响转写效果", sampleRate);
            }
        } catch (Exception e) {
            log.error("音频格式校验失败", e);
            throw new Exception("音频文件格式不合法/损坏：" + e.getMessage());
        }

        // 3. 原有上传+转写逻辑（替换为转换后的文件）
        String fileLink = uploadToOss(audioFile);
        if (fileLink == null) {
            throw new Exception("音频文件上传OSS失败");
        }
        String transResult = aliyunFileTransUtil.transcribe(fileLink);
        // 转写结果为空时，明确提示（而非抛异常）
        if (transResult == null) {
            throw new Exception("音频转写失败：未获取到转写结果");
        }
        if (transResult.isEmpty()) {
            return "音频转写完成：未识别到有效文字内容";
        }

        // 清理转换后的临时文件
        if (!audioFile.equals(file)) {
            cleanTempFile(audioFile);
        }
        return transResult;
    }

    // 新增：WEBM转MP3工具方法
    private File convertWebmToMp3(File webmFile) throws Exception {
        // 创建临时MP3文件
        File mp3File = new File(webmFile.getParent(), "temp_audio_" + System.currentTimeMillis() + ".mp3");
        // JAVE音频转换配置
        AudioAttributes audioAttr = new AudioAttributes();
        audioAttr.setCodec("libmp3lame");
        audioAttr.setBitRate(128000);
        audioAttr.setChannels(1);
        audioAttr.setSamplingRate(16000);

        EncodingAttributes encAttr = new EncodingAttributes();
        encAttr.setOutputFormat("mp3");
        encAttr.setAudioAttributes(audioAttr);

        // 执行转换
        Encoder encoder = new Encoder();
        encoder.encode(new MultimediaObject(webmFile), mp3File, encAttr);
        return mp3File;
    }



    /**
     * 本地文件上传到阿里云OSS（示例实现）
     * @param file 本地文件
     * @return OSS文件URL
     */
    private String uploadToOss(File file) throws IOException {
        // 1. 将File转换为MultipartFile（使用Spring的MockMultipartFile）
        FileInputStream inputStream = new FileInputStream(file);
        MultipartFile multipartFile = new MockMultipartFile(
                "file", // 参数名，与OSS工具类接收的参数名一致
                file.getName(), // 文件名
                null, // ContentType，若OSS工具类需要可根据文件类型设置
                inputStream // 文件流
        );

        // 2. 调用OSS工具类上传
        String analyzeUrl = aliOSSUtils.upload(multipartFile, "analyze/" + System.currentTimeMillis() + "_" + file.getName());

        inputStream.close(); // 关闭流
        return analyzeUrl;
    }

    /**
     * 解析视频文件（MP4）- 提取音频转写 + 抽关键帧
     * @param file MP4视频文件
     * @return Map<String, String> 包含摘要和关键帧Base64
     * @throws Exception 视频解析/音频提取异常
     */
    private String parseVideo(File file) throws Exception {
        MultimediaObject multimediaObject = new MultimediaObject(file);
        MultimediaInfo info = multimediaObject.getInfo();
        long durationSec = info.getDuration() / 1000;

        // 1. 解析音频（你原有的逻辑）
        File tempAudio = new File(file.getParent(), "temp_audio_" + System.currentTimeMillis() + ".mp3");
        String audioText = "";
        try {
            extractAudioFromVideo(file, tempAudio);
            audioText = parseAudio(tempAudio);
        } catch (Exception e) {
            log.error("视频音频解析失败", e);
            audioText = "音频解析失败：" + e.getMessage();
        } finally {
            cleanTempFile(tempAudio);
        }

        // 2. 解析画面：抽取关键帧并进行 OCR 识别
        String frameOcrText = "";
        try {
            frameOcrText = extractAndOcrVideoFrames(file, durationSec);
        } catch (Exception e) {
            log.error("视频画面解析失败", e);
            frameOcrText = "画面解析失败：" + e.getMessage();
        }

        // 3. 聚合结果
        return String.format("视频时长：%d秒。\n【音频转写内容】：%s\n【画面提取文字】：%s",
                durationSec,
                audioText,
                frameOcrText.isEmpty() ? "未识别到画面文字" : frameOcrText);
    }

    /**
     * 新增：利用 JavaCV 从视频中均匀抽取关键帧，并调用现有的百度 OCR 方法
     */
    private String extractAndOcrVideoFrames(File videoFile, long durationSec) throws Exception {
        StringBuilder ocrResultBuilder = new StringBuilder();

        // 如果视频太短（比如少于1秒），直接不抽帧
        if (durationSec <= 0) {
            return "";
        }

        // 计算抽帧间隔：尽量均匀分布，且不超过你定义的 MAX_VIDEO_FRAMES (2张)
        int maxFrames = MAX_VIDEO_FRAMES; // 你之前定义的常量 2
        long intervalSec = Math.max(1, durationSec / (maxFrames + 1));

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile)) {
            grabber.start();
            Java2DFrameConverter converter = new Java2DFrameConverter();

            int framesExtracted = 0;
            // 按秒数步进，抽取关键画面
            for (long currentSec = intervalSec; currentSec < durationSec && framesExtracted < maxFrames; currentSec += intervalSec) {
                // 将 grabber 定位到指定的时间戳（微秒）
                grabber.setVideoTimestamp(currentSec * 1000000L);
                Frame frame = grabber.grabImage();

                if (frame != null) {
                    // 将 Frame 转换为 BufferedImage
                    BufferedImage bufferedImage = converter.getBufferedImage(frame);
                    if (bufferedImage != null) {
                        // 将 BufferedImage 转换为 byte[] 以适配你的 OCR 接口
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(bufferedImage, "jpg", baos);
                        byte[] imageBytes = baos.toByteArray();

                        // 调用你已经写好的百度 OCR 方法
                        String text = recognizeImageText(imageBytes);
                        if (text != null && !text.isEmpty()) {
                            ocrResultBuilder.append("[第").append(currentSec).append("秒画面]: ").append(text).append("；");
                        }
                    }
                    framesExtracted++;
                }
            }
            grabber.stop();
        }

        return ocrResultBuilder.toString().trim();
    }

    /**
     * 解析图片文件（JPG/PNG）- 提取元信息 + OCR识别 + Base64编码
     * @param file 图片文件
     * @return Map<String, String> 包含完整信息和Base64编码
     * @throws Exception 图片读取/OCR识别异常
     */
    private String parseImage(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {

            BufferedImage image = ImageIO.read(bis);
            if (image == null) {
                throw new Exception("图片解析失败：无法识别的图片格式或损坏的文件");
            }

            int width = image.getWidth();
            int height = image.getHeight();
            long fileSize = file.length();
            String format = file.getName().substring(file.getName().lastIndexOf(".") + 1).toUpperCase();

            String metaInfo = String.format(
                    "图片格式：%s，宽度：%d像素，高度：%d像素，文件大小：%d字节（%.2fKB）",
                    format, width, height, fileSize, (double) fileSize / 1024
            );

            // 直接调用OCR识别图片文字
            byte[] imageBytes = Files.readAllBytes(file.toPath());
            String ocrText = recognizeImageText(imageBytes);

            return metaInfo + "；OCR识别文字：" + (ocrText.isEmpty() ? "无" : ocrText);

        } catch (Exception e) {
            throw new Exception("图片解析失败：" + e.getMessage());
        }
    }

    /**
     * 从视频中提取音频（MP4转MP3）- 参数微调
     * @param videoFile 视频文件
     * @param audioFile 输出的音频文件
     * @throws Exception 音频提取异常
     */
    private void extractAudioFromVideo(File videoFile, File audioFile) throws Exception {
        AudioAttributes audioAttr = new AudioAttributes();
        audioAttr.setCodec("libmp3lame"); // 显式指定编码器，兼容性更好
        audioAttr.setBitRate(128000);
        audioAttr.setChannels(1); // ASR通常单声道即可，减少文件体积
        audioAttr.setSamplingRate(16000); // 直接转为 16k，适配 ASR 推荐参数

        EncodingAttributes encAttr = new EncodingAttributes();
        encAttr.setOutputFormat("mp3");
        encAttr.setAudioAttributes(audioAttr);

        Encoder encoder = new Encoder();
        encoder.encode(new MultimediaObject(videoFile), audioFile, encAttr);
    }


    /**
     * 调用百度OCR识别图片文字（通用高精度识别）
     * @param imageBytes 图片字节数组
     * @return String 识别后的文字内容（空字符串表示识别失败/无文字）
     */
    private String recognizeImageText(byte[] imageBytes) {
        // 前置校验：图片字节数组为空直接返回空
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("OCR识别失败：图片字节数组为空");
            return "";
        }

        // 获取OCR客户端
        AipOcr client = getOcrClient();
        // 配置OCR参数
        HashMap<String, String> options = new HashMap<>();
        options.put("language_type", "CHN_ENG");
        options.put("detect_direction", "true");
        options.put("detect_language", "true");
        options.put("probability", "true");

        // 调用百度OCR通用高精度识别接口
        JSONObject res = client.basicAccurateGeneral(imageBytes, options);
        StringBuilder ocrText = new StringBuilder();

        // 解析OCR响应结果（增加空值/字段缺失防护）
        try {
            // 1. 先判断响应是否为空
            if (res == null) {
                log.warn("OCR识别失败：接口返回空响应");
                return "";
            }
            // 2. 检查是否包含error_code字段
            if (res.has("error_code")) {
                int errorCode = res.getInt("error_code");
                if (errorCode == 0) {
                    // 识别成功：遍历所有识别结果
                    JSONArray wordsResult = res.optJSONArray("words_result"); // 用opt避免字段缺失报错
                    if (wordsResult != null && wordsResult.length() > 0) {
                        for (int i = 0; i < wordsResult.length(); i++) {
                            JSONObject wordObj = wordsResult.optJSONObject(i);
                            if (wordObj != null && wordObj.has("words")) {
                                ocrText.append(wordObj.getString("words")).append(" ");
                            }
                        }
                    }
                } else {
                    // 识别失败：打印错误日志
                    String errorMsg = res.optString("error_msg", "未知错误");
                    log.error("OCR识别失败：错误码{}，错误信息{}", errorCode, errorMsg);
                }
            } else {
                // 无error_code字段：兼容其他响应格式（如直接返回结果）
                JSONArray wordsResult = res.optJSONArray("words_result");
                if (wordsResult != null && wordsResult.length() > 0) {
                    for (int i = 0; i < wordsResult.length(); i++) {
                        JSONObject wordObj = wordsResult.optJSONObject(i);
                        if (wordObj != null && wordObj.has("words")) {
                            ocrText.append(wordObj.getString("words")).append(" ");
                        }
                    }
                } else {
                    log.warn("OCR响应无error_code字段，且无识别结果，响应内容：{}", res.toString());
                }
            }
        } catch (Exception e) {
            // 捕获所有解析异常，避免中断图片解析流程
            log.error("OCR响应解析异常", e);
        }

        // 返回清洗后的识别结果（去首尾空格）
        return ocrText.toString().trim();
    }

    /**
     * 解析 Markdown 文件
     *
     * Markdown 文件本身已经是结构化文本，不能像普通 TXT 一样压缩所有空白，
     * 否则会破坏标题、列表、表格、代码块。
     */
    private String parseMarkdown(File file) throws Exception {
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        // 去掉 UTF-8 BOM
        content = content.replaceFirst("^\\uFEFF", "");

        // 统一换行符
        content = content.replace("\r\n", "\n").replace("\r", "\n");

        // 最多只压缩连续 3 个以上空行为 2 个空行，保留 Markdown 段落结构
        content = content.replaceAll("\\n{3,}", "\n\n");

        return content.trim();
    }

}