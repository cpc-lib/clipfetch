package cc.ivera.magnet.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.piece.NativeTaskCreationService;
import cc.ivera.magnet.engine.piece.NativeDownloadRootService;
import cc.ivera.magnet.engine.discovery.NativeMetadataJobManager;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Deliberate opt-in: upload a verified PUBLIC v1 torrent, select file indexes,
 * and persist a CREATED task. No network, no Piece download, no auto worker claim.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/magnet/native/tasks")
public class NativeTaskCreationController {
    private final NativeTorrentProperties settings;
    private final NativeTaskCreationService creator;
    private final NativeMetadataJobManager metadataJobs;
    private final NativeDownloadRootService downloadRoots;

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<NativeTaskCreationService.Created> create(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile torrent,
            @RequestParam("fileIndexes") List<Integer> indexes) {
        long owner=authenticatedOwner(request);
        if(!settings.isTaskCreationEnabled())
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "原生任务创建尚未开启，请继续使用现有磁力下载功能");
        if(torrent==null || torrent.isEmpty() || torrent.getSize()>Math.min(8L*1024*1024,
                settings.getMaxMetadataBytes()))
            throw new BusinessException("种子文件为空或超出允许大小");
        try {
            return ApiResponse.ok(creator.createFromTorrent(owner,torrent.getBytes(),indexes,
                    downloadRoots.selectedRoot(owner).orElse(null)));
        }catch(IOException e) {
            throw new BusinessException("读取种子文件失败");
        }catch(SecurityException e) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "该种子不符合公开元数据安全要求，暂不支持创建原生任务");
        }catch(IllegalArgumentException | UnsupportedOperationException e) {
            throw new BusinessException("种子文件或勾选索引无效：" + e.getMessage());
        }catch(IllegalStateException e) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "任务数量已达到限制，或可信元数据存在冲突");
        }
    }

    public record CreateFromMetadata(List<Integer> fileIndexes) {}

    /** Owner-only verified BEP9 metadata -> persisted CREATED task; no automatic download. */
    @PostMapping(value="/from-metadata/{jobId}")
    public ApiResponse<NativeTaskCreationService.Created> fromMetadata(
            HttpServletRequest request,@PathVariable java.util.UUID jobId,
            @RequestBody CreateFromMetadata body) {
        long owner=authenticatedOwner(request);
        if(!settings.isTaskCreationEnabled() || !settings.isMetadataLookupEnabled())
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"Native metadata task creation disabled");
        try {
            return ApiResponse.ok(creator.createFromTorrent(owner,
                    metadataJobs.verifiedTorrentBytes(owner,jobId),
                    body==null?null:body.fileIndexes(),
                    downloadRoots.selectedRoot(owner).orElse(null)));
        }catch(java.util.NoSuchElementException e) {
            throw new BusinessException(HttpStatus.NOT_FOUND,"元数据任务不存在或无权访问");
        }catch(IllegalArgumentException | UnsupportedOperationException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,"文件选择无效");
        }catch(SecurityException e) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,"元数据校验或隐私限制未通过");
        }catch(IllegalStateException e) {
            throw new BusinessException(HttpStatus.CONFLICT,"元数据未准备完成、已过期或任务超出配额");
        }
    }

    private static long authenticatedOwner(HttpServletRequest request) {
        Object identity=request.getAttribute(AuthInterceptor.ATTR_USER);
        if(!(identity instanceof User user) || user.getId()==null || user.getId()<=0)
            throw new BusinessException(HttpStatus.UNAUTHORIZED,"请先登录");
        return user.getId();
    }
}
