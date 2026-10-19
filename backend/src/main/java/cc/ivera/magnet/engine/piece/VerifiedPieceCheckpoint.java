package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/**
 * P3 experimental durable verified-piece checkpoint.
 *
 * A Piece is credited only AFTER: SHA-1 check, shadow persistence when needed,
 * selected-file FileChannel.force, then atomic bitmap manifest rename.
 *
 * On every restart, ALL claimed Pieces are reconstructed from the on-disk
 * selected file ranges and optional boundary shadows, and SHA-1 checked again.
 * No network bytes alone ever count as downloaded progress.
 *
 * Caller MUST ensure taskRoot is a private, authorized userId/taskUuid directory.
 * It is not safe against hostile filesystem race/junction attacks yet.
 */
public final class VerifiedPieceCheckpoint {
    private static final int MAGIC = 0x43465043; // CFPC
    private static final int VERSION = 1;
    private final TorrentInfo info;
    private final Set<Integer> selectedFiles;
    private final PieceBlockPlanner planner;
    private final VerifiedPieceFileStore fileStore;
    private final Path stateRoot;
    private final Path manifest;
    private final UUID taskId;
    private final long userId;
    private final BitSet verified = new BitSet();
    // Only the original freshly reverified checkpoint may consume this token.
    // An arbitrary stale in-memory checkpoint must never bypass disk recovery.
    private boolean freshReconciliationAvailable;

