package com.onessh.app.data;

import org.json.JSONObject;

/** 一台保存下来的主机。密码本身不在这里，只存密文。 */
public class Host {

    public static final String AUTH_PASSWORD = "password";
    public static final String AUTH_KEY = "key";

    public String id = "";
    public String name = "";
    public String host = "";
    public int port = 22;
    public String user = "";
    public String group = "";
    public String note = "";
    public String authType = AUTH_PASSWORD;

    /** 引用凭据库里的常用账号（有它就忽略 passwordEnc） */
    public String credId = "";
    /** 引用私钥库里的私钥（有它就忽略 keyEnc） */
    public String keyId = "";
    /** 跳板机：先连这台，再从它转发到目标主机 */
    public String jumpId = "";
    /** 用 Mosh 而不用 SSH 连这台主机（需服务器装 mosh-server 并放行 UDP 端口） */
    public boolean mosh;

    public String passwordEnc = "";
    public String keyEnc = "";
    public String passphraseEnc = "";

    public long createdAt;
    public long lastUsedAt;

    public String label() {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        return user + "@" + host;
    }

    public String subtitle() {
        String addr = host == null ? "" : host;
        if (addr.contains(":") && !addr.startsWith("[")) addr = "[" + addr + "]";
        return user + "@" + addr + (port == 22 ? "" : ":" + port);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("host", host);
            o.put("port", port);
            o.put("user", user);
            o.put("group", group);
            o.put("note", note);
            o.put("authType", authType);
            o.put("credId", credId);
            o.put("keyId", keyId);
            o.put("jumpId", jumpId);
            o.put("mosh", mosh);
            o.put("passwordEnc", passwordEnc);
            o.put("keyEnc", keyEnc);
            o.put("passphraseEnc", passphraseEnc);
            o.put("createdAt", createdAt);
            o.put("lastUsedAt", lastUsedAt);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Host fromJson(JSONObject o) {
        Host h = new Host();
        h.id = o.optString("id", "");
        h.name = o.optString("name", "");
        h.host = o.optString("host", "");
        h.port = o.optInt("port", 22);
        h.user = o.optString("user", "");
        h.group = o.optString("group", "");
        h.note = o.optString("note", "");
        h.authType = o.optString("authType", AUTH_PASSWORD);
        h.credId = o.optString("credId", "");
        h.keyId = o.optString("keyId", "");
        h.jumpId = o.optString("jumpId", "");
        h.mosh = o.optBoolean("mosh", false);
        h.passwordEnc = o.optString("passwordEnc", "");
        h.keyEnc = o.optString("keyEnc", "");
        h.passphraseEnc = o.optString("passphraseEnc", "");
        h.createdAt = o.optLong("createdAt", 0);
        h.lastUsedAt = o.optLong("lastUsedAt", 0);
        return h;
    }
}
