package cc.ivera.magnet.v2.config;

import cc.ivera.magnet.v2.application.parse.NativeV2ParseDispatcher;
import cc.ivera.magnet.v2.application.parse.NativeV2ParseLimits;
import cc.ivera.magnet.v2.application.parse.NativeV2ParseService;
import cc.ivera.magnet.v2.application.port.NativeV2EngineSessionRepository;
import cc.ivera.magnet.v2.application.port.NativeV2ParseJobRepository;
import cc.ivera.magnet.v2.application.port.NativeV2Quota;
import cc.ivera.magnet.v2.application.port.NativeV2ResourceRepository;
import cc.ivera.magnet.v2.application.port.NativeV2Storage;
import cc.ivera.magnet.v2.application.port.NativeV2TaskEventRepository;
import cc.ivera.magnet.v2.application.port.NativeV2TaskFileRepository;
import cc.ivera.magnet.v2.application.port.NativeV2TaskRepository;
import cc.ivera.magnet.v2.application.port.NativeV2UnitOfWork;
import cc.ivera.magnet.v2.application.task.NativeV2CompletionPolicy;
import cc.ivera.magnet.v2.application.task.NativeV2RetryPolicy;
import cc.ivera.magnet.v2.application.task.NativeV2TaskDispatcher;
import cc.ivera.magnet.v2.application.task.NativeV2TaskLimits;
import cc.ivera.magnet.v2.application.task.NativeV2TaskService;
import cc.ivera.magnet.v2.application.ticket.NativeV2TicketStore;
import cc.ivera.magnet.v2.engine.libtorrent.GrpcNativeV2Engine;
import cc.ivera.magnet.v2.engine.libtorrent.NativeV2GrpcProperties;
import cc.ivera.magnet.v2.engine.spi.NativeV2Engine;
import cc.ivera.magnet.v2.infrastructure.config.NativeV2Properties;
import cc.ivera.magnet.v2.scheduler.NativeV2Scheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.SplittableRandom;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.magnet.native-v2.enabled", havingValue = "true")
public class NativeV2BeansConfiguration {

    private static final int PARSE_BATCH_SIZE = 50;

    @Bean
    Clock nativeV2Clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    NativeV2ParseLimits nativeV2ParseLimits(NativeV2Properties properties) {
        return new NativeV2ParseLimits(properties.getMaxTrackers(), properties.getMaxFiles(),
                properties.getMaxTorrentBytes(), properties.getMaxTotalBytes());
    }

    @Bean
    NativeV2TaskLimits nativeV2TaskLimits(NativeV2Properties properties) {
        return new NativeV2TaskLimits(properties.getMaxSelectedFiles());
    }

    @Bean
    NativeV2RetryPolicy nativeV2RetryPolicy(NativeV2Properties properties) {
        return new NativeV2RetryPolicy(properties.getRetryMaxAttempts(),
                Duration.ofMillis(properties.getRetryInitialMs()),
                Duration.ofMillis(properties.getRetryMaxMs()),
                properties.getRetryJitterPercent() / 100.0,
                // 精简 JRE 镜像里 RandomGenerator.getDefault() 可能找不到算法实现，直接用具体类。
                new SplittableRandom());
    }

    @Bean
    NativeV2CompletionPolicy nativeV2CompletionPolicy() {
        return new NativeV2CompletionPolicy();
    }

    @Bean
    NativeV2TicketStore nativeV2TicketStore(NativeV2Properties properties) {
        return new NativeV2TicketStore(Duration.ofSeconds(properties.getTicketTtlSeconds()));
    }

