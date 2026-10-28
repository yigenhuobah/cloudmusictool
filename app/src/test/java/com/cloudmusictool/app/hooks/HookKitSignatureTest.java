package com.cloudmusictool.app.hooks;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Profile 映射方法的签名校验：映射分支必须和常规分支一样，拒绝参数/返回类型
 * 与调用方期望不一致的重载，避免 Profile 漂移时把 hook 挂到错误方法上。
 */
public class HookKitSignatureTest {

    @Test
    public void nullExpectationsAcceptAnyMethod() throws Exception {
        assertTrue(HookKit.matchesExpected(method("noArg"), null, null));
    }

    @Test
    public void paramsMustMatchExactly() throws Exception {
        assertTrue(HookKit.matchesExpected(
                method("stringArg", String.class), null, new Class<?>[]{String.class}));
        assertFalse(HookKit.matchesExpected(
                method("stringArg", String.class), null, new Class<?>[]{int.class}));
        assertFalse(HookKit.matchesExpected(
                method("stringArg", String.class), null, new Class<?>[0]));
    }

    @Test
    public void returnTypeMustMatchWhenSpecified() throws Exception {
        assertTrue(HookKit.matchesExpected(
                method("boolInt", int.class), boolean.class, new Class<?>[]{int.class}));
        assertFalse(HookKit.matchesExpected(
                method("boolInt", int.class), void.class, new Class<?>[]{int.class}));
    }

    @Test
    public void nullMethodRejected() {
        assertFalse(HookKit.matchesExpected(null, null, null));
    }

    private static Method method(String name, Class<?>... params) throws Exception {
        return Fixture.class.getDeclaredMethod(name, params);
    }

    @SuppressWarnings("unused")
    private static final class Fixture {
        void noArg() {
        }

        void stringArg(String value) {
        }

        boolean boolInt(int value) {
            return true;
        }

        String stringIntLong(int first, long second) {
            return null;
        }
    }
}
