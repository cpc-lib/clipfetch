package cc.ivera.magnet.v2.application.port;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** v2 application-facing storage authority. */
public interface NativeV2Storage {

    Path parseInput(UUID parseId);

    Path metadata(UUID resourceId);

    Path resourceRoot(UUID resourceId);

    Path resume(UUID resourceId);

    void writeParseInput(UUID parseId, byte[] bytes);

    byte[] readParseInput(UUID parseId);

    void deleteParseInput(UUID parseId);

    void writeMetadata(UUID resourceId, byte[] bytes);

    byte[] readMetadata(UUID resourceId);

    void writeResume(UUID resourceId, byte[] bytes);

    Optional<byte[]> readResume(UUID resourceId);

    Path resolveCompletedFile(UUID resourceId, String safeRelativePath);
}