    @Bean
    NativeV2GrpcProperties nativeV2GrpcProperties(NativeV2Properties properties) {
        NativeV2GrpcProperties grpc = NativeV2GrpcProperties.builder()
                .host(properties.getWorkerHost())
                .port(properties.getWorkerPort())
                .tlsEnabled(Boolean.TRUE.equals(properties.isWorkerTlsEnabled()))
                .caCertificatePath(pathOrNull(properties.getWorkerCaCert()))
                .clientCertificatePath(pathOrNull(properties.getWorkerClientCert()))
                .clientKeyPath(pathOrNull(properties.getWorkerClientKey()))
                .rpcTimeoutMs(properties.getRpcTimeoutMs())
                .maxMessageBytes(properties.getGrpcMaxMessageBytes())
                .build();
        grpc.validate();
        return grpc;
    }

    @Bean(destroyMethod = "close")
    NativeV2Engine nativeV2Engine(NativeV2GrpcProperties properties) {
        return new GrpcNativeV2Engine(properties);
    }

    @Bean
    NativeV2ParseService nativeV2ParseService(NativeV2ParseJobRepository jobs,
                                              NativeV2ResourceRepository resources,
                                              NativeV2Storage storage,
                                              NativeV2Quota quota,
                                              NativeV2Engine engine,
                                              NativeV2ParseLimits limits,
                                              Clock clock) {
        return new NativeV2ParseService(jobs, resources, storage, quota, engine, limits, clock);
    }

    @Bean
    NativeV2ParseDispatcher nativeV2ParseDispatcher(NativeV2ParseJobRepository jobs,
                                                     NativeV2ResourceRepository resources,
                                                     NativeV2Storage storage,
                                                     NativeV2Engine engine,
                                                     NativeV2UnitOfWork unitOfWork,
                                                     NativeV2ParseLimits limits,
                                                     NativeV2Properties properties,
                                                     Clock clock) {
        return new NativeV2ParseDispatcher(jobs, resources, storage, engine, unitOfWork, limits,
                Duration.ofMillis(properties.getMetadataTimeoutMs()),
                Duration.ofMillis(properties.getMetadataPollMs()), clock);
    }

    @Bean
    NativeV2TaskService nativeV2TaskService(NativeV2ParseJobRepository parseJobs,
                                            NativeV2ResourceRepository resources,
                                            NativeV2TaskRepository tasks,
                                            NativeV2TaskFileRepository taskFiles,
                                            NativeV2EngineSessionRepository sessions,
                                            NativeV2TaskEventRepository events,
                                            NativeV2UnitOfWork unitOfWork,
                                            NativeV2Engine engine,
                                            NativeV2Quota quota,
                                            NativeV2TicketStore tickets,
                                            NativeV2TaskLimits limits,
                                            Clock clock) {
        return new NativeV2TaskService(parseJobs, resources, tasks, taskFiles, sessions, events,
                unitOfWork, engine, quota, tickets, limits, clock);
    }

    @Bean
    NativeV2TaskDispatcher nativeV2TaskDispatcher(NativeV2TaskRepository tasks,
                                                  NativeV2TaskFileRepository taskFiles,
                                                  NativeV2ResourceRepository resources,
                                                  NativeV2EngineSessionRepository sessions,
                                                  NativeV2Storage storage,
                                                  NativeV2Engine engine,
                                                  NativeV2TaskEventRepository events,
                                                  NativeV2CompletionPolicy completionPolicy,
                                                  NativeV2RetryPolicy retryPolicy,
                                                  NativeV2Properties properties,
                                                  Clock clock) {
        return new NativeV2TaskDispatcher(tasks, taskFiles, resources, sessions, storage, engine,
                events, completionPolicy, retryPolicy,
                Duration.ofMillis(properties.getRecheckTimeoutMs()), clock);
    }

    @Bean
    NativeV2Scheduler nativeV2Scheduler(NativeV2ParseJobRepository parseJobs,
                                        NativeV2ParseDispatcher parseDispatcher,
                                        NativeV2TaskDispatcher taskDispatcher,
                                        Clock clock) {
        return new NativeV2Scheduler(parseJobs, parseDispatcher, taskDispatcher, PARSE_BATCH_SIZE, clock);
    }

    private static Path pathOrNull(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}
