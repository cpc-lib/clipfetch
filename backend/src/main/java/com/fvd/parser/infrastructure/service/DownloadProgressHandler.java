package com.fvd.parser.infrastructure.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下载进度推送：前端下载前用随机 taskId 建立 ws 连接，
 * 下载过程把 yt-dlp/直流的进度事件推给对应会话。
 * 无需认证（taskId 为随机 UUID，仅传输进度数字）。
 */
@Slf4j
@Component
public class DownloadProgressHandler extends TextWebSocketHandler {

    private static final long THROTTLE_MS = 300;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();

    private static String taskIdOf(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals("taskId")) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String taskId = taskIdOf(session);
        if (taskId != null) {
            sessions.put(taskId, session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String taskId = taskIdOf(session);
        if (taskId != null) {
            sessions.remove(taskId);
            lastSent.remove(taskId);
        }
    }

    /**
     * 进度事件（300ms 节流）；total<=0 表示总量未知
     */
    public void sendProgress(String taskId, long downloaded, long total, double speed) {
        if (taskId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSent.get(taskId);
        if (last != null && now - last < THROTTLE_MS) {
            return;
        }
        lastSent.put(taskId, now);
        send(taskId, "{\"type\":\"progress\",\"downloaded\":" + downloaded
                + ",\"total\":" + total + ",\"speed\":" + (long) speed + "}");
    }

    public void sendDone(String taskId) {
        send(taskId, "{\"type\":\"done\"}");
    }

    public void sendError(String taskId, String message) {
        String msg = message == null ? "下载失败" : message.replace("\"", "'").replaceAll("[\\r\\n]", " ");
        send(taskId, "{\"type\":\"error\",\"message\":\"" + msg + "\"}");
    }

    private void send(String taskId, String json) {
        if (taskId == null) {
            return;
        }
        WebSocketSession session = sessions.get(taskId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.debug("进度推送失败 taskId={}: {}", taskId, e.getMessage());
        }
    }
}
