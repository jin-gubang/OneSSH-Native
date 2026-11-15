<div align="center">

# 🛰️ OneSSH

**纯原生 Android SSH 客户端**

自绘终端 · 单跳 ProxyJump · SFTP · 零云端依赖

![版本](https://img.shields.io/badge/version-2.0.x%20auto--bump-2563EB?style=flat-square)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-21-ED8B00?style=flat-square)
![Gradle](https://img.shields.io/badge/Gradle-9.3.1-02303A?style=flat-square&logo=gradle&logoColor=white)
![依赖](https://img.shields.io/badge/deps-JSch%20only-64748B?style=flat-square)

> 纯 Java + 原生 View 手写界面：没有 Compose、没有 RN、没有 WebView。数据只留本机，不联网上报。

</div>

---

## ✨ 能力

| 🔐 连接 | 🖥️ 终端 | 📁 文件 |
|:---|:---|:---|
| 密码 / 私钥 / `keyboard-interactive`<br>分组折叠、搜索、临时直连 | 自绘 ANSI 终端、滚动缓冲<br>字号调节、复制全部、清屏 | SFTP 目录浏览、上传、下载<br>新建 / 重命名 / 删除 |
| 单跳 ProxyJump，终端 / SFTP / 编辑同链路<br>主机指纹确认与变更强警告 | 常用按键条、命令片段库<br>终端页保持常亮 | 内置编辑器打开远程文件<br>修改后直接保存回服务器 |

> 🔒 **安全**：凭据经 Android Keystore 的 AES-256-GCM 加密落盘 · `allowBackup=false` · 一键清空全部数据 · 卸载即清除
>
> 🧩 **工程**：minSdk 26 · targetSdk 34 · compileSdk 36 · 仅 `INTERNET` / `ACCESS_NETWORK_STATE` / `VIBRATE` 三项普通权限 · 无账号、无云同步

## 📱 界面

<p align="center">
  <img src="screenshots/home.png" width="30%" alt="主机列表" />
  <img src="screenshots/terminal.png" width="30%" alt="终端" />
  <img src="screenshots/files.png" width="30%" alt="文件管理" />
</p>

<details>
<summary>查看其余 8 张截图</summary>

| 跳板机 | 远程编辑 | 凭据库 |
|:---:|:---:|:---:|
| ![跳板机](screenshots/jump-host.png) | ![文件编辑](screenshots/file-edit.png) | ![凭据库](screenshots/vault.png) |

| 分组 | 命令片段 | 设置 | 编辑器 | 分组选择 |
|:---:|:---:|:---:|:---:|:---:|
| ![分组](screenshots/groups.png) | ![片段](screenshots/snippets.png) | ![设置](screenshots/settings.png) | ![编辑器](screenshots/editor.png) | ![分组选择](screenshots/group-pick.png) |

</details>

## 🔀 跳板机链路

> 📱 手机 ──SSH──▶ 🛰️ 跳板机 ──隧道──▶ 🎯 目标主机　·　单跳

| 顺序 | 通道 | 何时使用 |
|:---:|:---|:---|
| 1️⃣ | `direct-tcpip` | 默认，标准 ProxyJump，先预检再复用，性能最好 |
| 2️⃣ | `nc` / `ncat` / `socat` 命令隧道 | sshd 设了 `AllowTcpForwarding no` 时自动降级，用 `exec` 通道的 stdin/stdout 承载 TCP |
| 3️⃣ | ❌ 明确报错 | 两条都不通，并区分「跳板机缺管道工具」与「连不到目标」 |

> 限制：只支持单跳；命令隧道要求跳板机账号能执行 shell，`ForceCommand` / `rbash` 环境只能打开 `AllowTcpForwarding`。

## 🚀 构建

### GitHub Actions · 零配置

推到 GitHub 即自动编译，**每次 push 都会打一个新版本号并发布 Release**（PR 只编译）。

| 触发 | 编译 | 版本号 | Artifact | GitHub Release |
|:---|:---:|:---:|:---:|:---|
| `push` 到 `main` / `master` | ✅ | 自动 +1 | ✅ | ✅ 新建 `v<新版本>` |
| 手动 `Run workflow` | ✅ | 自动 +1 | ✅ | ✅ 新建 `v<新版本>` |
| 推送 `v*` 标签 | ✅ | 用标签版本，不递增 | ✅ | ✅ 以标签名发布 |
| Pull Request | ✅ | 不递增 | ✅ | — |

产物：`OneSSH-2.0.1-debug.apk`（可直接安装）、`OneSSH-2.0.1-release-unsigned.apk`（需签名）。在 **Actions → 对应 run → Artifacts** 下载，保留 30 天。

> 🔑 CI 使用仓库内固定的 debug 密钥签名，**每次构建的 debug 包签名一致，可直接覆盖安装**。若手机提示「软件包冲突 / 开发者签名异常」，说明旧版是另一把密钥签的：先卸载旧版再安装（会清空应用数据），之后升级就不会再冲突。

### 🔢 版本号：每次构建自动 +1

版本号只有一个来源 —— 仓库根目录的 `version.properties`：

```properties
major=2
minor=0
patch=0        # 每次构建 +1
build=0        # 构建计数，同样每次 +1
autoBump=true  # 改成 false 可全局关掉自动递增
```

- **构建时自动递增**：`assemble*` / `bundle*` 一执行，`patch` 与 `build` 各 +1 并立刻写回文件，所以一次编译必得一个新版本。
- **versionName 与 versionCode 都是推算出来的**：`versionName = major.minor.patch`，`versionCode = major*1000000 + minor*10000 + patch`，天然单调递增，装新包可直接覆盖旧包。`build.gradle.kts` 里不再出现任何写死的版本数字。
- **CI 会把新版本号推回仓库**：构建完自动 `commit + push`（提交信息带 `[skip ci]`，且用 `GITHUB_TOKEN` 推送不会再触发新运行，不存在死循环），所以仓库里的版本号永远是「最新已构建」的那个；随后用新版本号创建 tag 与 Release。
- **想锁住版本号**（连着调几次）：

  ```bash
  ./gradlew assembleDebug -PnoBump        # 或 export ONESSH_NO_BUMP=1
  ./gradlew versionInfo                   # 只看版本号，不构建、不递增
  ```

- **要改版本**：直接改 `major` / `minor` / `patch`，下一次构建从新值继续递增（例如发 2.1.0 就把 `minor=1`、`patch=0`）。

> ⚠️ 递增发生在 Gradle **配置阶段**，因此开了 configuration cache 的环境请关掉（本仓库默认未开启）。分支保护若禁止 bot 推送，回推会失败并给出 warning，APK 仍照常产出，只是仓库里的版本号落后一次。

### 本地

```bash
./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk            约 1.3 MB
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release-unsigned.apk  约 1.0 MB
```

JDK 17+（实测 21）· Android SDK 36 + build-tools `36.0.0` · Gradle 9.3.1 / AGP 9.1.0 由 wrapper 固定，无需预装。首次构建需联网，之后可加 `--offline`。

<details>
<summary><b>🔧 进阶 · 首次上传 / 签名 Release / 目录结构 / CI 备注</b></summary>

**首次上传**

```bash
git init && git add . && git update-index --chmod=+x gradlew
git commit -m "Initial commit: OneSSH 2.0.0"
git remote add origin git@github.com:<OWNER>/<REPO>.git && git push -u origin main
```

> 用 `git add .`，不要用 `git add *` —— 后者会漏掉 `.gitignore` / `.gitattributes` / `.github/`。`local.properties` 由 Android Studio 自动生成，**不要提交**。Fork 后同样零配置，只需在 Actions 页面启用 workflows。

**签名 Release**

仓库不含 keystore（`*.jks` / `keystore/` 已被 `.gitignore` 排除）：

```bash
keytool -genkeypair -v -keystore keystore/onessh-release.jks -alias onessh-release -keyalg RSA -keysize 2048 -validity 10000
$ANDROID_HOME/build-tools/36.0.0/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/aligned.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner sign --ks keystore/onessh-release.jks --out OneSSH-2.0.0.apk /tmp/aligned.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify OneSSH-2.0.0.apk
```

想让 CI 直接出签名包：把 keystore 以 base64 存进仓库 Secrets，再在 `app/build.gradle.kts` 用 `System.getenv()` 配 `signingConfigs`。自用直接装 debug 包即可。

**目录结构**

```
app/src/main/java/com/onessh/app/
├── *.Activity   界面：主页 / 主机表单 / 终端 / 文件 / 编辑 / 分组 / 片段 / 凭据 / 设置
├── data/        Store（JSON 落盘）、Crypto（Keystore 加密）
├── ssh/         SshSession（连接、跳板、SFTP）、KnownHosts、HostKeyPrompt
├── term/        TerminalView、TerminalBuffer、AnsiParser、TermColors
└── ui/          纯代码绘制的控件与图标
app/libs/jsch-2.28.8.jar   唯一第三方依赖
version.properties         版本号唯一来源（构建时自动 +1）
.github/workflows/build.yml  CI 流水线
```

**CI 备注**

- `ubuntu-latest` + Temurin JDK 21，自动安装 `platforms;android-36` / `build-tools;36.0.0` / `platform-tools`，Gradle 带缓存。
- ⚠️ 不要加回 `android-actions/setup-android@v3`：它默认安装已下架的 `tools` 包，会报 `Failed to find package 'tools'` 并退出 1。流水线直接定位 runner 预装的 `sdkmanager`。
- 🔑 `app/debug.keystore.base64` 是 CI 固定 debug 签名，证书 SHA-256 `7D:79:52:39:C2:B3:9B:F3:56:C7:0B:C4:D5:13:A7:9F:7F:E8:1E:BE:D8:51:DD:5E:81:85:93:7E:F8:D0:95:0D`（**仅调试用，切勿用于正式发布**）。
- ✅ 实测：JDK 21.0.9 + SDK 36，`assembleDebug assembleRelease` 一次通过（73 tasks，约 40 秒）。
- 🔢 版本号回推需要 `contents: write`（build job 已单独声明）。若分支开了保护规则，请把 `github-actions[bot]` 加进允许推送名单，否则回推会 warning 而不是失败。
- 只有 PR 里 `发布 Release` 会显示 skipped，属正常。

</details>

---

## 📄 依赖与许可

[JSch 2.28.8](http://www.jcraft.com/jsch/) · SSH2 实现，jar 随仓库分发，遵循其原有 BSD 风格许可。

> ⚠️ 本项目**尚未指定许可证** —— 公开仓库前请选一个（MIT / Apache-2.0 / GPL-3.0 等）并添加 `LICENSE`，同时建议在 `app/libs/` 附一份 JSch 原始许可文本。

<div align="center">

**OneSSH 2.0.0** · 数据只在本机 · 无账号 · 无云同步 · 暂无单元测试

</div>
