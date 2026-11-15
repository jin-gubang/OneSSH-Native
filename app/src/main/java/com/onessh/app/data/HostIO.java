package com.onessh.app.data;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主机的导入 / 导出：行文本格式，每行一组 key=value，空格分隔。
 *
 * <pre>
 * groupId=6716283131ed58f1dc5a7f30 title=测试分组
 * host=111.111.111.111 port=22 user=root authType=password password=12345678 groupId=6716283131ed58f1dc5a7f30 title=测试1 note=testnote
 * </pre>
 *
 * 第一行是分组（groupId + title），后面是主机行：host、port、user、
 * authType（password/key）、password（只在 password 认证时有）、
 * groupId（没有表示未分组）、title（主机显示名）、note。
 * 值里有空格、= 或引号时用双引号包起来，导入时照原样还原。
 *
 * 密码在文件里是明文（这个格式本身就这么定的），存进 App 时立刻用
 * Crypto 加密，不在任何地方留明文。跳板机、Mosh、凭据引用这些
 * 本 App 专属的设置不在这个格式里，导出不带，导入按默认处理。
 * 这里只做文本和合并，不碰剪贴板和文件，那是 Activity 的事。
 */
public final class HostIO {

    private HostIO() {
    }

    /* ------------------------------------------------------------- 导出 */

    public static String exportText(Store store) {
        List<Host> hosts = store.hosts();
        List<String> groups = store.groups();
        Map<String, List<Host>> byGroup = new LinkedHashMap<>();
        List<Host> ungrouped = new ArrayList<>();
        for (Host h : hosts) {
            if (h.group != null && !h.group.isEmpty()) {
                List<Host> g = byGroup.get(h.group);
                if (g == null) {
                    g = new ArrayList<>();
                    byGroup.put(h.group, g);
                }
                g.add(h);
            } else {
                ungrouped.add(h);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String name : groups) {
            List<Host> inGroup = byGroup.get(name);
            if (inGroup == null || inGroup.isEmpty()) continue;
            sb.append("groupId=").append(store.hostGroupId(name))
                    .append(" title=").append(quote(name)).append("\n");
            for (Host h : inGroup) appendHost(sb, store, h, store.hostGroupId(name));
        }
        for (Host h : ungrouped) appendHost(sb, store, h, "");
        return sb.toString();
    }

    private static void appendHost(StringBuilder sb, Store store, Host h, String groupId) {
        sb.append("host=").append(quote(h.host));
        sb.append(" port=").append(h.port);
        sb.append(" user=").append(quote(h.user == null ? "" : h.user));
        sb.append(" authType=").append(Host.AUTH_KEY.equals(h.authType)
                ? Host.AUTH_KEY : Host.AUTH_PASSWORD);
        if (Host.AUTH_PASSWORD.equals(h.authType)) {
            String pw = store.passwordFor(h);
            if (pw != null && !pw.isEmpty()) sb.append(" password=").append(quote(pw));
        }
        if (groupId != null && !groupId.isEmpty()) sb.append(" groupId=").append(groupId);
        sb.append(" title=").append(quote(h.label()));
        if (h.note != null && !h.note.isEmpty()) sb.append(" note=").append(quote(h.note));
        sb.append("\n");
    }

    /** 导出文件名，例如 onessh-hosts-all-20261115-200500.txt */
    public static String exportFileName() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        return "onessh-hosts-all-" + f.format(new Date()) + ".txt";
    }

