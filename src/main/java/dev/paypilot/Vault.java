package dev.paypilot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
class Vault {
    private static final int NONCE_LENGTH = 12;
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper;
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec tokenKey;
    private final Pbkdf2PasswordEncoder encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    Vault(ObjectMapper mapper, @Value("${paypilot.key-file}") String filename) throws IOException, GeneralSecurityException {
        this.mapper = mapper;
        Path keyFile = Path.of(filename).toAbsolutePath();
        Files.createDirectories(keyFile.getParent());
        if (!Files.exists(keyFile)) {
            byte[] material = new byte[32];
            random.nextBytes(material);
            Path pending = Files.createTempFile(keyFile.getParent(), ".new-key-", ".tmp");
            try {
                if (pending.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(pending, PosixFilePermissions.fromString("rw-------"));
                }
                Files.write(pending, material);
                try {
                    Files.move(pending, keyFile);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Another startup wrote the key first.
                }
            } finally {
                Files.deleteIfExists(pending);
            }
        }
        byte[] material = Files.readAllBytes(keyFile);
        if (material.length != 32) throw new IllegalStateException("PayPilot encryption key must contain 32 bytes.");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(material, "HmacSHA256"));
        encryptionKey = new SecretKeySpec(mac.doFinal("encryption".getBytes(StandardCharsets.UTF_8)), "AES");
        tokenKey = new SecretKeySpec(mac.doFinal("tokens".getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
    }

    String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    int draw(int max) {
        return random.nextInt(max);
    }

    String fingerprint(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(tokenKey);
            return HexFormat.of().formatHex(mac.doFinal((purpose + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not fingerprint PayPilot data.", e);
        }
    }

    static boolean matches(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    String hashPassword(String value) {
        return encoder.encode(value);
    }

    boolean matchesPassword(String value, String hashed) {
        return encoder.matches(value, hashed);
    }

    byte[] encrypt(byte[] value) {
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(value);
            return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt PayPilot data.", e);
        }
    }

    byte[] decrypt(byte[] value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, value, 0, NONCE_LENGTH));
            return cipher.doFinal(value, NONCE_LENGTH, value.length - NONCE_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt PayPilot data.", e);
        }
    }

    String json(Map<String, ?> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialize PayPilot data.", e);
        }
    }

    byte[] seal(Map<String, ?> value) {
        return encrypt(json(value).getBytes(StandardCharsets.UTF_8));
    }

    Map<String, Object> open(byte[] value) {
        try {
            return mapper.readValue(decrypt(value), new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Could not read PayPilot data.", e);
        }
    }

    byte[] sealText(String value) {
        return encrypt(value.getBytes(StandardCharsets.UTF_8));
    }

    String openText(byte[] value) {
        return value == null ? "" : new String(decrypt(value), StandardCharsets.UTF_8);
    }
}
