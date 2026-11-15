package com.onessh.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.onessh.app.data.Credential;
import com.onessh.app.data.Crypto;
import com.onessh.app.data.Host;
import com.onessh.app.data.KeyItem;
import com.onessh.app.data.Store;
import com.onessh.app.ui.Dialogs;
import com.onessh.app.ui.UI;
import com.onessh.app.ui.Widgets;

import java.util.ArrayList;
import java.util.List;

/** 添加 / 编辑主机。 */
public class HostEditActivity extends Activity {

    public static final String EXTRA_ID = "hostId";

    private Store store;
    private Host draft;
    private boolean creating;

    private Widgets.Field nameField;
    private Widgets.Field hostField;
    private Widgets.Field portField;
    private Widgets.Field userField;
    private LinearLayout groupHost;
    private LinearLayout jumpHost;
    private Widgets.Toggle moshToggle;
    private Widgets.Field noteField;
    private Widgets.Field passField;
    private Widgets.Field keyBodyField;
    private Widgets.Field keyPassField;

    private LinearLayout authBox;
    private LinearLayout authCard;
    private LinearLayout bodyBox;
    private String authType = Host.AUTH_PASSWORD;

    /** 切换标签页时暂存已填的内容，避免重建后丢失 */
    private String pendingPassword;
    private String pendingKeyBody;
    private String pendingKeyPass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);

        String id = getIntent().getStringExtra(EXTRA_ID);
        creating = id == null || id.isEmpty();
        draft = creating ? new Host() : store.host(id);
        if (draft == null) {
            draft = new Host();
            creating = true;
        }
        authType = draft.authType == null ? Host.AUTH_PASSWORD : draft.authType;
        // 跳板机被删了就当作没设置
        if (!draft.jumpId.isEmpty() && store.host(draft.jumpId) == null) draft.jumpId = "";

        LinearLayout root = Widgets.screen(this);

        TextView save = Widgets.textAction(this, getString(R.string.save), UI.ACCENT);
        save.setTypeface(UI.MEDIUM);
        save.setOnClickListener(v -> submit());

        root.addView(Widgets.bar(this,
                creating ? getString(R.string.host_new) : getString(R.string.host_edit),
                this::finish, save));

        ScrollView sc = Widgets.scroll(this);
        LinearLayout body = UI.column(this);
        bodyBox = body;
        body.setPadding(UI.dp(20), UI.dp(6), UI.dp(20), UI.dp(28));
        sc.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout basic = buildBasicCard();
        body.addView(basic);
        Widgets.bottom(basic, 12);

        authCard = buildAuthCard();
        body.addView(authCard);
        Widgets.bottom(authCard, 12);

        body.addView(buildNoteCard());

        if (!creating) {
            LinearLayout del = Widgets.button(this, getString(R.string.delete), null, false);
            ((TextView) del.getChildAt(0)).setTextColor(UI.RED);
            del.setBackground(UI.ripple(UI.bg(0xFFFFF7F6, 10f, 0xFFF0D5D1, 1f), 10f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = UI.dp(18);
            del.setLayoutParams(lp);
            del.setOnClickListener(v -> confirmDelete());
            body.addView(del);
        }

        setContentView(root);
    }

    /* --------------------------------------------------------- 基本信息 */

    private LinearLayout buildBasicCard() {
        LinearLayout card = Widgets.cardBox(this);
        card.addView(Widgets.cardTitle(this, "基本信息"));

        nameField = Widgets.field(this, getString(R.string.field_name),
                getString(R.string.field_name_hint), InputType.TYPE_CLASS_TEXT);
        nameField.setText(draft.name);
        card.addView(nameField.root);

        LinearLayout row = UI.row(this);
        row.setGravity(Gravity.BOTTOM);

        hostField = Widgets.field(this, getString(R.string.field_host),
                getString(R.string.field_host_hint), InputType.TYPE_CLASS_TEXT);
        hostField.input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostField.setText(draft.host);
        row.addView(hostField.root, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        portField = Widgets.field(this, getString(R.string.field_port), "22",
                InputType.TYPE_CLASS_NUMBER);
        portField.setText(String.valueOf(draft.port <= 0 ? 22 : draft.port));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(UI.dp(74),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.leftMargin = UI.dp(10);
        row.addView(portField.root, pp);
        Widgets.top(row, 12);
        card.addView(row);

        userField = Widgets.field(this, getString(R.string.field_user), "root",
                InputType.TYPE_CLASS_TEXT);
        userField.input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        userField.setText(draft.user);
        Widgets.top(userField.root, 12);
        card.addView(userField.root);

        // 分组：从已有分组里选，也可以顺手新建一个
        groupHost = UI.column(this);
        Widgets.top(groupHost, 12);
        card.addView(groupHost);
        renderGroupRow();

        // 跳板机：这台机器连不上时，先登到另一台再转发过去
        jumpHost = UI.column(this);
        Widgets.top(jumpHost, 12);
        card.addView(jumpHost);
        renderJumpRow();

        // Mosh：走 UDP 的可漫游终端，断网、换网不断线；服务器要装 mosh-server
        LinearLayout moshRow = UI.row(this);
        moshRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout moshCol = UI.column(this);
        moshCol.addView(UI.medium(this, "使用 Mosh", 13.5f, UI.INK));
        TextView moshSub = UI.text(this,
                "用 Mosh 而不是 SSH 连接：断网、换网不断线，需服务器装 mosh-server 并放行 UDP 端口",
                11f, UI.MUTED);
        moshSub.setLineSpacing(UI.dp(3), 1f);
        moshCol.addView(moshSub);
        moshRow.addView(moshCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        moshToggle = new Widgets.Toggle(this, draft.mosh);
        moshRow.addView(moshToggle);
        Widgets.top(moshRow, 12);
        card.addView(moshRow);

        return card;
    }

    /** 分组选择行：点开从已有分组里挑，或新建 */
    private void renderGroupRow() {
        groupHost.removeAllViews();
        String value = draft.group == null || draft.group.trim().isEmpty()
                ? getString(R.string.ungrouped) : draft.group.trim();
        groupHost.addView(Widgets.pickRow(this, "folder", "分组", value, this::pickGroup));
    }

    private void pickGroup() {
        final List<String> groups = store.groups();
        int n = groups.size();
        String[] labels = new String[n + 2];
        String[] subs = new String[n + 2];

        labels[0] = getString(R.string.ungrouped);
        subs[0] = "不归到任何分组";

        int selected = 0;
        for (int i = 0; i < n; i++) {
            String g = groups.get(i);
            int count = 0;
            for (Host h : store.hosts()) if (g.equals(h.group)) count++;
            labels[i + 1] = g;
            subs[i + 1] = count + " 台主机";
            if (g.equals(draft.group)) selected = i + 1;
        }

        labels[n + 1] = "＋ 新建分组";
        subs[n + 1] = "先建一个，再自动选中";

        Dialogs.choose(this, "选择分组", labels, subs, selected, idx -> {
            if (idx == 0) {
                draft.group = "";
                renderGroupRow();
            } else if (idx == n + 1) {
                Dialogs.prompt(this, "新建分组", "分组名称", "例如：生产环境", "", false, name -> {
                    String g = name.trim();
                    if (g.isEmpty()) return;
                    store.addGroup(g);
                    draft.group = g;
                    renderGroupRow();
                });
            } else {
                draft.group = groups.get(idx - 1);
                renderGroupRow();
            }
        });
    }

    /** 跳板机选择行 */
    private void renderJumpRow() {
        jumpHost.removeAllViews();

        Host jump = draft.jumpId == null || draft.jumpId.isEmpty()
                ? null : store.host(draft.jumpId);
        jumpHost.addView(Widgets.pickRow(this, "server", "跳板机",
                jump == null ? "不使用（直连）" : jump.label(), this::pickJump));

        if (jump != null) {
            TextView note = UI.text(this,
                    "连接时先登录 " + jump.label() + "，再从它转发到这台主机。",
                    11f, UI.MUTED);
            note.setLineSpacing(UI.dp(3), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = UI.dp(6);
            note.setLayoutParams(lp);
            jumpHost.addView(note);
        }
    }

    private void pickJump() {
        List<Host> all = store.hosts();
        List<Host> candidates = new ArrayList<>();
        for (Host h : all) {
            if (h.id.equals(draft.id)) continue;
            if (h.id.equals(draft.jumpId) && false) continue;
            candidates.add(h);
        }

        String[] labels = new String[candidates.size() + 1];
        String[] subs = new String[candidates.size() + 1];
        labels[0] = "不使用（直连）";
        subs[0] = "直接从手机连目标主机";

        int selected = 0;
        for (int i = 0; i < candidates.size(); i++) {
            Host h = candidates.get(i);
            labels[i + 1] = h.label();
            subs[i + 1] = h.subtitle();
            if (h.id.equals(draft.jumpId)) selected = i + 1;
        }

        Dialogs.choose(this, "选择跳板机", labels, subs, selected, idx -> {
            if (idx == 0) {
                draft.jumpId = "";
            } else {
                draft.jumpId = candidates.get(idx - 1).id;
            }
            renderJumpRow();
        });
    }

    /* ------------------------------------------------------------- 认证 */

    private LinearLayout buildAuthCard() {
        LinearLayout card = Widgets.cardBox(this);
        card.addView(Widgets.cardTitle(this, getString(R.string.field_auth)));

        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.auth_password));
        labels.add(getString(R.string.auth_key));
        card.addView(Widgets.tabs(this, labels, null,
                Host.AUTH_KEY.equals(authType) ? 1 : 0, idx -> {
                    authType = idx == 1 ? Host.AUTH_KEY : Host.AUTH_PASSWORD;
                    refreshAuthCard();
                }));

        authBox = UI.column(this);
        Widgets.top(authBox, 14);
        card.addView(authBox);
        renderAuth();
        return card;
    }

    /** 切换认证方式时整张卡片重建，这样标签页的选中态也跟着动 */
    private void refreshAuthCard() {
        if (passField != null) pendingPassword = passField.text();
        if (keyBodyField != null) pendingKeyBody = keyBodyField.text();
        if (keyPassField != null) pendingKeyPass = keyPassField.text();

        if (authCard == null || bodyBox == null) {
            renderAuth();
            return;
        }
        int idx = bodyBox.indexOfChild(authCard);
        bodyBox.removeView(authCard);
        authCard = buildAuthCard();
        if (idx >= 0) bodyBox.addView(authCard, idx);
        else bodyBox.addView(authCard);
        Widgets.bottom(authCard, 12);
    }

    private void renderAuth() {
        authBox.removeAllViews();
        passField = null;
        keyBodyField = null;
        keyPassField = null;

        if (Host.AUTH_PASSWORD.equals(authType)) {
            Credential cred = draft.credId.isEmpty() ? null : store.cred(draft.credId);
            if (cred == null) {
                passField = Widgets.field(this, "登录密码",
                        creating || draft.passwordEnc.isEmpty()
                                ? "登录密码"
                                : getString(R.string.field_password_keep),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                if (pendingPassword != null) passField.setText(pendingPassword);
                authBox.addView(passField.root);
            } else {
                authBox.addView(UI.text(this, "密码来自凭据库，不再单独保存。", 11.5f, UI.MUTED));
            }

            LinearLayout pick = Widgets.pickRow(this, "lock", getString(R.string.pick_cred),
                    cred == null ? null : cred.name, this::pickCredential);
            Widgets.top(pick, 12);
            authBox.addView(pick);
        } else {
            KeyItem key = draft.keyId.isEmpty() ? null : store.key(draft.keyId);
            LinearLayout pick = Widgets.pickRow(this, "key", getString(R.string.pick_key),
                    key == null ? null : key.name, this::pickKey);
            authBox.addView(pick);

            if (key == null) {
                keyBodyField = Widgets.field(this, getString(R.string.key_body),
                        draft.keyEnc.isEmpty()
                                ? getString(R.string.key_body_hint)
                                : "已保存私钥，留空则不修改",
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                keyBodyField.input.setTypeface(UI.MONO);
                keyBodyField.input.setTextSize(11.5f);
                if (pendingKeyBody != null) keyBodyField.setText(pendingKeyBody);
                Widgets.top(keyBodyField.root, 12);
                authBox.addView(keyBodyField.root);

                keyPassField = Widgets.field(this, getString(R.string.key_passphrase),
                        getString(R.string.key_passphrase_hint),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                if (pendingKeyPass != null) keyPassField.setText(pendingKeyPass);
                Widgets.top(keyPassField.root, 12);
                authBox.addView(keyPassField.root);
            }
        }
    }

    private void pickCredential() {
        List<Credential> creds = store.creds();
        String[] labels = new String[creds.size() + 1];
        String[] subs = new String[creds.size() + 1];
        labels[0] = "不使用凭据库";
        subs[0] = "直接在这台主机上填密码";
        int selected = 0;
        for (int i = 0; i < creds.size(); i++) {
            Credential c = creds.get(i);
            labels[i + 1] = c.name;
            subs[i + 1] = c.username;
            if (c.id.equals(draft.credId)) selected = i + 1;
        }
        Dialogs.choose(this, getString(R.string.pick_cred), labels, subs, selected, idx -> {
            if (idx == 0) {
                draft.credId = "";
            } else {
                Credential c = creds.get(idx - 1);
                draft.credId = c.id;
                if (userField.text().trim().isEmpty()) userField.setText(c.username);
            }
            renderAuth();
        });
    }

    private void pickKey() {
        List<KeyItem> keys = store.keys();
        String[] labels = new String[keys.size() + 1];
        String[] subs = new String[keys.size() + 1];
        labels[0] = "不使用私钥库";
        subs[0] = "直接粘贴私钥文本";
        int selected = 0;
        for (int i = 0; i < keys.size(); i++) {
            KeyItem k = keys.get(i);
            labels[i + 1] = k.name;
            subs[i + 1] = (k.algo == null || k.algo.isEmpty() ? "私钥" : k.algo)
                    + (k.fingerprint == null || k.fingerprint.isEmpty() ? "" : " · " + k.fingerprint);
            if (k.id.equals(draft.keyId)) selected = i + 1;
        }
        Dialogs.choose(this, getString(R.string.pick_key), labels, subs, selected, idx -> {
            draft.keyId = idx == 0 ? "" : keys.get(idx - 1).id;
            renderAuth();
        });
    }

    /* ------------------------------------------------------------- 备注 */

    private LinearLayout buildNoteCard() {
        LinearLayout card = Widgets.cardBox(this);
        card.addView(Widgets.cardTitle(this, getString(R.string.field_note)));
        noteField = Widgets.field(this, null, getString(R.string.field_note_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        noteField.setText(draft.note);
        noteField.input.setMinLines(2);
        card.addView(noteField.root);
        return card;
    }

    /* ------------------------------------------------------------- 保存 */

    private void submit() {
        String addr = hostField.text().trim();
        String user = userField.text().trim();
        String portText = portField.text().trim();

        if (addr.isEmpty()) {
            UI.toast(this, getString(R.string.err_host_required));
            return;
        }
        int port = 22;
        try {
            port = Integer.parseInt(portText.isEmpty() ? "22" : portText);
        } catch (Exception e) {
            UI.toast(this, getString(R.string.err_port));
            return;
        }
        if (port < 1 || port > 65535) {
            UI.toast(this, getString(R.string.err_port));
            return;
        }
        if (user.isEmpty() && draft.credId.isEmpty()) {
            UI.toast(this, getString(R.string.err_user_required));
            return;
        }

        String password = passField == null ? null : passField.text();
        String keyBody = keyBodyField == null ? null : keyBodyField.text().trim();
        String keyPass = keyPassField == null ? null : keyPassField.text();

        if (Host.AUTH_PASSWORD.equals(authType)) {
            boolean hasStored = !draft.passwordEnc.isEmpty() || !draft.credId.isEmpty();
            if ((password == null || password.isEmpty()) && !hasStored) {
                UI.toast(this, getString(R.string.err_password_required));
                return;
            }
        } else {
            boolean hasStored = !draft.keyEnc.isEmpty() || !draft.keyId.isEmpty();
            if ((keyBody == null || keyBody.isEmpty()) && !hasStored) {
                UI.toast(this, getString(R.string.err_key_required));
                return;
            }
        }

        draft.name = nameField.text().trim();
        draft.host = addr;
        draft.port = port;
        draft.user = user;
        draft.group = draft.group == null ? "" : draft.group.trim();
        draft.note = noteField.text().trim();
        draft.authType = authType;
        draft.mosh = moshToggle != null && moshToggle.isChecked();
        if (draft.mosh && draft.jumpId != null && !draft.jumpId.isEmpty()) {
            UI.toast(this, "Mosh 暂不支持跳板机：请先去掉跳板机，或关掉“使用 Mosh”");
            return;
        }

        if (Host.AUTH_PASSWORD.equals(authType)) {
            if (draft.credId.isEmpty() && password != null && !password.isEmpty()) {
                String enc = Crypto.encrypt(password);
                if (enc == null) {
                    UI.toast(this, "密码加密失败，未保存");
                    return;
                }
                draft.passwordEnc = enc;
            }
        } else {
            if (draft.keyId.isEmpty() && keyBody != null && !keyBody.isEmpty()) {
                String enc = Crypto.encrypt(keyBody);
                if (enc == null) {
                    UI.toast(this, "私钥加密失败，未保存");
                    return;
                }
                draft.keyEnc = enc;
            }
            if (draft.keyId.isEmpty() && keyPass != null && !keyPass.isEmpty()) {
                String enc = Crypto.encrypt(keyPass);
                if (enc == null) {
                    UI.toast(this, "口令加密失败，未保存");
                    return;
                }
                draft.passphraseEnc = enc;
            }
        }

        if (!draft.group.isEmpty()) store.addGroup(draft.group);
        store.saveHost(draft);
        UI.toast(this, "已保存");
        finish();
    }

    private void confirmDelete() {
        Dialogs.confirm(this, getString(R.string.delete_host_title),
                getString(R.string.delete_host_msg, draft.label()),
                getString(R.string.delete), true, () -> {
                    store.deleteHost(draft.id);
                    finish();
                });
    }
}