    /** 值里有空格、=、引号或换行就用双引号包起来，里面 " 和 \ 转义。 */
    private static String quote(String v) {
        if (v == null) v = "";
        boolean need = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ' ' || c == '=' || c == '"' || c == '\n' || c == '\r') {
                need = true;
                break;
            }
        }
        if (!need) return v;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    /* ------------------------------------------------------------- 导入 */

    public static final class GroupDef {
        public String id = "";
        public String title = "";
    }

    public static final class HostDef {
        public String host = "";
        public int port = 22;
        public String user = "root";
        public String authType = Host.AUTH_PASSWORD;
        public String password = "";
        public String groupId = "";
        public String title = "";
        public String note = "";
    }

    public static final class ImportData {
        public final List<GroupDef> groups = new ArrayList<>();
        public final List<HostDef> hosts = new ArrayList<>();
    }

    public static final class MergeResult {
        public int groupsAdded;
        public int hostsAdded;
        public int hostsSkipped;
    }

    /** 解析导出的行文本。格式不对的行跳过，不抛异常。 */
    public static ImportData parse(String text) {
        ImportData data = new ImportData();
        if (text == null) return data;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Map<String, String> kv = parseLine(line);
            if (kv.containsKey("host")) {
                HostDef h = new HostDef();
                h.host = kv.get("host");
                if (h.host == null || h.host.isEmpty()) continue;
                try {
                    h.port = Integer.parseInt(kv.get("port"));
                } catch (Exception ignored) {
                }
                if (h.port <= 0 || h.port > 65535) h.port = 22;
                if (kv.containsKey("user")) h.user = kv.get("user");
                if (Host.AUTH_KEY.equals(kv.get("authType"))) h.authType = Host.AUTH_KEY;
                h.password = kv.containsKey("password") ? kv.get("password") : "";
                h.groupId = kv.containsKey("groupId") ? kv.get("groupId") : "";
                h.title = kv.containsKey("title") ? kv.get("title") : "";
                h.note = kv.containsKey("note") ? kv.get("note") : "";
                if (h.title.isEmpty()) h.title = h.user + "@" + h.host;
                data.hosts.add(h);
            } else if (kv.containsKey("groupId") && kv.containsKey("title")) {
                GroupDef g = new GroupDef();
                g.id = kv.get("groupId");
                g.title = kv.get("title");
                if (!g.id.isEmpty() && !g.title.isEmpty()) data.groups.add(g);
            }
        }
        return data;
    }

    /** 一行按 key=value 切，值可以是双引号包起来的（含空格、转义）。 */
    private static Map<String, String> parseLine(String line) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            while (i < n && line.charAt(i) == ' ') i++;
            if (i >= n) break;
            int eq = line.indexOf('=', i);
            if (eq < 0) break;
            String key = line.substring(i, eq).trim();
            i = eq + 1;
            String value;
            if (i < n && line.charAt(i) == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n) {
                    char c = line.charAt(i);
                    if (c == '\\' && i + 1 < n) {
                        char next = line.charAt(i + 1);
                        if (next == 'n') sb.append('\n');
                        else if (next == 'r') sb.append('\r');
                        else sb.append(next);
                        i += 2;
                    } else if (c == '"') {
                        i++;
                        break;
                    } else {
                        sb.append(c);
                        i++;
                    }
                }
                value = sb.toString();
            } else {
                int end = line.indexOf(' ', i);
                if (end < 0) end = n;
                value = line.substring(i, end);
                i = end;
            }
            if (!key.isEmpty()) out.put(key, value);
        }
        return out;
    }

    /** 按（分组、地址、端口、用户名、显示名）去重合并：已有的跳过，只加新的。 */
    public static MergeResult merge(Store store, ImportData data) {
        MergeResult r = new MergeResult();
        // 先认分组：同 id 不同名是对方改了名，跟着改；新 id 就建分组
        Map<String, String> idToName = new LinkedHashMap<>();
        for (GroupDef g : data.groups) {
            String old = store.hostGroupNameForId(g.id);
            if (old == null) {
                store.setHostGroupId(g.id, g.title);
                r.groupsAdded++;
            } else if (!old.equals(g.title)) {
                store.renameGroup(old, g.title);
            }
            idToName.put(g.id, g.title);
        }
        List<Host> existing = store.hosts();
        for (HostDef d : data.hosts) {
            String group = "";
            if (d.groupId != null && !d.groupId.isEmpty()) {
                group = idToName.get(d.groupId);
                if (group == null) group = store.hostGroupNameForId(d.groupId);
                if (group == null) group = "";
            }
            boolean dup = false;
            for (Host h : existing) {
                String hg = h.group == null ? "" : h.group;
                if (hg.equals(group) && h.host.equals(d.host) && h.port == d.port
                        && h.user.equals(d.user) && h.label().equals(d.title)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                r.hostsSkipped++;
                continue;
            }
            Host h = new Host();
            h.host = d.host;
            h.port = d.port;
            h.user = d.user;
            h.name = d.title;
            h.group = group;
            h.note = d.note;
            h.authType = d.authType;
            if (Host.AUTH_PASSWORD.equals(d.authType)
                    && d.password != null && !d.password.isEmpty()) {
                h.passwordEnc = Crypto.encrypt(d.password);
            }
            store.saveHost(h);
            existing.add(h);
            r.hostsAdded++;
        }
        return r;
    }
}
