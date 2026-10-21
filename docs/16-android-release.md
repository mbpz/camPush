# 16 · Android APK 构建、版本与 GitHub Actions 发布

| 项目 | 值 |
|---|---|
| 文档状态 | 生效（CI 已接入） |
| 版本 | v1.0 |
| 适用阶段 | P1 起（后续 P2/P3 复用） |
| 入口 | `.github/workflows/android.yml` |

---

## 1. 版本规则（APK 版本维护）

| 字段 | 来源 | 规则 |
|---|---|---|
| `versionCode` | `git rev-list --count HEAD`（CI 注入） | 每次提交单调递增；手动构建默认 1 |
| `versionName` | tag 构建：git tag（`v1.2.3` → `1.2.3`）；main/PR：`0.0.0-dev-<短SHA>`；manual 可覆盖 | tag 去掉前缀 `v` |
| `BuildConfig.GIT_SHA` | CI 注入前 8 位提交 | 设置页「关于 → 构建」可查 |
| APK 文件名 | `campush-<versionName>-<versionCode>.apk` | Artifact/Release 中下载 |

Gradle 通过 `-PappVersionCode` / `-PappVersionName` / `-PgitSha` 注入，定义在 `app/build.gradle.kts`。
**不要在源码里手改 versionCode**；发版用 tag 或手动 workflow 输入。

## 2. 触发方式

| 事件 | 行为 |
|---|---|
| push `main` | 跑 `:lan-core:test` + `:app:assembleDebug`，上传 Artifact（保留 30 天） |
| pull_request | 同上，用于 PR 校验（不发布 Release） |
| push tag `v*` | 构建 + 上传 + **创建 GitHub Release**（tag 含 `-` 自动标记 prerelease） |
| manual `workflow_dispatch` | 可输入 `version_name` 覆盖；其余同上（无 tag 不发布 Release） |

## 3. 下载 APK

- **最新 P1 预发布**：<https://github.com/mbpz/camPush/releases/tag/v1.0.0-p1>（APK + SHA256SUMS）
- **每次提交**：GitHub → Actions → 对应 run → Artifacts → `campush-apk-<versionName>-<versionCode>`；
- **正式/预发布**：GitHub → Releases → 下载 `.apk` 与 `SHA256SUMS.txt`；
- 同一 run 会同时上传 `SHA256SUMS.txt` 便于校验。

## 4. 发版流程（推荐）

```bash
# 1) 确认 main 为待发版提交，构建/测试绿色
git checkout main && git pull --ff-only

# 2) 打 tag（P1 示例）
git tag -a v1.0.0-p1 -m "CamPush P1 (LAN) 条件 G1"
git push origin v1.0.0-p1

# 3) GitHub Actions 自动构建并创建 Release
# 4) 在 Release 页下载 APK，或用 Actions Artifacts
```

## 5. 本地构建（与 CI 等价）

```bash
# 本机（需要 workspace SDK/JDK）
scripts/build.sh :lan-core:test :app:assembleDebug \
  -PappVersionCode=$(git rev-list --count HEAD) \
  -PappVersionName=$(git describe --tags --always --dirty | sed 's/^v//') \
  -PgitSha=$(git rev-parse --short=8 HEAD)
```

## 6. 签名

- 当前 CI 构建的是 **debug 签名**（`app/debug.keystore` 随仓库；仅内测/调试）；
- 正式对外发布前需加入 release keystore + GitHub Secrets（`RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`），并在 workflow 中配置 `assembleRelease`；
- 未配置前，Release 资产明确标注“debug/内测”。

## 7. 故障排查

| 现象 | 处理 |
|---|---|
| Gradle 下载失败 | workflow 使用 `gradle/actions/setup-gradle` 安装 8.7，不走 wrapper 的腾讯镜像；依赖仓库为 aliyun/google/mavenCentral |
| Android SDK 找不到 | workflow 自动写 `local.properties`；如镜像缺 API 34，`sdkmanager` 安装 `platforms;android-34`、`build-tools;34.0.0` |
| 版本号不递增 | CI 用提交数；确保 `fetch-depth: 0`（已配置），否则 `git rev-list --count` 偏小 |
| Release 未创建 | 检查是否 push 了 `v*` tag、`permissions: contents: write` 是否被仓库策略禁用 |
