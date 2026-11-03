package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;
import com.cloudmusictool.app.profile.MountSpec;
import com.cloudmusictool.app.profile.ProfileRegistry;
import com.cloudmusictool.app.profile.ProfileUi;
import com.cloudmusictool.app.profile.TargetProfile;
import com.netease.play.livepage.promotion.LivePromotion;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 第二轮复核 id 10 回归：LivePromotion#listFromJson 的 JSONObject / JSONArray
 * 两个重载必须有各自唯一的挂载点 label。
 *
 * <p>旧实现用参数个数生成 label，两个重载都是 {@code ...listFromJson(1)}；
 * critical 的 JSONObject 重载安装失败后，非 critical 的 JSONArray 重载安装成功，
 * {@link HookState#installed(Feature, String)} 会按同一个 label 把 critical 失败
 * 一并撤销，criticalFailed 归零、degraded=false，功能被误报健康。
 */
public class LivePromotionOverloadAccountingTest {

    private static final String LIVE_PROMOTION = LivePromotion.class.getName();
    private static final String LIST_RETURN = "Ljava/util/List;";

    @Before
    public void resetProfile() {
        ProfileRegistry.setActive(null);
    }

    @After
    public void clearProfile() {
        ProfileRegistry.setActive(null);
    }

    @Test
    public void overloadLabelsMatchProfileAndAreDistinct() throws Exception {
        String objectLabel = CommunityLiveHooks.livePromotionLabel(overload(JSONObject.class));
        String arrayLabel = CommunityLiveHooks.livePromotionLabel(overload(JSONArray.class));

        assertEquals("LivePromotion#listFromJson(JSONObject)", objectLabel);
        assertEquals("LivePromotion#listFromJson(JSONArray)", arrayLabel);
        assertNotEquals("两个重载不得共用 label", objectLabel, arrayLabel);

        List<MountSpec> specs = profile().forFeature("UI-15");
        assertEquals("label 必须与 Profile 的 logicalName 对齐",
                objectLabel, specs.get(0).logicalName);
        assertEquals("label 必须与 Profile 的 logicalName 对齐",
                arrayLabel, specs.get(1).logicalName);

        // 真实 ProfileUi 也必须使用同一组 label，且只有 JSONObject 重载是 critical。
        boolean sawObject = false;
        boolean sawArray = false;
        for (MountSpec spec : ProfileUi.mounts()) {
            if (!"UI-15".equals(spec.featureId)) {
                continue;
            }
            if (objectLabel.equals(spec.logicalName)) {
                sawObject = true;
                assertTrue("JSONObject 重载在 Profile 中必须是 critical", spec.critical);
            }
            if (arrayLabel.equals(spec.logicalName)) {
                sawArray = true;
                assertFalse("JSONArray 重载在 Profile 中必须是非 critical", spec.critical);
            }
        }
        assertTrue("ProfileUi 缺少 JSONObject 重载坐标", sawObject);
        assertTrue("ProfileUi 缺少 JSONArray 重载坐标", sawArray);
    }

    @Test
    public void criticalOverloadFailureSurvivesNonCriticalOverloadSuccess() throws Exception {
        ProfileRegistry.setActive(profile());
        Feature f = Feature.COMMUNITY_LIVE;
        JSONObject before = featureJson(f);
        int criticalBefore = before.optInt("criticalFailed", 0);
        int failedBefore = before.optInt("failed", 0);
        int installedBefore = before.optInt("installed", 0);

        Method objectOverload = overload(JSONObject.class);
        Method arrayOverload = overload(JSONArray.class);
        String objectLabel = CommunityLiveHooks.livePromotionLabel(objectOverload);
        String arrayLabel = CommunityLiveHooks.livePromotionLabel(arrayOverload);

        HookState.enable(f);
        try {
            assertFalse("critical 的 JSONObject 重载安装失败", HookKit.install(
                    module(Collections.singleton("JSONObject"), null), f, objectLabel,
                    objectOverload, chain -> null));
            assertTrue("非 critical 的 JSONArray 重载随后安装成功", HookKit.install(
                    module(Collections.emptySet(), null), f, arrayLabel,
                    arrayOverload, chain -> null));
        } finally {
            HookState.finish(f);
        }

        JSONObject after = featureJson(f);
        assertEquals("critical 失败不能被另一个重载的安装成功清掉",
                criticalBefore + 1, after.getInt("criticalFailed"));
        assertEquals(failedBefore + 1, after.getInt("failed"));
        assertEquals(installedBefore + 1, after.getInt("installed"));
        assertTrue("critical 失败仍在，功能必须保持 degraded", after.getBoolean("degraded"));
        assertEquals("expected 必须与 installed+failed 对齐",
                after.getInt("installed") + after.getInt("failed"),
                after.getInt("expected"));
    }

    @Test
    public void nonCriticalOverloadFailureDoesNotCountAsCritical() throws Exception {
        ProfileRegistry.setActive(profile());
        Feature f = Feature.PODCAST_GUIDE;
        JSONObject before = featureJson(f);
        int criticalBefore = before.optInt("criticalFailed", 0);
        int failedBefore = before.optInt("failed", 0);

        Method arrayOverload = overload(JSONArray.class);
        String arrayLabel = CommunityLiveHooks.livePromotionLabel(arrayOverload);

        HookState.enable(f);
        try {
            assertFalse("非 critical 的 JSONArray 重载安装失败", HookKit.install(
                    module(Collections.singleton("JSONArray"), null), f, arrayLabel,
                    arrayOverload, chain -> null));
        } finally {
            HookState.finish(f);
        }

        JSONObject after = featureJson(f);
        assertEquals("非 critical 重载失败不得计入 criticalFailed",
                criticalBefore, after.getInt("criticalFailed"));
        assertEquals(failedBefore + 1, after.getInt("failed"));
        assertFalse("非 critical 重载失败不得触发 degraded", after.getBoolean("degraded"));
    }

    /** 安装入口必须把两个重载都按参数类型登记（不是参数个数）。 */
    @Test
    public void communityLiveInstallRegistersBothOverloadsWithDistinctLabels() throws Exception {
        ProfileRegistry.setActive(profile());
        List<String> logs = new ArrayList<>();

        CommunityLiveHooks.install(module(Collections.emptySet(), logs),
                LivePromotion.class.getClassLoader(), Feature.SEARCH_GUIDE);

        assertTrue("JSONObject 重载必须按参数类型登记",
                containsLog(logs, "registered LivePromotion#listFromJson(JSONObject)"));
        assertTrue("JSONArray 重载必须按参数类型登记",
                containsLog(logs, "registered LivePromotion#listFromJson(JSONArray)"));
    }

    // ---------------------------------------------------------------- 夹具

    private static TargetProfile profile() {
        return new TargetProfile(9006008L, "test", Arrays.asList(
                MountSpec.method("UI-15", "LivePromotion#listFromJson(JSONObject)", true,
                        LIVE_PROMOTION, "listFromJson", LIST_RETURN,
                        new String[]{"Lorg/json/JSONObject;"}),
                MountSpec.method("UI-15", "LivePromotion#listFromJson(JSONArray)", false,
                        LIVE_PROMOTION, "listFromJson", LIST_RETURN,
                        new String[]{"Lorg/json/JSONArray;"})));
    }

    private static Method overload(Class<?> paramType) throws Exception {
        return LivePromotion.class.getDeclaredMethod("listFromJson", paramType);
    }

    private static boolean containsLog(List<String> logs, String needle) {
        for (String log : logs) {
            if (log != null && log.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 最小 XposedModule：hook() 对指定参数类型的重载抛错（模拟安装失败），
     * 其余方法返回可链式调用的 HookBuilder 代理（模拟安装成功）。
     */
    private static XposedModule module(Set<String> failingParamTypes, List<String> logs)
            throws Exception {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("log".equals(name)) {
                            if (logs != null && args != null && args.length >= 3) {
                                logs.add(String.valueOf(args[2]));
                            }
                            return null;
                        }
                        if ("hook".equals(name)) {
                            Method target = (Method) args[0];
                            Class<?>[] params = target.getParameterTypes();
                            if (params.length == 1
                                    && failingParamTypes.contains(params[0].getSimpleName())) {
                                throw new IllegalStateException("fixture hook failure: "
                                        + params[0].getSimpleName());
                            }
                            return Proxy.newProxyInstance(
                                    XposedInterface.HookBuilder.class.getClassLoader(),
                                    new Class<?>[]{XposedInterface.HookBuilder.class},
                                    new InvocationHandler() {
                                        @Override
                                        public Object invoke(Object builder, Method builderMethod,
                                                             Object[] builderArgs) {
                                            return "intercept".equals(builderMethod.getName())
                                                    ? null : builder;
                                        }
                                    });
                        }
                        throw new UnsupportedOperationException(name);
                    }
                });
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(base, new Runnable() {
            @Override
            public void run() {
            }
        });
        return module;
    }

    private static JSONObject featureJson(Feature f) throws Exception {
        Method m = HookState.class.getDeclaredMethod("buildJson");
        m.setAccessible(true);
        String json = (String) m.invoke(null);
        JSONObject features = new JSONObject(json).optJSONObject("features");
        JSONObject feature = features == null ? null : features.optJSONObject(f.name());
        return feature == null ? new JSONObject() : feature;
    }
}
