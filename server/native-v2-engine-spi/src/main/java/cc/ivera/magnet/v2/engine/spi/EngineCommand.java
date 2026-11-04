package cc.ivera.magnet.v2.engine.spi;

/** 仅携带信封的命令（Pause / Resume / GetSnapshot / SaveResumeData / RemoveTorrent）。 */
public record EngineCommand(EngineEnvelope envelope) {
}
