package cc.ivera.magnet.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.piece.NativeDownloadRootService;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Owner-scoped selection of the native download root.
 *
 * The server only offers directories inside its administrator-approved bases, so
 * the client never sends a free-form absolute path that the server trusts as-is;
 * the same owner scope is applied to every read and write.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/magnet/native/download-roots")
public class NativeDownloadRootController {
    private final NativeTorrentProperties settings;
    private final NativeDownloadRootService roots;

    @GetMapping
    public ApiResponse<NativeDownloadRootService.Current> current(HttpServletRequest request) {
        long owner=authenticatedOwner(request);
        requireEnabled();
        return ApiResponse.ok(roots.current(owner));
    }

    /** Directory-only listing; returns folders, never file names or contents. */
    @GetMapping("/browse")
    public ApiResponse<NativeDownloadRootService.Browse> browse(
            HttpServletRequest request,
            @RequestParam(value="path",required=false) String path) {
        long owner=authenticatedOwner(request);
        requireEnabled();
        try {
            return ApiResponse.ok(roots.browse(owner,path));
        }catch(IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,"目录无效：" + e.getMessage());
        }catch(SecurityException e) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,e.getMessage());
        }
    }

    @PutMapping
    public ApiResponse<NativeDownloadRootService.Selected> save(
            HttpServletRequest request,@Valid @RequestBody SaveRequest body) {
        long owner=authenticatedOwner(request);
        requireEnabled();
        try {
            return ApiResponse.ok(roots.save(owner,body.path()));
        }catch(IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,"目录无效：" + e.getMessage());
        }catch(SecurityException e) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,e.getMessage());
        }
    }

    /** Drops the saved selection; the administrator default root applies again. */
    @DeleteMapping
    public ApiResponse<Boolean> clear(HttpServletRequest request) {
        long owner=authenticatedOwner(request);
        requireEnabled();
        roots.clear(owner);
        return ApiResponse.ok(true);
    }

    public record SaveRequest(@NotBlank @Size(max=1024) String path) {}

    private void requireEnabled() {
        if(!settings.isEnabled() || !settings.isTaskCreationEnabled())
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "原生任务创建尚未开启，暂时无法选择下载目录");
    }

    private static long authenticatedOwner(HttpServletRequest request) {
        Object identity=request.getAttribute(AuthInterceptor.ATTR_USER);
        if(!(identity instanceof User user) || user.getId()==null || user.getId()<=0)
            throw new BusinessException(HttpStatus.UNAUTHORIZED,"请先登录");
        return user.getId();
    }
}
