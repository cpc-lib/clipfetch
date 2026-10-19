package cc.ivera.magnet.engine.metainfo;

import cc.ivera.magnet.engine.codec.BencodeDecoder;
import cc.ivera.magnet.engine.codec.BencodeDecoder.*;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** BEP3 info parsing, with original-info SHA-1 and strong path/size validation. */
public final class TorrentV1MetainfoParser {
    private static final int MAX_FILES = 100_000;
    private static final long MAX_PIECES = 1_000_000;
    private static final long MAX_PIECE_LENGTH = 64L * 1024 * 1024;
    private final BencodeDecoder decoder = new BencodeDecoder();

    public TorrentInfo parse(byte[] data) {
        TorrentDocument root = decoder.decodeTorrent(data);
        DictionaryValue info = dictionary(root.root().required("info"));
        if (info.get("meta version") != null || info.get("file tree") != null) {
            throw new UnsupportedOperationException("BitTorrent v2/hybrid is not supported by the v1 engine");
        }
        long pieceLengthLong = integer(info.required("piece length"));
        if (pieceLengthLong < 1 || pieceLengthLong > MAX_PIECE_LENGTH)
            throw new IllegalArgumentException("Piece length out of bounds");
        int pieceLength = (int) pieceLengthLong;
        byte[] hashBytes = bytes(info.required("pieces"));
        if (hashBytes.length % 20 != 0) throw new IllegalArgumentException("Invalid SHA-1 piece hash bytes");
        String name = segment(text(bytes(info.required("name"))));
        List<PieceLayout.FileEntry> entries = new ArrayList<>();
        long offset = 0;
        Value multi = info.get("files");
        if (multi != null) {
            if (!(multi instanceof ListValue list)) throw new IllegalArgumentException("files is not a list");
            Set<String> paths = new HashSet<>();
            for (Value val : list.values()) {
                if (entries.size() >= MAX_FILES) throw new IllegalArgumentException("Too many files");
                DictionaryValue f = dictionary(val);
                long length = nonnegativeLength(f.required("length"));
                if (!(f.required("path") instanceof ListValue pathParts) || pathParts.values().isEmpty())
                    throw new IllegalArgumentException("Invalid file path");
                StringBuilder relative = new StringBuilder(name);
                for (Value part : pathParts.values()) relative.append('/').append(segment(text(bytes(part))));
                String relativePath = relative.toString();
                if (!paths.add(relativePath.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("Duplicate file name after case folding");
                entries.add(new PieceLayout.FileEntry(entries.size(), relativePath, offset, length));
                offset = Math.addExact(offset, length);
            }
        } else {
            long length = nonnegativeLength(info.required("length"));
            entries.add(new PieceLayout.FileEntry(0, name, 0, length));
            offset = length;
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("Torrent must describe at least one file");
        long expected = offset == 0 ? 0 : 1 + (offset - 1) / pieceLength;
        if (expected > MAX_PIECES || hashBytes.length / 20 != expected)
            throw new IllegalArgumentException("Piece count and torrent size mismatch");
        byte[] digest = sha1(root.infoBytes());
        return new TorrentInfo(HexFormat.of().formatHex(digest), name, List.copyOf(entries),
                offset, pieceLength, hashBytes);
    }

    private static DictionaryValue dictionary(Value value) {
        if (value instanceof DictionaryValue d) return d;
        throw new IllegalArgumentException("Expected bencode dictionary");
    }
    private static long integer(Value value) {
        if (value instanceof IntegerValue n) return n.value();
        throw new IllegalArgumentException("Expected bencode integer");
    }
    private static long nonnegativeLength(Value value) {
        long num = integer(value);
        if (num < 0) throw new IllegalArgumentException("Negative file length");
        return num;
    }
    private static byte[] bytes(Value value) {
        if (value instanceof BytesValue b) return b.bytes();
        throw new IllegalArgumentException("Expected bencode byte string");
    }
    private static String text(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Malformed UTF-8 filename", e);
        }
    }
    private static String segment(String value) {
        if (value.isBlank() || value.equals(".") || value.equals("..") || value.endsWith(".") || value.endsWith(" ")
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Unsafe torrent path segment");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127) throw new IllegalArgumentException("Control character in filename");
        }
        String base = value.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5",
                   "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4",
                   "LPT5", "LPT6", "LPT7", "LPT8", "LPT9").contains(base))
            throw new IllegalArgumentException("Reserved filename");
        return value;
    }
    private static byte[] sha1(byte[] data) {
        try { return MessageDigest.getInstance("SHA-1").digest(data); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public record TorrentInfo(String infoHash, String name, List<PieceLayout.FileEntry> files,
                              long totalBytes, int pieceLength, byte[] pieceSha1) {
        public TorrentInfo {
            files = List.copyOf(files);
            pieceSha1 = pieceSha1.clone();
        }
        @Override public byte[] pieceSha1() { return pieceSha1.clone(); }
        public PieceLayout pieceLayout() { return new PieceLayout(files, totalBytes, pieceLength); }
    }
}
