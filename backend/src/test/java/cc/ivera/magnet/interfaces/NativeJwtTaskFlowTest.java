package cc.ivera.magnet.interfaces;

import cc.ivera.auth.application.JwtService;
import cc.ivera.auth.domain.User;
import cc.ivera.auth.domain.UserMapper;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.piece.NativeTaskCreationService;
import cc.ivera.magnet.engine.piece.NativeTaskLifecycleService;
import cc.ivera.magnet.engine.piece.NativeWorkerJobManager;
import cc.ivera.shared.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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

/** JWT interceptor + Native controller request boundaries; never opens a Peer socket. */
class NativeJwtTaskFlowTest {
    private final NativeTorrentProperties flags=new NativeTorrentProperties();
    private final NativeTaskLifecycleService tasks=mock(NativeTaskLifecycleService.class);
    private final NativeTaskCreationService creation=mock(NativeTaskCreationService.class);
    private final NativeWorkerJobManager jobs=mock(NativeWorkerJobManager.class);
    private final UserMapper users=mock(UserMapper.class);
    private final JwtService jwt=new JwtService(
            "test-only-native-jwt-auth-key-longer-than-32-characters-no-production-use",30);
    private MockMvc mvc;
    private final UUID taskId=UUID.randomUUID(),jobId=UUID.randomUUID();
    private String ownerToken,anotherToken;

