package com.demo.entity;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数校验的单元测试：直接跑 Bean Validation，不启动 Spring、不连库。
 * 用来保证"用户名不能为空、邮箱必须合法"这类接口契约不会被误改。
 */
class UserValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private Set<ConstraintViolation<User>> violationsOf(User user) {
        return validator.validate(user);
    }

    @Test
    @DisplayName("合法用户没有任何校验错误")
    void validUserHasNoViolation() {
        User user = new User();
        user.setUsername("张三");
        user.setEmail("zhangsan@example.com");
        user.setAge(28);

        assertTrue(violationsOf(user).isEmpty());
    }

    @Test
    @DisplayName("用户名为空会被拦下")
    void blankUsernameIsRejected() {
        User user = new User();
        user.setUsername("   ");
        user.setEmail("zhangsan@example.com");

        Set<ConstraintViolation<User>> violations = violationsOf(user);
        assertEquals(1, violations.size());
        assertEquals("用户名不能为空", violations.iterator().next().getMessage());
    }

    @Test
    @DisplayName("邮箱格式不合法会被拦下")
    void invalidEmailIsRejected() {
        User user = new User();
        user.setUsername("张三");
        user.setEmail("not-an-email");

        Set<ConstraintViolation<User>> violations = violationsOf(user);
        assertEquals(1, violations.size());
        assertEquals("邮箱格式不正确", violations.iterator().next().getMessage());
    }
}
