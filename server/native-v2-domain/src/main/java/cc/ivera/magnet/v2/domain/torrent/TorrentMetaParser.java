package cc.ivera.magnet.v2.domain.torrent;

import cc.ivera.magnet.v2.domain.common.Digests;
import cc.ivera.magnet.v2.domain.common.ErrorCodes;
import cc.ivera.magnet.v2.domain.common.V2DomainException;
import cc.ivera.magnet.v2.domain.resource.VersionType;
import cc.ivera.magnet.v2.domain.torrent.bencode.BNode;
import cc.ivera.magnet.v2.domain.torrent.bencode.BencodeDecoder;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * .torrent 元数据解析（BEP 3 / BEP 52）：
 * - V1：SHA-1 infohash、pieces 连接哈希、files/length；
 * - V2：SHA-256 infohash、file tree、每文件 pieces root；
 * - Hybrid：两套元数据并存，交叉校验非 pad 文件一致。
 * infohash 对 info 字典的原始字节区间计算，杜绝重编码漂移。
 */
public final class TorrentMetaParser {

    public static final int MAX_TORRENT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_FILES = 100_000;
    private static final long MAX_PIECE_LENGTH = 1L << 30;
    private static final int V2_BLOCK_SIZE = 16 * 1024;
    private static final int SHA1_LENGTH = 20;
    private static final int SHA256_LENGTH = 32;

    public ParsedTorrent parse(byte[] torrentBytes) {
        if (torrentBytes == null || torrentBytes.length == 0) {
            throw invalid("torrent 内容为空");
        }
        if (torrentBytes.length > MAX_TORRENT_BYTES) {
            throw invalid("torrent 超过字节上限 " + MAX_TORRENT_BYTES);
        }
        BNode root = new BencodeDecoder().decode(torrentBytes, BencodeDecoder.Limits.torrentDefault());
        if (!(root instanceof BNode.BDict rootDict)) {
            throw invalid("顶层必须是字典");
        }
        BNode infoNode = rootDict.values().get("info");
        if (!(infoNode instanceof BNode.BDict info)) {
            throw invalid("缺少 info 字典");
        }
        byte[] infoBytes = Arrays.copyOfRange(torrentBytes, infoNode.start(), infoNode.end());

        BNode metaVersionNode = info.values().get("meta version");
        long metaVersion = 0;
        if (metaVersionNode != null) {
            if (!(metaVersionNode instanceof BNode.BInt version) || version.value() != 2) {
                throw invalid("meta version 存在时必须为 2");
            }
            metaVersion = 2;
        }
        String name = optText(info, "name.utf-8", null);
        if (name == null) {
            name = optText(info, "name", null);
        }
        if (name == null) {
            throw invalid("缺少 name");
        }
        name = PathSanitizer.sanitizeComponent(name);
        long pieceLength = reqInt(info, "piece length");
        if (pieceLength <= 0 || pieceLength > MAX_PIECE_LENGTH) {
            throw invalid("piece length 必须为正数且不超过 " + MAX_PIECE_LENGTH);
        }
        if (metaVersion == 2
                && ((pieceLength & (pieceLength - 1)) != 0 || pieceLength < V2_BLOCK_SIZE)) {
            throw invalid("V2 piece length 必须是 2 的幂且不能小于 " + V2_BLOCK_SIZE);
        }
        boolean privateFlag = optInt(info, "private", 0) == 1;
        List<String> trackers = parseTrackers(rootDict);

        boolean hasV2Tree = info.values().get("file tree") instanceof BNode.BDict;
        boolean hasV1Pieces = info.values().get("pieces") instanceof BNode.BBytes;
        boolean hasV1Files = info.values().get("files") instanceof BNode.BList
                || info.values().get("length") instanceof BNode.BInt;

        final VersionType versionType;
        final List<ParsedTorrentFile> files;
        final long totalBytes;
        final byte[] infoHashV1;
        final byte[] infoHashV2;

        if (metaVersion == 2) {
            if (!hasV2Tree) {
                throw invalid("V2 种子缺少 file tree");
            }
            List<ParsedTorrentFile> v2Files = new ArrayList<>();
            walkFileTree(asDict(info.values().get("file tree"), "file tree"), List.of(), v2Files);
            if (v2Files.isEmpty()) {
                throw invalid("file tree 为空");
            }
            long v2TotalBytes = checkedTotalBytes(v2Files);
            validatePieceLayers(rootDict, v2Files, pieceLength);
            infoHashV2 = Digests.sha256(infoBytes);
            if (hasV1Pieces || hasV1Files) {
                V1Side v1 = parseV1Side(info, name, pieceLength);
                crossCheckHybrid(v1, v2Files, pieceLength);
                versionType = VersionType.HYBRID;
                infoHashV1 = Digests.sha1(infoBytes);
                totalBytes = v2TotalBytes;
            } else {
                versionType = VersionType.V2;
                infoHashV1 = null;
                totalBytes = v2TotalBytes;
            }
            files = List.copyOf(v2Files);
        } else {
            if (!hasV1Pieces) {
                throw invalid("V1 种子缺少 pieces");
            }
            V1Side v1 = parseV1Side(info, name, pieceLength);
            versionType = VersionType.V1;
            files = v1.files();
            totalBytes = v1.totalBytes();
            infoHashV1 = Digests.sha1(infoBytes);
            infoHashV2 = null;
        }

        if (files.size() > MAX_FILES) {
            throw invalid("文件数超过限制 " + MAX_FILES);
        }
        List<ParsedTorrentFile> storedFiles = storagePaths(name, files);
        validateFilePathCollisions(storedFiles);
        if (privateFlag) {
            throw new V2DomainException(ErrorCodes.PRIVATE_TORRENT, "不支持 private torrent");
        }
        return new ParsedTorrent(infoHashV1, infoHashV2, versionType, name, pieceLength,
                totalBytes, false, trackers, storedFiles);
    }

