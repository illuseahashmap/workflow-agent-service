package io.github.illuseahashmap.workflow.security.infrastructure.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class MessageDigestSupport {

    private MessageDigestSupport() {
    }

    static boolean constantTimeEquals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }
}
