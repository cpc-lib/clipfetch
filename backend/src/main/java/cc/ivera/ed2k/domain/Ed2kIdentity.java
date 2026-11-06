package cc.ivera.ed2k.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record Ed2kIdentity(String md4Hash, long fileSize) {

    private static final Pattern MD4_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    public Ed2kIdentity {
        Objects.requireNonNull(md4Hash, "md4Hash must not be null");
        String normalized = md4Hash.trim().toLowerCase(Locale.ROOT);
        if (!MD4_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid MD4 hash: " + md4Hash);
        }
        if (fileSize <= 0) {
            throw new IllegalArgumentException("fileSize must be positive: " + fileSize);
        }
        md4Hash = normalized;
    }
}
