package cc.ivera.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e, HttpServletResponse response) {
        // 下载端点可能在异常前已设置 Content-Type（如 video/mp4），
        // 清除后 Spring 才能按 ApiResponse 正确序列化为 JSON
        if (response.isCommitted()) {
            log.debug("Binary response already started; cannot send JSON error: {}", e.getMessage());
            return null;
        }
        response.reset();
        return ResponseEntity.status(e.getStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e, HttpServletResponse response) {
        log.error("未处理异常", e);
        if (response.isCommitted()) {
            // A browser can abort a multi-GB download; writing JSON now is impossible.
            log.debug("Binary response already committed, cannot serialize an error response");
            return null;
        }
        response.reset();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail("服务器内部错误，请稍后重试"));
    }
}
