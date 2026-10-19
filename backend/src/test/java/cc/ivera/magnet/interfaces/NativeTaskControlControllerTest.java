package cc.ivera.magnet.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.piece.NativeTaskLifecycleService;
import cc.ivera.magnet.engine.piece.NativeTaskCheckpointFactory;
import cc.ivera.magnet.engine.piece.VerifiedPieceCheckpoint;
import cc.ivera.shared.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NativeTaskControlControllerTest {
    private static final String API="/api/magnet/native/tasks";
    private NativeTorrentProperties config;
    private NativeTaskLifecycleService service;
    private NativeTaskCheckpointFactory checkpoints;
    private MockMvc mvc;
    private UUID id;

    @BeforeEach void setup() {
        config=new NativeTorrentProperties();
        service=mock(NativeTaskLifecycleService.class);
        checkpoints=mock(NativeTaskCheckpointFactory.class);
        mvc=MockMvcBuilders.standaloneSetup(
                new NativeTaskControlController(config,service,checkpoints))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        id=UUID.randomUUID();
    }
    private static User principal(long id) { return User.builder().id(id).build(); }

    @Test void authenticationAndSwitchPreventAccessWithoutServiceCalls() throws Exception {
        mvc.perform(get(API)).andExpect(status().isUnauthorized());
        mvc.perform(get(API).requestAttr(AuthInterceptor.ATTR_USER,principal(7)))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post(API+"/"+id+"/prepare")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(7))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":0}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(delete(API+"/"+id).requestAttr(AuthInterceptor.ATTR_USER,principal(7))
                        .param("expectedRevision","0"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(service);
    }

    @Test void readsAreOwnerScopedPaginatedAndNeverExposeDatabaseStoragePaths() throws Exception {
        config.setTaskControlEnabled(true);
        var first=new NativeTaskLifecycleService.TaskState(id,"PAUSED",3,4,8,12);
        when(service.list(11L,1,20)).thenReturn(List.of(first));
        when(service.get(11L,id)).thenReturn(first);
        mvc.perform(get(API).requestAttr(AuthInterceptor.ATTR_USER,principal(11)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].state").value("PAUSED"))
                .andExpect(jsonPath("$.data[0].progressPercent").value(50.0))
                .andExpect(jsonPath("$.data[0].statePath").doesNotExist());
        mvc.perform(get(API+"/"+id).requestAttr(AuthInterceptor.ATTR_USER,principal(11)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(3))
                .andExpect(jsonPath("$.data.userId").doesNotExist());
        when(service.get(12L,id)).thenThrow(new NoSuchElementException());
        mvc.perform(get(API+"/"+id).requestAttr(AuthInterceptor.ATTR_USER,principal(12)))
                .andExpect(status().isNotFound());
        verify(service).list(11L,1,20);
        verify(service).get(12L,id);
    }

    @Test void preparePauseAndDeleteRequireExplicitOptimisticRevision() throws Exception {
        config.setTaskControlEnabled(true);
        var ready=new NativeTaskLifecycleService.TaskState(id,"READY",4,4,8,8);
        var paused=new NativeTaskLifecycleService.TaskState(id,"PAUSED",5,4,8,8);
        when(service.prepare(20L,id,3L)).thenReturn(ready);
        when(service.pause(20L,id,4L)).thenReturn(paused);
        mvc.perform(post(API+"/"+id+"/prepare")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(20))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("READY"));
        mvc.perform(post(API+"/"+id+"/pause")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(20))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PAUSED"));
        mvc.perform(delete(API+"/"+id)
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(20))
                        .param("expectedRevision","5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        verify(service).logicalDelete(20L,id,5L);
        mvc.perform(delete(API+"/"+id)
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(20))
                        .param("expectedRevision","-1"))
                .andExpect(status().isBadRequest());
        verify(service,never()).logicalDelete(20L,id,-1L);
        verify(service,never()).claimForWorker(anyLong(),any(),anyLong());
    }

    @Test void staleRevisionAndCrossUserMutationsFailWithoutChangingState() throws Exception {
        config.setTaskControlEnabled(true);
        when(service.prepare(30L,id,5L)).thenThrow(new IllegalStateException("stale"));
        when(service.pause(31L,id,5L)).thenThrow(new NoSuchElementException("wrong owner"));
        mvc.perform(post(API+"/"+id+"/prepare")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(30))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":5}"))
                .andExpect(status().isConflict());
        mvc.perform(post(API+"/"+id+"/pause")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(31))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":5}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(API+"/"+id+"/prepare")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(30))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":null}"))
                .andExpect(status().isBadRequest());
        verify(service,never()).claimForWorker(anyLong(),any(),anyLong());
    }

    @Test void reconciliationRequiresStoppedOwnedTaskAndValidRevision() throws Exception {
        config.setTaskControlEnabled(true);
        var stopped=new NativeTaskLifecycleService.TaskState(id,"PAUSED",4,8,8,12);
        var corrected=new NativeTaskLifecycleService.TaskState(id,"PAUSED",5,4,8,12);
        var checkpoint=mock(VerifiedPieceCheckpoint.class);
        when(service.get(11,id)).thenReturn(stopped);
        when(checkpoints.openOwned(11,id)).thenReturn(checkpoint);
        when(service.reconcileStopped(11,id,4,checkpoint)).thenReturn(corrected);
        String url=API+"/"+id+"/reconcile";
        mvc.perform(post(url).requestAttr(AuthInterceptor.ATTR_USER,principal(11))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifiedBytes").value(4));
        mvc.perform(post(url).requestAttr(AuthInterceptor.ATTR_USER,principal(11))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":3}"))
                .andExpect(status().isConflict());
        when(service.get(11,id)).thenReturn(new NativeTaskLifecycleService.TaskState(id,"RUNNING",4,8,8,12));
        mvc.perform(post(url).requestAttr(AuthInterceptor.ATTR_USER,principal(11))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":4}"))
                .andExpect(status().isConflict());
        when(service.get(12,id)).thenThrow(new NoSuchElementException());
        mvc.perform(post(url).requestAttr(AuthInterceptor.ATTR_USER,principal(12))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":4}"))
                .andExpect(status().isNotFound());
        verify(checkpoints,never()).openOwned(eq(12L),any());
        verify(service,times(1)).reconcileStopped(11,id,4,checkpoint);
    }

    @Test void paginationLimitsAreCheckedBeforeQueryingDatabase() throws Exception {
        config.setTaskControlEnabled(true);
        when(service.list(eq(50L),anyInt(),anyInt()))
                .thenThrow(new IllegalArgumentException("invalid page"));
        mvc.perform(get(API).requestAttr(AuthInterceptor.ATTR_USER,principal(50))
                        .param("pageSize","31"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(API).requestAttr(AuthInterceptor.ATTR_USER,principal(50))
                        .param("page","2147483647").param("pageSize","30"))
                .andExpect(status().isBadRequest());
    }
}
