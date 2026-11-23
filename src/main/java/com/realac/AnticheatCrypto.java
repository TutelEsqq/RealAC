package com.realac;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class AnticheatCrypto {
    // Shared secret for heartbeat challenge-response. Changing it requires updating the mod on both sides.
    public static final String SHARED_SECRET = "cc9f4e2b1a7d4c8ea3f05b6d92e1c7a8b4d6f0e2c9a1b3d5e7f8091a2c3e4f506";

    private AnticheatCrypto() {
    }

    public static String challengeHash(String nonce) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((nonce + ":" + SHARED_SECRET).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
