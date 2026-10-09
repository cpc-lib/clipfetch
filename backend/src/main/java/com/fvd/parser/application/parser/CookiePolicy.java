package com.fvd.parser.application.parser;

/**
 * Parser 的 cookie 策略，由 ParserRegistry 在调 parse() 前后统一处理。
 *
 * <ul>
 *   <li>NONE — 不查 cookie，不调 markInvalidIfAuth（netmirror/qqmusic/tencent 等）</li>
 *   <li>OPTIONAL — findContent（可为 null）；parse 失败时 markInvalidIfAuth（instagram/cctv/weibo 等）</li>
 *   <li>REQUIRED — requireContent（无 cookie 抛异常）；parse 失败时 markInvalidIfAuth（douyin）</li>
 * </ul>
 */
public enum CookiePolicy {
    NONE,
    OPTIONAL,
    REQUIRED
}