    public VerifiedPieceCheckpoint(TorrentInfo info, Set<Integer> selectedFiles,
                                   Path taskRoot, long userId, UUID taskId) throws IOException {
        if(info==null || taskRoot==null || selectedFiles==null ||
                userId<=0 || taskId==null || !info.infoHash().matches("(?i)[0-9a-f]{40}"))
            throw new IllegalArgumentException("Invalid checkpoint identity");
        this.info=info;
        this.userId=userId;
        this.taskId=taskId;
        this.selectedFiles=Set.copyOf(selectedFiles);
        this.planner=new PieceBlockPlanner(info,this.selectedFiles);
        this.fileStore=new VerifiedPieceFileStore(info,this.selectedFiles,taskRoot);
        this.stateRoot=taskRoot.toAbsolutePath().normalize().resolve(".clipfetch-native-state");
        if(Files.isSymbolicLink(stateRoot))throw new IOException("Unsafe native state directory");
        Files.createDirectories(stateRoot);
        if(!Files.isDirectory(stateRoot,LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(stateRoot))
            throw new IOException("Native state is not a regular directory");
        this.manifest=stateRoot.resolve("checkpoint.v1");
        loadAndReverify();
        freshReconciliationAvailable=true;
    }

    TorrentInfo torrentInfo() { return info; }
    Set<Integer> selection() { return selectedFiles; }
    public synchronized BitSet verifiedPieces() { return (BitSet)verified.clone(); }
    public synchronized int completedPieces() { return verified.cardinality(); }
    public synchronized long verifiedSelectedBytes() {
        return info.pieceLayout().verifiedSelectedBytes(selectedFiles,verified);
    }
    public long selectedTotalBytes() { return info.pieceLayout().selectedTotalBytes(selectedFiles); }
    public synchronized double progressPercent() {
        return info.pieceLayout().progressPercent(selectedFiles,verified);
    }
    public synchronized boolean isComplete() {
        return selectedTotalBytes() > 0 && verifiedSelectedBytes() >= selectedTotalBytes();
    }
    public BitSet wantedPieces() { return planner.wantedPieces(); }

    /**
     * Rename the verified selected .part files to their real torrent names.
     * Only admissible once EVERY wanted Piece is durably verified; recovery
     * accepts either name, so the rename never invalidates the checkpoint.
     */
    public synchronized void finalizeVerifiedFiles() throws IOException {
        BitSet missing=planner.wantedPieces();
        missing.andNot(verified);
        if(!missing.isEmpty())
            throw new IllegalStateException("Torrent is not fully verified");
        fileStore.finalizeVerifiedFiles();
    }

    /**
     * Re-read the actual files before presenting recovery progress to the database.
     * An in-memory bitmap alone is never sufficient evidence after a pause/restart.
     */
    public synchronized VerifiedPieceCheckpoint rescanOwned(long owner,UUID task,String hash,String databaseStatePath)
            throws IOException {
        assertOwned(owner,task,hash,databaseStatePath);
        freshReconciliationAvailable=false;
        return new VerifiedPieceCheckpoint(info,selectedFiles,stateRoot.getParent(),userId,taskId);
    }

    /**
     * Used ONLY by the active, CAS-fenced Worker after a SHA-1 checked, fsynced
     * Piece commit. The checkpoint was fully reverified when opened, so repeating
     * an O(all previously downloaded bytes) rescan after every Piece is wasteful.
     * Stopped/recovered tasks MUST continue using rescanOwned().
     */
    synchronized VerifiedPieceCheckpoint consumeFreshlyReverified(long owner,UUID task,
                                                                  String hash,String databaseStatePath) {
        assertOwned(owner,task,hash,databaseStatePath);
        if(!freshReconciliationAvailable)
            throw new IllegalStateException("Fresh checkpoint verification has already been consumed");
        freshReconciliationAvailable=false;
        return this;
    }

    public synchronized VerifiedPieceCheckpoint activeOwned(long owner,UUID task,
                                                              String hash,String databaseStatePath) {
        assertOwned(owner,task,hash,databaseStatePath);
        return this;
    }

    private void assertOwned(long owner,UUID task,String hash,String databaseStatePath) {
        if(owner!=userId || !taskId.equals(task) || hash==null ||
                !info.infoHash().equalsIgnoreCase(hash) || databaseStatePath==null ||
                !stateRoot.getParent().equals(Path.of(databaseStatePath).toAbsolutePath().normalize()))
            throw new SecurityException("Checkpoint does not belong to this user/task and storage root");
    }

    @FunctionalInterface
    public interface CommitFence {
        void check() throws IOException;
    }

    /**
     * Serialize cooperative JVM writers to the same checkpoint. Recheck the
     * durable execution fence after acquiring the file lock, immediately
     * before disk writes. Other claim paths must cooperate for full fencing.
     */
    public synchronized void commit(int piece,byte[] data,CommitFence fence) throws IOException {
        if(fence==null)throw new IllegalArgumentException("Missing commit fence");
        if(!stagedPieces.isEmpty())
            throw new IllegalStateException("Pending group must be published or discarded first");
        freshReconciliationAvailable=false;
        NativeCheckpointOwnershipLock.requireLocalStorage(stateRoot);
        try(FileChannel lockChannel=FileChannel.open(stateRoot.resolve("piece-commit.lock"),
                StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            // Never wait indefinitely on a different JVM, nor block interruption.
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            java.nio.channels.FileLock lock=null;
            while(lock==null) {
                if(Thread.currentThread().isInterrupted())
                    throw new IOException("Native checkpoint commit interrupted");
                fence.check();
                try {lock=lockChannel.tryLock();}
                catch(java.nio.channels.OverlappingFileLockException busy) {
                    // A different checkpoint instance in this JVM owns the lock.
                }
                if(lock==null) {
                    if(System.nanoTime()>=deadline)
                        throw new IOException("Native checkpoint commit lock timed out");
                    try {Thread.sleep(25);}
                    catch(InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Native checkpoint commit interrupted",cancelled);
                    }
                }
            }
            try(java.nio.channels.FileLock acquired=lock) {
                fence.check();
                persistPieceUnderLock(piece,data);
            }
        }
    }

    /** A small durable group commit: all Piece payloads are fsynced before a
     * single manifest rename, and the caller performs one fenced DB CAS.
     * If interrupted midway, the old manifest is still authoritative; orphan
     * payload bytes are never credited without a later fresh verification.
     */
    record BatchPiece(int index,byte[] bytes) {}
    private static final int MAX_GROUP_PIECES=256;
    private final BitSet stagedPieces=new BitSet();
    private Thread stagingOwner;

    @FunctionalInterface
    private interface LockedOperation {void execute() throws IOException;}

    private void withCommitLock(CommitFence fence,LockedOperation operation) throws IOException {
        if(fence==null)throw new IllegalArgumentException("Missing execution fence");
        NativeCheckpointOwnershipLock.requireLocalStorage(stateRoot);
        try(FileChannel channel=FileChannel.open(stateRoot.resolve("piece-commit.lock"),
                StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            java.nio.channels.FileLock acquired=null;
            while(acquired==null) {
                if(Thread.currentThread().isInterrupted())
                    throw new IOException("Native group commit interrupted");
                fence.check();
                try {acquired=channel.tryLock();}
                catch(java.nio.channels.OverlappingFileLockException busy) {}
                if(acquired==null) {
                    if(System.nanoTime()>=deadline)
                        throw new IOException("Native group commit lock timed out");
                    try {Thread.sleep(25);}
                    catch(InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Native group commit interrupted",cancelled);
                    }
                }
            }
            try(java.nio.channels.FileLock ignored=acquired) {
                fence.check();
                operation.execute();
            }
        }
    }

    /** Stream each verified Piece to fsynced storage without retaining its payload.
     * No checkpoint/DB progress is credited until publishStagedGroup succeeds.
     */
    synchronized void stageGroupPiece(int piece,byte[] bytes,CommitFence fence) throws IOException {
        if(bytes==null || piece<0 || piece>=planner.pieceCount() ||
                bytes.length!=planner.pieceBytes(piece) || !planner.wantedPieces().get(piece) ||
                verified.get(piece) || stagedPieces.get(piece) ||
                stagedPieces.cardinality()>=MAX_GROUP_PIECES)
            throw new IllegalArgumentException("Invalid or duplicate staged Piece");
        if(stagingOwner!=null && stagingOwner!=Thread.currentThread())
            throw new IllegalStateException("Another owner has pending Piece writes");
        freshReconciliationAvailable=false;
        withCommitLock(fence,()->{
            persistPayloadUnderLock(piece,bytes);
            stagedPieces.set(piece);
            stagingOwner=Thread.currentThread();
        });
    }

    synchronized int stagedGroupSize() {return stagedPieces.cardinality();}
    /** SHA-1 verified, fsynced payload awaiting the manifest and DB CAS.
     * This is transient telemetry, not durable credited progress.
     */
    synchronized long stagedSelectedBytes() {
        return info.pieceLayout().verifiedSelectedBytes(selectedFiles,stagedPieces);
    }

    /** Publish one durable bitmap update only after every staged file was fsynced.
     * The caller must then issue one fenced CAS for the whole group.
     */
    synchronized void publishStagedGroup(CommitFence fence) throws IOException {
        if(stagedPieces.isEmpty() || stagingOwner!=Thread.currentThread())
            throw new IllegalStateException("No owned staged Piece group to publish");
        withCommitLock(fence,()->{
            BitSet next=(BitSet)verified.clone();
            next.or(stagedPieces);
            storeManifest(next);
            verified.or(stagedPieces);
            stagedPieces.clear();
            stagingOwner=null;
        });
    }

    /** An aborted group leaves orphan payloads, not credited checkpoint bits. */
    synchronized void discardStagedGroup() {
        if(stagingOwner!=null && stagingOwner!=Thread.currentThread())
            throw new IllegalStateException("Only the staging owner can discard Pieces");
        stagedPieces.clear();
        stagingOwner=null;
    }

    /** Compatibility API, also streams payloads rather than holding a 64-Piece buffer. */
    synchronized void commitBatch(List<BatchPiece> batch,CommitFence fence) throws IOException {
        if(batch==null || batch.isEmpty() || batch.size()>MAX_GROUP_PIECES || fence==null)
            throw new IllegalArgumentException("Invalid Piece commit batch");
        if(!stagedPieces.isEmpty())throw new IllegalStateException("Pending checkpoint group");
        if(batch.size()==1) {
            var one=batch.get(0);
            commit(one.index(),one.bytes(),fence);
            return;
        }
        Set<Integer> unique=new HashSet<>();
        for(var entry:batch) {
            if(entry==null || !unique.add(entry.index()))
                throw new IllegalArgumentException("Duplicate or missing Piece");
        }
        try {
            for(var entry:batch)stageGroupPiece(entry.index(),entry.bytes(),fence);
            publishStagedGroup(fence);
        }catch(IOException | RuntimeException failure) {
            discardStagedGroup();
            throw failure;
        }
    }

    private void persistPieceUnderLock(int piece,byte[] data) throws IOException {
        persistPayloadUnderLock(piece,data);
        if(!verified.get(piece)) {
            BitSet next=(BitSet)verified.clone();
            next.set(piece);
            storeManifest(next); // durable manifest is last, always
            verified.set(piece);
        }
    }

    private void persistPayloadUnderLock(int piece,byte[] data) throws IOException {
        if(data==null || data.length>PieceBlockAssembler.MAX_BUFFERED_PIECE_BYTES ||
                !planner.wantedPieces().get(piece))
            throw new IllegalArgumentException("Piece not selected or over memory limit");
        if(!PieceHashVerifier.verify(info,piece,data))
            throw new IOException("Piece SHA-1 mismatch before checkpoint");

        // Preserve unselected bytes only for mixed selected/unselected boundary Pieces.
        if(fileStore.selectedOverlapBytes(piece)<data.length)
            writeAtomic(stateRoot.resolve(shadowName(piece)),data);

        // The guarded checkpoint verified the Piece immediately above. Avoid
        // a third SHA-1 pass in the file writer while retaining the fsync fence.
        fileStore.persistCheckpointVerifiedPiece(piece,data);
    }

    private void loadAndReverify() throws IOException {
        if(!Files.exists(manifest,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(manifest) ||
                Files.size(manifest)>256*1024)throw new IOException("Unsafe checkpoint manifest");
        BitSet claims;
        try(DataInputStream in=new DataInputStream(Files.newInputStream(manifest,LinkOption.NOFOLLOW_LINKS))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)
                throw new IOException("Unknown native checkpoint version");
            if(in.readLong()!=userId || !in.readUTF().equals(taskId.toString()) ||
                    !in.readUTF().equalsIgnoreCase(info.infoHash()))
                throw new IOException("Checkpoint belongs to a different user/task/torrent");
            int size=in.readInt();
            if(size!=selectedFiles.size() || size>100000)throw new IOException("Checkpoint selection mismatch");
            Set<Integer> saved=new HashSet<>();
            for(int i=0;i<size;i++)saved.add(in.readInt());
            if(!saved.equals(selectedFiles))throw new IOException("Checkpoint file selection mismatch");
            int bytes=in.readInt();
            if(bytes<0 || bytes>(planner.pieceCount()+7)/8)
                throw new IOException("Checkpoint bitmap exceeds Piece count");
            byte[] bitmap=new byte[bytes];
            in.readFully(bitmap);
            if(in.read()!=-1)throw new IOException("Unexpected trailing checkpoint bytes");
            claims=BitSet.valueOf(bitmap);
            if(claims.length()>planner.pieceCount())
                throw new IOException("Checkpoint references nonexistent Piece");
        }catch(EOFException e) {
            throw new IOException("Truncated native checkpoint",e);
        }

        boolean repaired=false;
        BitSet wanted=planner.wantedPieces();
        for(int piece=claims.nextSetBit(0);piece>=0;piece=claims.nextSetBit(piece+1)) {
            if(!wanted.get(piece)) { claims.clear(piece); repaired=true; continue; }
            try {
                int length=planner.pieceBytes(piece);
                byte[] shadow=null;
                if(fileStore.selectedOverlapBytes(piece)<length)
                    shadow=readShadow(piece,length);
                byte[] actual=fileStore.readForRecovery(piece,shadow);
                if(!PieceHashVerifier.verify(info,piece,actual)) {
                    claims.clear(piece);
                    repaired=true;
                }
            }catch(IOException | IllegalArgumentException failure) {
                claims.clear(piece);
                repaired=true;
            }
        }
        verified.or(claims);
        if(repaired)storeManifest(verified); // remove invalid progress claims
    }

    private byte[] readShadow(int piece,int length) throws IOException {
        Path target=stateRoot.resolve(shadowName(piece));
        if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(target) || Files.size(target)!=length)
            throw new IOException("Missing or invalid boundary Piece shadow");
        try(FileChannel channel=FileChannel.open(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer data=ByteBuffer.allocate(length);
            while(data.hasRemaining())if(channel.read(data)<0)
                throw new IOException("Truncated boundary shadow");
            return data.array();
        }
    }

    private String shadowName(int piece) { return String.format(Locale.ROOT,"boundary-%08d.bin",piece); }

    private void storeManifest(BitSet bits) throws IOException {
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(data)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(userId);
            out.writeUTF(taskId.toString());
            out.writeUTF(info.infoHash());
            out.writeInt(selectedFiles.size());
            for(int index:selectedFiles.stream().sorted().toList())out.writeInt(index);
            byte[] encoded=bits.toByteArray();
            out.writeInt(encoded.length);
            out.write(encoded);
        }
        byte[] bytes=data.toByteArray();
        if(bytes.length>256*1024)throw new IOException("Native checkpoint too large");
        writeAtomic(manifest,bytes);
    }

    private void writeAtomic(Path destination,byte[] contents) throws IOException {
        if(Files.isSymbolicLink(stateRoot) || Files.isSymbolicLink(destination))
            throw new IOException("Unsafe native checkpoint destination");
        Path temp=stateRoot.resolve(".checkpoint-"+UUID.randomUUID()+".tmp");
        try {
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer payload=ByteBuffer.wrap(contents);
                while(payload.hasRemaining()) {
                    if(channel.write(payload)<=0)throw new IOException("Failed to write checkpoint data");
                }
                channel.force(true);
            }
            VerifiedPieceFileStore.moveAtomically(temp,destination);
        }finally {
            Files.deleteIfExists(temp);
        }
    }
}
