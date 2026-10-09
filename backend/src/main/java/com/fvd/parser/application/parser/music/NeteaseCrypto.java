package com.fvd.parser.application.parser.music;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * 网易云音乐 weapi 加密工具（用于官方播放 URL 接口）
 * 加密流程：AES-128-CBC 两次加密 + RSA 加密密钥
 * 参考：https://github.com/Binaryify/NeteaseCloudMusicApi
 */
public class NeteaseCrypto {

    // 网易云公开的 RSA 公钥参数
    private static final String PUBKEY_MODULUS =
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7"
            + "b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280"
            + "104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932"
            + "575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3"
            + "ece0462db0a22b8e7";
    private static final String PUBKEY_EXPONENT = "010001";
    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String IV = "0102030405060708";
    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /**
     * 生成 weapi 请求参数
     *
     * @param text 请求体 JSON 字符串
     * @return [params, encSecKey]
     */
    public static String[] encrypt(String text) {
        String secretKey = randomString(16);
        String params = aesEncrypt(aesEncrypt(text, PRESET_KEY), secretKey);
        String encSecKey = rsaEncrypt(reverse(secretKey));
        return new String[]{params, encSecKey};
    }

    private static String randomString(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        return sb.toString();
    }

    private static String aesEncrypt(String text, String key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("AES 加密失败", e);
        }
    }

    private static String rsaEncrypt(String text) {
        try {
            BigInteger modulus = new BigInteger(PUBKEY_MODULUS, 16);
            BigInteger exponent = new BigInteger(PUBKEY_EXPONENT, 16);
            RSAPublicKeySpec keySpec = new RSAPublicKeySpec(modulus, exponent);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(keySpec);
            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("RSA 加密失败", e);
        }
    }

    private static String reverse(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
