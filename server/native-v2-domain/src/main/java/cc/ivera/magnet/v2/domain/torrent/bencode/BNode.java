package cc.ivera.magnet.v2.domain.torrent.bencode;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * bencode 值节点。start/end 为原始字节区间（end 开区间），
 * 用于对 info 字典按原始字节计算 infohash，避免重编码导致哈希漂移。
 */
public sealed interface BNode {

    int start();

    int end();

    record BInt(long value, int start, int end) implements BNode {
    }

    record BBytes(byte[] value, int start, int end) implements BNode {

        public BBytes {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }

    record BList(List<BNode> values, int start, int end) implements BNode {

        public BList {
            values = List.copyOf(values);
        }
    }

    record BKey(byte[] value) {

        public BKey {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BKey key && Arrays.equals(value, key.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }

    /** 文本 key 保持原始顺序；rawValues 用于 BEP 52 的二进制 piece-layer key。 */
    record BDict(Map<String, BNode> values,
                 Map<BKey, BNode> rawValues,
                 int start,
                 int end) implements BNode {

        public BDict {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            rawValues = Collections.unmodifiableMap(new LinkedHashMap<>(rawValues));
        }

        public BNode rawValue(byte[] key) {
            return rawValues.get(new BKey(key));
        }
    }
}
