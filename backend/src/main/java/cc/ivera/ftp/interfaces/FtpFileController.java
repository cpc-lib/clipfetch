package cc.ivera.ftp.interfaces;

import cc.ivera.ftp.application.FtpFileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ftp")
@RequiredArgsConstructor
public class FtpFileController {

    private final FtpFileService fileService;

    @GetMapping("/tasks/{taskUuid}/files/{fileIndex}/content")
    public void stream(@PathVariable String taskUuid,
                       @PathVariable int fileIndex,
                       @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
                       HttpServletRequest request,
                       HttpServletResponse response) {
        fileService.stream(FtpController.currentUserId(request), taskUuid, fileIndex, range, response);
    }
}
