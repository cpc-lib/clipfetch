package com.fvd.shared.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 分页插件：不注册时 selectPage 不会真正分页（返回全量数据）。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor();
        // SQLite 与 MySQL 分页语法一致（LIMIT ? OFFSET ?），用 SQLITE 兼容两种数据源
        pagination.setDbType(DbType.SQLITE);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
