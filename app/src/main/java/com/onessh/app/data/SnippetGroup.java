package com.onessh.app.data;

import org.json.JSONObject;

/** 片段分组：名字 + 可选的上级分组（parentKey 为空表示顶级分组）。 */
public class SnippetGroup {

    public String key = "";
    public String name = "";
    public String parentKey = "";
    public long createdAt;

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("key", key);
            o.put("name", name);
            o.put("parentKey", parentKey == null ? "" : parentKey);
            o.put("createdAt", createdAt);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static SnippetGroup fromJson(JSONObject o) {
        SnippetGroup g = new SnippetGroup();
        g.key = o.optString("key", "");
        g.name = o.optString("name", "");
        g.parentKey = o.optString("parentKey", "");
        g.createdAt = o.optLong("createdAt", 0);
        return g;
    }
}
