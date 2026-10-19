package cc.ivera.magnet.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.piece.NativeWorkerJobManager;
import cc.ivera.shared.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NativeWorkerJobControllerTest {
    private NativeTorrentProperties flags;
    private NativeWorkerJobManager jobs;
    private MockMvc mvc;
    private UUID task,job;
    @BeforeEach void setup() {
        flags=new NativeTorrentProperties();
        jobs=mock(NativeWorkerJobManager.class);
        mvc=MockMvcBuilders.standaloneSetup(new NativeWorkerJobController(flags,jobs))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        task=UUID.randomUUID();job=UUID.randomUUID();
    }
    private static User principal(long id) {return User.builder().id(id).build();}
    private void enabled() {
        flags.setEnabled(true);
        flags.setWorkerRoundEnabled(true);
        flags.setTaskControlEnabled(true);
        flags.setAsyncWorkerEnabled(true);
    }
    private String path() {return "/api/magnet/native/tasks/"+task+"/rounds";}
    private String jobPath() {return "/api/magnet/native/worker-jobs/"+job;}

    @Test void unauthenticatedAndDisabledNeverEnterQueue() throws Exception {
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":1,\"maxPieces\":2}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path()).requestAttr(AuthInterceptor.ATTR_USER,principal(50))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"maxPieces\":2}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get(jobPath()).requestAttr(AuthInterceptor.ATTR_USER,principal(50)))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(jobs);
    }

    @Test void createsFourPieceMaximumQueueJobOwnedByJwtIdentity() throws Exception {
        enabled();
        var snapshot=new NativeWorkerJobManager.Snapshot(job,task,
                NativeWorkerJobManager.State.QUEUED,0,0,0,null,Instant.now());
        when(jobs.submit(61L,task,4L,2)).thenReturn(job);
        when(jobs.status(61L,job)).thenReturn(snapshot);
        mvc.perform(post(path()).requestAttr(AuthInterceptor.ATTR_USER,principal(61))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":4,\"maxPieces\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(job.toString()))
                .andExpect(jsonPath("$.data.status.state").value("QUEUED"));
        mvc.perform(get(jobPath()).requestAttr(AuthInterceptor.ATTR_USER,principal(61)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskUuid").value(task.toString()));
        verify(jobs).submit(61L,task,4L,2);
        verify(jobs,never()).submit(eq(62L),any(),anyLong(),anyInt());
    }

    @Test void perUserOwnershipAndErrorsHaveCorrectStatus() throws Exception {
        enabled();
        when(jobs.status(99L,job)).thenThrow(new NoSuchElementException());
        when(jobs.cancel(99L,job)).thenThrow(new NoSuchElementException());
        mvc.perform(get(jobPath()).requestAttr(AuthInterceptor.ATTR_USER,principal(99)))
                .andExpect(status().isNotFound());
        mvc.perform(delete(jobPath()).requestAttr(AuthInterceptor.ATTR_USER,principal(99)))
                .andExpect(status().isNotFound());
        when(jobs.submit(99L,task,3L,1)).thenThrow(new RejectedExecutionException());
        mvc.perform(post(path()).requestAttr(AuthInterceptor.ATTR_USER,principal(99))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":3,\"maxPieces\":1}"))
                .andExpect(status().isTooManyRequests());
        when(jobs.submit(99L,task,2L,2)).thenThrow(new IllegalStateException());
        mvc.perform(post(path()).requestAttr(AuthInterceptor.ATTR_USER,principal(99))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"maxPieces\":2}"))
                .andExpect(status().isConflict());
        mvc.perform(post(path()).requestAttr(AuthInterceptor.ATTR_USER,principal(99))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"maxPieces\":5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path()).requestAttr(AuthInterceptor.ATTR_USER,principal(99))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":null,\"maxPieces\":2}"))
                .andExpect(status().isBadRequest());
    }

    @Test void continuousModeRequiresSeparateOptInAndEnforcesBoundedRequests() throws Exception {
        enabled();
        var path="/api/magnet/native/tasks/"+task+"/continuous";
        var body="{\"expectedRevision\":5,\"piecesPerRound\":2,\"maxRounds\":12,\"maxSeconds\":90}";
        mvc.perform(post(path).requestAttr(AuthInterceptor.ATTR_USER,principal(41))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());
        flags.setContinuousWorkerEnabled(true);
        var snapshot=new NativeWorkerJobManager.Snapshot(job,task,
                NativeWorkerJobManager.State.QUEUED,0,0,0,null,Instant.now());
        when(jobs.submitContinuous(41L,task,5L,2,12,90)).thenReturn(job);
        when(jobs.status(41L,job)).thenReturn(snapshot);
        mvc.perform(post(path).requestAttr(AuthInterceptor.ATTR_USER,principal(41))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(job.toString()));
        verify(jobs).submitContinuous(41L,task,5L,2,12,90);
        mvc.perform(post(path).requestAttr(AuthInterceptor.ATTR_USER,principal(41))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":5,\"piecesPerRound\":5,\"maxRounds\":12,\"maxSeconds\":90}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).requestAttr(AuthInterceptor.ATTR_USER,principal(41))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":5,\"piecesPerRound\":2,\"maxRounds\":65,\"maxSeconds\":90}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).requestAttr(AuthInterceptor.ATTR_USER,principal(41))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":5,\"piecesPerRound\":2,\"maxRounds\":12,\"maxSeconds\":601}"))
                .andExpect(status().isBadRequest());
        verify(jobs,times(1)).submitContinuous(anyLong(),any(),anyLong(),anyInt(),anyInt(),anyInt());
    }

    @Test void metricsAreOwnerScopedAndReportVerifiedSpeed() throws Exception {
        enabled();
        var metrics=new NativeWorkerJobManager.Metrics(job,task,
                NativeWorkerJobManager.State.RUNNING,3,12,8192,10000,8192,
                2048.0,1024.0,8000,Instant.now());
        when(jobs.metrics(41L,job)).thenReturn(metrics);
        var url="/api/magnet/native/worker-jobs/"+job+"/metrics";
        mvc.perform(get(url).requestAttr(AuthInterceptor.ATTR_USER,principal(41)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rounds").value(3))
                .andExpect(jsonPath("$.data.verifiedBytes").value(8192))
                .andExpect(jsonPath("$.data.recentBytesPerSecond").value(2048.0));
        when(jobs.metrics(42L,job)).thenThrow(new NoSuchElementException());
        mvc.perform(get(url).requestAttr(AuthInterceptor.ATTR_USER,principal(42)))
                .andExpect(status().isNotFound());
    }

    @Test void queueHealthIsAuthenticatedEnabledAndOwnerScoped() throws Exception {
        String url="/api/magnet/native/worker-jobs/queue-health";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).requestAttr(AuthInterceptor.ATTR_USER,principal(41)))
                .andExpect(status().isServiceUnavailable());
        verify(jobs,never()).ownerQueueHealth(anyLong());
        enabled();
        when(jobs.ownerQueueHealth(41L))
                .thenReturn(new NativeWorkerJobManager.OwnerQueueHealth(1,1,1,2));
        when(jobs.ownerQueueHealth(42L))
                .thenReturn(new NativeWorkerJobManager.OwnerQueueHealth(0,0,0,0));
        mvc.perform(get(url).requestAttr(AuthInterceptor.ATTR_USER,principal(41)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queuedJobs").value(1))
                .andExpect(jsonPath("$.data.runningJobs").value(1))
                .andExpect(jsonPath("$.data.trackedJobs").value(2))
                .andExpect(jsonPath("$.data.rejectedSubmissions").doesNotExist())
                .andExpect(jsonPath("$.data.activeThreads").doesNotExist());
        mvc.perform(get(url).requestAttr(AuthInterceptor.ATTR_USER,principal(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trackedJobs").value(0));
        verify(jobs).ownerQueueHealth(41L);
        verify(jobs).ownerQueueHealth(42L);
    }

    @Test void ownerCanCancelAndSeeTerminalStatus() throws Exception {
        enabled();
        var snapshot=new NativeWorkerJobManager.Snapshot(job,task,
                NativeWorkerJobManager.State.CANCELLED,0,0,0,null,Instant.now());
        when(jobs.cancel(52L,job)).thenReturn(snapshot);
        mvc.perform(delete(jobPath())
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(52)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CANCELLED"));
        verify(jobs).cancel(52L,job);
    }
}
