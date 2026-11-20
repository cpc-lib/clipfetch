package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeFile;
import cc.ivera.magnet.domain.TorrentNativeTask;
import cc.ivera.magnet.domain.TorrentNativeTaskMapper;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Read-only export of a COMPLETED native task so the browser can stream the
 * verified files into a user-picked local folder. The server never trusts a
 * client path: files are always re-resolved inside the persisted task root.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NativeFileExportService {
    private final TorrentNativeTaskMapper mapper;
    private final NativeTorrentProperties settings;

    public record FileView(int index,String path,long size,boolean available) {}
    public record Download(String fileName,Path file) {}

    public List<FileView> list(long owner,UUID taskId) {
        Path root=completedTaskRoot(owner,taskId);
        return mapper.listTaskFiles(taskId.toString(),owner).stream().map(row -> {
            String path=row.getRelativePath();
            long size=sizeOf(row);
            return new FileView(indexOf(row),path,size,resolve(root,path,size)!=null);
        }).toList();
    }

    /** Resolve exactly one selected file; unavailable bytes are a retryable conflict. */
    public Download resolveDownload(long owner,UUID taskId,int fileIndex) {
        Path root=completedTaskRoot(owner,taskId);
        TorrentNativeFile row=mapper.listTaskFiles(taskId.toString(),owner).stream()
                .filter(candidate -> indexOf(candidate)==fileIndex).findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "文件不在该任务已选中的文件列表中"));
        Path file=resolve(root,row.getRelativePath(),sizeOf(row));
        if (file==null)
            throw new BusinessException(HttpStatus.CONFLICT,
                    "服务器文件尚未就绪，请稍后重试；若持续失败请先校验任务文件");
        return new Download(fileName(row.getRelativePath()),file);
    }

    /**
     * Commit headers only after every validation passed, then copy with a fixed
     * buffer. A client abort must not attempt to write JSON over octet-stream.
     */
    public void stream(Download download,HttpServletResponse response) {
        Path file=download.file();
        long size;
        try {
            size=Files.size(file);
        }catch(IOException e) {
            throw new BusinessException(HttpStatus.CONFLICT,"服务器文件暂时不可读，请稍后重试");
        }
        String safeName=download.fileName().replace("\r","_").replace("\n","_").replace("\"","_");
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/octet-stream");
        response.setContentLengthLong(size);
        response.setHeader("Cache-Control","private, no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Content-Disposition","attachment; filename*=UTF-8''"
                +URLEncoder.encode(safeName,StandardCharsets.UTF_8).replace("+","%20"));

        long copied=0;
        try(InputStream input=Files.newInputStream(file)) {
            OutputStream output=response.getOutputStream();
            byte[] buffer=new byte[64*1024];
            int read;
            while((read=input.read(buffer))>=0) {
                if(read==0) continue;
                output.write(buffer,0,read);
                copied+=read;
            }
            output.flush();
        }catch(IOException e) {
            log.warn("Native 文件导出中断，已传输 {} 字节（{}）：{}",copied,safeName,
                    e.getClass().getSimpleName());
            if(!response.isCommitted())
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "读取服务器文件失败，请重试");
        }
    }

    private Path completedTaskRoot(long owner,UUID taskId) {
        if(owner<=0 || taskId==null) throw new NoSuchElementException("Native task unavailable");
        TorrentNativeTask task=mapper.findOwned(taskId.toString(),owner);
        if(task==null) throw new NoSuchElementException("Native task unavailable");
        if(!"COMPLETED".equals(task.getStatus()))
            throw new BusinessException(HttpStatus.CONFLICT,
                    "任务尚未下载完成，请等待下载结束后再保存到本机");
        try {
            return NativeTaskStoragePaths.assertPersisted(settings,owner,taskId,task.getStatePath());
        }catch(SecurityException | IllegalArgumentException e) {
            log.warn("Native 导出目录校验失败：{}",e.getMessage());
            throw new BusinessException(HttpStatus.CONFLICT,
                    "任务存储目录不安全，请联系管理员检查下载目录配置");
        }
    }

    /**
     * Accept the finalized torrent name, or a same-sized pending
     * {@code .clipfetch.part} file when the final rename has not run yet.
     */
    private static Path resolve(Path taskRoot,String relativePath,long expectedBytes) {
        if(relativePath==null || relativePath.isBlank() || relativePath.length()>1024) return null;
        if(relativePath.startsWith("/") || relativePath.indexOf('\\')>=0
                || relativePath.indexOf(':')>=0 || relativePath.indexOf('\0')>=0) return null;
        for(String segment:relativePath.split("/",-1))
            if(segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return null;
        Path candidate=taskRoot.resolve(relativePath).normalize();
        if(candidate.equals(taskRoot) || !candidate.startsWith(taskRoot)) return null;
        Path finalized=existingFile(candidate,expectedBytes);
        if(finalized!=null) return finalized;
        return existingFile(taskRoot.resolve(relativePath+".clipfetch.part").normalize(),expectedBytes);
    }

    private static Path existingFile(Path candidate,long expectedBytes) {
        try {
            NativeTaskStoragePaths.rejectSymbolicLinks(candidate);
            if(!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)) return null;
            return Files.size(candidate)==expectedBytes?candidate:null;
        }catch(IOException | SecurityException e) {
            return null;
        }
    }

    private static int indexOf(TorrentNativeFile row) {
        return row.getFileIndex()==null?-1:row.getFileIndex();
    }
    private static long sizeOf(TorrentNativeFile row) {
        return row.getTotalBytes()==null?0:row.getTotalBytes();
    }
    private static String fileName(String relativePath) {
        int slash=relativePath.lastIndexOf('/');
        return slash>=0?relativePath.substring(slash+1):relativePath;
    }
}
