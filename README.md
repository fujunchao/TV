# 影視 TV-MPV

基于 [FongMi/TV](https://github.com/FongMi/TV) 的修改版，集成 mpv 播放器作为第三方播放内核。

## 主要改动

### mpv 播放器集成

在原有 ExoPlayer 基础上新增 mpv 播放器选项，用户可在设置中切换播放内核。

- 基于 [aniyomi-mpv-lib](https://github.com/aniyomiorg/aniyomi-mpv-lib) 集成 libmpv
- 硬解码：`mediacodec-copy`，渲染：`gpu` + `android` context
- 支持 mpv 音轨/字幕轨选择
- 内置 `subfont.ttf` 字幕字体

### 竖屏播放优化

解决 mpv 在竖屏模式下的多个显示问题：

- **消除播放闪烁**：TextureView 初始 alpha=0，首帧渲染后才显示
- **跳过尺寸动画**：mpv 模式下 `changeHeight()` 直接设最终高度，避免 300ms 过渡动画导致的画面闪烁
- **进度恢复优化**：有播放历史的视频 seek 到上次位置时，不显示开头帧
- **黑屏保护**：500ms 超时兜底，防止短距离 seek 时 mpv 不触发事件导致画面不显示

### 全屏切换（手机端）

手机端新增全屏/退出全屏按钮。

### 其他

- QuickJS 引擎增加缓存和解析工具类
- 更新依赖版本

## 构建

```bash
# 构建变体：{mode}-{abi}
# mode: leanback(TV) / mobile(手机)
# abi: arm64_v8a / armeabi_v7a

# TV版 ARM64
./gradlew assembleLeanbackArm64_v8aRelease

# 手机版 ARM64
./gradlew assembleMobileArm64_v8aRelease

# 全部变体
./gradlew assembleLeanbackArm64_v8aRelease assembleLeanbackArmeabi_v7aRelease assembleMobileArm64_v8aRelease assembleMobileArmeabi_v7aRelease
```

输出 APK 位于 `app/build/outputs/apk/{mode}/{abi}/release/{mode}-{abi}.apk`

### GitHub Actions 自动构建

推送到 `TV-MPV` 分支会自动触发构建。创建 `v*` 格式的 tag 会自动生成 Release 并附带全部 4 个变体的 APK。

需要在仓库 Settings → Secrets 中配置：

| Secret 名称 | 说明 |
|---|---|
| `KEYSTORE_BASE64` | 签名文件的 base64 编码 |
| `KEYSTORE_PASSWORD` | 签名库密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

## 上游项目

- [FongMi/TV](https://github.com/FongMi/TV) — 原始项目
- [CatVodTVOfficial/CatVodTVJarLoader](https://github.com/CatVodTVOfficial/CatVodTVJarLoader) — CatVod 爬虫框架
- [aniyomiorg/aniyomi-mpv-lib](https://github.com/aniyomiorg/aniyomi-mpv-lib) — mpv Android 库