    /**
     * Worker 以 resources/{resourceUuid} 作为 save_path，libtorrent 对多文件种子会把文件放在
     * save_path/{name}/... 下；单文件且文件路径本身等于 name 时则直接放在 save_path/{name}。
     * 因此 relative_path 必须以该落盘位置为准，否则 ticket 会解析到不存在的文件。
     */
    private static List<ParsedTorrentFile> storagePaths(String name, List<ParsedTorrentFile> files) {
        boolean prefixed = files.size() > 1
                || (files.size() == 1 && !files.get(0).relativePath().equals(name));
        if (!prefixed) {
            return files;
        }
        return files.stream()
                .map(file -> {
                    String path = name + "/" + file.relativePath();
                    if (path.length() > PathSanitizer.MAX_PATH_LENGTH) {
                        throw invalid("相对路径超长");
                    }
                    return new ParsedTorrentFile(file.index(), path, file.sizeBytes(), file.v2PiecesRoot());
                })
                .toList();
    }

    private record V1Side(List<ParsedTorrentFile> files,
                          long totalBytes,
                          List<V1Entry> entries) {
    }

    private V1Side parseV1Side(BNode.BDict info, String name, long pieceLength) {
        byte[] pieces = reqBytes(info, "pieces");
        if (pieces.length % SHA1_LENGTH != 0) {
            throw invalid("pieces 长度必须是 20 的倍数");
        }
        List<V1Entry> entries = new ArrayList<>();
        BNode filesNode = info.values().get("files");
        boolean hasFiles = info.values().containsKey("files");
        boolean hasLength = info.values().containsKey("length");
        if (hasFiles == hasLength) {
            throw invalid("必须且只能包含 files 或 length 其中之一");
        }
        if (hasFiles) {
            if (!(filesNode instanceof BNode.BList filesList)) {
                throw invalid("files 必须是列表");
            }
            if (filesList.values().isEmpty()) {
                throw invalid("files 列表不能为空");
            }
            int index = 0;
            for (BNode item : filesList.values()) {
                if (!(item instanceof BNode.BDict fileDict)) {
                    throw invalid("files 条目必须是字典");
                }
                long length = reqInt(fileDict, "length");
                if (length < 0) {
                    throw invalid("文件长度不能为负");
                }
                List<String> pathComponents = reqPathComponents(fileDict);
                boolean pad = isPadFile(fileDict);
                entries.add(new V1Entry(index++, PathSanitizer.joinRelativePath(pathComponents), length, pad));
            }
        } else if (info.values().get("length") instanceof BNode.BInt lengthNode) {
            long length = lengthNode.value();
            if (length < 0) {
                throw invalid("文件长度不能为负");
            }
            entries.add(new V1Entry(0, name, length, false));
        } else {
            throw invalid("length 必须是整数");
        }

        long total = checkedTotalEntries(entries);
        long expectedPieces = ceilingDivide(total, pieceLength);
        if (pieces.length / (long) SHA1_LENGTH != expectedPieces) {
            throw invalid("pieces 数量与总大小不匹配");
        }
        List<ParsedTorrentFile> files = entries.stream()
                .map(entry -> new ParsedTorrentFile(entry.index(), entry.path(), entry.length(), null))
                .toList();
        return new V1Side(List.copyOf(files), total, List.copyOf(entries));
    }

