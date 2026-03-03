package com.fongmi.android.tv.player.mpv;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import is.xyz.mpv.MPV;
/**
 * mpv 播放器初始化与配置工具类
 */
public class MpvUtil {

    private static boolean initialized;
    private static MPV mpv;

    /**
     * 获取 MPV 实例（供其他类调用实例方法）
     */
    public static MPV get() {
        return mpv;
    }

    /**
     * 初始化 mpv 本地库，写入推荐配置文件
     */
    public static void init(Context context) {
        if (initialized) return;
        copyFonts(context);
        writeConfig(context);
        mpv = new MPV();
        mpv.create(context);
        mpv.setOptionString("config", "yes");
        mpv.setOptionString("config-dir", getConfigDir(context));
        setOptions(context);
        mpv.init();
        setPostInitOptions();
        initialized = true;
    }

    /**
     * 获取 mpv 配置目录
     */
    public static String getConfigDir(Context context) {
        File dir = new File(context.getFilesDir(), "mpv");
        if (!dir.exists()) dir.mkdirs();
        return dir.getAbsolutePath();
    }

    /**
     * 将 assets 中的字体文件复制到 mpv 配置目录下的 fonts/ 子目录
     * libass 需要字体文件来渲染字幕，Android 上没有系统 fontconfig
     */
    private static void copyFonts(Context context) {
        String configPath = getConfigDir(context);
        File fontsDir = new File(configPath, "fonts");
        if (!fontsDir.exists()) fontsDir.mkdirs();
        // 复制 subfont.ttf 到 fonts/ 目录（sub-fonts-dir 指向此处）
        copyAssetIfNeeded(context, "subfont.ttf", new File(fontsDir, "subfont.ttf"));
        // 同时复制到 config-dir 根目录（mpv 自动回退查找 config-dir/subfont.ttf）
        copyAssetIfNeeded(context, "subfont.ttf", new File(configPath, "subfont.ttf"));
    }

