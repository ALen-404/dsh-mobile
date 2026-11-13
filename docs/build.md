# 构建说明

本项目**不用 Gradle**：只用 Android SDK 自带的 `aapt2` / `d8` / `zipalign` / `apksigner`，
零 Maven 依赖、可完全离线复现。代价是构建脚本要自己写，好处是构建过程透明、没有几百 MB 的依赖树。

## 一、准备工具链（一次）

```powershell
powershell -ExecutionPolicy Bypass -File toolchain/fetch-toolchain.ps1
```

会下载并铺好三样东西（约 400 MB）：

| 组件 | 来源 | 用途 |
|---|---|---|
| JDK 17 | Adoptium API（latest GA） | javac / jar / keytool |
| build-tools 34.0.0 | `dl.google.com/.../build-tools_r34-windows.zip` | aapt2 / d8 / zipalign / apksigner |
| platform 34 | `dl.google.com/.../platform-34-ext12_r01.zip` | `android.jar`（编译期 API） |

> **为什么不用 sdkmanager？** 受限网络下 `sdkmanager` 经常拿不到仓库索引
> （`Failed to download any source lists`），且它不认系统代理。curl 则稳定可用，
> 所以脚本直接下官方 zip 手工铺目录。

## 二、构建

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
# 产物：dist/dsh-mobile-debug.apk
```

构建流水线（7 步，全部可见于 `build.ps1`）：

```
aapt2 compile  →  aapt2 link  →  javac  →  jar  →  d8  →  打包 dex  →  zipalign  →  apksigner
```

第一次构建会自动用 `keytool` 生成 `toolchain/debug.keystore`（口令 `android`，仅调试用）。

## 三、踩过的坑（都已在脚本里处理，列出来免得你重踩）

### 1. 路径含中文 → aapt2 直接失败

`aapt2` 打不开含非 ASCII 字符的目录（本项目工作区路径里有「小宝」），报
`error: failed to open directory: 系统找不到指定的文件`。

**解法**：克隆/放置到纯 ASCII 路径；或建一个 junction 给它一个 ASCII 入口：

```powershell
New-Item -ItemType Junction -Path <盘符>:\dshm -Target 'E:\你的含中文路径\dsh-mobile'
powershell -ExecutionPolicy Bypass -File <盘符>:\dshm\build.ps1
```

### 2. `.ps1` 存成 UTF-8 无 BOM → 中文注释被当成 ANSI，解析报一堆语法错

Windows PowerShell 5.1 读无 BOM 脚本时按 ANSI 解码。含中文的脚本**必须存为 UTF-8 **带 BOM****。
（`toolchain/fetch-toolchain.ps1` 是纯 ASCII 的，所以不受影响。）

### 3. javac 默认 GBK → 源码里的中文注释报“非法字符”

`build.ps1` 里已固定 `-encoding UTF-8`。

### 4. d8 不接受目录，只接受文件/jar

所以流程里先用 `jar cf` 把 classes 打成 jar，再喂给 d8。

### 5. `$ErrorActionPreference='Stop'` + 原生工具写 stderr = 误判失败

`keytool` / `javac` 会往 stderr 写「注: …」这类正常信息，`Stop` 会把它当致命错误。
脚本改成 `Continue`，真正的失败靠 `$LASTEXITCODE` 显式判断。

### 6. 执行策略禁止运行脚本

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

### 7. 构建明明成功，进程却返回 exit code 1

症状：屏幕打出 `== build ok ==`，但调用方（CI、脚本）拿到 `1`。

原因有两层：
- `javac` 即使加了 `-nowarn` 也会往 **stderr** 写「注: …使用了已过时的 API」这类**编译注记**；
- PowerShell 把原生命令的 stderr 变成 error record，进程退出码就可能被带成非 0。

脚本里的处理：
1. `javac` 的 stderr 重定向到 `build/javac.err`，成功后只打印一句「N javac notes, ignored」，
   失败时再把内容打出来（真正的诊断信息不丢）；
2. 构建末尾显式 `exit 0`，让"成功"和"退出码"对齐；
3. `-Xlint:none` 顺带压掉注记。

> 这条对 CI 很重要：否则每次构建都会被判成失败，久而久之就没人看构建结果了。

## 四、安装到手机

```bash
adb install -r dist/dsh-mobile-debug.apk
```

没有 adb 就手动拷到手机安装（小米/ColorOS 等需允许「安装未知来源应用」）。
debug 签名意味着每次重装都可用于自测，但不能上架应用商店。

## 五、为什么不用 Gradle / Compose（未来可以换）

v0.1 的目标是**先跑起来**：手写流水线可以完全避开依赖解析和 AGP 版本地狱，
在没有 Android Studio 的机器上也能构建。等项目稳定、需要 Compose 时再迁移到 Gradle，
届时 `app/src` 与 `app/res` 可以原样复用。
