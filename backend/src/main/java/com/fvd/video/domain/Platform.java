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
    XVIDEOS("XVideos"),
    AMASIAN_TV("Amasian TV"),
    TENCENT("腾讯视频"),
    REDNOTE("小红书"),
    WEIBO("微博"),
    PORNHUB("Pornhub"),
    NETMIRROR("NetMirror"),
    QQMUSIC("QQ音乐"),
    NETEASE_MUSIC("网易云音乐"),
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
        if (host.equals("xvideos.com") || host.endsWith(".xvideos.com")) {
            return XVIDEOS;
        }
        if (host.equals("amasian.tv") || host.endsWith(".amasian.tv")) {
            return AMASIAN_TV;
        }
        if (host.equals("v.qq.com") || host.endsWith(".v.qq.com")) {
            return TENCENT;
        }
        if (host.endsWith("xiaohongshu.com") || host.endsWith("xhslink.com")) {
            return REDNOTE;
        }
        if (host.endsWith("weibo.com") || host.endsWith("weibo.cn") || host.endsWith("sinaimg.cn")) {
            return WEIBO;
        }
        if (host.equals("pornhub.com") || host.endsWith(".pornhub.com")
                || host.equals("pornhub.net") || host.endsWith(".pornhub.net")
                || host.equals("pornhub.org") || host.endsWith(".pornhub.org")) {
            return PORNHUB;
        }
        if (host.contains("netmirror.")) {
            return NETMIRROR;
        }
        if (host.contains("y.qq.com") || host.equals("music.qq.com")
                || host.endsWith(".gtimg.cn") || host.endsWith(".qqmusic.qq.com")) {
            return QQMUSIC;
        }
        if (host.contains("music.163.com") || host.endsWith(".music.163.com")) {
            return NETEASE_MUSIC;
        }
        return OTHER;
    }
}
