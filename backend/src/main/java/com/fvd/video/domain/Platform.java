package com.fvd.video.domain;

import java.net.URI;

/**
 * 平台识别
 */
public enum Platform {
    YOUTUBE("YouTube"),
    DOUYIN("抖音"),
    TWITTER("Twitter/X"),
    TIKTOK("TikTok"),
    INSTAGRAM("Instagram"),
    BILIBILI("Bilibili"),
    CCTV("央视网"),
    CGTN("CGTN"),
    TUBI("Tubi"),
    BBC("BBC"),
    SPANKBANG("SpankBang"),
    AMASIAN_TV("Amasian TV"),
    OTHER("其他");

    public final String display;

    Platform(String display) {
        this.display = display;
    }

    public static Platform from(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception e) {
            return OTHER;
        }
        if (host == null) {
            return OTHER;
        }
        host = host.toLowerCase();
        if (host.contains("youtube.com") || host.contains("youtu.be")) {
            return YOUTUBE;
        }
        if (host.contains("douyin.com") || host.contains("iesdouyin.com")) {
            return DOUYIN;
        }
        if (host.contains("twitter.com") || host.equals("x.com") || host.endsWith(".x.com")) {
            return TWITTER;
        }
        if (host.contains("tiktok.com")) {
            return TIKTOK;
        }
        if (host.contains("instagram.com") || host.endsWith(".cdninstagram.com")) {
            return INSTAGRAM;
        }
        if (host.contains("bilibili.com") || host.contains("b23.tv")) {
            return BILIBILI;
        }
        if (host.equals("tv.cctv.com") || host.endsWith(".cctv.com")
                || host.endsWith(".cctv.cn") || host.endsWith(".cntv.cn")) {
            return CCTV;
        }
        if (host.endsWith(".cgtn.com")) {
            return CGTN;
        }
        if (host.contains("tubitv.com")) {
            return TUBI;
        }
        if (host.endsWith(".bbc.com") || host.endsWith(".bbc.co.uk")) {
            return BBC;
        }
        if (host.equals("spankbang.com") || host.endsWith(".spankbang.com")) {
            return SPANKBANG;
        }
        if (host.equals("amasian.tv") || host.endsWith(".amasian.tv")) {
            return AMASIAN_TV;
        }
        return OTHER;
    }
}
