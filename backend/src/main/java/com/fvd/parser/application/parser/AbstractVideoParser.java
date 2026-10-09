package com.fvd.parser.application.parser;

import com.fvd.parser.domain.Platform;

/**
 *  Parser 基类（Template Method 模式，最小化）。
 *
 * <p>默认 {@link #supports} 按 {@link Platform#from(String)} 匹配，
 * 默认 {@link #cookiePolicy} 为 NONE。
 * 自定义 host 匹配的 parser 覆写 {@link #supports}。
 */
public abstract class AbstractVideoParser implements VideoParser {

    @Override
    public boolean supports(String url) {
        return platform() != null && Platform.from(url) == platform();
    }

    @Override
    public CookiePolicy cookiePolicy() {
        return CookiePolicy.NONE;
    }
}