    private static void copyAssetIfNeeded(Context context, String assetName, File destFile) {
        if (destFile.exists()) return;
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    /**
     * 写入 mpv.conf 配置文件
     */
    private static void writeConfig(Context context) {
        File configDir = new File(getConfigDir(context));
        File configFile = new File(configDir, "mpv.conf");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(configFile), "UTF-8")) {
            writer.write("# mpv 推荐配置 - FongMi TV\n");
            writer.write("vo=gpu\n");
            writer.write("gpu-context=android\n");
            writer.write("hwdec=mediacodec-copy\n");
            writer.write("tone-mapping=bt.2446a\n");
            writer.write("hdr-compute-peak=yes\n");
            writer.write("target-colorspace-hint=yes\n");
            writer.write("ao=audiotrack\n");
            writer.write("demuxer-max-bytes=67108864\n");
            writer.write("demuxer-max-back-bytes=33554432\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 通过 API 设置运行时选项
     */
    private static void setOptions(Context context) {
        mpv.setOptionString("keep-open", "always");
        mpv.setOptionString("save-position-on-quit", "no");
        // force-window 在 surfaceCreated 中动态设为 yes，初始设为 no（对齐官方 BaseMPVView.kt）
        mpv.setOptionString("force-window", "no");
        mpv.setOptionString("idle", "yes");
        mpv.setOptionString("sub-auto", "fuzzy");
        mpv.setOptionString("sub-font-size", "40");
        mpv.setOptionString("network-timeout", "15");
        mpv.setOptionString("demuxer-lavf-propagate-opts", "yes");
        // 字体配置：禁用系统字体提供程序（Android 上无 fontconfig），改用手动提供的字体
        mpv.setOptionString("sub-font-provider", "none");
        String fontsDir = new File(getConfigDir(context), "fonts").getAbsolutePath();
        mpv.setOptionString("sub-fonts-dir", fontsDir);
        mpv.setOptionString("osd-fonts-dir", fontsDir);
        // mpv 日志级别：生产环境只记录警告和错误
        mpv.setOptionString("msg-level", "all=warn");
    }

    /**
     * init() 后用 setPropertyString 确保关键选项生效
     */
    private static void setPostInitOptions() {
        // 预留用于 init() 后需要通过 setPropertyString 设置的选项
    }

    /**
     * 设置 mpv 画面比例
     * @param scale 0=Default, 1=16:9, 2=4:3, 3=Fill, 4=Zoom
     */
    public static void setScale(int scale) {
        if (mpv == null) return;
        try {
            switch (scale) {
                case 1: // 16:9
                    mpv.setPropertyString("video-aspect-override", "16:9");
                    mpv.setPropertyString("panscan", "0.0");
                    break;
                case 2: // 4:3
                    mpv.setPropertyString("video-aspect-override", "4:3");
                    mpv.setPropertyString("panscan", "0.0");
                    break;
                case 3: // Fill
                case 4: // Zoom
                    mpv.setPropertyString("video-aspect-override", "no");
                    mpv.setPropertyString("video-aspect-mode", "container");
                    mpv.setPropertyString("panscan", "1.0");
                    break;
                default: // 0 = Default
                    mpv.setPropertyString("video-aspect-override", "no");
                    mpv.setPropertyString("video-aspect-mode", "container");
                    mpv.setPropertyString("panscan", "0.0");
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 释放 mpv 本地库
     */
    public static void destroy() {
        if (!initialized) return;
        try {
            if (logObserver != null && mpv != null) mpv.removeLogObserver(logObserver);
            if (mpv != null) mpv.destroy();
        } catch (Exception e) {
            e.printStackTrace();
        }
        logObserver = null;
        mpv = null;
        initialized = false;
    }

    /**
     * mpv 是否已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * 重置初始化状态（用于重新初始化）
     */
    public static void resetState() {
        initialized = false;
    }
    // ========== mpv 日志收集 ==========

    private static MPV.LogObserver logObserver;
    private static OutputStreamWriter logWriter;
    private static final String LOG_TAG = "mpv";

    /**
     * 启用 mpv 日志收集：同时输出到 logcat 和文件
     * 日志文件位置：/data/data/com.fongmi.android.tv/files/mpv/mpv.log
     * adb 抓取：adb logcat -s mpv:V
     * 文件提取：adb pull /data/data/com.fongmi.android.tv/files/mpv/mpv.log
     */
    public static void enableLogObserver(Context context) {
        if (mpv == null || logObserver != null) return;
        try {
            File logFile = new File(getConfigDir(context), "mpv.log");
            logWriter = new OutputStreamWriter(new FileOutputStream(logFile, false), "UTF-8");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            logWriter.write("=== mpv 日志开始 " + sdf.format(new Date()) + " ===\n");
            logWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
            logWriter = null;
        }
        logObserver = (prefix, level, text) -> {
            // 输出到 logcat
            String msg = "[" + prefix + "] " + text;
            if (level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR) {
                Log.e(LOG_TAG, msg);
            } else if (level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN) {
                Log.w(LOG_TAG, msg);
            } else if (level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_INFO) {
                Log.i(LOG_TAG, msg);
            } else {
                Log.d(LOG_TAG, msg);
            }
            // 写入文件
            if (logWriter != null) {
                try {
                    logWriter.write(levelStr(level) + " [" + prefix + "] " + text + "\n");
                    logWriter.flush();
                } catch (IOException ignored) {}
            }
        };
        mpv.addLogObserver(logObserver);
    }

    private static String levelStr(int level) {
        if (level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_FATAL) return "F";
        if (level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR) return "E";
        if (level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN) return "W";
        if (level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_INFO) return "I";
        if (level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_V) return "V";
        if (level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_DEBUG) return "D";
        return "T";
    }

    /**
     * 获取日志文件路径（供 UI 展示或分享）
     */
    public static String getLogFilePath(Context context) {
        return new File(getConfigDir(context), "mpv.log").getAbsolutePath();
    }

    /**
     * 写入 Java 层日志到 mpv 日志文件（同时输出到 logcat）
     * 用于记录 mpv LogObserver 无法捕获的 Java 层事件（如 sub-add 调用）
     */
    public static void log(String message) {
        Log.w(LOG_TAG, message);
        if (logWriter != null) {
            try {
                logWriter.write("W [java] " + message + "\n");
                logWriter.flush();
            } catch (IOException ignored) {}
        }
    }
}
