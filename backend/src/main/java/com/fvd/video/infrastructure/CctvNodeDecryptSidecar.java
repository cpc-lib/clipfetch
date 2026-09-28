package com.fvd.video.infrastructure;

import com.fvd.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * CCTV h5e 流 Node.js 批量解密 sidecar。
 *
 * <p>替代旧的浏览器实时播放方案，通过 Node.js 脚本批量下载 TS 段，
 * 在 Playwright 页面上下文中用 WASM 批量解密，最后由 ffmpeg 合并为 MP4。
 *
 * <p>优势：速度从 1:1 实时提升至约 20x，且消除段丢失导致的雪花问题。
 */
@Slf4j
@Component
public class CctvNodeDecryptSidecar {

    private final String downloadsDir;
    private final String nodePath;
    private final String decryptScript;
    private final DownloadProgressHandler progress;
    /**
     * Node 脚本最长执行时间（毫秒）——45 分钟视频约 3 分钟，默认 10 分钟足够
     */
    private final long decryptTimeoutMs;

    public CctvNodeDecryptSidecar(@Value("${app.downloads-dir:downloads}") String downloadsDir,
                                  @Value("${app.node-exe:node}") String nodePath,
                                  @Value("${app.cctv-decrypt-script:}") String decryptScript,
                                  @Value("${app.cctv-decrypt-timeout-ms:600000}") long decryptTimeoutMs,
                                  DownloadProgressHandler progress) {
        this.downloadsDir = downloadsDir;
        this.nodePath = nodePath;
        this.decryptScript = resolveScript(decryptScript);
        this.decryptTimeoutMs = decryptTimeoutMs;
        this.progress = progress;
    }

    /**
     * 解析脚本路径：显式配置 > 默认 resources/cctv/decrypt_browser.js
     */
    private static String resolveScript(String configured) {
        if (configured != null && !configured.isBlank()) {
            if (Files.exists(Path.of(configured))) return configured;
            log.warn("配置的 cctv-decrypt-script 不存在: {}", configured);
        }
        // 默认：backend/src/main/resources/cctv/decrypt_browser.js（相对工作目录 backend/）
        Path defaultPath = Path.of("src", "main", "resources", "cctv", "decrypt_browser.js");
        if (Files.exists(defaultPath)) return defaultPath.toAbsolutePath().toString();
        // 回退：从项目根目录启动时
        Path rootPath = Path.of("backend", "src", "main", "resources", "cctv", "decrypt_browser.js");
        if (Files.exists(rootPath)) return rootPath.toAbsolutePath().toString();
        throw new IllegalStateException("找不到 decrypt_browser.js，请配置 app.cctv-decrypt-script");
    }

    private static String sanitizeTitle(String title) {
        if (title == null || title.isBlank()) return "video";
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 80).trim() : cleaned;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /**
     * 批量解密并下载。
     *
     * @param variantUrl h5e 变体 m3u8 URL（已选定清晰度的那个）
     * @param title      文件名
     * @param response   Servlet 响应
     * @param taskId     WebSocket 进度任务 ID
     */
    public void decryptAndDownload(String variantUrl, String title,
                                   HttpServletResponse response, String taskId) {
        Path dir = null;
        try {
            dir = Files.createDirectories(Path.of(downloadsDir, UUID.randomUUID().toString()));
            log.info("CCTV Node 批量解密：{} -> {}", variantUrl, dir);

            progress.sendProgress(taskId, 0, -1, 0);

            // 启动 Node 进程
            ProcessBuilder pb = new ProcessBuilder(
                    nodePath,
                    decryptScript,
                    variantUrl,
                    dir.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取输出并转发进度
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    log.info("[node] {}", line);
                    // 解析进度：seg X/Y frames=...
                    if (line.contains("seg ") && line.contains("/")) {
                        try {
                            String segPart = line.substring(line.indexOf("seg ") + 4);
                            String[] parts = segPart.split("/");
                            int current = Integer.parseInt(parts[0].trim());
                            int total = Integer.parseInt(parts[1].trim().split(" ")[0]);
                            progress.sendProgress(taskId, current, total, 0);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            boolean finished = process.waitFor(decryptTimeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException("CCTV 解密超时（" + (decryptTimeoutMs / 60000) + " 分钟）");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String tail = output.length() > 2000
                        ? "..." + output.substring(output.length() - 2000)
                        : output.toString();
                throw new BusinessException("Node 解密脚本失败 (exit=" + exitCode + "): " + tail);
            }

            // 查找输出文件
            Path outMp4 = dir.resolve("out.mp4");
            if (!Files.exists(outMp4) || Files.size(outMp4) == 0) {
                throw new BusinessException("解密输出文件不存在或为空");
            }

            long size = Files.size(outMp4);
            String filename = sanitizeTitle(title) + ".mp4";
            response.setContentType("video/mp4");
            response.setContentLengthLong(size);
            response.setHeader("Content-Disposition",
                    "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
            response.setHeader("Cache-Control", "no-store");

            try (OutputStream out = response.getOutputStream()) {
                Files.copy(outMp4, out);
                out.flush();
            } catch (Exception e) {
                // 响应已提交（Content-Type 已设为 video/mp4），客户端断开属正常，不再抛异常
                log.warn("写入响应流失败（客户端可能已断开）: {}", e.getMessage());
                return;
            }
            progress.sendDone(taskId);
            log.info("CCTV Node 解密下载完成: {} ({})", filename, humanSize(size));

        } catch (BusinessException e) {
            progress.sendError(taskId, e.getMessage());
            throw e;
        } catch (Exception e) {
            progress.sendError(taskId, "CCTV 解密下载失败：" + e.getMessage());
            throw new BusinessException("CCTV 解密下载失败：" + e.getMessage());
        } finally {
            cleanup(dir);
        }
    }

    private void cleanup(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        if (Boolean.parseBoolean(System.getenv("CCTV_DEBUG_KEEP"))) {
            log.info("CCTV_DEBUG_KEEP 已启用，保留临时目录: {}", dir);
            return;
        }
        try (var list = Files.list(dir)) {
            list.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
            Files.deleteIfExists(dir);
        } catch (Exception e) {
            log.debug("清理临时目录失败: {}", dir);
        }
    }
}
