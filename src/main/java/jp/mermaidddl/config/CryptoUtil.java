package jp.mermaidddl.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * パスワード暗号化（AES-256-GCM）。鍵はローカルの鍵ファイルに自動生成して保存する。
 * 暗号文は "enc:" + Base64(IV 12byte + 暗号文) の形式。
 * ※ 鍵ファイルと connections.json の両方を入手されると復号できるため、鍵ファイルの取り扱いに注意。
 */
public final class CryptoUtil {

    private static final String PREFIX = "enc:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public CryptoUtil(Path keyFile) throws IOException {
        this.key = loadOrCreateKey(keyFile);
    }

    private static SecretKey loadOrCreateKey(Path keyFile) throws IOException {
        if (Files.exists(keyFile)) {
            byte[] raw = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
            return new SecretKeySpec(raw, "AES");
        }
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256, RANDOM);
            SecretKey k = gen.generateKey();
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(k.getEncoded()), StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignore) {
                // Windows などでは POSIX 権限を設定できないので無視
            }
            return k;
        } catch (GeneralSecurityException e) {
            throw new IOException("暗号鍵を生成できませんでした", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(all);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("暗号化に失敗しました", e);
        }
    }

    /** 復号する。"enc:" で始まらない値は平文として扱う（手で書いた設定も読めるように）。 */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!stored.startsWith(PREFIX)) return stored;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("パスワードを復号できません（鍵ファイルが変わった可能性があります）", e);
        }
    }
}
