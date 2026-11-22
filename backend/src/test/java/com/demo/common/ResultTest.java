package com.demo.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一返回体的单元测试：不启动 Spring、不连库，纯逻辑校验。
 * 这类测试在 CI 的 test 阶段跑，几十毫秒出结果，用来做"提交即回归"的第一道门。
 */
class ResultTest {

    @Test
    @DisplayName("成功返回 code=0、message=success，并原样带回数据")
    void okShouldCarryData() {
        Result<String> r = Result.ok("hello");

        assertEquals(0, r.getCode());
        assertEquals("success", r.getMessage());
        assertEquals("hello", r.getData());
    }

    @Test
    @DisplayName("成功返回允许 data 为 null（删除接口就是这么用的）")
    void okShouldAllowNullData() {
        Result<Void> r = Result.ok(null);

        assertEquals(0, r.getCode());
        assertNull(r.getData());
    }

    @Test
    @DisplayName("失败返回 code=500，并保留错误信息")
    void failShouldKeepMessage() {
        Result<String> r = Result.fail("用户名已存在");

        assertEquals(500, r.getCode());
        assertEquals("用户名已存在", r.getMessage());
        assertNull(r.getData());
    }
}
