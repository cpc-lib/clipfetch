package cc.ivera.magnet.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.discovery.MetadataResolver;
import cc.ivera.magnet.engine.discovery.NativeMetadataJobManager;
import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.shared.web.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NativeMetadataJobsControllerTest {
    private static final String PATH="/api/magnet/native/metadata/jobs";
    private static final String MAGNET="magnet:?xt=urn:btih:"+"0".repeat(40);
    private final ObjectMapper json=new ObjectMapper();
    private NativeTorrentProperties properties;
    private NativeMetadataJobManager jobs;
    private MockMvc mvc;

    @BeforeEach void init() {
        properties=new NativeTorrentProperties();
        jobs=new NativeMetadataJobManager((uri,id,port)->
                new MetadataResolver.Resolution(
                        new TorrentInfo("0".repeat(40),"fixture",
                                List.of(new PieceLayout.FileEntry(0,"fixture.bin",0,8)),
                                8,8,new byte[20]),1,1),
                1,2,1,Duration.ofSeconds(2));
        mvc=MockMvcBuilders.standaloneSetup(
                new NativeMetadataJobsController(properties,jobs))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void close(){jobs.close();}
    private static User owner(long id){return User.builder().id(id).build();}
    private static String request(boolean consent){
        return "{\"magnetUri\":\""+MAGNET+"\",\"consentToPublicDiscovery\":"+consent+"}";
    }

    @Test void unauthenticatedOrDisabledEndpointCannotSubmit() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(request(true)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).requestAttr(AuthInterceptor.ATTR_USER,owner(7L))
                        .contentType(MediaType.APPLICATION_JSON).content(request(true)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));
        properties.setMetadataLookupEnabled(true);
        mvc.perform(post(PATH).requestAttr(AuthInterceptor.ATTR_USER,owner(7L))
                        .contentType(MediaType.APPLICATION_JSON).content(request(false)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).requestAttr(AuthInterceptor.ATTR_USER,owner(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"magnetUri\":\"invalid\",\"consentToPublicDiscovery\":true}"))
                .andExpect(status().isBadRequest());
    }
    @Test void submitPollAndFilePageAreOwnerOnly() throws Exception {
        properties.setMetadataLookupEnabled(true);
        var response=mvc.perform(post(PATH).requestAttr(AuthInterceptor.ATTR_USER,owner(10L))
                        .contentType(MediaType.APPLICATION_JSON).content(request(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        var jobId=UUID.fromString(json.readTree(response.getResponse().getContentAsString())
                .path("data").path("jobId").asText());
        assertEquals(NativeMetadataJobManager.State.SUCCEEDED,
                jobs.await(10L,jobId,Duration.ofSeconds(1)).state());

        mvc.perform(get(PATH+"/"+jobId).requestAttr(AuthInterceptor.ATTR_USER,owner(10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("fixture"));
        mvc.perform(get(PATH+"/"+jobId+"/files")
                        .requestAttr(AuthInterceptor.ATTR_USER,owner(10L))
                        .param("page","1").param("pageSize","20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalFiles").value(1))
                .andExpect(jsonPath("$.data.files[0].path").value("fixture.bin"));
        mvc.perform(get(PATH+"/"+jobId+"/files")
                        .requestAttr(AuthInterceptor.ATTR_USER,owner(10L))
                        .param("pageSize","31"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH+"/"+jobId).requestAttr(AuthInterceptor.ATTR_USER,owner(11L)))
                .andExpect(status().isNotFound());
        mvc.perform(get(PATH+"/"+jobId+"/files")
                        .requestAttr(AuthInterceptor.ATTR_USER,owner(11L)))
                .andExpect(status().isNotFound());
        mvc.perform(delete(PATH+"/"+jobId)
                        .requestAttr(AuthInterceptor.ATTR_USER,owner(11L)))
                .andExpect(status().isNotFound());
        mvc.perform(delete(PATH+"/"+jobId)
                        .requestAttr(AuthInterceptor.ATTR_USER,owner(10L)))
                .andExpect(status().isOk());
    }
}
