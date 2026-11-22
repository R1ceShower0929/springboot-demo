package com.demo.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.entity.User;
import com.demo.mapper.UserMapper;
import jakarta.annotation.Resource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户业务层 —— 读写分离和缓存都发生在这里。
 *
 * <p><b>读写分离</b>：类里没写 @DS 的方法走主库（master）；标注 {@code @DS("slave")} 的方法走从库。
 * 由 dynamic-datasource 的 AOP 切面在方法执行前切换数据源，方法结束自动恢复。
 *
 * <p><b>缓存</b>：读方法标 {@code @Cacheable}，第一次查库并写入 Redis，后续命中缓存直接返回；
 * 写方法标 {@code @CacheEvict(allEntries = true)}，把整个缓存清掉，避免读到脏数据。
 */
@Service
public class UserService {

    @Resource
    private UserMapper userMapper;

    /** 分页查询：走从库 + 缓存 */
    @DS("slave")
    @Cacheable(cacheNames = "user:page", key = "'p' + #page + ':s' + #size")
    public Map<String, Object> page(int page, int size) {
        Page<User> p = new Page<>(page, size);
        userMapper.selectPage(p, new QueryWrapper<User>().orderByDesc("id"));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("records", p.getRecords());
        map.put("total", p.getTotal());
        map.put("page", page);
        map.put("size", size);
        // 记录这次实际用的是哪个数据源，方便前端页面上直观看到
        map.put("datasource", DynamicDataSourceContextHolder.peek());
        return map;
    }

    /** 详情：走从库 + 缓存 */
    @DS("slave")
    @Cacheable(cacheNames = "user:item", key = "#id")
    public User getById(Long id) {
        return userMapper.selectById(id);
    }

    /** 新增：走主库（没有 @DS，默认 primary），并清空缓存 */
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = {"user:page", "user:item"}, allEntries = true)
    public User create(User user) {
        user.setId(null);
        userMapper.insert(user);
        return user;
    }

    /** 修改：走主库，并清空缓存 */
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = {"user:page", "user:item"}, allEntries = true)
    public User update(User user) {
        userMapper.updateById(user);
        return user;
    }

    /** 删除（逻辑删除）：走主库，并清空缓存 */
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = {"user:page", "user:item"}, allEntries = true)
    public void delete(Long id) {
        userMapper.deleteById(id);
    }

    /** 用于演示：把当前线程上的数据源名带出去 */
    @DS("slave")
    public String currentDatasource() {
        return DynamicDataSourceContextHolder.peek();
    }
}