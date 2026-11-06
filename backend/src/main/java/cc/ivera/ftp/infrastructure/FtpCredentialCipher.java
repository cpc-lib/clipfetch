package cc.ivera.ftp.infrastructure;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class FtpCredentialCipher {

    private static final int VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String keyId;
    private final SecretKeySpec key;
    private final SecureRandom random;

    @Autowired
    public FtpCredentialCipher(FtpProperties properties) {
        this(properties.getCredentialKeyId(), properties.getCredentialKey(), new SecureRandom(), properties.isEnabled());
    }

    FtpCredentialCipher(String keyId, String encodedKey, SecureRandom random) {
        this(keyId, encodedKey, random, true);
    }

    private FtpCredentialCipher(String keyId, String encodedKey, SecureRandom random, boolean required) {
        this.random = random;
        if (keyId == null || keyId.isBlank() || encodedKey == null || encodedKey.isBlank()) {
            if (!required) {
                this.keyId = "";
                this.key = null;
                return;
            }
            throw new IllegalStateException("FTP 凭据加密密钥未配置");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("FTP 凭据加密密钥不是合法 Base64", ex);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("FTP 凭据加密密钥必须是 32 字节 AES-256 密钥");
        }
        this.keyId = keyId;
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public CipherPayload encrypt(long userId, String taskUuid, String fieldName, String plainText) {
        if (plainText == null) {
            return null;
        }
        requireConfigured();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(userId, taskUuid, fieldName));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return new CipherPayload(
                    Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(nonce),
                    keyId,
                    VERSION
            );
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("FTP 凭据加密失败", ex);
        }
    }

    public String decrypt(long userId, String taskUuid, String fieldName, CipherPayload payload) {
        if (payload == null) {
            return null;
        }
        requireConfigured();
        if (payload.version() != VERSION || !keyId.equals(payload.keyId())) {
            throw new IllegalStateException("FTP 凭据密钥版本不可用");
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(payload.nonce());
            if (nonce.length != NONCE_BYTES) {
                throw new IllegalStateException("FTP 凭据 nonce 长度无效");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(userId, taskUuid, fieldName));
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(payload.cipherText()));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (AEADBadTagException ex) {
            throw new IllegalStateException("FTP 凭据校验失败", ex);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("FTP 凭据解密失败", ex);
        }
    }

    private static byte[] aad(long userId, String taskUuid, String fieldName) {
        String value = userId + "\u0000" + taskUuid + "\u0000" + fieldName + "\u0000" + VERSION;
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private void requireConfigured() {
        if (key == null) {
            throw new IllegalStateException("FTP 凭据加密密钥未配置");
        }
    }

    public record CipherPayload(String cipherText, String nonce, String keyId, int version) {
    }
}
