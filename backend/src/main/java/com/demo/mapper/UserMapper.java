package com.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper。继承 BaseMapper 即拥有 CRUD 能力，不需要手写 SQL。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}