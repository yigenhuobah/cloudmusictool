package com.cloudmusictool.app.hooks;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * ABTest 观测去重：一次 checkExperiment 查询在宿主内部还会进 abtest2.b#x 的内层 hook，
 * 两个 hook 各自 observe 同一个 key 会让 ExperimentState 的 count 翻倍，且 source 被外层
 * （libra/old）覆盖内层的 "model"。这里直接驱动两个 hook 体，验证同一次调用只记一次。
 */
public class AbTestHooksTest {

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    @Test
    public void nestedModelHook_recordsOnceWithModelSource() throws Throwable {
        String key = "ab_nested_once";
        Object cfg = new FakeConfig("t1");
        OuterChain chain = new OuterChain(module, key, cfg, true);

        Object result = AbTestHooks.interceptCheckExperiment(module, null, chain);

        assertSame("宿主返回值必须原样透传", cfg, result);
        assertEquals("同一次查询只允许记一次", 1, countOf(key));
        assertEquals("source 保持内层模型层取值", "model", sourceOf(key));
        assertEquals("t1", groupOf(key));
        assertEquals("proceed 恰好一次", 1, chain.proceedCount);
    }

    @Test
    public void withoutNestedModelHook_recordsOnceWithManagerSource() throws Throwable {
        // b#x 的 hook 未安装（或宿主走缓存分支）时，外层照常记录，source 为配置实例来源。
        String key = "ab_outer_only";
        Object cfg = new FakeConfig("t2");
        OuterChain chain = new OuterChain(module, key, cfg, false);

        Object result = AbTestHooks.interceptCheckExperiment(module, null, chain);

        assertSame(cfg, result);
        assertEquals(1, countOf(key));
        assertEquals("old", sourceOf(key));
    }

    @Test
    public void repeatedCalls_recordEachCall() throws Throwable {
        // 去重只作用于同一次 checkExperiment 调用：不同调用各自计数，作用域不得残留。
        String key = "ab_repeat";
        AbTestHooks.interceptCheckExperiment(module, null,
                new OuterChain(module, key, new FakeConfig("t1"), true));
        AbTestHooks.interceptCheckExperiment(module, null,
                new OuterChain(module, key, new FakeConfig("t1"), true));

        assertEquals(2, countOf(key));
    }

    @Test
    public void modelHook_alone_stillRecords() throws Throwable {
        // b#x 直接调用（不经过 checkExperiment）时观测不得被吞掉。
        String key = "ab_model_alone";
        HookTestSupport.FakeChain inner = new HookTestSupport.FakeChain(null);
        inner.args.add(key);
        inner.args.add(Boolean.TRUE);
        inner.result = new FakeConfig("t3");

        AbTestHooks.interceptModel(module, null, inner);

        assertEquals(1, countOf(key));
        assertEquals("model", sourceOf(key));
        assertEquals(1, inner.proceedCount);
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    /** 与宿主 ABTestConfig 同构的最小配置：groupOf 按字段名 abtestgroup 读取。 */
    public static final class FakeConfig {
        final String abtestgroup;

        FakeConfig(String group) {
            this.abtestgroup = group;
        }
    }

    /**
     * 外层 checkExperiment 链：proceed 模拟宿主内部 invoke b->x（nested=true 时
     * 驱动真实的 b#x hook 体，其观测会落入本文件的 ExperimentState）。
     */
    private static final class OuterChain implements XposedInterface.Chain {
        final List<Object> args = new ArrayList<>();
        final HookTestSupport.FakeModule module;
        final Object result;
        final boolean nested;
        int proceedCount;

        OuterChain(HookTestSupport.FakeModule module, String key, Object result, boolean nested) {
            this.module = module;
            this.result = result;
            this.nested = nested;
            this.args.add(key);
            this.args.add(Boolean.TRUE);
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
            return null;
        }

        @Override
        public Executable getExecutable() {
            return null;
        }

        @Override
        public Object proceed() throws Throwable {
            proceedCount++;
            if (!nested) {
                return result;
            }
            HookTestSupport.FakeChain inner = new HookTestSupport.FakeChain(null);
            inner.args.add(args.get(0));
            inner.args.add(args.get(1));
            inner.result = result;
            return AbTestHooks.interceptModel(module, null, inner);
        }

        @Override
        public Object proceed(Object[] newArgs) throws Throwable {
            return proceed();
        }

        @Override
        public Object proceedWith(Object thisObject) throws Throwable {
            return proceed();
        }

        @Override
        public Object proceedWith(Object thisObject, Object[] newArgs) throws Throwable {
            return proceed();
        }
    }

    // ------------------------------------------------------------------
    // ExperimentState 读取（sItems 为私有静态，按本套件惯例用反射）
    // ------------------------------------------------------------------

    private static Object item(String key) throws Exception {
        Field items = ExperimentState.class.getDeclaredField("sItems");
        items.setAccessible(true);
        return ((Map<?, ?>) items.get(null)).get(key);
    }

    private static int countOf(String key) throws Exception {
        Object item = item(key);
        if (item == null) {
            return 0;
        }
        Field count = item.getClass().getDeclaredField("count");
        count.setAccessible(true);
        return ((AtomicInteger) count.get(item)).get();
    }

    private static String sourceOf(String key) throws Exception {
        return stringField(key, "source");
    }

    private static String groupOf(String key) throws Exception {
        return stringField(key, "group");
    }

    private static String stringField(String key, String name) throws Exception {
        Object item = item(key);
        if (item == null) {
            return null;
        }
        Field field = item.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(item);
    }
}
