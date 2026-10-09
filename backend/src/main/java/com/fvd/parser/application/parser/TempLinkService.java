package com.fvd.parser.application.parser;

import com.fvd.temp.domain.TempLink;
import com.fvd.temp.domain.TempLinkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 临时子链接保存服务，从 VideoController 提取。
 *
 * <p>歌单/搜索/歌手/专辑解析后，将子链接保存到 temp 表，按 url 去重。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TempLinkService {

    private final TempLinkMapper tempLinkMapper;

    /** 统一的歌曲输入记录，各音乐 parser 的不同 song record 都映射到此。 */
    public record TempSongInput(String url, String title, Integer vip) {}

    /**
     * 保存歌曲子链接到 temp 表：url 非空且按 url 去重。
     *
     * @return [解析总数, 保存数, 跳过数]
     */
    public int[] saveTempLinks(List<TempSongInput> songs, String sourceUrl) {
        int parsed = songs.size();
        int saved = 0;
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now();
        for (TempSongInput song : songs) {
            if (song.url() == null || song.url().isBlank()) {
                skipped++;
                continue;
            }
            if (tempLinkMapper.countPhysicalByUrl(song.url()) > 0) {
                tempLinkMapper.undeleteByUrl(song.url(), song.vip());
                skipped++;
                continue;
            }
            tempLinkMapper.insert(TempLink.builder()
                    .url(song.url())
                    .title(song.title())
                    .sourceUrl(sourceUrl)
                    .downloaded(false)
                    .vip(song.vip())
                    .createdAt(now)
                    .build());
            saved++;
        }
        log.info("temp 保存: 来源={}, 解析={}, 保存={}, 跳过={}", sourceUrl, parsed, saved, skipped);
        return new int[]{parsed, saved, skipped};
    }
}
