package com.demo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 启动类。
 * <p>@EnableCaching 打开 Spring Cache，配合 Redis 实现方法级缓存。
 * <p>@MapperScan 扫描 MyBatis-Plus 的 Mapper 接口。
 */
@SpringBootApplication
@EnableCaching
@MapperScan("com.demo.mapper")
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}