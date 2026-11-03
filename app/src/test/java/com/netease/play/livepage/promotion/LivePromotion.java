package com.netease.play.livepage.promotion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * 单测夹具：复现宿主 LivePromotion 的两个同名重载
 * {@code listFromJson(JSONObject)} / {@code listFromJson(JSONArray)}。
 *
 * <p>用于验证 CommunityLiveHooks 为两个重载生成不同的挂载点 label，
 * 以及一个重载安装失败不会被另一个重载的安装成功误清。
 */
public class LivePromotion {

    public List<Object> listFromJson(JSONObject json) {
        return Collections.emptyList();
    }

    public List<Object> listFromJson(JSONArray json) {
        return Collections.emptyList();
    }
}
