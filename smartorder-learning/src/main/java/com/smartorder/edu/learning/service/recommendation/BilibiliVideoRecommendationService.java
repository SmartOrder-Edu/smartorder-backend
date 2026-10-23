package com.smartorder.edu.learning.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchOrganicResult;
import dev.langchain4j.web.search.WebSearchResults;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BilibiliVideoRecommendationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final Duration FALLBACK_CACHE_TTL = Duration.ofMinutes(15);
    private static final int CACHE_LIMIT = 256;
    private static final String SOURCE_BILIBILI_SEARCH = "bilibili_search";
    private static final String SOURCE_WEB_SEARCH_FALLBACK = "web_search_fallback";
    private static final String SOURCE_SEARCH_FALLBACK = "search_fallback";

    private final ObjectProvider<WebSearchEngine> webSearchEngineProvider;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ConcurrentHashMap<String, CachedRecommendation> cache = new ConcurrentHashMap<>();

    public BilibiliVideoRecommendationService(ObjectProvider<WebSearchEngine> webSearchEngineProvider) {
        this.webSearchEngineProvider = webSearchEngineProvider;
    }

    public RecommendationResult recommend(String knowledgePoint, String reason, int limit) {
        String safeKnowledgePoint = normalizeKnowledgePoint(knowledgePoint);
        int safeLimit = Math.max(1, Math.min(limit, 5));
        String query = safeKnowledgePoint + " 讲解 例题";
        String cacheKey = safeKnowledgePoint.toLowerCase(Locale.ROOT) + "#" + safeLimit;

        CachedRecommendation cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.result().withCacheHit(true);
        }

        RecommendationResult result = fetchFromBilibili(safeKnowledgePoint, reason, query, safeLimit)
                .filter(r -> !r.videos().isEmpty())
                .or(() -> fetchFromWebSearch(safeKnowledgePoint, reason, query, safeLimit))
                .orElseGet(() -> fallback(safeKnowledgePoint, reason, query, safeLimit));
        putCache(cacheKey, result);
        return result;
    }

    public Map<String, Object> preview(RecommendationResult result) {
        return result.videos().stream()
                .findFirst()
                .map(video -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("title", video.title());
                    map.put("coverUrl", publicCoverUrl(video.coverUrl()));
                    map.put("upName", video.upName());
                    map.put("durationSeconds", video.durationSeconds());
                    map.put("fitStage", video.fitStage());
                    map.put("matchScore", video.matchScore());
                    map.put("sourceType", video.sourceType());
                    return map;
                })
                .orElseGet(LinkedHashMap::new);
    }

    public Map<String, Object> contentPayload(
            String title,
            String knowledgePoint,
            String reason,
            RecommendationResult result
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "bilibili_recommendation");
        payload.put("title", title);
        payload.put("url", result.url());
        payload.put("platform", "bilibili");
        payload.put("knowledgePoint", knowledgePoint);
        payload.put("query", result.query());
        payload.put("reason", reason);
        payload.put("usageAdvice", "建议按推荐顺序观看 1-2 个短视频，再回到本空间完成练习题。");
        payload.put("videos", videoMaps(result));
        payload.put("recommendationSource", result.sourceType());
        payload.put("cacheHit", result.cacheHit());
        return payload;
    }

    public List<Map<String, Object>> videoMaps(RecommendationResult result) {
        return result.videos().stream().map(video -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("title", video.title());
            map.put("coverUrl", publicCoverUrl(video.coverUrl()));
            map.put("url", video.url());
            map.put("upName", video.upName());
            map.put("durationSeconds", video.durationSeconds());
            map.put("playCount", video.playCount());
            map.put("publishTime", video.publishTime());
            map.put("reason", video.reason());
            map.put("fitStage", video.fitStage());
            map.put("matchScore", video.matchScore());
            map.put("sourceType", video.sourceType());
            return map;
        }).toList();
    }

    public String searchUrl(String query) {
        return "https://search.bilibili.com/all?keyword="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    public CoverImage fetchCover(String encodedUrl) {
        String rawUrl = decodeCoverUrl(encodedUrl);
        URI uri = validateCoverUri(rawUrl);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("B站封面获取失败");
            }
            String contentType = response.headers()
                    .firstValue("content-type")
                    .filter(value -> value.startsWith("image/"))
                    .orElse("image/jpeg");
            return new CoverImage(response.body(), contentType);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[BilibiliRecommendation] cover proxy failed, url={}", rawUrl, e);
            throw new IllegalArgumentException("B站封面获取失败");
        }
    }

    private Optional<RecommendationResult> fetchFromBilibili(
            String knowledgePoint,
            String reason,
            String query,
            int limit
    ) {
        try {
            URI uri = URI.create("https://api.bilibili.com/x/web-interface/search/type"
                    + "?search_type=video"
                    + "&page=1"
                    + "&page_size=20"
                    + "&order=totalrank"
                    + "&duration=0"
                    + "&keyword=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .header("Referer", searchUrl(knowledgePoint))
                    .header("Origin", "https://search.bilibili.com")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[BilibiliRecommendation] search failed, status={}, query={}", response.statusCode(), query);
                return Optional.empty();
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            if (root.path("code").asInt(-1) != 0) {
                log.warn("[BilibiliRecommendation] search api returned code={}, message={}, query={}",
                        root.path("code").asInt(), root.path("message").asText(), query);
                return Optional.empty();
            }
            JsonNode resultNode = root.path("data").path("result");
            if (!resultNode.isArray()) {
                return Optional.empty();
            }
            List<VideoRecommendation> videos = new ArrayList<>();
            for (JsonNode item : resultNode) {
                parseVideo(item, knowledgePoint, reason).ifPresent(videos::add);
            }
            List<VideoRecommendation> selected = videos.stream()
                    .sorted(Comparator.comparingInt(VideoRecommendation::matchScore).reversed())
                    .limit(limit)
                    .toList();
            return Optional.of(new RecommendationResult(
                    query,
                    searchUrl(query),
                    SOURCE_BILIBILI_SEARCH,
                    false,
                    selected
            ));
        } catch (Exception e) {
            log.warn("[BilibiliRecommendation] search exception, query={}", query, e);
            return Optional.empty();
        }
    }

    private Optional<RecommendationResult> fetchFromWebSearch(
            String knowledgePoint,
            String reason,
            String query,
            int limit
    ) {
        WebSearchEngine searchEngine = webSearchEngineProvider == null ? null : webSearchEngineProvider.getIfAvailable();
        if (searchEngine == null) {
            return Optional.empty();
        }
        try {
            String searchTerms = "site:bilibili.com/video " + query;
            dev.langchain4j.web.search.WebSearchRequest request =
                    dev.langchain4j.web.search.WebSearchRequest.builder()
                            .searchTerms(searchTerms)
                            .maxResults(Math.max(5, Math.min(limit * 3, 10)))
                            .build();
            WebSearchResults results = searchEngine.search(request);
            if (results == null || results.results() == null || results.results().isEmpty()) {
                return Optional.empty();
            }

            Map<String, VideoRecommendation> deduped = new LinkedHashMap<>();
            for (WebSearchOrganicResult result : results.results()) {
                parseWebSearchVideo(result, knowledgePoint, reason)
                        .ifPresent(video -> deduped.putIfAbsent(video.url(), video));
            }
            if (deduped.isEmpty()) {
                return Optional.empty();
            }

            List<VideoRecommendation> selected = deduped.values().stream()
                    .sorted(Comparator.comparingInt(VideoRecommendation::matchScore).reversed())
                    .limit(limit)
                    .toList();
            return Optional.of(new RecommendationResult(
                    query,
                    searchUrl(query),
                    SOURCE_WEB_SEARCH_FALLBACK,
                    false,
                    selected
            ));
        } catch (Exception e) {
            log.warn("[BilibiliRecommendation] web search fallback exception, query={}", query, e);
            return Optional.empty();
        }
    }

    private Optional<VideoRecommendation> parseWebSearchVideo(
            WebSearchOrganicResult result,
            String knowledgePoint,
            String reason
    ) {
        if (result == null) {
            return Optional.empty();
        }
        String url = normalizeBilibiliVideoUrl(result.url() == null ? "" : result.url().toString());
        if (url.isBlank()) {
            return Optional.empty();
        }
        String title = cleanSearchTitle(result.title());
        if (title.isBlank()) {
            return Optional.empty();
        }
        String snippet = cleanText(result.snippet());
        int score = qualityScore(knowledgePoint, title, snippet, "", 0, 0, 0);
        String fitStage = fitStage(score, 0);
        return Optional.of(new VideoRecommendation(
                title,
                "",
                url,
                "B站视频",
                0,
                0,
                "",
                webSearchRecommendationReason(knowledgePoint, reason, fitStage, score),
                fitStage,
                score,
                SOURCE_WEB_SEARCH_FALLBACK
        ));
    }

    private Optional<VideoRecommendation> parseVideo(JsonNode item, String knowledgePoint, String reason) {
        String title = cleanText(item.path("title").asText(""));
        String bvid = item.path("bvid").asText("");
        if (title.isBlank() || bvid.isBlank()) {
            return Optional.empty();
        }
        String description = cleanText(item.path("description").asText(""));
        String tags = cleanText(item.path("tag").asText(""));
        long playCount = parseLong(item.path("play").asText("0"));
        int durationSeconds = parseDuration(item.path("duration").asText(""));
        long pubdate = item.path("pubdate").asLong(0L);
        int score = qualityScore(knowledgePoint, title, description, tags, playCount, durationSeconds, pubdate);
        String fitStage = fitStage(score, durationSeconds);
        return Optional.of(new VideoRecommendation(
                title,
                normalizeCoverUrl(item.path("pic").asText("")),
                videoUrl(item, bvid),
                item.path("author").asText("B站UP主"),
                durationSeconds,
                playCount,
                publishDate(pubdate),
                recommendationReason(knowledgePoint, reason, fitStage, score, playCount, durationSeconds),
                fitStage,
                score,
                SOURCE_BILIBILI_SEARCH
        ));
    }

    private RecommendationResult fallback(String knowledgePoint, String reason, String query, int limit) {
        List<VideoRecommendation> videos = List.of(
                fallbackVideo(knowledgePoint + " 入门到例题讲解", knowledgePoint + " 入门 例题", reason, "入门", 68),
                fallbackVideo(knowledgePoint + " 易错点与条件判断", knowledgePoint + " 易错点", reason, "强化", 64),
                fallbackVideo(knowledgePoint + " 高频题型速练", knowledgePoint + " 题型 速练", reason, "巩固", 60)
        ).stream().limit(limit).toList();
        return new RecommendationResult(query, searchUrl(query), SOURCE_SEARCH_FALLBACK, false, videos);
    }

    private VideoRecommendation fallbackVideo(String title, String query, String reason, String fitStage, int score) {
        return new VideoRecommendation(
                title,
                "",
                searchUrl(query),
                "B站搜索",
                0,
                0,
                "",
                "暂未获取到稳定的 B站实时结果，先提供检索入口；" + safeReason(reason),
                fitStage,
                score,
                SOURCE_SEARCH_FALLBACK
        );
    }

    private int qualityScore(
            String knowledgePoint,
            String title,
            String description,
            String tags,
            long playCount,
            int durationSeconds,
            long pubdate
    ) {
        String normalizedKnowledge = normalizeForMatch(knowledgePoint);
        String normalizedTitle = normalizeForMatch(title);
        String normalizedDescription = normalizeForMatch(description);
        String normalizedTags = normalizeForMatch(tags);

        int score = 30;
        if (!normalizedKnowledge.isBlank() && normalizedTitle.contains(normalizedKnowledge)) {
            score += 26;
        }
        if (!normalizedKnowledge.isBlank() && normalizedDescription.contains(normalizedKnowledge)) {
            score += 10;
        }
        if (!normalizedKnowledge.isBlank() && normalizedTags.contains(normalizedKnowledge)) {
            score += 8;
        }
        for (String token : matchTokens(knowledgePoint)) {
            if (normalizedTitle.contains(token)) score += 5;
            if (normalizedDescription.contains(token)) score += 2;
            if (normalizedTags.contains(token)) score += 2;
        }
        if (playCount > 0) {
            score += Math.min(16, (int) Math.round(Math.log10(playCount + 1) * 4));
        }
        if (durationSeconds >= 300 && durationSeconds <= 1500) {
            score += 12;
        } else if (durationSeconds >= 120 && durationSeconds <= 2400) {
            score += 7;
        } else if (durationSeconds > 0) {
            score += 3;
        }
        if (pubdate > 0) {
            long ageDays = Math.max(0, (Instant.now().getEpochSecond() - pubdate) / 86400);
            if (ageDays <= 730) {
                score += 8;
            } else if (ageDays <= 1825) {
                score += 4;
            }
        }
        return Math.max(1, Math.min(score, 100));
    }

    private List<String> matchTokens(String knowledgePoint) {
        String normalized = normalizeForMatch(knowledgePoint);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split("[,，、\\s]+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty() && normalized.length() >= 2) {
            tokens.add(normalized);
        }
        return tokens.stream().distinct().limit(6).toList();
    }

    private String recommendationReason(
            String knowledgePoint,
            String reason,
            String fitStage,
            int score,
            long playCount,
            int durationSeconds
    ) {
        String play = playCount > 0 ? "，播放量约 " + playCount : "";
        String duration = durationSeconds > 0 ? "，时长 " + formatDuration(durationSeconds) : "";
        return "与「" + knowledgePoint + "」的标题/标签匹配度较高" + play + duration
                + "，适合作为" + fitStage + "阶段补充材料。"
                + "评分 " + score + " 分。" + safeReason(reason);
    }

    private String webSearchRecommendationReason(String knowledgePoint, String reason, String fitStage, int score) {
        return "B站实时接口被限制时，通过网络搜索定位到公开 B站视频结果；"
                + "标题/摘要与「" + knowledgePoint + "」相关，适合作为" + fitStage + "阶段补充材料。"
                + "评分 " + score + " 分。" + safeReason(reason);
    }

    private String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        return "当前推荐依据：" + reason;
    }

    private String fitStage(int score, int durationSeconds) {
        if (score >= 82 && durationSeconds > 0 && durationSeconds <= 1200) {
            return "入门";
        }
        if (score >= 70) {
            return "强化";
        }
        return "巩固";
    }

    private String cleanText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return Jsoup.parse(text).text().replace('\u00A0', ' ').trim();
    }

    private String cleanSearchTitle(String title) {
        String cleaned = cleanText(title);
        if (cleaned.isBlank()) {
            return "";
        }
        return cleaned
                .replaceAll("(?i)\\s*[-_—|]*\\s*哔哩哔哩\\s*bilibili\\s*$", "")
                .replaceAll("(?i)\\s*[-_—|]*\\s*bilibili\\s*$", "")
                .trim();
    }

    private String normalizeForMatch(String value) {
        return cleanText(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[《》【】\\[\\]（）()：:·,，。.!！?？\\-_/|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeKnowledgePoint(String value) {
        if (value == null || value.isBlank()) {
            return "当前知识点";
        }
        return value.trim();
    }

    private String normalizeCoverUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("http://")) {
            return "https://" + url.substring("http://".length());
        }
        return url;
    }

    private String publicCoverUrl(String rawCoverUrl) {
        if (rawCoverUrl == null || rawCoverUrl.isBlank()) {
            return "";
        }
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(rawCoverUrl.getBytes(StandardCharsets.UTF_8));
        return "/api/resources/bilibili/cover?url=" + encoded;
    }

    private String decodeCoverUrl(String encodedUrl) {
        if (encodedUrl == null || encodedUrl.isBlank()) {
            throw new IllegalArgumentException("封面地址不能为空");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encodedUrl);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("封面地址格式错误");
        }
    }

    private URI validateCoverUri(String rawUrl) {
        URI uri = URI.create(rawUrl);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(scheme) || host == null || !host.endsWith(".hdslb.com")) {
            throw new IllegalArgumentException("不允许代理该封面地址");
        }
        return uri;
    }

    private String videoUrl(JsonNode item, String bvid) {
        String arcUrl = item.path("arcurl").asText("");
        if (arcUrl.startsWith("http")) {
            return arcUrl;
        }
        return "https://www.bilibili.com/video/" + bvid;
    }

    private String normalizeBilibiliVideoUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || path == null || !host.endsWith("bilibili.com") || !path.startsWith("/video/")) {
                return "";
            }
            String[] parts = path.split("/");
            if (parts.length < 3 || parts[2].isBlank()) {
                return "";
            }
            return "https://www.bilibili.com/video/" + parts[2];
        } catch (Exception e) {
            return "";
        }
    }

    private int parseDuration(String duration) {
        if (duration == null || duration.isBlank()) {
            return 0;
        }
        String[] parts = duration.split(":");
        int seconds = 0;
        for (String part : parts) {
            seconds = seconds * 60 + (int) parseLong(part);
        }
        return seconds;
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank() || "--".equals(value)) {
            return 0L;
        }
        try {
            return Long.parseLong(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String publishDate(long epochSeconds) {
        if (epochSeconds <= 0) {
            return "";
        }
        return LocalDate.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault()).toString();
    }

    private String formatDuration(int durationSeconds) {
        int minutes = durationSeconds / 60;
        int seconds = durationSeconds % 60;
        return minutes + ":" + String.format("%02d", seconds);
    }

    private void putCache(String cacheKey, RecommendationResult result) {
        if (cache.size() >= CACHE_LIMIT) {
            cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
            if (cache.size() >= CACHE_LIMIT) {
                cache.keySet().stream().findFirst().ifPresent(cache::remove);
            }
        }
        Duration ttl = SOURCE_SEARCH_FALLBACK.equals(result.sourceType()) ? FALLBACK_CACHE_TTL : CACHE_TTL;
        cache.put(cacheKey, new CachedRecommendation(result, Instant.now().plus(ttl)));
    }

    public record RecommendationResult(
            String query,
            String url,
            String sourceType,
            boolean cacheHit,
            List<VideoRecommendation> videos
    ) {
        private RecommendationResult withCacheHit(boolean value) {
            return new RecommendationResult(query, url, sourceType, value, videos);
        }
    }

    public record VideoRecommendation(
            String title,
            String coverUrl,
            String url,
            String upName,
            int durationSeconds,
            long playCount,
            String publishTime,
            String reason,
            String fitStage,
            int matchScore,
            String sourceType
    ) {
        public VideoRecommendation {
            title = Objects.requireNonNullElse(title, "");
            coverUrl = Objects.requireNonNullElse(coverUrl, "");
            url = Objects.requireNonNullElse(url, "");
            upName = Objects.requireNonNullElse(upName, "");
            publishTime = Objects.requireNonNullElse(publishTime, "");
            reason = Objects.requireNonNullElse(reason, "");
            fitStage = Objects.requireNonNullElse(fitStage, "");
            sourceType = Objects.requireNonNullElse(sourceType, "");
        }
    }

    private record CachedRecommendation(RecommendationResult result, Instant expiresAt) {
        private boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public record CoverImage(byte[] body, String contentType) {
    }
}
