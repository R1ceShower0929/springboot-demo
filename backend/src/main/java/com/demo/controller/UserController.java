package com.demo.controller;

import com.demo.common.Result;
import com.demo.entity.User;
import com.demo.service.UserService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户接口。
 * <p>读接口（GET）走从库 + Redis 缓存，写接口（POST/PUT/DELETE）走主库并清缓存。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Resource
    private UserService userService;

    /** 分页列表。返回耗时，方便观察"第一次查库慢、第二次走缓存快" */
    @GetMapping
    public Result<Map<String, Object>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        long start = System.currentTimeMillis();
        Map<String, Object> data = userService.page(page, size);
        data.put("elapsedMs", System.currentTimeMillis() - start);
        return Result.ok(data);
    }

    @GetMapping("/{id}")
    public Result<User> get(@PathVariable Long id) {
        return Result.ok(userService.getById(id));
    }

    @PostMapping
    public Result<User> create(@RequestBody @Valid User user) {
        return Result.ok(userService.create(user));
    }

    @PutMapping("/{id}")
    public Result<User> update(@PathVariable Long id, @RequestBody @Valid User user) {
        user.setId(id);
        return Result.ok(userService.update(user));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return Result.ok(null);
    }

    /** 演示用：读操作实际命中的数据源（应为 slave） */
    @GetMapping("/ping/datasource")
    public Result<Map<String, Object>> datasource() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("readDatasource", userService.currentDatasource());
        m.put("writeDatasource", "master");
        return Result.ok(m);
    }
}