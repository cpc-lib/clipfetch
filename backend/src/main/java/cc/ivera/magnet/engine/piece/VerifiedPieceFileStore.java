package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * P3 storage primitive: write a verified piece into selected-file .part targets.
 * Callers must provide an authorized, unique per-user/per-task root.
 * This is NOT an atomic multi-file transaction or a resumable checkpoint.
 */
public final class VerifiedPieceFileStore {
    private final TorrentInfo torrent;
    private final Set<Integer> selected;
    private final Path root;
    private final PieceBlockPlanner planner;

    public VerifiedPieceFileStore(TorrentInfo torrent,Set<Integer> selectedFiles,Path taskRoot)
            throws IOException {
        if (torrent == null || taskRoot == null) throw new IllegalArgumentException("Missing file storage arguments");
        this.planner = new PieceBlockPlanner(torrent,selectedFiles);
        this.torrent = torrent;
        this.selected = Set.copyOf(selectedFiles);
        this.root = taskRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) throw new IOException("Task root is a symbolic link");
        Files.createDirectories(root);
        if (!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            throw new IOException("Unsafe task root");
    }

    /**
     * Return bytes written to SELECTED file ranges only after all channels are forced.
     * Do not turn this return value into durable task progress until checkpoint recovery exists.
     */
    public synchronized long persistVerifiedPiece(int piece,byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > PieceBlockAssembler.MAX_BUFFERED_PIECE_BYTES
                || !planner.wantedPieces().get(piece)) throw new IllegalArgumentException("Piece not wanted");
        if (!PieceHashVerifier.verify(torrent,piece,bytes))
            throw new IOException("Piece SHA-1 mismatch; nothing was written");
        return persistCheckpointVerifiedPiece(piece,bytes);
    }

    /** Only the guarded VerifiedPieceCheckpoint calls this after checking SHA-1. */
    synchronized long persistCheckpointVerifiedPiece(int piece,byte[] bytes) throws IOException {
        if(bytes==null || bytes.length!=planner.pieceBytes(piece) ||
                !planner.wantedPieces().get(piece))
            throw new IllegalArgumentException("Invalid checkpoint-verified Piece");
        long pieceStart=(long)piece*torrent.pieceLength();
        long pieceEnd=pieceStart+bytes.length;
        long written=0;

        for (PieceLayout.FileEntry file : torrent.files()) {
            if (!selected.contains(file.index())) continue;
            long first=Math.max(pieceStart,file.start());
            long last=Math.min(pieceEnd,file.end());
            if (first>=last) continue;
            Path target=validatedTarget(file.path());
            long fileOffset=first-file.start();
            int dataOffset=Math.toIntExact(first-pieceStart);
            int length=Math.toIntExact(last-first);
            try(FileChannel channel=FileChannel.open(target,StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes,dataOffset,length);
                long position=fileOffset;
                while(buffer.hasRemaining()) {
                    int n=channel.write(buffer,position);
                    if(n<=0) throw new IOException("Failed to persist verified Piece");
                    position+=n;
                }
                channel.force(true);
            }
            written+=length;
        }
        return written;
    }

    /** Count selected bytes in a full Piece; boundary pieces can require a private shadow. */
    public long selectedOverlapBytes(int piece) {
        int pieceSize=planner.pieceBytes(piece);
        if(!planner.wantedPieces().get(piece))throw new IllegalArgumentException("Piece not selected");
        long start=(long)piece*torrent.pieceLength();
        long end=start+pieceSize;
        long selectedBytes=0;
        for(PieceLayout.FileEntry file:torrent.files()) {
            if(!selected.contains(file.index()))continue;
            selectedBytes+=Math.max(0,Math.min(end,file.end())-Math.max(start,file.start()));
        }
        return selectedBytes;
    }

    /**
     * Reconstruct an on-disk verified Piece from ALL selected .part file ranges.
     * For partially selected boundary pieces, the caller must pass a full-size
     * shadow containing the bytes belonging to unselected files.
     */
    public synchronized byte[] readForRecovery(int piece,byte[] boundaryShadow) throws IOException {
        int expected=planner.pieceBytes(piece);
        long selectedBytes=selectedOverlapBytes(piece);
        if(selectedBytes!=expected && (boundaryShadow==null || boundaryShadow.length!=expected))
            throw new IOException("Missing complete boundary Piece shadow");
        byte[] result=selectedBytes==expected?new byte[expected]:boundaryShadow.clone();
        long pieceStart=(long)piece*torrent.pieceLength();
        long pieceEnd=pieceStart+expected;
        for(PieceLayout.FileEntry file:torrent.files()) {
            if(!selected.contains(file.index()))continue;
            long from=Math.max(pieceStart,file.start());
            long to=Math.min(pieceEnd,file.end());
            if(to<=from)continue;
            Path target=existingTarget(file.path());
            if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Selected file missing during recovery");
            try(FileChannel channel=FileChannel.open(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                int size=Math.toIntExact(to-from);
                ByteBuffer out=ByteBuffer.wrap(result,Math.toIntExact(from-pieceStart),size);
                long pos=from-file.start();
                while(out.hasRemaining()) {
                    int n=channel.read(out,pos);
                    if(n<=0)throw new IOException("Incomplete .part file during recovery");
                    pos+=n;
                }
            }
        }
        return result;
    }

    /**
     * Rename every fully verified selected .part file to its real torrent name.
     * Callers must have verified ALL selected bytes first: this method only moves
     * existing bytes and never fabricates or truncates data. Idempotent, so a
     * failed run is safely retried.
     */
    public synchronized void finalizeVerifiedFiles() throws IOException {
        for(PieceLayout.FileEntry file:torrent.files()) {
            if(!selected.contains(file.index()))continue;
            Path part=validatedTarget(file.path());
            Path target=validatedFinalTarget(file.path());
            if(file.length()==0) {
                // No bytes to move, but an empty torrent file is still materialized.
                if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))Files.createFile(target);
                continue;
            }
            if(!Files.isRegularFile(part,LinkOption.NOFOLLOW_LINKS)) {
                if(Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)
                        && Files.size(target)==file.length())
                    continue; // an earlier run already finalized this file
                throw new IOException("Missing completed .part file for verified selection");
            }
            if(Files.size(part)!=file.length())
                throw new IOException("Incomplete .part file for verified selection");
            moveAtomically(part,target);
        }
    }

    /** Windows indexers/antivirus can briefly hold a rename target open. */
    static void moveAtomically(Path source,Path target) throws IOException {
        AccessDeniedException denied=null;
        for(int attempt=0;attempt<10;attempt++) {
            try {
                Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            }catch(AccessDeniedException e) {
                denied=e;
                try {Thread.sleep(50);}
                catch(InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Torrent file rename interrupted",denied);
                }
            }
        }
        throw denied;
    }

    private Path validatedTarget(String fileName) throws IOException {
        return validatedRelative(fileName,".clipfetch.part");
    }

    private Path validatedFinalTarget(String fileName) throws IOException {
        return validatedRelative(fileName,"");
    }

    /**
     * Resolve how a selected file is currently stored: prefer the in-progress
     * .part name, then an already finalized file at its torrent name. Preferring
     * the partial name never over-claims progress after a finalized rename.
     */
    private Path existingTarget(String fileName) throws IOException {
        Path part=validatedTarget(fileName);
        if(Files.isRegularFile(part,LinkOption.NOFOLLOW_LINKS))return part;
        Path finalized=validatedFinalTarget(fileName);
        if(Files.isRegularFile(finalized,LinkOption.NOFOLLOW_LINKS))return finalized;
        throw new IOException("Selected file missing during recovery");
    }

    private Path validatedRelative(String fileName,String suffix) throws IOException {
        if (fileName == null || fileName.isBlank()) throw new IOException("Invalid torrent file path");
        Path relative;
        try { relative=Path.of(fileName + suffix); }
        catch (RuntimeException e) { throw new IOException("Invalid torrent file path",e); }
        if (relative.isAbsolute() || relative.toString().contains(":"))
            throw new IOException("Torrent file path is not relative");
        Path target=root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root))
            throw new IOException("Torrent path escapes task root");
        Path parent=target.getParent();
        Path cursor=root;
        Path pathFromRoot=root.relativize(parent);
        for (Path component:pathFromRoot) {
            cursor=cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Symbolic link in torrent path");
            Files.createDirectories(cursor);
            if (!Files.isDirectory(cursor,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid torrent output directory");
        }
        if (Files.isSymbolicLink(target)) throw new IOException("Symbolic link as torrent output");
        return target;
    }
}
