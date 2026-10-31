package com.cloudmusictool.app.hooks;

import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * hook 体单测的最小运行时：一个已 attach 的 XposedModule（框架代理只记录日志、其余返回默认值）
 * 和一个可断言 proceed 次数的 Chain。
 *
 * 本簇修复的核心语义（proceed 恰好一次、宿主异常原样抛出、Runnable 异常不触发 proceed）
 * 只有直接驱动 hook 体才能验证，而 HookKit.error / HookKit.hit 会调用 module.log()；
 * 未 attach 的 XposedModule 会抛 "Framework not attached"，因此这里用动态代理伪造框架。
 */
final class HookTestSupport {

    private HookTestSupport() {
    }

    /** 已 attach 的 XposedModule：log() 落到 FakeModule.logs，不会抛未 attach 异常。 */
    static final class FakeModule extends XposedModule {
        final List<String> logs = new ArrayList<>();

        FakeModule() {
            attachFramework(proxyFramework(this), () -> {
            });
        }

        private static XposedInterface proxyFramework(FakeModule module) {
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("toString".equals(method.getName())) {
                        return "FakeFramework";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return null;
                }
                if ("log".equals(method.getName()) && args != null) {
                    StringBuilder sb = new StringBuilder();
                    for (Object arg : args) {
                        if (arg instanceof Throwable) {
                            sb.append(" | ").append(arg);
                        } else {
                            sb.append(' ').append(arg);
                        }
                    }
                    module.logs.add(sb.toString());
                }
                return defaultValue(method.getReturnType());
            };
            return (XposedInterface) Proxy.newProxyInstance(
                    HookTestSupport.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.class}, handler);
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return Boolean.FALSE;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == char.class) {
                return (char) 0;
            }
            if (type == float.class) {
                return 0f;
            }
            if (type == double.class) {
                return 0d;
            }
            return null;
        }
    }

    /** 可断言 proceed 次数 / 入参 / 异常的 Chain。 */
    static final class FakeChain implements XposedInterface.Chain {
        final List<Object> args = new ArrayList<>();
        final Object thisObject;
        /** 可选的 getThisObject 失败，用来驱动后处理异常分支。 */
        RuntimeException thisObjectFailure;
        int proceedCount;
        Object[] lastArgs;
        Object result = "host-result";
        Throwable failure;

        FakeChain(Object thisObject) {
            this.thisObject = thisObject;
        }

        @Override
        public List<Object> getArgs() {
            return args;
        }

        @Override
        public Object getArg(int index) {
            return args.get(index);
        }

        @Override
        public Object getThisObject() {
            if (thisObjectFailure != null) {
                throw thisObjectFailure;
            }
            return thisObject;
        }

        @Override
        public Executable getExecutable() {
            return null;
        }

        @Override
        public Object proceed() throws Throwable {
            return doProceed(args.toArray());
        }

        @Override
        public Object proceed(Object[] newArgs) throws Throwable {
            return doProceed(newArgs);
        }

        @Override
        public Object proceedWith(Object thisObject) throws Throwable {
            return doProceed(args.toArray());
        }

        @Override
        public Object proceedWith(Object thisObject, Object[] newArgs) throws Throwable {
            return doProceed(newArgs);
        }

        private Object doProceed(Object[] used) throws Throwable {
            proceedCount++;
            lastArgs = used;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
