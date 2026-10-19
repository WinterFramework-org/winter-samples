package board.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BoardEtag {

    private static final Pattern SINGLE_TAG = Pattern.compile(
        "[ \\t]*(W/)?(\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\")[ \\t]*");

    private BoardEtag() {
    }

    public static String of(String content) {
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder tag = new StringBuilder(66).append('"');
        for (byte value : digest) {
            int unsigned = value & 0xff;
            tag.append(hex[unsigned >>> 4]).append(hex[unsigned & 0x0f]);
        }
        return tag.append('"').toString();
    }

    public static void requireMatch(String ifMatch, boolean repeated, String currentContent) {
        if (repeated) {
            throw invalidCondition();
        }
        if (ifMatch == null) {
            throw new BoardConditionException(428, "IF_MATCH_REQUIRED",
                "조회 응답의 ETag를 If-Match 헤더로 전달해주세요.");
        }
        Matcher matcher = SINGLE_TAG.matcher(ifMatch);
        if (!matcher.matches()) {
            throw invalidCondition();
        }
        if (matcher.group(1) != null || !matcher.group(2).equals(of(currentContent))) {
            throw new BoardConditionException(412, "IF_MATCH_FAILED",
                "게시글이 변경되었습니다. 다시 조회한 뒤 수정해주세요.");
        }
    }

    private static BoardConditionException invalidCondition() {
        return new BoardConditionException(400, "INVALID_IF_MATCH",
            "If-Match에는 큰따옴표로 감싼 ETag 하나만 전달해주세요. 여러 값과 *는 허용하지 않습니다.");
    }
}
