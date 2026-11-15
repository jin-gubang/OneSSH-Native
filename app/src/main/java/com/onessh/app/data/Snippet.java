package com.onessh.app.data;

import org.json.JSONObject;

/** 一条代码片段：名字 + 要发送/复制的命令。groupKey 为空表示未分组。 */
public class Snippet {

    public String id = "";
    public String name = "";
    public String command = "";
    public long createdAt;

    /** 所属分组的 key，为空表示未分组。 */
    public String groupKey = "";
    /** 备注，只做展示和导出，不参与发送。 */
    public String note = "";
    /** 为 true 时只把命令填到输入框，不按回车执行。 */
    public boolean onlyInput = false;

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("command", command);
            o.put("createdAt", createdAt);
            o.put("groupKey", groupKey == null ? "" : groupKey);
            o.put("note", note == null ? "" : note);
            o.put("onlyInput", onlyInput);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Snippet fromJson(JSONObject o) {
        Snippet s = new Snippet();
        s.id = o.optString("id", "");
        s.name = o.optString("name", "");
        s.command = o.optString("command", "");
        s.createdAt = o.optLong("createdAt", 0);
        s.groupKey = o.optString("groupKey", "");
        s.note = o.optString("note", "");
        s.onlyInput = o.optBoolean("onlyInput", false);
        return s;
    }
}
