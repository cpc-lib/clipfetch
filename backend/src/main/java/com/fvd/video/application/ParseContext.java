package com.fvd.video.application;

import com.fvd.auth.domain.User;

/**
 * 单次解析请求的上下文数据。仅携带 per-request 信息（url、当前用户、cookie 字符串），
 * service 依赖通过 Spring 构造器注入，不放在此处。
 *
 * @param url     已校验的视频链接
 * @param user    当前登录用户（可为 null，如免登录接口）
 * @param cookies 由 ParserRegistry 按 CookiePolicy 解析的 cookie 字符串，NONE 策略下为 null
 */
public record ParseContext(String url, User user, String cookies) {
}
