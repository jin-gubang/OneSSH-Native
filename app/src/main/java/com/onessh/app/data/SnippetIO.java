package com.onessh.app.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 片段的导入 / 导出：JSON 格式跟 quick-command 导出示例一致。
 *
 * <pre>
 * {
 *   "$schema": "OneSSH.quick-command.export/v1",
 *   "version": 1,
 *   "meta": {"exportedAt": "...", "sourceStore": "local",
 *            "scope": "all", "scopeName": "all"},
 *   "groups": [{"key": "...", "name": "基础", "parentKey": null}],
 *   "commands": [{"key": "...", "groupKey": "...", "name": "...",
 *                 "content": "...", "onlyInput": false, "note": "..."}]
 * }
 * </pre>
 *
 * 导出是全部片段 + 全部分组（scope all）。导入按 key 去重：已有的分组
 * 和片段跳过，只加新的；命令里 groupKey 指不到已导入/已有分组的，归到未分组。
 * 这里只做 JSON 和合并，不碰剪贴板和文件，那是 Activity 的事。
 */
public final class SnippetIO {

    public static final String SCHEMA = "OneSSH.quick-command.export/v1";

    private SnippetIO() {
    }

    /* ------------------------------------------------------------- 导出 */

    public static String exportJson(List<SnippetGroup> groups, List<Snippet> snippets) {
        JSONObject root = new JSONObject();
        try {
            root.put("$schema", SCHEMA);
            root.put("version", 1);

            JSONObject meta = new JSONObject();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            meta.put("exportedAt", f.format(new Date()));
            meta.put("sourceStore", "local");
            meta.put("scope", "all");
            meta.put("scopeName", "all");
            root.put("meta", meta);

            JSONArray ga = new JSONArray();
            for (SnippetGroup g : groups) {
                JSONObject o = new JSONObject();
                o.put("key", g.key);
                o.put("name", g.name);
                if (g.parentKey == null || g.parentKey.isEmpty()) o.put("parentKey", JSONObject.NULL);
                else o.put("parentKey", g.parentKey);
                ga.put(o);
            }
            root.put("groups", ga);

            JSONArray ca = new JSONArray();
            for (Snippet s : snippets) {
                JSONObject o = new JSONObject();
                o.put("key", s.id);
                if (s.groupKey == null || s.groupKey.isEmpty()) o.put("groupKey", JSONObject.NULL);
                else o.put("groupKey", s.groupKey);
                o.put("name", s.name);
                o.put("content", s.command);
                o.put("onlyInput", s.onlyInput);
                if (s.note != null && !s.note.isEmpty()) o.put("note", s.note);
                ca.put(o);
            }
            root.put("commands", ca);
            return root.toString(2);
        } catch (Exception ignored) {
        }
        return "{}";
    }

    /** 导出文件名，例如 onessh-snippets-all-20261115-193045.json */
    public static String exportFileName() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        return "onessh-snippets-all-" + f.format(new Date()) + ".json";
    }

    /* ------------------------------------------------------------- 导入 */

    public static final class ImportData {
        public final List<SnippetGroup> groups = new ArrayList<>();
        public final List<Snippet> snippets = new ArrayList<>();
    }

    public static final class MergeResult {
        public int groupsAdded;
        public int snippetsAdded;
        public int snippetsSkipped;
    }

    /** 解析导出的 JSON。格式不对（不是 JSON、没有 commands 数组）就抛异常。 */
    public static ImportData parse(String text) throws Exception {
        JSONObject root = new JSONObject(text);
        ImportData data = new ImportData();

        JSONArray ga = root.optJSONArray("groups");
        if (ga != null) {
            for (int i = 0; i < ga.length(); i++) {
                JSONObject o = ga.optJSONObject(i);
                if (o == null) continue;
                SnippetGroup g = new SnippetGroup();
                g.key = o.optString("key", "");
                g.name = o.optString("name", "");
                g.parentKey = o.isNull("parentKey") ? "" : o.optString("parentKey", "");
                if (g.key.isEmpty() || g.name.isEmpty()) continue;
                data.groups.add(g);
            }
        }

        JSONArray ca = root.optJSONArray("commands");
        if (ca == null) throw new Exception("缺少 commands");
        for (int i = 0; i < ca.length(); i++) {
            JSONObject o = ca.optJSONObject(i);
            if (o == null) continue;
            String content = o.optString("content", "");
            if (content.isEmpty()) continue;
            Snippet s = new Snippet();
            s.id = o.optString("key", "");
            s.groupKey = o.isNull("groupKey") ? "" : o.optString("groupKey", "");
            s.name = o.optString("name", "");
            s.command = content;
            s.onlyInput = o.optBoolean("onlyInput", false);
            s.note = o.optString("note", "");
            if (s.name.isEmpty()) {
                String first = content.split("\n")[0].trim();
                s.name = first.length() > 24 ? first.substring(0, 24) + "…" : first;
            }
            data.snippets.add(s);
        }
        return data;
    }

    /** 按 key 合并进 Store：已有的跳过，只加新的。返回新增/跳过数。 */
    public static MergeResult merge(Store store, ImportData data) {
        MergeResult r = new MergeResult();
        for (SnippetGroup g : data.groups) {
            if (store.snippetGroup(g.key) != null) continue;
            store.saveSnippetGroup(g);
            r.groupsAdded++;
        }
        for (Snippet s : data.snippets) {
            if (s.id != null && !s.id.isEmpty() && store.snippet(s.id) != null) {
                r.snippetsSkipped++;
                continue;
            }
            if (s.groupKey != null && !s.groupKey.isEmpty()
                    && store.snippetGroup(s.groupKey) == null) {
                s.groupKey = "";
            }
            // key 没撞上就原样保留，导出再导入能认出是同一条；空 key 由 saveSnippet 生成
            s.createdAt = 0;
            store.saveSnippet(s);
            r.snippetsAdded++;
        }
        return r;
    }
}
