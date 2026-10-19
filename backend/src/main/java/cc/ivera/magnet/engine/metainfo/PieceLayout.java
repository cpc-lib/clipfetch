package cc.ivera.magnet.engine.metainfo;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A v1 torrent concatenates all files before slicing global bytes into pieces.
 * Progress credits only selected-file overlap of fully verified, durably saved pieces.
 */
public final class PieceLayout {
    private final List<FileEntry> files;
    private final long totalBytes;
    private final int pieceLength;
    private final int pieceCount;

    public PieceLayout(List<FileEntry> files, long totalBytes, int pieceLength) {
        if (files == null || files.isEmpty() || totalBytes < 0 || pieceLength < 1)
            throw new IllegalArgumentException("Invalid piece layout");
        long expected = 0;
        for (int i = 0; i < files.size(); i++) {
            FileEntry f = files.get(i);
            if (f.index() != i || f.start() != expected || f.length() < 0)
                throw new IllegalArgumentException("Invalid file ranges");
            expected = Math.addExact(expected, f.length());
        }
        if (expected != totalBytes) throw new IllegalArgumentException("File ranges exceed torrent size");
        long count = totalBytes == 0 ? 0 : 1 + (totalBytes - 1) / pieceLength;
        if (count > 1_000_000) throw new IllegalArgumentException("Too many pieces");
        this.files = List.copyOf(files);
        this.totalBytes = totalBytes;
        this.pieceLength = pieceLength;
        this.pieceCount = (int) count;
    }

    public int pieceCount() { return pieceCount; }

    public long selectedTotalBytes(Set<Integer> chosen) {
        Set<Integer> selected = validateSelection(chosen);
        long sum = 0;
        for (FileEntry file : files) if (selected.contains(file.index())) sum = Math.addExact(sum, file.length());
        return sum;
    }

    public BitSet wantedPieces(Set<Integer> chosen) {
        Set<Integer> selected = validateSelection(chosen);
        BitSet wanted = new BitSet(pieceCount);
        for (FileEntry file : files) {
            if (!selected.contains(file.index()) || file.length() == 0) continue;
            int begin = (int) (file.start() / pieceLength);
            int endExclusive = (int) ((file.start() + file.length() - 1) / pieceLength) + 1;
            wanted.set(begin, endExclusive);
        }
        return wanted;
    }

    public long verifiedSelectedBytes(Set<Integer> chosen, BitSet verifiedPieces) {
        Set<Integer> selected = validateSelection(chosen);
        if (verifiedPieces.length() > pieceCount)
            throw new IllegalArgumentException("Verified bitmap contains invalid pieces");
        long result = 0;
        for (int piece = verifiedPieces.nextSetBit(0); piece >= 0; piece = verifiedPieces.nextSetBit(piece + 1)) {
            long from = (long) piece * pieceLength;
            long to = Math.min(totalBytes, from + pieceLength);
            for (FileEntry file : files) {
                if (!selected.contains(file.index())) continue;
                long overlap = Math.max(0, Math.min(to, file.end()) - Math.max(from, file.start()));
                result = Math.addExact(result, overlap);
            }
        }
        return result;
    }

    public double progressPercent(Set<Integer> chosen, BitSet verifiedPieces) {
        long total = selectedTotalBytes(chosen);
        if (total == 0) return 100.0;
        return Math.min(100.0, 100.0 * verifiedSelectedBytes(chosen, verifiedPieces) / total);
    }

    private Set<Integer> validateSelection(Set<Integer> chosen) {
        if (chosen == null || chosen.isEmpty()) throw new IllegalArgumentException("Choose at least one file");
        Set<Integer> indexes = new HashSet<>(chosen);
        for (int index : indexes) {
            if (index < 0 || index >= files.size()) throw new IllegalArgumentException("File index out of bounds");
        }
        return indexes;
    }

    public record FileEntry(int index, String path, long start, long length) {
        public long end() { return Math.addExact(start, length); }
    }
}
