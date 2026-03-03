package com.fongmi.android.tv.player.mpv;

import androidx.media3.common.C;

import com.fongmi.android.tv.bean.Track;

import java.util.ArrayList;
import java.util.List;

import is.xyz.mpv.MPV;

/**
 * mpv 轨道管理工具类
 * <p>
 * mpv 通过 track-list/count 和 track-list/N/xxx 属性获取轨道信息
 */
public class MpvTrackUtil {

    /**
     * 获取指定类型的轨道数量
     *
     * @param type C.TRACK_TYPE_AUDIO / C.TRACK_TYPE_VIDEO / C.TRACK_TYPE_TEXT
     */
    public static int count(int type) {
        int total = 0;
        MPV mpv = MpvUtil.get();
        if (mpv == null) return total;
        try {
            int trackCount = mpv.getPropertyInt("track-list/count");
            for (int i = 0; i < trackCount; i++) {
                String trackType = mpv.getPropertyString("track-list/" + i + "/type");
                if (matchType(trackType, type)) total++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return total;
    }

    /**
     * 获取指定类型的轨道列表，构建为 Track 对象
     *
     * @param type C.TRACK_TYPE_AUDIO / C.TRACK_TYPE_VIDEO / C.TRACK_TYPE_TEXT
     * @param key  当前播放 key，用于匹配已保存的轨道选择
     */
    public static List<Track> getTracks(int type, String key) {
        List<Track> tracks = new ArrayList<>();
        List<Track> saved = Track.find(key);
        MPV mpv = MpvUtil.get();
        if (mpv == null) return tracks;
        try {
            int trackCount = mpv.getPropertyInt("track-list/count");
            for (int i = 0; i < trackCount; i++) {
                String trackType = mpv.getPropertyString("track-list/" + i + "/type");
                if (!matchType(trackType, type)) continue;
                int trackId = mpv.getPropertyInt("track-list/" + i + "/id");
                String title = getPropertyStringSafe(mpv, "track-list/" + i + "/title");
                String lang = getPropertyStringSafe(mpv, "track-list/" + i + "/lang");
                String codec = getPropertyStringSafe(mpv, "track-list/" + i + "/codec");
                boolean selected = mpv.getPropertyBoolean("track-list/" + i + "/selected");
                String displayName = buildDisplayName(trackId, title, lang, codec);
                String format = trackId + codec;
                Track track = new Track(type, displayName, format);
                track.setSelected(selected);
                track.key(key);
                // 恢复已保存的轨道选择
                for (Track s : saved) {
                    if (s.getFormat().equals(format)) {
                        track.setSelected(s.isSelected());
                        break;
                    }
                }
                tracks.add(track);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return tracks;
    }

    /**
     * 应用轨道选择到 mpv
     */
    public static void setTrackSelection(List<Track> tracks) {
        MPV mpv = MpvUtil.get();
        if (mpv == null) return;
        for (Track track : tracks) {
            if (!track.isSelected()) continue;
            // 从 format 中提取 trackId（format = trackId + codec）
            String format = track.getFormat();
            try {
                int trackCount = mpv.getPropertyInt("track-list/count");
                for (int i = 0; i < trackCount; i++) {
                    String trackType = mpv.getPropertyString("track-list/" + i + "/type");
                    if (!matchType(trackType, track.getType())) continue;
                    int tid = mpv.getPropertyInt("track-list/" + i + "/id");
                    String codec = getPropertyStringSafe(mpv, "track-list/" + i + "/codec");
                    if (format.equals(tid + codec)) {
                        String propName = getMpvTrackProperty(track.getType());
                        if (propName != null) mpv.setPropertyInt(propName, tid);
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 重置所有轨道选择为自动
     */
    public static void reset() {
        MPV mpv = MpvUtil.get();
        if (mpv == null) return;
        try {
            mpv.setPropertyString("aid", "auto");
            mpv.setPropertyString("vid", "auto");
            mpv.setPropertyString("sid", "auto");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean matchType(String mpvType, int type) {
        if (mpvType == null) return false;
        return switch (type) {
            case C.TRACK_TYPE_AUDIO -> "audio".equals(mpvType);
            case C.TRACK_TYPE_VIDEO -> "video".equals(mpvType);
            case C.TRACK_TYPE_TEXT -> "sub".equals(mpvType);
            default -> false;
        };
    }

    private static String getMpvTrackProperty(int type) {
        return switch (type) {
            case C.TRACK_TYPE_AUDIO -> "aid";
            case C.TRACK_TYPE_VIDEO -> "vid";
            case C.TRACK_TYPE_TEXT -> "sid";
            default -> null;
        };
    }

    private static String buildDisplayName(int id, String title, String lang, String codec) {
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(id);
        if (title != null && !title.isEmpty()) sb.append(" ").append(title);
        if (lang != null && !lang.isEmpty()) sb.append(" [").append(lang).append("]");
        if (codec != null && !codec.isEmpty()) sb.append(" (").append(codec).append(")");
        return sb.toString();
    }

    private static String getPropertyStringSafe(MPV mpv, String property) {
        try {
            return mpv.getPropertyString(property);
        } catch (Exception e) {
            return "";
        }
    }
}
