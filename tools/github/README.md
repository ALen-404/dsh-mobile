# GitHub Actions 工作流（需要一步启用）

| 文件 | 触发 | 做什么 |
|---|---|---|
| `ci.yml` | push 到 main / PR / 手动 | 跑协议自检 + 静态检查，**并打出 APK 作为 artifact**（每次提交都能下载安装包） |
| `release.yml` | 推 `v*` tag / 手动 | 用 tag 的版本号构建，自动创建 GitHub Release 并附上 APK |

## 为什么没直接放在 `.github/workflows/`

推送到 `.github/workflows/` 需要 token 具备 **`workflow`** scope，否则 push 会被拒绝：

```
refusing to allow an OAuth App to create or update workflow
` .github/workflows/... ` without `workflow` scope
```

所以这几个文件先放在这里。启用只需要一步：

```powershell
# 1) 给 gh 加 workflow 权限（会打开浏览器点一下授权）
gh auth refresh -s workflow

# 2) 安装并推送
powershell -ExecutionPolicy Bypass -File tools/github/install-workflows.ps1
```

`install-workflows.ps1` 会把本目录的 yml 复制到 `.github/workflows/`、提交并推送。
如果不想动 gh，也可以在 GitHub 网页上新建 `.github/workflows/ci.yml` 粘贴内容 —— 效果一样。

## 版本号怎么来

- `ci.yml`：`versionCode = 运行序号`，`versionName` 用 `app/AndroidManifest.xml` 里的值
- `release.yml`：`versionName = tag 去掉 v`（如 tag `v0.2.0` → `0.2.0`），`versionCode = 运行序号`

`build.ps1` 通过 `-VersionName` / `-VersionCode` 接收，并把它**写进一份临时的 AndroidManifest 副本**
（`aapt2 link` 没有 `--version-code` 这种参数，那是老 aapt 的选项）。

## CI 里怎么找到 JDK 和 Android SDK

`build.ps1` 不再只认仓库内的 `toolchain/`，解析顺序是：

- JDK：`-JdkPath` → `toolchain/jdk` → `$env:JAVA_HOME` → PATH 上的 `javac`
- SDK：`-SdkPath` → `toolchain/sdk` → `$env:ANDROID_SDK_ROOT` → `$env:ANDROID_HOME`

并且会自动挑 `build-tools/` 与 `platforms/` 下**版本最高**的目录，所以 CI 镜像里的
SDK 版本和仓库里钉的 34.0.0 不一致也能构建。

## 发生在 CI 里的签名

用的是仓库里没有的 `toolchain/debug.keystore` —— `build.ps1` 发现它不存在时会用 `keytool`
现场生成一个。所以 CI 产出的 APK 每次运行签名不同，**只适合自测分发，不能上架**。
需要稳定签名（可覆盖升级）时，把 keystore 放进 GitHub Secrets 并让 `build.ps1` 从环境变量读取 ——
这一条尚未实现，属于后续工作。
