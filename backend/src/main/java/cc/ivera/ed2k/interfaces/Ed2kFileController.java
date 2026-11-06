package cc.ivera.ed2k.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.ed2k.application.Ed2kFileService;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ed2k")
@RequiredArgsConstructor
public class Ed2kFileController {

    private final Ed2kFileService fileService;

    @GetMapping("/tasks/{taskUuid}/files/{fileIndex}/content")
    public void streamFile(@PathVariable String taskUuid,
                           @PathVariable int fileIndex,
                           @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                           HttpServletRequest request,
                           HttpServletResponse response) {
        long userId = currentUserId(request);
        fileService.streamTaskFile(userId, taskUuid, fileIndex, rangeHeader, response);
    }

    private long currentUserId(HttpServletRequest request) {
        Object raw = request.getAttribute(AuthInterceptor.ATTR_USER);
        if (raw instanceof User user && user.getId() != null) {
            return user.getId();
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
}
