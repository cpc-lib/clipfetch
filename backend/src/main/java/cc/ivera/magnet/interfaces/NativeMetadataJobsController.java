package cc.ivera.magnet.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.discovery.NativeMetadataJobManager;
import cc.ivera.magnet.engine.magnet.MagnetUriParser;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Authenticated metadata-only, in-memory jobs. Never creates Piece download tasks.
 * Unknown magnet privacy: explicit per-submission peer/Tracker disclosure consent is mandatory.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/magnet/native/metadata/jobs")
public class NativeMetadataJobsController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final NativeTorrentProperties properties;
    private final NativeMetadataJobManager manager;
    private final MagnetUriParser magnetParser = new MagnetUriParser();

    public record StartRequest(@NotBlank @Size(max=16384) String magnetUri,
                               boolean consentToPublicDiscovery) {}
    public record Accepted(UUID jobId, NativeMetadataJobManager.Snapshot status) {}

    @PostMapping
    public ApiResponse<Accepted> create(HttpServletRequest request,
                                        @Valid @RequestBody StartRequest body) {
        long userId=authorizedUser(request);
        requireLookupEnabled();
        if(!body.consentToPublicDiscovery()) {
            throw new BusinessException("纯磁力链接可能向公网 Tracker/DHT 披露 InfoHash，请确认该资源允许公开发现后再提交");
        }
        try {
            magnetParser.parse(body.magnetUri()); // validate BEFORE queuing/network disclosure
        }catch(IllegalArgumentException | UnsupportedOperationException e) {
            throw new BusinessException("磁力链接无效：" + e.getMessage());
        }
        byte[] peerId=new byte[20];
        byte[] random=new byte[12];
        RANDOM.nextBytes(random);
        System.arraycopy("-CF0001-".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0,peerId,0,8);
        System.arraycopy(random,0,peerId,8,12);
        try {
            UUID jobId=manager.submit(userId,body.magnetUri(),peerId,properties.getMetadataListenPort());
            return ApiResponse.ok(new Accepted(jobId,manager.status(userId,jobId)));
        }catch(RejectedExecutionException e) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "当前原生元数据解析任务已达到并发上限，请稍后再试");
        }
    }

    @GetMapping("/{jobId}")
    public ApiResponse<NativeMetadataJobManager.Snapshot> status(
            HttpServletRequest request,@PathVariable UUID jobId) {
        long userId=authorizedUser(request);
        requireLookupEnabled();
        try{return ApiResponse.ok(manager.status(userId,jobId));}
        catch(NoSuchElementException e){throw notFound();}
    }

    @GetMapping("/{jobId}/files")
    public ApiResponse<NativeMetadataJobManager.FilesPage> files(
            HttpServletRequest request,@PathVariable UUID jobId,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize) {
        long userId=authorizedUser(request);
        requireLookupEnabled();
        try {return ApiResponse.ok(manager.files(userId,jobId,page,pageSize));}
        catch(NoSuchElementException e){throw notFound();}
        catch(IllegalArgumentException | IllegalStateException e){
            throw new BusinessException("文件列表尚不可用或分页参数错误，请先确认元数据解析成功");
        }
    }

    @DeleteMapping("/{jobId}")
    public ApiResponse<NativeMetadataJobManager.Snapshot> cancel(
            HttpServletRequest request,@PathVariable UUID jobId) {
        long userId=authorizedUser(request);
        requireLookupEnabled();
        try{return ApiResponse.ok(manager.cancel(userId,jobId));}
        catch(NoSuchElementException e){throw notFound();}
    }

    private void requireLookupEnabled() {
        if(!properties.isMetadataLookupEnabled()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "原生元数据解析尚未开启，请继续使用现有磁力解析功能");
        }
    }
    private static BusinessException notFound(){
        return new BusinessException(HttpStatus.NOT_FOUND,"元数据任务不存在或无权访问");
    }
    private static long authorizedUser(HttpServletRequest request) {
        Object auth=request.getAttribute(AuthInterceptor.ATTR_USER);
        if(!(auth instanceof User user) || user.getId()==null || user.getId()<=0)
            throw new BusinessException(HttpStatus.UNAUTHORIZED,"请先登录");
        return user.getId();
    }
}
