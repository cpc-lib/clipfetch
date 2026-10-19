package cc.ivera.magnet.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.piece.NativeDownloadRootService;
import cc.ivera.magnet.engine.piece.NativeTaskCreationService;
import cc.ivera.shared.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NativeTaskCreationControllerTest {
    private static final String API="/api/magnet/native/tasks";
    private NativeTorrentProperties properties;
    private NativeTaskCreationService creation;
    private cc.ivera.magnet.engine.discovery.NativeMetadataJobManager metadataJobs;
    private NativeDownloadRootService downloadRoots;
    private MockMvc mvc;
    private MockMultipartFile torrent;

    @BeforeEach void setup() {
        properties=new NativeTorrentProperties();
        creation=mock(NativeTaskCreationService.class);
        metadataJobs=mock(cc.ivera.magnet.engine.discovery.NativeMetadataJobManager.class);
        downloadRoots=mock(NativeDownloadRootService.class);
        mvc=MockMvcBuilders.standaloneSetup(
                new NativeTaskCreationController(properties,creation,metadataJobs,downloadRoots))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        torrent=new MockMultipartFile("file","fixture.torrent","application/x-bittorrent",
                new byte[]{'d','4',':','i','n','f','o','d','e','e'});
    }
    private static User principal(long id) {return User.builder().id(id).build();}

    @Test void disabledOrUnauthenticatedCannotCreate() throws Exception {
        mvc.perform(multipart(API).file(torrent).param("fileIndexes","0"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart(API).file(torrent).param("fileIndexes","0")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(101)))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(creation);
    }
    @Test void bindsOwnerAndFileSelectionFromAuthenticatedRequest() throws Exception {
        properties.setTaskCreationEnabled(true);
        UUID task=UUID.randomUUID();
        when(creation.createFromTorrent(eq(42L),any(byte[].class),eq(List.of(0,1)),isNull()))
                .thenReturn(new NativeTaskCreationService.Created(task,"a".repeat(40),
                        "CREATED",0,9,2));
        mvc.perform(multipart(API).file(torrent).param("fileIndexes","0","1")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(42)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskUuid").value(task.toString()))
                .andExpect(jsonPath("$.data.state").value("CREATED"))
                .andExpect(jsonPath("$.data.selectedBytes").value(9))
                .andExpect(jsonPath("$.data.statePath").doesNotExist());
        verify(creation).createFromTorrent(eq(42L),any(byte[].class),eq(List.of(0,1)),isNull());
    }
    @Test void deniesPrivateTorrentQuotaAndInvalidSelections() throws Exception {
        properties.setTaskCreationEnabled(true);
        when(creation.createFromTorrent(eq(7L),any(byte[].class),eq(List.of(0)),isNull()))
                .thenThrow(new SecurityException("private torrent"));
        mvc.perform(multipart(API).file(torrent).param("fileIndexes","0")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(7)))
                .andExpect(status().isUnprocessableEntity());
        when(creation.createFromTorrent(eq(7L),any(byte[].class),eq(List.of(3)),isNull()))
                .thenThrow(new IllegalArgumentException("invalid index"));
        mvc.perform(multipart(API).file(torrent).param("fileIndexes","3")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(7)))
                .andExpect(status().isBadRequest());
        when(creation.createFromTorrent(eq(7L),any(byte[].class),eq(List.of(1)),isNull()))
                .thenThrow(new IllegalStateException("quota"));
        mvc.perform(multipart(API).file(torrent).param("fileIndexes","1")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(7)))
                .andExpect(status().isConflict());
    }
    @Test void metadataJobCreateRequiresAuthenticatedOwnerAndEnabledFlags() throws Exception {
        UUID jobId=UUID.randomUUID();
        String url=API+"/from-metadata/"+jobId;
        String body="{\"fileIndexes\":[0]}";
        mvc.perform(post(url).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).requestAttr(AuthInterceptor.ATTR_USER,principal(42))
                        .contentType("application/json").content(body))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(metadataJobs);
        properties.setTaskCreationEnabled(true);
        properties.setMetadataLookupEnabled(true);
        when(metadataJobs.verifiedTorrentBytes(42L,jobId)).thenReturn(new byte[]{'d','e'});
        when(creation.createFromTorrent(eq(42L),any(byte[].class),eq(List.of(0)),isNull()))
                .thenReturn(new NativeTaskCreationService.Created(UUID.randomUUID(),
                        "a".repeat(40),"CREATED",0,4,1));
        mvc.perform(post(url).requestAttr(AuthInterceptor.ATTR_USER,principal(42))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CREATED"));
        when(metadataJobs.verifiedTorrentBytes(43L,jobId))
                .thenThrow(new java.util.NoSuchElementException());
        mvc.perform(post(url).requestAttr(AuthInterceptor.ATTR_USER,principal(43))
                        .contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        verify(creation,never()).createFromTorrent(eq(43L),any(byte[].class),anyList(),any());
    }

    @Test void rejectsEmptyOrOversizedTorrentBeforeCreator() throws Exception {
        properties.setTaskCreationEnabled(true);
        var empty=new MockMultipartFile("file","empty.torrent","application/x-bittorrent",new byte[0]);
        mvc.perform(multipart(API).file(empty).param("fileIndexes","0")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(7)))
                .andExpect(status().isBadRequest());
        properties.setMaxMetadataBytes(3);
        mvc.perform(multipart(API).file(torrent).param("fileIndexes","0")
                        .requestAttr(AuthInterceptor.ATTR_USER,principal(7)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(creation);
    }
}
