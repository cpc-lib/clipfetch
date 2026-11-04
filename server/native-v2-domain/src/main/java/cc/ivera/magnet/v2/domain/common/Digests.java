package cc.ivera.magnet.v2.domain.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Digests {

    private Digests() {
    }

    public static byte[] sha1(byte[] data) {
        return digest("SHA-1", data);
    }

    public static byte[] sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少摘要算法 " + algorithm, e);
        }
    }
}
