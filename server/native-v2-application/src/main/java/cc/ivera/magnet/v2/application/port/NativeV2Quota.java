package cc.ivera.magnet.v2.application.port;

/** Ephemeral per-user quota gate. Redis state is never business truth. */
public interface NativeV2Quota {

    void consumeParseRate(long userId);

    Lease acquireParseLease(long userId);

    void consumeTicketRate(long userId);

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
