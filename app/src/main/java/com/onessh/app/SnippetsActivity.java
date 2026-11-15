package com.onessh.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.onessh.app.data.Snippet;
import com.onessh.app.data.SnippetGroup;
import com.onessh.app.data.SnippetIO;
import com.onessh.app.data.Store;
import com.onessh.app.ui.Dialogs;
import com.onessh.app.ui.UI;
import com.onessh.app.ui.Widgets;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 代码片段：把常用命令存起来，点一下就复制（终端里则是一键发送）。 */
public class SnippetsActivity extends Activity {

    private static final int REQ_IMPORT_FILE = 101;
    private static final int REQ_EXPORT_FILE = 102;

    private Store store;
    private LinearLayout listBox;
    private TextView countText;

    /* 编辑器状态 */
    private Snippet editing;
    private Widgets.Field nameField;
    private Widgets.Field cmdField;
    private Widgets.Field noteField;
    private Widgets.Toggle onlyInputToggle;
    private String editingGroupKey = "";

    /* 导出文件时先把 JSON 攒着，等用户选好位置再写 */
    private String pendingExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
        showList();
    }

    /* ------------------------------------------------------------- 列表 */

    private void showList() {
        editing = null;

        LinearLayout root = Widgets.screen(this);

        TextView add = Widgets.textAction(this, "新建", UI.ACCENT);
        add.setTypeface(UI.MEDIUM);
        add.setOnClickListener(v -> showEditor(null));
        root.addView(Widgets.bar(this, "代码片段", this::finish, add));

        LinearLayout head = UI.column(this);
        head.setPadding(UI.dp(20), UI.dp(4), UI.dp(20), 0);
        head.addView(Widgets.eyebrow(this, "SNIPPETS"));
        TextView hint = Widgets.hint(this,
                "把常用命令存成片段。这里点一下是复制，在终端里点一下是直接发送。");
        hint.setLineSpacing(UI.dp(3), 1f);
        Widgets.top(hint, 6);
        head.addView(hint);
        root.addView(head);

        ScrollView sc = Widgets.scroll(this);
        listBox = UI.column(this);
        listBox.setPadding(UI.dp(20), UI.dp(16), UI.dp(20), UI.dp(16));
        sc.addView(listBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout foot = UI.row(this);
        foot.setPadding(UI.dp(20), UI.dp(10), UI.dp(20), UI.dp(12));
        countText = UI.text(this, "", 11.5f, UI.MUTED);
        foot.addView(countText);
        UI.weight(countText, 1f);
        TextView importBtn = Widgets.textAction(this, "导入", UI.ACCENT);
        importBtn.setOnClickListener(v -> importSheet());
        foot.addView(importBtn);
        TextView exportBtn = Widgets.textAction(this, "导出", UI.ACCENT);
        exportBtn.setOnClickListener(v -> exportSheet());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.leftMargin = UI.dp(16);
        exportBtn.setLayoutParams(elp);
        foot.addView(exportBtn);
        LinearLayout wrap = UI.column(this);
        wrap.addView(UI.hline(this, UI.LINE));
        wrap.addView(foot);
        root.addView(wrap);

        setContentView(root);
        render();
    }

    private void render() {
        listBox.removeAllViews();
        List<Snippet> list = store.snippets();
        List<SnippetGroup> groups = store.snippetGroups();

        if (list.isEmpty()) {
            listBox.addView(Widgets.empty(this, "code", "还没有代码片段",
                    "把常用的命令存成片段，之后在终端里一键发送。\n例如：查看磁盘占用、重启服务。",
                    "新建片段", () -> showEditor(null)));
        } else {
            // 按分组分段：先是各分组（按创建时间），最后是未分组
            Map<String, List<Snippet>> byGroup = new LinkedHashMap<>();
            List<Snippet> ungrouped = new ArrayList<>();
            for (Snippet s : list) {
                if (s.groupKey != null && !s.groupKey.isEmpty()
                        && store.snippetGroup(s.groupKey) != null) {
                    List<Snippet> g = byGroup.get(s.groupKey);
                    if (g == null) {
                        g = new ArrayList<>();
                        byGroup.put(s.groupKey, g);
                    }
                    g.add(s);
                } else {
                    ungrouped.add(s);
                }
            }
            for (SnippetGroup g : groups) {
                List<Snippet> inGroup = byGroup.get(g.key);
                if (inGroup == null || inGroup.isEmpty()) continue;
                listBox.addView(groupHeader(g, inGroup.size()));
                for (Snippet s : inGroup) listBox.addView(row(s));
            }
            if (!ungrouped.isEmpty()) {
                listBox.addView(groupHeader(null, ungrouped.size()));
                for (Snippet s : ungrouped) listBox.addView(row(s));
            }
        }

        if (countText != null) {
            countText.setText(list.size() + " 条片段 · " + groups.size() + " 个分组");
        }
    }

    /** 分组标题行：名字 + 条数；有分组的右边 ⋮ 可以重命名 / 删除。 */
    private View groupHeader(final SnippetGroup g, int count) {
        LinearLayout row = UI.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UI.dp(10);
        lp.bottomMargin = UI.dp(6);
        row.setLayoutParams(lp);

        TextView name = UI.text(this,
                (g == null ? "未分组" : g.name) + "  ·  " + count + " 条", 12f, UI.MUTED);
        name.setTypeface(UI.MEDIUM);
        row.addView(name);
        UI.weight(name, 1f);

        if (g != null) {
            row.addView(Widgets.barIcon(this, "more", UI.MUTED, () -> Dialogs.sheet(this, g.name,
                    new String[]{"重命名分组", "删除分组"},
                    new String[]{"edit", "trash"}, 1, idx -> {
                        if (idx == 0) {
                            Dialogs.prompt(this, "重命名分组", "分组名称", "例如：网络",
                                    g.name, false, text -> {
                                        String n = text == null ? "" : text.trim();
                                        if (n.isEmpty()) {
                                            UI.toast(this, "分组名称不能为空");
                                            return;
                                        }
                                        g.name = n;
                                        store.saveSnippetGroup(g);
                                        render();
                                    });
                        } else {
                            int n = 0;
                            for (Snippet s : store.snippets()) {
                                if (g.key.equals(s.groupKey)) n++;
                            }
                            final int inGroup = n;
                            Dialogs.confirm(this, "删除分组",
                                    "确定删除分组「" + g.name + "」吗？里面的 " + inGroup
                                            + " 条片段会移到未分组，不会删除。",
                                    "删除", true, () -> {
                                        store.deleteSnippetGroup(g.key);
                                        render();
                                    });
                        }
                    })));
        }
        return row;
    }

    private View row(Snippet s) {
        LinearLayout row = UI.row(this);
        row.setBackground(UI.ripple(UI.bg(UI.CARD, 12f, UI.LINE, 1f), 12f));
        row.setElevation(UI.dp(1f));
        UI.padding(row, 12, 12, 6, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UI.dp(8);
        row.setLayoutParams(lp);

        LinearLayout tile = UI.row(this);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(UI.bg(UI.PAPER2, 10f));
        tile.addView(UI.icon(this, "code", UI.INK2, 18));
        row.addView(tile, new LinearLayout.LayoutParams(UI.dp(36), UI.dp(36)));

        LinearLayout col = UI.column(this);
        TextView name = UI.medium(this,
                s.name == null || s.name.isEmpty() ? "未命名" : s.name, 13.5f, UI.INK);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);

        TextView cmd = UI.mono(this, s.command.replace("\n", " ⏎ "), 11f, UI.MUTED);
        cmd.setSingleLine(true);
        cmd.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Widgets.top(cmd, 3);
        col.addView(cmd);

        if (s.note != null && !s.note.isEmpty()) {
            TextView note = UI.text(this, s.note, 11f, UI.MUTED);
            note.setSingleLine(true);
            note.setEllipsize(android.text.TextUtils.TruncateAt.END);
            Widgets.top(note, 2);
            col.addView(note);
        }

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = UI.dp(12);
        row.addView(col, cp);

        row.addView(Widgets.barIcon(this, "more", UI.MUTED, () -> Dialogs.sheet(this, s.name,
                new String[]{"复制命令", "编辑", "删除"},
                new String[]{"copy", "edit", "trash"}, 2, idx -> {
                    switch (idx) {
                        case 0:
                            copy(s.command);
                            break;
                        case 1:
                            showEditor(s);
                            break;
                        default:
                            Dialogs.confirm(this, "删除片段",
                                    "确定删除「" + s.name + "」吗？", "删除", true, () -> {
                                        store.deleteSnippet(s.id);
                                        render();
                                    });
                            break;
                    }
                })));

        row.setOnClickListener(v -> {
            copy(s.command);
            UI.toast(this, "已复制：" + s.name);
        });
        return row;
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("snippet", text));
    }

    /* ------------------------------------------------------------- 编辑器 */

    private void showEditor(Snippet snippet) {
        editing = snippet != null ? snippet : new Snippet();
        editingGroupKey = editing.groupKey == null ? "" : editing.groupKey;

        LinearLayout root = Widgets.screen(this);

        TextView save = Widgets.textAction(this, "保存", UI.ACCENT);
        save.setTypeface(UI.MEDIUM);
        save.setOnClickListener(v -> save());
        root.addView(Widgets.bar(this,
                snippet == null ? "新建片段" : "编辑片段", this::showList, save));

        ScrollView sc = Widgets.scroll(this);
        LinearLayout body = UI.column(this);
        body.setPadding(UI.dp(20), UI.dp(6), UI.dp(20), UI.dp(24));
        sc.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout card = Widgets.cardBox(this);
        card.addView(Widgets.cardTitle(this, "片段"));

        nameField = Widgets.field(this, "名称", "例如：查看磁盘占用", InputType.TYPE_CLASS_TEXT);
        nameField.setText(editing.name);
        card.addView(nameField.root);

        cmdField = Widgets.field(this, "命令",
                "例如：df -h\ntop -b -n1 | head -20",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        cmdField.input.setTypeface(UI.MONO);
        cmdField.input.setTextSize(12f);
        cmdField.input.setMinLines(4);
        cmdField.input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cmdField.setText(editing.command);
        Widgets.top(cmdField.root, 12);
        card.addView(cmdField.root);

        noteField = Widgets.field(this, "备注（可选）", "例如：先装 speedtest-cli",
                InputType.TYPE_CLASS_TEXT);
        noteField.setText(editing.note == null ? "" : editing.note);
        Widgets.top(noteField.root, 12);
        card.addView(noteField.root);

        body.addView(card);

        LinearLayout groupCard = Widgets.cardBox(this);
        Widgets.top(groupCard, 12);
        groupCard.addView(Widgets.cardTitle(this, "分组"));
        LinearLayout groupRow = Widgets.pickRow(this, "folder",
                "分组", groupName(editingGroupKey), this::pickGroup);
        groupCard.addView(groupRow);
        body.addView(groupCard);

        LinearLayout optCard = Widgets.cardBox(this);
        Widgets.top(optCard, 12);
        optCard.addView(Widgets.cardTitle(this, "发送方式"));
        LinearLayout onlyRow = UI.row(this);
        onlyRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = UI.column(this);
        col.addView(UI.medium(this, "仅输入，不执行", 13.5f, UI.INK));
        TextView onlyHint = UI.text(this, "只把命令填到输入框，不按回车，适合要改几个参数再跑的命令。",
                11.5f, UI.MUTED);
        onlyHint.setLineSpacing(UI.dp(3), 1f);
        Widgets.top(onlyHint, 3);
        col.addView(onlyHint);
        onlyRow.addView(col);
        UI.weight(col, 1f);
        onlyInputToggle = new Widgets.Toggle(this, editing.onlyInput);
        onlyRow.addView(onlyInputToggle);
        optCard.addView(onlyRow);
        body.addView(optCard);

        TextView note = UI.text(this,
                "命令按原样发送（末尾会自动补一个回车）。多行命令会逐行发送。", 11.5f, UI.MUTED);
        note.setLineSpacing(UI.dp(3), 1f);
        Widgets.top(note, 14);
        body.addView(note);

        setContentView(root);
        if (snippet == null) nameField.input.requestFocus();
    }

    private String groupName(String key) {
        if (key == null || key.isEmpty()) return "未分组";
        SnippetGroup g = store.snippetGroup(key);
        return g == null ? "未分组" : g.name;
    }

    /** 选完分组要重建编辑器才刷新显示，先把已填的文字暂存到 editing 里 */
    private void stashEditorText() {
        if (editing == null) return;
        if (nameField != null) editing.name = nameField.text();
        if (cmdField != null) editing.command = cmdField.text();
        if (noteField != null) editing.note = noteField.text();
        if (onlyInputToggle != null) editing.onlyInput = onlyInputToggle.isChecked();
    }

    /** 选分组：未分组 / 已有分组 / 新建分组 */
    private void pickGroup() {
        List<SnippetGroup> groups = store.snippetGroups();
        List<String> labels = new ArrayList<>();
        List<String> subs = new ArrayList<>();
        labels.add("未分组");
        subs.add("不放在任何分组里");
        int selected = 0;
        for (int i = 0; i < groups.size(); i++) {
            SnippetGroup g = groups.get(i);
            labels.add(g.name);
            int n = 0;
            for (Snippet s : store.snippets()) if (g.key.equals(s.groupKey)) n++;
            subs.add(n + " 条片段");
            if (g.key.equals(editingGroupKey)) selected = i + 1;
        }
        labels.add("＋ 新建分组…");
        subs.add(null);
        Dialogs.choose(this, "选择分组",
                labels.toArray(new String[0]), subs.toArray(new String[0]),
                selected, idx -> {
                    if (idx == labels.size() - 1) {
                        Dialogs.prompt(this, "新建分组", "分组名称", "例如：网络",
                                "", false, text -> {
                                    String n = text == null ? "" : text.trim();
                                    if (n.isEmpty()) {
                                        UI.toast(this, "分组名称不能为空");
                                        return;
                                    }
                                    SnippetGroup g = new SnippetGroup();
                                    g.name = n;
                                    store.saveSnippetGroup(g);
                                    editingGroupKey = g.key;
                                    stashEditorText();
                                    showEditor(editing);
                                });
                    } else if (idx == 0) {
                        editingGroupKey = "";
                        stashEditorText();
                        showEditor(editing);
                    } else {
                        editingGroupKey = groups.get(idx - 1).key;
                        stashEditorText();
                        showEditor(editing);
                    }
                });
    }

    private void save() {
        String name = nameField.text().trim();
        String command = cmdField.text().trim();

        if (command.isEmpty()) {
            UI.toast(this, "命令不能为空");
            return;
        }
        if (name.isEmpty()) {
            // 用命令的第一行兜底当名字
            String first = command.split("\n")[0].trim();
            name = first.length() > 24 ? first.substring(0, 24) + "…" : first;
        }

        editing.name = name;
        editing.command = command;
        editing.groupKey = editingGroupKey;
        editing.note = noteField == null ? "" : noteField.text().trim();
        editing.onlyInput = onlyInputToggle != null && onlyInputToggle.isChecked();
        store.saveSnippet(editing);
        UI.toast(this, "已保存");
        showList();
    }

    /* ------------------------------------------------------------- 导入 / 导出 */

    private void importSheet() {
        Dialogs.sheet(this, "导入片段",
                new String[]{"从剪贴板导入", "从文件导入"},
                new String[]{"clipboard", "folder"}, -1, idx -> {
                    if (idx == 0) importFromClipboard();
                    else importFromFile();
                });
    }

    private void exportSheet() {
        Dialogs.sheet(this, "导出片段",
                new String[]{"复制到剪贴板", "保存到文件"},
                new String[]{"clipboard", "folder"}, -1, idx -> {
                    if (idx == 0) exportToClipboard();
                    else exportToFile();
                });
    }

    private void importFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            UI.toast(this, "剪贴板里没有内容");
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            UI.toast(this, "剪贴板里没有内容");
            return;
        }
        CharSequence text = clip.getItemAt(0).getText();
        if (text == null || text.toString().trim().isEmpty()) {
            UI.toast(this, "剪贴板里没有内容");
            return;
        }
        doImport(text.toString());
    }

    private void importFromFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT_FILE);
        } catch (Exception e) {
            UI.toast(this, "没有可用的文件选择器");
        }
    }

    private void doImport(String text) {
        SnippetIO.ImportData data;
        try {
            data = SnippetIO.parse(text);
        } catch (Exception e) {
            UI.toast(this, "导入失败：不是有效的片段导出文件");
            return;
        }
        if (data.snippets.isEmpty() && data.groups.isEmpty()) {
            UI.toast(this, "没有可导入的片段");
            return;
        }
        SnippetIO.MergeResult r = SnippetIO.merge(store, data);
        UI.toast(this, "导入完成：新增 " + r.snippetsAdded + " 条片段、"
                + r.groupsAdded + " 个分组"
                + (r.snippetsSkipped > 0 ? "，跳过 " + r.snippetsSkipped + " 条重复" : ""));
        render();
    }

    private void exportToClipboard() {
        String json = SnippetIO.exportJson(store.snippetGroups(), store.snippets());
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("snippets", json));
        UI.toast(this, "已复制到剪贴板");
    }

    private void exportToFile() {
        pendingExport = SnippetIO.exportJson(store.snippetGroups(), store.snippets());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, SnippetIO.exportFileName());
        try {
            startActivityForResult(i, REQ_EXPORT_FILE);
        } catch (Exception e) {
            UI.toast(this, "没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_FILE) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("读不到文件");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                doImport(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                UI.toast(this, "导入失败：" + e.getMessage());
            }
        } else if (requestCode == REQ_EXPORT_FILE) {
            if (pendingExport == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("写不到文件");
                out.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                out.flush();
                UI.toast(this, "已导出");
            } catch (Exception e) {
                UI.toast(this, "导出失败：" + e.getMessage());
            } finally {
                pendingExport = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (editing != null) showList();
        else super.onBackPressed();
    }
}
