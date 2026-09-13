package com.shiva.wallet.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Fingerprints the meaningful fields of a transfer request.
 *
 * <p>This is what distinguishes an honest retry from a key collision. The brief requires
 * that the same key with a <em>different</em> body is a 409 rather than a second debit,
 * which means the original body has to be remembered somehow. Storing a hash rather than
 * the body keeps the transfer row small and avoids having to canonicalise JSON.
 */
@Component
public class RequestHasher {

    public String hash(Long fromWalletId, Long toWalletId, long amountPaise) {
        String canonical = fromWalletId + "|" + toWalletId + "|" + amountPaise;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