    private record V1Entry(int index, String path, long length, boolean pad) {
    }

    private static boolean isPadFile(BNode.BDict fileDict) {
        BNode attr = fileDict.values().get("attr");
        return attr instanceof BNode.BBytes bytes
                && new String(bytes.value(), StandardCharsets.UTF_8).contains("p");
    }

    private static long checkedTotalEntries(List<V1Entry> entries) {
        long total = 0;
        try {
            for (V1Entry entry : entries) {
                total = Math.addExact(total, entry.length());
            }
            return total;
        } catch (ArithmeticException ignored) {
            throw invalid("文件总大小溢出");
        }
    }

    private static long checkedTotalBytes(List<ParsedTorrentFile> files) {
        long total = 0;
        try {
            for (ParsedTorrentFile file : files) {
                total = Math.addExact(total, file.sizeBytes());
            }
            return total;
        } catch (ArithmeticException ignored) {
            throw invalid("文件总大小溢出");
        }
    }

    private static long ceilingDivide(long value, long divisor) {
        return value == 0 ? 0 : 1 + (value - 1) / divisor;
    }

    private void walkFileTree(BNode.BDict tree, List<String> prefix, List<ParsedTorrentFile> out) {
        for (Map.Entry<BNode.BKey, BNode> entry : tree.rawValues().entrySet()) {
            String name = decodeFileTreeKey(entry.getKey().value());
            if (name.isEmpty()) {
                throw invalid("file tree 中间层不允许出现叶子键");
            }
            if (!(entry.getValue() instanceof BNode.BDict node)) {
                throw invalid("file tree 节点必须是字典");
            }
            PathSanitizer.sanitizeComponent(name);
            BNode leaf = node.values().get("");
            List<String> path = new ArrayList<>(prefix);
            path.add(name);
            if (leaf != null) {
                if (!(leaf instanceof BNode.BDict leafDict)) {
                    throw invalid("file tree 叶子必须是字典");
                }
                if (node.rawValues().size() > 1) {
                    throw invalid("file tree 叶子节点不允许同时拥有子节点");
                }
                long length = reqInt(leafDict, "length");
                if (length < 0) {
                    throw invalid("文件长度不能为负");
                }
                byte[] piecesRoot = null;
                BNode rootNode = leafDict.values().get("pieces root");
                if (rootNode instanceof BNode.BBytes rootBytes) {
                    if (rootBytes.value().length != SHA256_LENGTH) {
                        throw invalid("pieces root 必须是 32 字节");
                    }
                    piecesRoot = rootBytes.value();
                }
                if (length > 0 && piecesRoot == null) {
                    throw invalid("非零长度文件缺少 pieces root");
                }
                if (out.size() >= MAX_FILES) {
                    throw invalid("文件数超过限制 " + MAX_FILES);
                }
                out.add(new ParsedTorrentFile(out.size(), PathSanitizer.joinRelativePath(path), length, piecesRoot));
            } else {
                walkFileTree(node, path, out);
            }
        }
    }

    private static String decodeFileTreeKey(byte[] rawKey) {
        return decodeUtf8(rawKey, "file tree 路径键");
    }

    private static String decodeUtf8(byte[] bytes, String field) {
        String decoded = tryDecodeUtf8(bytes);
        if (decoded == null) {
            throw invalid(field + " 不是合法 UTF-8");
        }
        return decoded;
    }

