package com.fvd.video.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.video.domain.VideoInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissavParserTest {

    private static final String PAGE_URL = "https://missav.ws/en/waaa-674";
    private static final String VIDEO_ID = "03f09e77-b6f2-4b09-9460-79dca053640d";

    @Test
    void supportsOnlyMissavWsHosts() {
        assertTrue(MissavParser.supportsUrl(PAGE_URL));
        assertTrue(MissavParser.supportsUrl("https://www.missav.ws/en/example"));
        assertFalse(MissavParser.supportsUrl("https://missav.ws.evil.test/en/example"));
        assertFalse(MissavParser.supportsUrl("ftp://missav.ws/en/example"));
        assertFalse(MissavParser.supportsUrl("not-a-url"));
    }

    @Test
    void extractsMasterUrlAndMetadataFromEscapedPage() {
        String html = """
                <html><head>
                <meta content="https://fourhoi.com/waaa-674/cover-n.jpg" property="og:image">
                <meta property="og:title" content="Sample &amp; Title">
                </head><body><script>
                const seek = "https:\\/\\/surrit.com\\/03f09e77-b6f2-4b09-9460-79dca053640d\\/seek\\/_0.jpg";
                </script></body></html>
                """;

        MissavParser.PageData page = MissavParser.extractPage(PAGE_URL, html);

        assertEquals("waaa-674", page.id());
        assertEquals("Sample & Title", page.title());
        assertEquals("https://fourhoi.com/waaa-674/cover-n.jpg", page.thumbnail());
        assertEquals("https://surrit.com/" + VIDEO_ID + "/playlist.m3u8", page.masterUrl());
    }

    @Test
    void mapsRealHlsFormatsAndKeepsTheirYtDlpIds() throws Exception {
        MissavParser.PageData page = new MissavParser.PageData(
                PAGE_URL, "waaa-674", "Sample title", "https://example.test/cover.jpg",
                "https://surrit.com/" + VIDEO_ID + "/playlist.m3u8");
        JsonNode raw = new ObjectMapper().readTree("""
                {
                  "duration": 61,
                  "formats": [
                    {"format_id":"storyboard","ext":"mhtml","vcodec":"none","acodec":"none"},
                    {"format_id":"audio","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2"},
                    {"format_id":"644","ext":"mp4","resolution":"640x360","height":360,
                     "vcodec":"avc1","acodec":"mp4a.40.2","protocol":"m3u8_native"},
                    {"format_id":"1718","ext":"mp4","resolution":"1280x720","height":720,
                     "vcodec":"avc1","acodec":"mp4a.40.2","protocol":"m3u8_native"},
                    {"format_id":"976","ext":"mp4","resolution":"854x480","height":480,
                     "vcodec":"avc1","acodec":"mp4a.40.2","protocol":"m3u8_native"}
                  ]
                }
                """);

        VideoInfo info = MissavParser.mapInfo(page, raw);

        assertEquals("MissAV", info.platform());
        assertEquals("1:01", info.durationString());
        assertEquals(List.of("1718", "976", "644"),
                info.formats().stream().map(format -> format.formatId()).toList());
        assertEquals(List.of(720, 480, 360),
                info.formats().stream().map(format -> format.height()).toList());
        assertTrue(info.formats().stream().allMatch(format -> format.serverOnly()));
    }

    @Test
    void buildsBrowserShapedArgumentsForManifestAndSegments() {
        List<String> args = MissavParser.ytDlpArgs(PAGE_URL);

        assertTrue(args.containsAll(List.of(
                "--impersonate", "chrome",
                "--referer", PAGE_URL,
                "Origin:https://missav.ws",
                "Sec-Fetch-Site:cross-site",
                "--socket-timeout", "60",
                "--concurrent-fragments", "8",
                "--fragment-retries", "20",
                "--downloader", "m3u8:native")));
    }

}
