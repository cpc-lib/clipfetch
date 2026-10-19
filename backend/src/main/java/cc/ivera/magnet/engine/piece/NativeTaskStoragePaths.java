package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import java.nio.file.*;
import java.util.*;

/**
 * Resolve only server-approved userId/taskUuid storage roots.
 * A client may never supply a raw absolute path: the base directory must already
 * live inside an administrator-configured download-root base (or the default root).
 */
final class NativeTaskStoragePaths {
    private NativeTaskStoragePaths() {}

    /** Administrator-approved bases: the default storage root plus any configured download-root bases. */
    static List<Path> approvedBases(NativeTorrentProperties settings) {
        if(settings==null || settings.getStorageRoot()==null || settings.getStorageRoot().isBlank())
            throw new IllegalArgumentException("Invalid native task storage identity");
        LinkedHashSet<Path> bases=new LinkedHashSet<>();
        bases.add(Path.of(settings.getStorageRoot()).toAbsolutePath().normalize());
        List<String> configured=settings.getDownloadRootBases();
        if(configured!=null) for(String extra:configured) {
            if(extra==null || extra.isBlank()) continue;
            bases.add(Path.of(extra.trim()).toAbsolutePath().normalize());
        }
        return List.copyOf(bases);
    }

    /** True when the path itself lies inside one of the approved bases. */
    static boolean isApproved(NativeTorrentProperties settings,Path candidate) {
        if(candidate==null) return false;
        Path normalized=candidate.toAbsolutePath().normalize();
        for(Path approved:approvedBases(settings))
            if(normalized.startsWith(approved)) return true;
        return false;
    }

    static Path resolve(NativeTorrentProperties settings,long owner,UUID task) {
        return resolve(settings,owner,task,null);
    }

    /** {@code base} of {@code null} falls back to the configured default storage root. */
    static Path resolve(NativeTorrentProperties settings,long owner,UUID task,Path base) {
        if(owner<=0 || task==null)
            throw new IllegalArgumentException("Invalid native task storage identity");
        List<Path> bases=approvedBases(settings);
        Path root=(base==null?bases.get(0):base).toAbsolutePath().normalize();
        if(!startsWithAny(root,bases))
            throw new SecurityException("Native task storage path escapes its approved root");
        Path output=root.resolve(Long.toString(owner)).resolve(task.toString()).normalize();
        if(!output.startsWith(root) || output.toString().length()>1024)
            throw new SecurityException("Native task storage path escapes its configured root");
        rejectSymbolicLinks(output);
        return output;
    }

    /** Re-validate an already persisted state path without trusting the current user selection. */
    static Path assertPersisted(NativeTorrentProperties settings,long owner,UUID task,String statePath) {
        if(owner<=0 || task==null || statePath==null || statePath.isBlank())
            throw new SecurityException("Native task storage path is missing");
        if(statePath.length()>1024)
            throw new SecurityException("Native task storage path is too long");
        Path stored=Path.of(statePath).toAbsolutePath().normalize();
        if(!task.toString().equals(String.valueOf(stored.getFileName())) ||
                stored.getParent()==null ||
                !Long.toString(owner).equals(String.valueOf(stored.getParent().getFileName())))
            throw new SecurityException("Native task storage path does not match its owner/task");
        if(!startsWithAny(stored,approvedBases(settings)))
            throw new SecurityException("Native task storage path escapes its approved root");
        rejectSymbolicLinks(stored);
        return stored;
    }

    private static boolean startsWithAny(Path candidate,List<Path> bases) {
        for(Path base:bases) if(candidate.startsWith(base)) return true;
        return false;
    }

    static void rejectSymbolicLinks(Path path) {
        Path segment=path.getRoot();
        for(Path part:path) {
            segment=segment.resolve(part);
            if(Files.isSymbolicLink(segment))
                throw new SecurityException("Symbolic link in native task path");
        }
    }
}