    private static String tryDecodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ignored) {
            return null;
        }
    }

    private static void crossCheckHybrid(V1Side v1,
                                         List<ParsedTorrentFile> v2Files,
                                         long pieceLength) {
        List<V1Entry> entries = v1.entries();
        int entryIndex = 0;
        long offset = 0;
        for (int v2Index = 0; v2Index < v2Files.size(); v2Index++) {
            ParsedTorrentFile v2File = v2Files.get(v2Index);
            if (entryIndex >= entries.size()) {
                throw invalid("Hybrid 的 V1/V2 文件清单数量不一致");
            }
            long requiredPadding = v2File.sizeBytes() == 0
                    ? 0
                    : (pieceLength - offset % pieceLength) % pieceLength;
            if (entries.get(entryIndex).pad()) {
                if (requiredPadding == 0) {
                    throw invalid("Hybrid 含多余 padding 文件");
                }
                V1Entry padding = entries.get(entryIndex++);
                if (padding.length() != requiredPadding) {
                    throw invalid("Hybrid padding 长度不正确");
                }
                offset += padding.length();
            } else if (requiredPadding != 0) {
                throw invalid("Hybrid 缺少 BEP 47 padding 文件");
            }

            if (entryIndex >= entries.size()) {
                throw invalid("Hybrid 的 V1/V2 文件清单数量不一致");
            }
            V1Entry v1File = entries.get(entryIndex++);
            if (v1File.pad()) {
                throw invalid("Hybrid padding 文件位置非法");
            }
            if (!v1File.path().equals(v2File.relativePath()) || v1File.length() != v2File.sizeBytes()) {
                throw invalid("Hybrid 的 V1/V2 文件不一致: " + v1File.path());
            }
            if (v1File.length() > 0) {
                if (offset % pieceLength != 0) {
                    throw invalid("Hybrid 文件未按 piece 边界对齐: " + v1File.path());
                }
                offset += v1File.length();
            }
        }
        if (entryIndex != entries.size()) {
            throw invalid("Hybrid 含多余或尾随 padding 文件");
        }
    }

    private static void validatePieceLayers(BNode.BDict root,
                                            List<ParsedTorrentFile> files,
                                            long pieceLength) {
        Map<BNode.BKey, Long> requiredLayers = new HashMap<>();
        for (ParsedTorrentFile file : files) {
            if (file.sizeBytes() <= pieceLength) {
                continue;
            }
            byte[] piecesRoot = file.v2PiecesRoot();
            if (piecesRoot == null) {
                throw invalid("多分片文件缺少 pieces root: " + file.relativePath());
            }
            BNode.BKey key = new BNode.BKey(piecesRoot);
            long expectedHashes = ceilingDivide(file.sizeBytes(), pieceLength);
            Long previous = requiredLayers.putIfAbsent(key, expectedHashes);
            if (previous != null && previous != expectedHashes) {
                throw invalid("相同 pieces root 的分片数量不一致");
            }
        }

        BNode layersNode = root.values().get("piece layers");
        if (layersNode == null) {
            if (!requiredLayers.isEmpty()) {
                throw invalid("多分片文件缺少 piece layers");
            }
            return;
        }
        if (!(layersNode instanceof BNode.BDict layers)) {
            throw invalid("piece layers 必须是字典");
        }
        for (Map.Entry<BNode.BKey, Long> required : requiredLayers.entrySet()) {
            BNode layerNode = layers.rawValues().get(required.getKey());
            if (!(layerNode instanceof BNode.BBytes layer)) {
                throw invalid("缺少 pieces root 对应的 piece layer");
            }
            int layerBytes = layer.value().length;
            if (layerBytes == 0 || layerBytes % SHA256_LENGTH != 0) {
                throw invalid("piece layer 长度必须是 32 的正整数倍");
            }
            if (layerBytes / (long) SHA256_LENGTH != required.getValue()) {
                throw invalid("piece layer 分片哈希数量不匹配");
            }
            byte[] reconstructedRoot = reconstructPieceLayerRoot(layer.value(), pieceLength);
            if (!Arrays.equals(reconstructedRoot, required.getKey().value())) {
                throw invalid("piece layer 哈希无法生成声明的 pieces root");
            }
        }
        if (layers.rawValues().size() != requiredLayers.size()) {
            throw invalid("piece layers 含未知条目");
        }
    }

    private static byte[] reconstructPieceLayerRoot(byte[] layer, long pieceLength) {
        List<byte[]> hashes = new ArrayList<>(layer.length / SHA256_LENGTH);
        for (int offset = 0; offset < layer.length; offset += SHA256_LENGTH) {
            hashes.add(Arrays.copyOfRange(layer, offset, offset + SHA256_LENGTH));
        }

        byte[] zeroHash = new byte[SHA256_LENGTH];
        long blocksPerPiece = pieceLength / V2_BLOCK_SIZE;
        while (blocksPerPiece > 1) {
            zeroHash = hashPair(zeroHash, zeroHash);
            blocksPerPiece /= 2;
        }

        int paddedCount = 1;
        while (paddedCount < hashes.size()) {
            paddedCount *= 2;
        }
        while (hashes.size() < paddedCount) {
            hashes.add(zeroHash);
        }
        while (hashes.size() > 1) {
            List<byte[]> parents = new ArrayList<>(hashes.size() / 2);
            for (int i = 0; i < hashes.size(); i += 2) {
                parents.add(hashPair(hashes.get(i), hashes.get(i + 1)));
            }
            hashes = parents;
        }
        return hashes.get(0);
    }

    private static byte[] hashPair(byte[] left, byte[] right) {
        byte[] pair = new byte[SHA256_LENGTH * 2];
        System.arraycopy(left, 0, pair, 0, SHA256_LENGTH);
        System.arraycopy(right, 0, pair, SHA256_LENGTH, SHA256_LENGTH);
        return Digests.sha256(pair);
    }

    private static void validateFilePathCollisions(List<ParsedTorrentFile> files) {
        Set<String> normalizedFiles = new HashSet<>();
        Set<String> normalizedDirectories = new HashSet<>();
        for (ParsedTorrentFile file : files) {
            String normalized = file.relativePath().toLowerCase(Locale.ROOT);
            if (!normalizedFiles.add(normalized) || normalizedDirectories.contains(normalized)) {
                throw invalid("文件路径冲突: " + file.relativePath());
            }
            int slash = normalized.indexOf('/');
            while (slash >= 0) {
                String directory = normalized.substring(0, slash);
                if (normalizedFiles.contains(directory)) {
                    throw invalid("文件与目录路径冲突: " + file.relativePath());
                }
                normalizedDirectories.add(directory);
                slash = normalized.indexOf('/', slash + 1);
            }
        }
    }

    private static BNode.BDict asDict(BNode node, String what) {
        if (!(node instanceof BNode.BDict dict)) {
            throw invalid(what + " 必须是字典");
        }
        return dict;
    }

    private static long reqInt(BNode.BDict dict, String key) {
        BNode node = dict.values().get(key);
        if (!(node instanceof BNode.BInt value)) {
            throw invalid("缺少整数键 " + key);
        }
        return value.value();
    }

    private static long optInt(BNode.BDict dict, String key, long defaultValue) {
        BNode node = dict.values().get(key);
        return node instanceof BNode.BInt value ? value.value() : defaultValue;
    }

    private static byte[] reqBytes(BNode.BDict dict, String key) {
        BNode node = dict.values().get(key);
        if (!(node instanceof BNode.BBytes value)) {
            throw invalid("缺少字符串键 " + key);
        }
        return value.value();
    }

    private static String optText(BNode.BDict dict, String key, String defaultValue) {
        BNode node = dict.values().get(key);
        if (!(node instanceof BNode.BBytes value)) {
            return defaultValue;
        }
        String text = decodeUtf8(value.value(), key);
        return text.isEmpty() ? defaultValue : text;
    }

    private static List<String> reqPathComponents(BNode.BDict fileDict) {
        BNode node = fileDict.values().get("path.utf-8");
        if (!(node instanceof BNode.BList)) {
            node = fileDict.values().get("path");
        }
        if (!(node instanceof BNode.BList pathList) || pathList.values().isEmpty()) {
            throw invalid("files 条目缺少 path");
        }
        List<String> components = new ArrayList<>();
        for (BNode component : pathList.values()) {
            if (!(component instanceof BNode.BBytes text)) {
                throw invalid("path 分量必须是字符串");
            }
            components.add(decodeUtf8(text.value(), "path 分量"));
        }
        return components;
    }

    private static List<String> parseTrackers(BNode.BDict rootDict) {
        Set<String> trackers = new LinkedHashSet<>();
        BNode announce = rootDict.values().get("announce");
        if (announce instanceof BNode.BBytes url) {
            addTracker(trackers, url);
        }
        BNode announceList = rootDict.values().get("announce-list");
        if (announceList instanceof BNode.BList tiers) {
            for (BNode tier : tiers.values()) {
                if (tier instanceof BNode.BList urls) {
                    for (BNode url : urls.values()) {
                        if (url instanceof BNode.BBytes text) {
                            addTracker(trackers, text);
                        }
                    }
                }
            }
        }
        return List.copyOf(trackers);
    }

    private static void addTracker(Set<String> trackers, BNode.BBytes encodedUrl) {
        String url = tryDecodeUtf8(encodedUrl.value());
        if (url != null && MagnetParser.isAllowedTracker(url)) {
            trackers.add(url);
        }
    }

    private static V2DomainException invalid(String reason) {
        return new V2DomainException(ErrorCodes.INVALID_TORRENT, reason);
    }
}
