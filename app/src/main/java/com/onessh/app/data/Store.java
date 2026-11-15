package com.onessh.app.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地存储：一个 JSON 文件放在应用私有目录，原子写入（临时文件 + rename）。
 * 密码、私钥这类字段只以密文形式出现，密钥在 Android Keystore 里。
 */
public final class Store {

    private static final String TAG = "Store";
    private static final String FILE = "onessh.json";
    private static Store instance;

    private final File file;
    private final JSONObject root;

    private Store(Context ctx) {
        file = new File(ctx.getFilesDir(), FILE);
        JSONObject loaded = null;
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                loaded = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "读取存储失败，按空库处理", e);
            }
        }
        root = loaded != null ? loaded : new JSONObject();
    }

    public static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    private synchronized void persist() {
        try {
            File tmp = new File(file.getParentFile(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                // 某些文件系统 rename 会失败，退化成直接覆盖
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "写入存储失败", e);
        }
    }

    private JSONArray arr(String key) {
        JSONArray a = root.optJSONArray(key);
        if (a == null) {
            a = new JSONArray();
            try {
                root.put(key, a);
            } catch (Exception ignored) {
            }
        }
        return a;
    }

    private JSONObject obj(String key) {
        JSONObject o = root.optJSONObject(key);
        if (o == null) {
            o = new JSONObject();
            try {
                root.put(key, o);
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    /* ------------------------------------------------------------- 主机 */

    public synchronized List<Host> hosts() {
        List<Host> out = new ArrayList<>();
        JSONArray a = arr("hosts");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(Host.fromJson(o));
        }
        Collections.sort(out, (x, y) -> {
            if (x.createdAt != y.createdAt) return Long.compare(x.createdAt, y.createdAt);
            return x.label().compareToIgnoreCase(y.label());
        });
        return out;
    }

    public synchronized Host host(String id) {
        for (Host h : hosts()) if (h.id.equals(id)) return h;
        return null;
    }

    public synchronized void saveHost(Host h) {
        if (h.id == null || h.id.isEmpty()) {
            h.id = "h" + Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString((int) (Math.random() * 1e6), 36);
        }
        if (h.createdAt == 0) h.createdAt = System.currentTimeMillis();
        JSONArray a = arr("hosts");
        boolean replaced = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && h.id.equals(o.optString("id"))) {
                try {
                    a.put(i, h.toJson());
                    replaced = true;
                } catch (Exception ignored) {
                }
                break;
            }
        }
        if (!replaced) a.put(h.toJson());
        persist();
    }

    public synchronized void deleteHost(String id) {
        JSONArray a = arr("hosts");
        JSONArray next = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) next.put(o);
        }
        try {
            root.put("hosts", next);
        } catch (Exception ignored) {
        }
        persist();
    }

    public synchronized void touchHost(String id) {
        Host h = host(id);
        if (h == null) return;
        h.lastUsedAt = System.currentTimeMillis();
        saveHost(h);
    }

    public synchronized int hostCountUsingCred(String credId) {
        int n = 0;
        for (Host h : hosts()) if (credId.equals(h.credId)) n++;
        return n;
    }

    public synchronized int hostCountUsingKey(String keyId) {
        int n = 0;
        for (Host h : hosts()) if (keyId.equals(h.keyId)) n++;
        return n;
    }

    /* ------------------------------------------------------------- 分组 */

    public synchronized List<String> groups() {
        List<String> out = new ArrayList<>();
        JSONArray a = arr("groups");
        for (int i = 0; i < a.length(); i++) {
            String g = a.optString(i, "").trim();
            if (!g.isEmpty() && !out.contains(g)) out.add(g);
        }
        // 主机里出现过但没登记的分组也算
        for (Host h : hosts()) {
            String g = h.group == null ? "" : h.group.trim();
            if (!g.isEmpty() && !out.contains(g)) out.add(g);
        }
        return out;
    }

    public synchronized void addGroup(String name) {
        String g = name == null ? "" : name.trim();
        if (g.isEmpty() || groups().contains(g)) return;
        arr("groups").put(g);
        persist();
    }

    public synchronized int renameGroup(String from, String to) {
        String target = to == null ? "" : to.trim();
        if (target.isEmpty()) return 0;
        JSONArray a = arr("groups");
        JSONArray next = new JSONArray();
        boolean added = false;
        for (int i = 0; i < a.length(); i++) {
            String g = a.optString(i, "");
            if (g.equals(from)) {
                next.put(target);
                added = true;
            } else {
                next.put(g);
            }
        }
        if (!added) next.put(target);
        try {
            root.put("groups", next);
        } catch (Exception ignored) {
        }
        // 分组改名，id 映射跟着改名，id 本身不变，导出还是同一个 groupId
        JSONObject ids = obj("hostGroupIds");
        java.util.Iterator<String> it = ids.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (from.equals(ids.optString(k, ""))) {
                try {
                    ids.put(k, target);
                } catch (Exception ignored) {
                }
            }
        }
        int moved = 0;
        for (Host h : hosts()) {
            if (from.equals(h.group)) {
                h.group = target;
                saveHost(h);
                moved++;
            }
        }
        persist();
        return moved;
    }

    public synchronized int removeGroup(String name) {
        JSONArray a = arr("groups");
        JSONArray next = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            String g = a.optString(i, "");
            if (!g.equals(name)) next.put(g);
        }
        try {
            root.put("groups", next);
        } catch (Exception ignored) {
        }
        JSONObject ids = obj("hostGroupIds");
        java.util.List<String> dead = new java.util.ArrayList<>();
        java.util.Iterator<String> it = ids.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (name.equals(ids.optString(k, ""))) dead.add(k);
        }
        for (String k : dead) ids.remove(k);
        int moved = 0;
        for (Host h : hosts()) {
            if (name.equals(h.group)) {
                h.group = "";
                saveHost(h);
                moved++;
            }
        }
        persist();
        return moved;
    }

    /* ---------------------------------------------- 主机分组 id（导入/导出用） */

    /**
     * 主机分组在 App 里只认名字，导入/导出格式里认 groupId。
     * 这里存一份 id → 名字的映射，导出时同一个分组永远是同一个 id，
     * 导入时按 id 认出已有的分组（对端改了名字也认得出来）。
     */
    public synchronized String hostGroupId(String name) {
        String g = name == null ? "" : name.trim();
        if (g.isEmpty()) return "";
        JSONObject ids = obj("hostGroupIds");
        java.util.Iterator<String> it = ids.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (g.equals(ids.optString(k, ""))) return k;
        }
        String id = newGroupId();
        try {
            ids.put(id, g);
        } catch (Exception ignored) {
        }
        persist();
        return id;
    }

    public synchronized String hostGroupNameForId(String id) {
        if (id == null || id.isEmpty()) return null;
        String name = obj("hostGroupIds").optString(id, "");
        return name.isEmpty() ? null : name;
    }

    public synchronized void setHostGroupId(String id, String name) {
        String g = name == null ? "" : name.trim();
        if (id == null || id.isEmpty() || g.isEmpty()) return;
        try {
            obj("hostGroupIds").put(id, g);
        } catch (Exception ignored) {
        }
        addGroup(g);
        persist();
    }

    /** 24 位十六进制 id，跟示例里的 groupId 一个样子。 */
    private static String newGroupId() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(24);
        for (byte v : b) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------- 凭据 */

    public synchronized List<Credential> creds() {
        List<Credential> out = new ArrayList<>();
        JSONArray a = arr("creds");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(Credential.fromJson(o));
        }
        return out;
    }

    public synchronized Credential cred(String id) {
        for (Credential c : creds()) if (c.id.equals(id)) return c;
        return null;
    }

    public synchronized void saveCred(Credential c) {
        if (c.id == null || c.id.isEmpty()) {
            c.id = "c" + Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString((int) (Math.random() * 1e6), 36);
        }
        if (c.createdAt == 0) c.createdAt = System.currentTimeMillis();
        JSONArray a = arr("creds");
        boolean replaced = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && c.id.equals(o.optString("id"))) {
                try {
                    a.put(i, c.toJson());
                    replaced = true;
                } catch (Exception ignored) {
                }
                break;
            }
        }
        if (!replaced) a.put(c.toJson());
        persist();
    }

    public synchronized void deleteCred(String id) {
        JSONArray a = arr("creds");
        JSONArray next = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) next.put(o);
        }
        try {
            root.put("creds", next);
        } catch (Exception ignored) {
        }
        persist();
    }

    /* ------------------------------------------------------------- 私钥 */

    public synchronized List<KeyItem> keys() {
        List<KeyItem> out = new ArrayList<>();
        JSONArray a = arr("keys");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(KeyItem.fromJson(o));
        }
        return out;
    }

    public synchronized KeyItem key(String id) {
        for (KeyItem k : keys()) if (k.id.equals(id)) return k;
        return null;
    }

    public synchronized void saveKey(KeyItem k) {
        if (k.id == null || k.id.isEmpty()) {
            k.id = "k" + Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString((int) (Math.random() * 1e6), 36);
        }
        if (k.createdAt == 0) k.createdAt = System.currentTimeMillis();
        JSONArray a = arr("keys");
        boolean replaced = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && k.id.equals(o.optString("id"))) {
                try {
                    a.put(i, k.toJson());
                    replaced = true;
                } catch (Exception ignored) {
                }
                break;
            }
        }
        if (!replaced) a.put(k.toJson());
        persist();
    }

    public synchronized void deleteKey(String id) {
        JSONArray a = arr("keys");
        JSONArray next = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) next.put(o);
        }
        try {
            root.put("keys", next);
        } catch (Exception ignored) {
        }
        persist();
    }

    /* ------------------------------------------------------------- 片段 */

    public synchronized List<Snippet> snippets() {
        List<Snippet> out = new ArrayList<>();
        JSONArray a = arr("snippets");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(Snippet.fromJson(o));
        }
        Collections.sort(out, (x, y) -> {
            if (x.createdAt != y.createdAt) return Long.compare(x.createdAt, y.createdAt);
            return x.name.compareToIgnoreCase(y.name);
        });
        return out;
    }

    public synchronized Snippet snippet(String id) {
        for (Snippet s : snippets()) if (s.id.equals(id)) return s;
        return null;
    }

    public synchronized void saveSnippet(Snippet sn) {
        if (sn.id == null || sn.id.isEmpty()) {
            sn.id = "s" + Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString((int) (Math.random() * 1e6), 36);
        }
        if (sn.createdAt == 0) sn.createdAt = System.currentTimeMillis();
        JSONArray a = arr("snippets");
        boolean replaced = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && sn.id.equals(o.optString("id"))) {
                try {
                    a.put(i, sn.toJson());
                    replaced = true;
                } catch (Exception ignored) {
                }
                break;
            }
        }
        if (!replaced) a.put(sn.toJson());
        persist();
    }

    public synchronized void deleteSnippet(String id) {
        JSONArray a = arr("snippets");
        JSONArray next = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) next.put(o);
        }
        try {
            root.put("snippets", next);
        } catch (Exception ignored) {
        }
        persist();
    }

    /* ------------------------------------------------------------- 片段分组 */

    public synchronized List<SnippetGroup> snippetGroups() {
        List<SnippetGroup> out = new ArrayList<>();
        JSONArray a = arr("snippetGroups");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(SnippetGroup.fromJson(o));
        }
        Collections.sort(out, (x, y) -> {
            if (x.createdAt != y.createdAt) return Long.compare(x.createdAt, y.createdAt);
            return x.name.compareToIgnoreCase(y.name);
        });
        return out;
    }

    public synchronized SnippetGroup snippetGroup(String key) {
        if (key == null || key.isEmpty()) return null;
        for (SnippetGroup g : snippetGroups()) if (g.key.equals(key)) return g;
        return null;
    }

    public synchronized void saveSnippetGroup(SnippetGroup g) {
        if (g.key == null || g.key.isEmpty()) {
            g.key = "g" + Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString((int) (Math.random() * 1e6), 36);
        }
        if (g.createdAt == 0) g.createdAt = System.currentTimeMillis();
        JSONArray a = arr("snippetGroups");
        boolean replaced = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && g.key.equals(o.optString("key"))) {
                try {
                    a.put(i, g.toJson());
                    replaced = true;
                } catch (Exception ignored) {
                }
                break;
            }
        }
        if (!replaced) a.put(g.toJson());
        persist();
    }

    /** 删除分组：里面的片段不删除，移到未分组。 */
    public synchronized void deleteSnippetGroup(String key) {
        JSONArray a = arr("snippetGroups");
        JSONArray next = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !key.equals(o.optString("key"))) next.put(o);
        }
        try {
            root.put("snippetGroups", next);
        } catch (Exception ignored) {
        }
        JSONArray s = arr("snippets");
        for (int i = 0; i < s.length(); i++) {
            JSONObject o = s.optJSONObject(i);
            if (o != null && key.equals(o.optString("groupKey"))) {
                try {
                    o.put("groupKey", "");
                } catch (Exception ignored) {
                }
            }
        }
        persist();
    }

    /* ------------------------------------------------------- 已信任主机 */

    public synchronized String knownFingerprint(String hostKey) {
        return obj("known").optString(hostKey, "");
    }

    public synchronized Map<String, String> knownHosts() {
        JSONObject o = obj("known");
        Map<String, String> out = new LinkedHashMap<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            out.put(k, o.optString(k, ""));
        }
        return out;
    }

    public synchronized void trustHost(String hostKey, String fingerprint) {
        try {
            obj("known").put(hostKey, fingerprint);
        } catch (Exception ignored) {
        }
        persist();
    }

    public synchronized void forgetHost(String hostKey) {
        obj("known").remove(hostKey);
        persist();
    }

    /* ------------------------------------------------------------- 设置 */

    public synchronized int fontSize() {
        return obj("settings").optInt("fontSize", 13);
    }

    public synchronized void setFontSize(int sp) {
        try {
            obj("settings").put("fontSize", Math.max(9, Math.min(24, sp)));
        } catch (Exception ignored) {
        }
        persist();
    }

    public synchronized boolean keepAwake() {
        return obj("settings").optBoolean("keepAwake", true);
    }

    public synchronized void setKeepAwake(boolean on) {
        try {
            obj("settings").put("keepAwake", on);
        } catch (Exception ignored) {
        }
        persist();
    }

    public synchronized void clearAll() {
        java.util.Iterator<String> it = root.keys();
        List<String> keys = new ArrayList<>();
        while (it.hasNext()) keys.add(it.next());
        for (String k : keys) root.remove(k);
        persist();
    }

    /* ------------------------------------------------------- 凭据解析 */

    /** 真正用来登录的用户名：引用了凭据库就用库里的。 */
    public synchronized String usernameFor(Host h) {
        if (h.credId != null && !h.credId.isEmpty()) {
            Credential c = cred(h.credId);
            if (c != null && !c.username.isEmpty()) return c.username;
        }
        return h.user;
    }

    public synchronized String passwordFor(Host h) {
        if (h.credId != null && !h.credId.isEmpty()) {
            Credential c = cred(h.credId);
            if (c != null) return Crypto.decrypt(c.passwordEnc);
        }
        return Crypto.decrypt(h.passwordEnc);
    }

    public synchronized String privateKeyFor(Host h) {
        if (h.keyId != null && !h.keyId.isEmpty()) {
            KeyItem k = key(h.keyId);
            if (k != null) return Crypto.decrypt(k.keyEnc);
        }
        return Crypto.decrypt(h.keyEnc);
    }

    public synchronized String passphraseFor(Host h) {
        if (h.keyId != null && !h.keyId.isEmpty()) {
            KeyItem k = key(h.keyId);
            if (k != null) return Crypto.decrypt(k.passphraseEnc);
        }
        return Crypto.decrypt(h.passphraseEnc);
    }
}
