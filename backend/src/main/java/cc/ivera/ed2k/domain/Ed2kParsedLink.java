package cc.ivera.ed2k.domain;

import java.util.Objects;

public record Ed2kParsedLink(
        Ed2kIdentity identity,
        String requestedName,
        String canonicalLink
) {
    public Ed2kParsedLink {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(requestedName, "requestedName must not be null");
        Objects.requireNonNull(canonicalLink, "canonicalLink must not be null");
    }
}
