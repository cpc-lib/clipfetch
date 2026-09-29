package com.fvd.ai.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.ai.application.SubtitleExtractor;
import com.fvd.ai.domain.SubtitleData;
import com.fvd.ai.infrastructure.DeepSeekClient;
import com.fvd.auth.application.AiQuotaService;
import com.fvd.auth.domain.User;
import com.fvd.auth.interfaces.AuthInterceptor;
import com.fvd.cookie.application.CookieService;
import com.fvd.shared.web.BusinessException;
import com.fvd.video.domain.Platform;
import com.fvd.video.infrastructure.YtDlpService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 视频总结 / 问答（SSE 流式）
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SummaryController {

    private static final int MAX_SUBTITLE_CHARS = 15000;
    private static final String SUMMARY_SYSTEM = """
            你是一位专业的视频内容分析师。请基于提供的视频字幕，用中文生成一份结构化的视频总结，
            严格按以下 Markdown 结构输出（不要输出多余内容）：
            ## 视频概述
            （2-3 句话概括视频主题与价值）
            ## 内容大纲
            （按时间/逻辑顺序列出视频的章节脉络，使用有序列表）
            ## 核心要点
            （3-6 条最重要的知识点/结论，使用无序列表，可加粗关键词）
            ## 一句话总结
            （> 引用格式，一句话说清这个视频讲了什么）
            """;
    private static final String MINDMAP_SYSTEM = """
            你是一位思维导图专家。基于提供的视频字幕，输出一份 Markdown 格式的思维导图。
            要求：一级标题(#)为视频主题；二级标题(##)为 3-6 个核心分支；三级(###)或列表(-)为具体要点。
            只输出 Markdown 本身，不要任何解释。
            """;
    private static final String CHAT_SYSTEM = """
            你是一位视频内容助手。请根据提供的视频字幕内容回答用户问题，用中文、简洁准确地回答。
            如果字幕中没有相关信息，请如实说明。回答可使用 Markdown 格式。
            """;
    private final YtDlpService ytDlp;
    private final SubtitleExtractor subtitleExtractor;
    private final DeepSeekClient deepSeek;
    private final AiQuotaService aiQuotaService;
    private final CookieService cookieService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PostMapping(value = "/summarize", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter summarize(@RequestBody SummarizeReq req,
                                @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user) {
        String url = req.getUrl();
        if (url == null || url.isBlank()) {
            throw new BusinessException("链接不能为空");
        }
        aiQuotaService.checkAndConsume(user);
        SseEmitter emitter = new SseEmitter(300_000L);
        executor.submit(() -> runSummarize(emitter, url, user));
        return emitter;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatReq req,
                           @RequestAttribute(value = AuthInterceptor.ATTR_USER, required = false) User user) {
        String url = req.getUrl();
        String question = req.getQuestion();
        if (url == null || url.isBlank() || question == null || question.isBlank()) {
            throw new BusinessException("请输入链接和问题");
        }
        aiQuotaService.checkAndConsume(user);
        SseEmitter emitter = new SseEmitter(300_000L);
        executor.submit(() -> runChat(emitter, url, question, req.getSubtitleText(), user));
        return emitter;
    }

    /**
     * 已登录用户为该平台上传过 cookies 就带上（抖音/IG 强制、其余可选）；未登录或无则返回 null
     */
    private String userCookies(User user, String url) {
        return cookieService.findContent(user, Platform.from(url));
    }

    private void runSummarize(SseEmitter emitter, String url, User user) {
        try {
            log.info("AI 总结开始: {}", url);
            var info = ytDlp.dumpInfo(url, userCookies(user, url));
            var subtitleResult = subtitleExtractor.extract(info);
            if (subtitleResult == null) {
                sendEvent(emitter, "error", Map.of("message", "该视频没有可用的字幕，无法生成 AI 总结"));
                emitter.complete();
                return;
            }
            SubtitleData subtitle = subtitleResult.data();
            sendEvent(emitter, "subtitle", subtitle);

            String text = subtitle.fullText();
            if (text.length() > MAX_SUBTITLE_CHARS) {
                text = text.substring(0, MAX_SUBTITLE_CHARS);
            }
            String title = info.path("title").asText("未知视频");

            String summary = deepSeek.streamChat(SUMMARY_SYSTEM,
                    "视频标题：《" + title + "》\n字幕内容：\n" + text,
                    token -> sendEvent(emitter, "summary", Map.of("content", token)));
            sendEvent(emitter, "summary_done", Map.of("summary", summary));

            String mindmap = deepSeek.streamChat(MINDMAP_SYSTEM,
                    "视频标题：《" + title + "》\n字幕内容：\n" + text, null);
            sendEvent(emitter, "mindmap", Map.of("markdown", mindmap));

            sendEvent(emitter, "done", Map.of());
            emitter.complete();
            log.info("AI 总结完成: {}", url);
        } catch (Exception e) {
            completeWithError(emitter, e);
        }
    }

    // ===== 内部 =====

    private void runChat(SseEmitter emitter, String url, String question, String subtitleText, User user) {
        try {
            String text = subtitleText;
            if (text == null || text.isBlank()) {
                var info = ytDlp.dumpInfo(url, userCookies(user, url));
                var subtitleResult = subtitleExtractor.extract(info);
                if (subtitleResult == null) {
                    sendEvent(emitter, "error", Map.of("message", "该视频没有可用的字幕，无法进行 AI 问答"));
                    emitter.complete();
                    return;
                }
                text = subtitleResult.data().fullText();
            }
            if (text.length() > MAX_SUBTITLE_CHARS) {
                text = text.substring(0, MAX_SUBTITLE_CHARS);
            }
            String answer = deepSeek.streamChat(CHAT_SYSTEM,
                    "视频字幕内容：\n" + text + "\n\n用户问题：" + question,
                    token -> sendEvent(emitter, "answer", Map.of("content", token)));
            sendEvent(emitter, "done", Map.of("answer", answer));
            emitter.complete();
        } catch (Exception e) {
            completeWithError(emitter, e);
        }
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            String json = mapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(name).data(json));
        } catch (Exception e) {
            log.warn("SSE 发送失败 [{}]: {}", name, e.getMessage());
        }
    }

    private void completeWithError(SseEmitter emitter, Exception e) {
        String message = e instanceof BusinessException ? e.getMessage() : "AI 服务异常，请稍后重试";
        log.warn("AI 任务失败: {}", message);
        sendEvent(emitter, "error", Map.of("message", message));
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    @Data
    public static class SummarizeReq {
        private String url;
        private String language;
    }

    @Data
    public static class ChatReq {
        private String url;
        private String question;
        private String subtitleText;
    }
}
