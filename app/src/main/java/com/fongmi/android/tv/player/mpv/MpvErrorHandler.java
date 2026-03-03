package com.fongmi.android.tv.player.mpv;

/**
 * mpv 错误处理工具类
 * <p>
 * 将 mpv 的错误事件转换为可读的错误消息
 */
public class MpvErrorHandler {

    /**
     * 将 mpv 错误字符串转换为用户可读的消息
     */
    public static String getErrorMessage(String error) {
        if (error == null || error.isEmpty()) return "Unknown error";
        // mpv 错误通常是描述性文本，直接使用
        if (error.contains("403")) return "HTTP 403 Forbidden";
        if (error.contains("404")) return "HTTP 404 Not Found";
        if (error.contains("timeout") || error.contains("timed out")) return "Connection Timeout";
        if (error.contains("network") || error.contains("connection")) return "Network Error";
        if (error.contains("codec") || error.contains("decoder")) return "Decoder Error";
        if (error.contains("format") || error.contains("demuxer")) return "Format Unsupported";
        if (error.contains("permission")) return "Permission Denied";
        return error;
    }

    /**
     * 判断错误是否为可重试的错误
     */
    public static boolean isRetryable(String error) {
        if (error == null) return false;
        return error.contains("timeout") || error.contains("timed out") || error.contains("network") || error.contains("connection");
    }
}