    @BeforeEach void init() {
        flags.setTaskCreationEnabled(true);
        flags.setTaskControlEnabled(true);
        flags.setEnabled(true);
        flags.setWorkerRoundEnabled(true);
        flags.setAsyncWorkerEnabled(true);
        User owner=User.builder().id(71L).email("native-owner@example.test").build();
        User other=User.builder().id(72L).email("other-user@example.test").build();
        when(users.selectById(71L)).thenReturn(owner);
        when(users.selectById(72L)).thenReturn(other);
        ownerToken=jwt.generateAccessToken(owner);
        anotherToken=jwt.generateAccessToken(other);
        mvc=MockMvcBuilders.standaloneSetup(
                new NativeTaskCreationController(flags,creation,
                        mock(cc.ivera.magnet.engine.discovery.NativeMetadataJobManager.class),
                        mock(cc.ivera.magnet.engine.piece.NativeDownloadRootService.class)),
                new NativeTaskControlController(flags,tasks,
                        mock(cc.ivera.magnet.engine.piece.NativeTaskCheckpointFactory.class)),
                new NativeWorkerJobController(flags,jobs))
                .addInterceptors(new AuthInterceptor(jwt,users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    private static String base(){return "/api/magnet/native";}
    private String task(){return base()+"/tasks/"+taskId;}
    private String job(){return base()+"/worker-jobs/"+jobId;}

    @Test void realBearerTokenCreatesTaskPreparesAndSubmitsBoundedWork() throws Exception {
        var file=new MockMultipartFile("file","public.torrent",
                "application/x-bittorrent",new byte[]{'d','e'});
        when(creation.createFromTorrent(eq(71L),any(byte[].class),eq(java.util.List.of(0)),isNull()))
                .thenReturn(new NativeTaskCreationService.Created(taskId,
                        "f".repeat(40),"CREATED",0,262144,1));
        mvc.perform(multipart(base()+"/tasks").file(file).param("fileIndexes","0")
                        .header("Authorization","Bearer "+ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskUuid").value(taskId.toString()));
        var ready=new NativeTaskLifecycleService.TaskState(taskId,"READY",1,0,262144,0);
        when(tasks.prepare(71L,taskId,0)).thenReturn(ready);
        mvc.perform(post(task()+"/prepare")
                        .header("Authorization","Bearer "+ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("READY"));
        when(jobs.submit(71L,taskId,1,2)).thenReturn(jobId);
        when(jobs.status(71L,jobId)).thenReturn(new NativeWorkerJobManager.Snapshot(
                jobId,taskId,NativeWorkerJobManager.State.QUEUED,0,0,0,null,Instant.now()));
        mvc.perform(post(task()+"/rounds")
                        .header("Authorization","Bearer "+ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"maxPieces\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.data.status.state").value("QUEUED"));
        verify(tasks).prepare(71L,taskId,0);
        verify(jobs).submit(71L,taskId,1,2);
        verify(creation).createFromTorrent(eq(71L),any(byte[].class),eq(java.util.List.of(0)),isNull());
    }

    @Test void duplicateQueueAndStaleRevisionReturnConflictsWithoutSecondDownload() throws Exception {
        when(jobs.submit(71L,taskId,2,1)).thenThrow(new RejectedExecutionException("already running"));
        mvc.perform(post(task()+"/rounds").header("Authorization","Bearer "+ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"maxPieces\":1}"))
                .andExpect(status().isTooManyRequests());
        when(tasks.prepare(71L,taskId,9)).thenThrow(new IllegalStateException("stale"));
        mvc.perform(post(task()+"/prepare").header("Authorization","Bearer "+ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":9}"))
                .andExpect(status().isConflict());
        verify(jobs,times(1)).submit(anyLong(),any(),anyLong(),anyInt());
    }

    @Test void authenticatingAnotherUserDoesNotGrantTaskOrJobAccess() throws Exception {
        when(tasks.get(72L,taskId)).thenThrow(new NoSuchElementException("wrong owner"));
        when(jobs.status(72L,jobId)).thenThrow(new NoSuchElementException("wrong owner"));
        when(jobs.cancel(72L,jobId)).thenThrow(new NoSuchElementException("wrong owner"));
        mvc.perform(get(task()).header("Authorization","Bearer "+anotherToken))
                .andExpect(status().isNotFound());
        mvc.perform(get(job()).header("Authorization","Bearer "+anotherToken))
                .andExpect(status().isNotFound());
        mvc.perform(delete(job()).header("Authorization","Bearer "+anotherToken))
                .andExpect(status().isNotFound());
        verify(jobs,never()).cancel(eq(71L),any());
    }

    @Test void authorizationHeaderIsRequiredAndForgedTokenIsRejected() throws Exception {
        mvc.perform(get(task())).andExpect(status().isUnauthorized());
        mvc.perform(get(task()).header("Authorization","Bearer forged.invalid.token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tasks,creation,jobs);
    }

    @Test void browserReloadRecoversOnlyAuthenticatedOwnersActiveWorkerId() throws Exception {
        var live=new NativeWorkerJobManager.Snapshot(jobId,taskId,
                NativeWorkerJobManager.State.RUNNING,4,0,0,null,Instant.now());
        when(jobs.latestOwnedTaskJob(71L,taskId)).thenReturn(java.util.Optional.of(live));
        mvc.perform(get(task()+"/worker-job").header("Authorization","Bearer "+ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.data.state").value("RUNNING"));
        when(jobs.latestOwnedTaskJob(72L,taskId)).thenThrow(new NoSuchElementException());
        mvc.perform(get(task()+"/worker-job").header("Authorization","Bearer "+anotherToken))
                .andExpect(status().isNotFound());
        verify(jobs).latestOwnedTaskJob(71L,taskId);
        verify(jobs).latestOwnedTaskJob(72L,taskId);
    }

    @Test void orphanRecoveryRequiresSeparateFeatureFlagAndMatchingOwnedRevision()
            throws Exception {
        var url=task()+"/recover-orphan";
        mvc.perform(post(url).header("Authorization","Bearer "+ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7}"))
                .andExpect(status().isServiceUnavailable());
        verify(jobs,never()).recoverOwnedOrphan(anyLong(),any(),anyLong());

        flags.setSingleInstanceOrphanRecoveryEnabled(true);
        var recovered=new NativeTaskLifecycleService.TaskState(taskId,"PAUSED",8,262144,524288,262144);
        when(jobs.recoverOwnedOrphan(71L,taskId,7L)).thenReturn(recovered);
        mvc.perform(post(url).header("Authorization","Bearer "+ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PAUSED"))
                .andExpect(jsonPath("$.data.revision").value(8));
        when(jobs.recoverOwnedOrphan(72L,taskId,7L)).thenThrow(new NoSuchElementException());
        mvc.perform(post(url).header("Authorization","Bearer "+anotherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7}"))
                .andExpect(status().isNotFound());
        when(jobs.recoverOwnedOrphan(71L,taskId,5L))
                .thenThrow(new IllegalStateException("Stale revision"));
        mvc.perform(post(url).header("Authorization","Bearer "+ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":5}"))
                .andExpect(status().isConflict());
        mvc.perform(post(url).header("Authorization","Bearer "+ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7}"))
                .andExpect(status().isUnauthorized());
        verify(jobs,never()).recoverOwnedOrphan(eq(71L),eq(taskId),eq(-1L));
    }

    @Test void orphanRecoveryRefusesWhileServerHasAnyActiveWorker() throws Exception {
        flags.setSingleInstanceOrphanRecoveryEnabled(true);
        when(jobs.recoverOwnedOrphan(71L,taskId,7L))
                .thenThrow(new IllegalStateException("Active native job"));
        mvc.perform(post(task()+"/recover-orphan")
                        .header("Authorization","Bearer "+ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7}"))
                .andExpect(status().isConflict());
    }

    @Test void cancellationAndThenRevisionSafeResumeRemainUserScoped() throws Exception {
        var cancelled=new NativeWorkerJobManager.Snapshot(jobId,taskId,
                NativeWorkerJobManager.State.CANCELLED,0,0,262144,null,Instant.now());
        when(jobs.cancel(71L,jobId)).thenReturn(cancelled);
        mvc.perform(delete(job()).header("Authorization","Bearer "+ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CANCELLED"));
        var ready=new NativeTaskLifecycleService.TaskState(taskId,"READY",6,262144,524288,262144);
        when(tasks.prepare(71L,taskId,5)).thenReturn(ready);
        mvc.perform(post(task()+"/prepare").header("Authorization","Bearer "+ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(6));
        verify(jobs).cancel(71L,jobId);
        verify(tasks).prepare(71L,taskId,5);
    }
}
