package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.NativeDownloadRoot;
import cc.ivera.magnet.domain.NativeDownloadRootMapper;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Owner-scoped native download roots.
 *
 * The server only ever offers directories inside its administrator-approved bases,
 * so a client supplied path is never trusted: it must already live inside an
 * approved base, exist as a real directory (no symbolic links) and must not be an
 * OS-managed location. Task output still appends {@code userId/taskUuid} below the
 * selected directory, keeping multi-user isolation intact.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NativeDownloadRootService {
    /** Never offered even when an administrator widened the approved bases. */
    private static final List<String> SENSITIVE=List.of(
            "/etc","/usr","/bin","/sbin","/boot","/dev","/proc","/sys","/root","/lib","/lib64",
            "c:/windows","c:/program files","c:/program files (x86)","c:/programdata",
            "c:/system volume information");
    private static final int MAX_ENTRIES=200;

    private final NativeDownloadRootMapper mapper;
    private final NativeTorrentProperties settings;

    public record Entry(String name,String path) {}
    public record Browse(String path,String parent,List<Entry> entries) {}
    public record Current(String rootPath,String defaultRootPath,List<String> bases) {}
    public record Selected(String rootPath) {}

    public Current current(long owner) {
        requireOwner(owner);
        return new Current(selectedRoot(owner).map(Path::toString).orElse(null),
                NativeTaskStoragePaths.approvedBases(settings).get(0).toString(),
                NativeTaskStoragePaths.approvedBases(settings).stream().map(Path::toString).toList());
    }

    /** The saved root, or empty when unset or no longer selectable (falls back to the default root). */
    public Optional<Path> selectedRoot(long owner) {
        requireOwner(owner);
        NativeDownloadRoot row=mapper.findActiveByUser(owner);
        if(row==null || row.getRootPath()==null || row.getRootPath().isBlank()) return Optional.empty();
        try {
            return Optional.of(requireSelectable(row.getRootPath()));
        }catch(IllegalArgumentException | SecurityException e) {
            log.warn("Native 下载目录已失效，回退到服务器默认目录：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Directories only: never exposes file names or contents, only folder entries. */
    public Browse browse(long owner,String requested) {
        requireOwner(owner);
        List<Path> bases=selectableBases();
        if(requested==null || requested.isBlank()) return new Browse(null,null,listBases(settings));
        Path directory=requireSelectable(requested);
        return new Browse(directory.toString(),parentWithin(directory,bases),
                listDirectories(List.of(directory)));
    }

    /**
     * The root level only offers the administrator-configured extra roots. The
     * default storage root is intentionally left out: it is the fallback shown as
     * {@code defaultRootPath}, not a folder a client gets to "pick" explicitly.
     */
    private static List<Entry> listBases(NativeTorrentProperties settings) {
        List<Entry> entries=new ArrayList<>();
        List<String> configured=settings.getDownloadRootBases();
        if(configured!=null) for(String raw:configured) {
            if(raw==null || raw.isBlank()) continue;
            Path base=Path.of(raw.trim()).toAbsolutePath().normalize();
            // The default root is internal task storage, never a user-browsable folder,
            // even if the administrator also configured an overlapping extra base.
            Path defaultRoot=NativeTaskStoragePaths.approvedBases(settings).get(0);
            if(isSensitive(base) || base.startsWith(defaultRoot) ||
                    !Files.isDirectory(base) || containsSymbolicLink(base)) continue;
            String name=base.getFileName()==null?base.toString():base.getFileName().toString();
            entries.add(new Entry(name,base.toString()));
        }
        entries.sort((left,right)->String.CASE_INSENSITIVE_ORDER.compare(left.name(),right.name()));
        return List.copyOf(entries);
    }

    public Selected save(long owner,String rawPath) {
        requireOwner(owner);
        Path directory=requireSelectable(rawPath);
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
        NativeDownloadRoot existing=mapper.findActiveByUser(owner);
        if(existing==null) {
            NativeDownloadRoot row=NativeDownloadRoot.builder().userId(owner)
                    .rootPath(directory.toString()).createdAt(now).updatedAt(now).deleted(0L).build();
            if(mapper.insertRoot(row)!=1) throw new IllegalStateException("未能保存下载目录");
        }else if(mapper.updateActivePath(owner,directory.toString())!=1) {
            throw new IllegalStateException("未能更新下载目录");
        }
        return new Selected(directory.toString());
    }

    /** Drops the saved selection so the administrator default root applies again. */
    public void clear(long owner) {
        requireOwner(owner);
        mapper.logicalDeleteActive(owner);
    }

    Path requireSelectable(String rawPath) {
        if(rawPath==null || rawPath.isBlank() || rawPath.length()>1024)
            throw new IllegalArgumentException("目录路径无效");
        Path candidate;
        try { candidate=Path.of(rawPath.trim()).toAbsolutePath().normalize(); }
        catch(InvalidPathException e) { throw new IllegalArgumentException("目录路径无效"); }
        if(candidate.getParent()==null || candidate.getFileName()==null)
            throw new SecurityException("不允许选择文件系统根目录");
        Path defaultRoot=NativeTaskStoragePaths.approvedBases(settings).get(0);
        if(candidate.startsWith(defaultRoot) || selectableBases().stream().noneMatch(candidate::startsWith))
            throw new SecurityException("仅允许选择服务器单独批准的下载目录，不能浏览内部任务存储区");
        NativeTaskStoragePaths.rejectSymbolicLinks(candidate);
        if(isSensitive(candidate)) throw new SecurityException("不允许选择系统敏感目录");
        if(!Files.exists(candidate)) throw new IllegalArgumentException("目录不存在");
        if(!Files.isDirectory(candidate)) throw new IllegalArgumentException("请选择一个文件夹");
        return candidate;
    }

    private List<Entry> listDirectories(List<Path> directories) {
        List<Entry> entries=new ArrayList<>();
        for(Path directory:directories) {
            if(!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) continue;
            try(var children=Files.newDirectoryStream(directory)) {
                for(Path child:children) {
                    if(entries.size()>=MAX_ENTRIES) break;
                    if(!Files.isDirectory(child) || Files.isSymbolicLink(child)) continue;
                    if(isSensitive(child) || child.toAbsolutePath().normalize()
                            .startsWith(NativeTaskStoragePaths.approvedBases(settings).get(0))) continue;
                    entries.add(new Entry(child.getFileName().toString(),
                            child.toAbsolutePath().normalize().toString()));
                }
            }catch(IOException e) {
                throw new IllegalStateException("无法读取目录：" + directory,e);
            }
        }
        entries.sort((left,right)->String.CASE_INSENSITIVE_ORDER.compare(left.name(),right.name()));
        return List.copyOf(entries.size()>MAX_ENTRIES?entries.subList(0,MAX_ENTRIES):entries);
    }

    private List<Path> selectableBases() {
        // The first approved base is the built-in storage root, not a user grant.
        return NativeTaskStoragePaths.approvedBases(settings).stream().skip(1).toList();
    }

    private static boolean containsSymbolicLink(Path path) {
        try { NativeTaskStoragePaths.rejectSymbolicLinks(path); return false; }
        catch(SecurityException e) { return true; }
    }

    private static String parentWithin(Path directory,List<Path> bases) {
        Path parent=directory.getParent();
        if(parent==null) return null;
        for(Path base:bases) if(parent.startsWith(base)) return parent.toString();
        return null;
    }

    private static boolean isSensitive(Path path) {
        String value=path.toAbsolutePath().normalize().toString()
                .replace('\\','/').toLowerCase(Locale.ROOT);
        if(value.equals("/") || value.matches("[a-z]:/?")) return true;
        for(String prefix:SENSITIVE)
            if(value.equals(prefix) || value.startsWith(prefix+"/")) return true;
        return false;
    }

    private static void requireOwner(long owner) {
        if(owner<=0) throw new IllegalArgumentException("Invalid download root owner");
    }
}
