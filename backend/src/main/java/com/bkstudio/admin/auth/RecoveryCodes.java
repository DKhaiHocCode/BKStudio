package com.bkstudio.admin.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** One-time recovery codes: 16 Base32 chars (80 bits), shown as XXXX-XXXX-XXXX-XXXX, stored as SHA-256 hex. */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    private static final int LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {}

    /** Plain codes in display form (with dashes). Show once, never store. */
    public static List<String> generate() {
        List<String> codes = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            StringBuilder sb = new StringBuilder(LENGTH + 3);
            for (int j = 0; j < LENGTH; j++) {
                if (j > 0 && j % 4 == 0) {
                    sb.append('-');
                }
                sb.append(Base32.ALPHABET.charAt(RANDOM.nextInt(32)));
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    /** SHA-256 hex of the normalised code (dashes and spaces removed, upper-cased). */
    public static String hash(String code) {
        String normalised = code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
