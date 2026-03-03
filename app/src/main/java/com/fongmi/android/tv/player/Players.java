package com.fongmi.android.tv.player;

import static androidx.media3.common.Player.COMMAND_SET_SPEED_AND_PITCH;
import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Drm;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.event.ActionEvent;
import com.fongmi.android.tv.event.ErrorEvent;
import com.fongmi.android.tv.event.PlayerEvent;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.impl.SessionCallback;
import com.fongmi.android.tv.player.danmaku.DanPlayer;
import com.fongmi.android.tv.player.exo.ErrorMsgProvider;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.exo.TrackUtil;
import com.fongmi.android.tv.player.mpv.MpvErrorHandler;
import com.fongmi.android.tv.player.mpv.MpvTrackUtil;
import com.fongmi.android.tv.player.mpv.MpvUtil;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.utils.Path;
import com.google.common.net.HttpHeaders;
import com.orhanobut.logger.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import is.xyz.mpv.MPVNode;
import master.flame.danmaku.ui.widget.DanmakuView;

public class Players implements Player.Listener, ParseCallback, is.xyz.mpv.MPV.EventObserver {

    private static final String TAG = Players.class.getSimpleName();

    public static final int EXO = 0;
    public static final int MPV = 1;

    public static final int SOFT = 0;
    public static final int HARD = 1;

    private final ErrorMsgProvider provider;
    private final AudioManager audioManager;
    private final StringBuilder builder;
    private final Formatter formatter;
    private final Runnable runnable;

    private Map<String, String> headers;
    private MediaSessionCompat session;
    private List<Danmaku> danmakus;
    private ExoPlayer exoPlayer;
    private DanPlayer danPlayer;
    private TextureView mpvSurface;
    private TextureView.SurfaceTextureListener mpvSurfaceListener;
    private Surface mpvTextureViewSurface; // TextureView 创建的 Surface，需手动管理生命周期
    private ParseJob parseJob;
    private PlayerView view;
    private VideoSize size;
    private List<Sub> subs;
    private String format;
    private String tag;
    private String key;
    private String url;
    private Drm drm;
    private Sub sub;

    private boolean mpvPlaying;
    private boolean mpvPaused;
    private boolean mpvIdle;
    private boolean initTrack;
    private int playerType;
    private int decode;
    private int retry;
    private boolean drmFallback; // DRM 内容自动回退到 ExoPlayer

    // mpv 播放状态缓存
    private long mpvPosition;
    private long mpvDuration;
    private int mpvWidth;
    private int mpvHeight;
    private float mpvSpeed;
    private long pendingSeekPosition = C.TIME_UNSET; // mpv 文件加载完成前的 seek 缓存
    private boolean mpvFileLoaded; // mpv 文件是否已加载完成
    private boolean mpvSurfaceReady; // mpv surface 是否已 attach
    private boolean mpvFirstFrameRendered; // mpv 首帧是否已渲染
    private boolean mpvSeekingToResume; // mpv 正在执行进度恢复 seek，首帧显示需延迟到 seek 完成
    private Runnable pendingSizeUpdate; // debounce surface size 更新
    // pending media item：surface 未就绪时缓存 loadfile 参数
    private Map<String, String> pendingMpvHeaders;
    private String pendingMpvUrl;
    private String pendingMpvFormat;

    public static Players create(Activity activity) {
        Players player = new Players(activity);
        Server.get().setPlayer(player);
        return player;
    }

    private Players(Activity activity) {
        decode = HARD;
        playerType = Setting.getPlayer();
        builder = new StringBuilder();
        provider = new ErrorMsgProvider();
        runnable = () -> ErrorEvent.timeout(tag);
        formatter = new Formatter(builder, Locale.getDefault());
        audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        createSession(activity);
    }

    private void createSession(Activity activity) {
        session = new MediaSessionCompat(activity, "TV");
        session.setCallback(SessionCallback.create(this));
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setSessionActivity(PendingIntent.getActivity(App.get(), 0, new Intent(App.get(), activity.getClass()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        MediaControllerCompat.setMediaController(activity, session.getController());
    }

    private void releaseSession() {
        session.setActive(false);
        session.release();
    }

    public boolean isMpv() {
        return playerType == MPV;
    }

    public int getPlayerType() {
        return playerType;
    }

    public String getPlayerText() {
        return ResUtil.getStringArray(R.array.select_player)[playerType];
    }

    /**
     * 切换播放器类型，保持播放位置续播
     */
    public void togglePlayer() {
        long position = getPosition();
        playerType = isMpv() ? EXO : MPV;
        Setting.putPlayer(playerType);
        releasePlayer();
        if (isMpv()) {
            setMpvPlayer();
        } else {
            setPlayer(view);
        }
        if (url != null) {
            setMediaItem();
            if (position > 0) App.post(() -> seekTo(position), 500);
        }
    }

    public void init(PlayerView view) {
        releasePlayer();
        if (isMpv()) {
            this.view = view;
            setMpvPlayer();
        } else {
            setPlayer(view);
        }
        setMediaItem();
    }

    /**
     * 设置 mpv 使用的 TextureView
     */
    public void setMpvSurface(TextureView surface) {
        this.mpvSurface = surface;
    }

    private void setPlayer(PlayerView view) {
        exoPlayer = new ExoPlayer.Builder(App.get()).setLoadControl(ExoUtil.buildLoadControl()).setTrackSelector(ExoUtil.buildTrackSelector()).setRenderersFactory(ExoUtil.buildRenderersFactory(isHard() ? EXTENSION_RENDERER_MODE_ON : EXTENSION_RENDERER_MODE_PREFER)).setMediaSourceFactory(ExoUtil.buildMediaSourceFactory()).build();
        if (BuildConfig.DEBUG) exoPlayer.addAnalyticsListener(new EventLogger());
        exoPlayer.setAudioAttributes(AudioAttributes.DEFAULT, true);
        exoPlayer.setHandleAudioBecomingNoisy(true);
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.addListener(this);
        view.setPlayer(exoPlayer);
        this.view = view;
    }

    private void setMpvPlayer() {
        MpvUtil.init(App.get());
        MpvUtil.enableLogObserver(App.get());
        MpvUtil.get().addObserver(this);
        observeMpvProperties();
        if (mpvSurface != null) {
            // 首帧渲染前隐藏 TextureView（alpha=0），FrameLayout 黑色背景充当 loading 遮罩
            // 避免 mpv 初始化阶段 TextureView 透明导致的视觉闪烁
            mpvFirstFrameRendered = false;
            mpvSurface.setAlpha(0f);
            mpvSurface.setVisibility(View.VISIBLE);
            // 如果 SurfaceTexture 已经可用（TextureView 已经 attached），直接 attach
            if (mpvSurface.isAvailable()) {
                MpvUtil.log("[DEBUG-SURFACE] isAvailable=true, attaching surface immediately");
                mpvTextureViewSurface = new Surface(mpvSurface.getSurfaceTexture());
                MpvUtil.get().attachSurface(mpvTextureViewSurface);
                MpvUtil.get().setPropertyString("force-window", "yes");
                mpvSurfaceReady = true;
            } else {
                MpvUtil.log("[DEBUG-SURFACE] isAvailable=false, waiting for onSurfaceTextureAvailable");
            }
            mpvSurfaceListener = new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                    try {
                        MpvUtil.log("[DEBUG-SURFACE] onSurfaceTextureAvailable: " + width + "x" + height);
                        mpvTextureViewSurface = new Surface(surfaceTexture);
                        MpvUtil.get().attachSurface(mpvTextureViewSurface);
                        MpvUtil.get().setPropertyString("force-window", "yes");
                        MpvUtil.get().setPropertyString("android-surface-size", width + "x" + height);
                        mpvSurfaceReady = true;
                        // Surface 就绪后，执行缓存的 loadfile（解决 TextureView GONE→VISIBLE 后 surface 延迟可用的时序问题）
                        if (pendingMpvUrl != null) {
                            setMpvMediaItem(pendingMpvHeaders, pendingMpvUrl, pendingMpvFormat);
                            pendingMpvHeaders = null;
                            pendingMpvUrl = null;
                            pendingMpvFormat = null;
                            if (!mpvPaused) play();
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                    MpvUtil.log("[DEBUG-SURFACE] onSurfaceTextureSizeChanged: " + width + "x" + height);
                    // mpv 模式下 changeHeight() 已改为直接设置最终高度，不会有密集回调
                    // 对齐官方 BaseMPVView.surfaceChanged 行为：直接更新 android-surface-size
                    try { MpvUtil.get().setPropertyString("android-surface-size", width + "x" + height); } catch (Exception e) { e.printStackTrace(); }
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                    MpvUtil.log("[DEBUG-SURFACE] onSurfaceTextureDestroyed");
                    mpvSurfaceReady = false;
                    try {
                        MpvUtil.get().setPropertyString("vo", "null");
                        MpvUtil.get().setPropertyString("force-window", "no");
                        MpvUtil.get().detachSurface();
                    } catch (Exception e) { e.printStackTrace(); }
                    if (mpvTextureViewSurface != null) {
                        mpvTextureViewSurface.release();
                        mpvTextureViewSurface = null;
                    }
                    // 返回 true 表示由我们释放 SurfaceTexture
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
                    // 不在此检测首帧：mpv idle 状态的 GPU context 初始化也会触发此回调
                    // 真正的首帧检测通过 MPV_EVENT_PLAYBACK_RESTART 事件实现
                }
            };
            mpvSurface.setSurfaceTextureListener(mpvSurfaceListener);
        }
        // mpv 模式：立即隐藏 exo PlayerView，避免 exo view 覆盖 mpv TextureView 导致闪烁
        // TextureView 默认透明，视频区域背景色（黑色 FrameLayout）自然作为 loading 遮罩
        if (view != null) view.setVisibility(View.GONE);
        mpvIdle = true;
        mpvPlaying = false;
        mpvPaused = false;
        mpvSpeed = 1.0f;
    }

    private void observeMpvProperties() {
        MpvUtil.get().observeProperty("time-pos", is.xyz.mpv.MPV.mpvFormat.MPV_FORMAT_INT64);
        MpvUtil.get().observeProperty("duration", is.xyz.mpv.MPV.mpvFormat.MPV_FORMAT_INT64);
        MpvUtil.get().observeProperty("pause", is.xyz.mpv.MPV.mpvFormat.MPV_FORMAT_FLAG);
        MpvUtil.get().observeProperty("paused-for-cache", is.xyz.mpv.MPV.mpvFormat.MPV_FORMAT_FLAG);
        MpvUtil.get().observeProperty("track-list/count", is.xyz.mpv.MPV.mpvFormat.MPV_FORMAT_INT64);
        MpvUtil.get().observeProperty("video-params/w", is.xyz.mpv.MPV.mpvFormat.MPV_FORMAT_INT64);
        MpvUtil.get().observeProperty("video-params/h", is.xyz.mpv.MPV.mpvFormat.MPV_FORMAT_INT64);
        MpvUtil.get().observeProperty("speed", is.xyz.mpv.MPV.mpvFormat.MPV_FORMAT_DOUBLE);
    }

    public void setDanmakuView(DanmakuView view) {
        danPlayer = new DanPlayer();
        danPlayer.setPlayer(this);
        danPlayer.setView(view);
    }

    public ExoPlayer get() {
        return exoPlayer;
    }

    public MediaSessionCompat getSession() {
        return session;
    }

    public List<Danmaku> getDanmakus() {
        return danmakus;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers == null ? new HashMap<>() : headers;
    }

    public void setSub(Sub sub) {
        this.sub = sub;
        setMediaItem();
    }

    public void setFormat(String format) {
        this.format = format;
        setMediaItem();
    }

    public String getKey() {
        return key != null ? key : url;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public void reset() {
        removeTimeoutCheck();
        restoreFromDrmFallback();
        retry = 0;
    }

    private void restoreFromDrmFallback() {
        if (drmFallback) {
            drmFallback = false;
            playerType = MPV;
            releasePlayer();
            setMpvPlayer();
        }
    }

    public void clearMediaItems() {
        if (isMpv()) {
            try { MpvUtil.get().command(new String[]{"stop"}); } catch (Exception e) { e.printStackTrace(); }
        } else {
            if (exoPlayer != null) exoPlayer.clearMediaItems();
        }
    }

    public void clear() {
        danmakus = null;
        headers = null;
        format = null;
        subs = null;
        drm = null;
        url = null;
    }

    public String stringToTime(long time) {
        return Util.format(builder, formatter, time);
    }

    public int getVideoWidth() {
        if (isMpv()) return mpvWidth;
        return size == null ? 0 : size.width;
    }

    public int getVideoHeight() {
        if (isMpv()) return mpvHeight;
        return size == null ? 0 : size.height;
    }

    public float getSpeed() {
        if (isMpv()) return mpvSpeed;
        return exoPlayer == null ? 1.0f : exoPlayer.getPlaybackParameters().speed;
    }

    public long getPosition() {
        if (isMpv()) return mpvPosition;
        return exoPlayer == null ? C.TIME_UNSET : exoPlayer.getCurrentPosition();
    }

    public long getDuration() {
        if (isMpv()) return mpvDuration;
        return exoPlayer == null ? -1 : exoPlayer.getDuration();
    }

    public long getBuffered() {
        if (isMpv()) {
            try {
                double percent = MpvUtil.get().getPropertyDouble("cache-buffering-state");
                if (mpvDuration > 0) return (long) (mpvDuration * percent / 100.0);
            } catch (Exception e) { /* 忽略 */ }
            return mpvPosition;
        }
        return exoPlayer == null ? 0 : exoPlayer.getBufferedPosition();
    }

    public boolean haveTrack(int type) {
        if (isMpv()) return MpvTrackUtil.count(type) > 0;
        return exoPlayer != null && TrackUtil.count(exoPlayer.getCurrentTracks(), type) > 0;
    }

    public boolean haveDanmaku() {
        if (danmakus != null) for (Danmaku danmaku : danmakus) if (danmaku.isSelected()) return true;
        return false;
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public boolean isPlaying() {
        if (isMpv()) return mpvPlaying;
        return exoPlayer != null && exoPlayer.isPlaying();
    }

    public boolean isEnded() {
        if (isMpv()) return mpvIdle && !mpvPlaying && url != null;
        return exoPlayer != null && exoPlayer.getPlaybackState() == Player.STATE_ENDED;
    }

    public boolean isIdle() {
        if (isMpv()) return mpvIdle;
        return exoPlayer != null && exoPlayer.getPlaybackState() == Player.STATE_IDLE;
    }

    public boolean isEmpty() {
        return TextUtils.isEmpty(getUrl());
    }

    public boolean isLive() {
        if (isMpv()) return getDuration() < TimeUnit.MINUTES.toMillis(1);
        return getDuration() < TimeUnit.MINUTES.toMillis(1) || exoPlayer.isCurrentMediaItemLive();
    }

    public boolean isVod() {
        if (isMpv()) return getDuration() > TimeUnit.MINUTES.toMillis(1);
        return getDuration() > TimeUnit.MINUTES.toMillis(1) && !exoPlayer.isCurrentMediaItemLive();
    }

    public boolean isHard() {
        return decode == HARD;
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public String getSizeText() {
        return getVideoWidth() == 0 && getVideoHeight() == 0 ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getSpeedText() {
        return String.format(Locale.getDefault(), "%.2f", getSpeed());
    }

    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    public String setSpeed(float speed) {
        if (isMpv()) {
            try {
                MpvUtil.get().setPropertyDouble("speed", (double) speed);
                mpvSpeed = speed;
            } catch (Exception e) { e.printStackTrace(); }
            return getSpeedText();
        }
        if (exoPlayer == null || !exoPlayer.isCommandAvailable(COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
        exoPlayer.setPlaybackParameters(exoPlayer.getPlaybackParameters().withSpeed(speed));
        return getSpeedText();
    }

    public String addSpeed() {
        float speed = getSpeed();
        float addon = speed >= 2 ? 1f : 0.25f;
        speed = speed >= 5 ? 0.25f : Math.min(speed + addon, 5.0f);
        return setSpeed(speed);
    }

    public String addSpeed(float value) {
        float speed = getSpeed();
        speed = Math.min(speed + value, 5);
        return setSpeed(speed);
    }

    public String subSpeed(float value) {
        float speed = getSpeed();
        speed = Math.max(speed - value, 0.25f);
        return setSpeed(speed);
    }

    public String toggleSpeed() {
        float speed = getSpeed();
        speed = speed == 1 ? Setting.getSpeed() : 1;
        return setSpeed(speed);
    }

    public void toggleDecode() {
        if (isMpv()) return;
        decode = isHard() ? SOFT : HARD;
        init(view);
    }

    public String getPositionTime(long time) {
        time = getPosition() + time;
        if (time > getDuration()) time = getDuration();
        else if (time < 0) time = 0;
        return stringToTime(time);
    }

    public String getDurationTime() {
        long time = getDuration();
        if (time < 0) time = 0;
        return stringToTime(time);
    }

    public void seek(long time) {
        seekTo(getPosition() + time);
    }

    public void seekTo(long time) {
        if (isMpv()) {
            if (!mpvFileLoaded) {
                // mpv 文件还未加载完成，缓存 seek 位置，待 FILE_LOADED 后执行
                pendingSeekPosition = time;
                return;
            }
            try {
                MpvUtil.get().command(new String[]{"seek", String.valueOf(time / 1000.0), "absolute"});
            } catch (Exception e) { e.printStackTrace(); }
        } else {
            if (exoPlayer != null) exoPlayer.seekTo(time);
        }
        if (danPlayer != null) danPlayer.seekTo(time);
    }

    public void seekToDefaultPosition() {
        if (isMpv()) {
            seekTo(0);
        } else {
            if (exoPlayer != null) exoPlayer.seekToDefaultPosition();
            prepare();
        }
    }

    public void prepare() {
        if (isMpv()) {
            // mpv idle 且有 URL 时重新加载
            if (mpvIdle && url != null) setMpvMediaItem(headers, url, format);
            return;
        }
        if (exoPlayer != null) exoPlayer.prepare();
    }

    public void play() {
        if (isMpv()) {
            try { MpvUtil.get().setPropertyBoolean("pause", false); } catch (Exception e) { e.printStackTrace(); }
        } else {
            if (exoPlayer != null) exoPlayer.play();
        }
        if (danPlayer != null) danPlayer.play();
    }

    public void pause() {
        if (isMpv()) {
            try { MpvUtil.get().setPropertyBoolean("pause", true); } catch (Exception e) { e.printStackTrace(); }
        } else {
            if (exoPlayer != null) exoPlayer.pause();
        }
        if (danPlayer != null) danPlayer.pause();
    }

    public void stop() {
        if (isMpv()) {
            try { MpvUtil.get().command(new String[]{"stop"}); } catch (Exception e) { e.printStackTrace(); }
        } else {
            if (exoPlayer != null) exoPlayer.stop();
        }
        if (danPlayer != null) danPlayer.stop();
        stopParse();
    }

    public void release() {
        stopParse();
        releasePlayer();
        releaseSession();
        removeTimeoutCheck();
        Server.get().setPlayer(null);
        App.execute(() -> Source.get().stop());
    }

    private void releasePlayer() {
        if (exoPlayer != null) exoPlayer.release();
        if (danPlayer != null) danPlayer.release();
        if (view != null) view.setPlayer(null);
        releaseMpv();
        exoPlayer = null;
    }

    private void releaseMpv() {
        try {
            if (MpvUtil.get() != null) {
                MpvUtil.get().command(new String[]{"stop"}); // 先停止播放
                MpvUtil.get().removeObserver(this);
            }
        } catch (Exception e) { /* 忽略 */ }
        if (mpvSurface != null) {
            if (mpvSurfaceListener != null) {
                mpvSurface.setSurfaceTextureListener(null);
                mpvSurfaceListener = null;
            }
            try { if (MpvUtil.get() != null) MpvUtil.get().detachSurface(); } catch (Exception e) { /* 忽略 */ }
            if (mpvTextureViewSurface != null) {
                mpvTextureViewSurface.release();
                mpvTextureViewSurface = null;
            }
            mpvSurface.setAlpha(1f); // 重置 alpha
            mpvSurface.setVisibility(View.GONE);
        }
        MpvUtil.destroy(); // 释放 mpv 引擎
        if (view != null) view.setVisibility(View.VISIBLE);
        mpvPlaying = false;
        mpvPaused = false;
        mpvIdle = true;
        mpvFileLoaded = false;
        mpvPosition = 0;
        mpvDuration = 0;
        mpvWidth = 0;
        mpvHeight = 0;
        mpvSpeed = 1.0f;
        pendingSeekPosition = C.TIME_UNSET;
        mpvSurfaceReady = false;
        mpvFirstFrameRendered = false;
        mpvSeekingToResume = false;
        if (pendingSizeUpdate != null) {
            App.removeCallbacks(pendingSizeUpdate);
            pendingSizeUpdate = null;
        }
        pendingMpvHeaders = null;
        pendingMpvUrl = null;
        pendingMpvFormat = null;
    }

    private void removeTimeoutCheck() {
        App.removeCallbacks(runnable);
    }

    public void start(Result result, boolean useParse, long timeout) {
        if (result.getDrm() != null && !FrameworkMediaDrm.isCryptoSchemeSupported(result.getDrm().getUUID())) {
            ErrorEvent.drm(tag);
        } else if (result.getDrm() != null && isMpv()) {
            // mpv 不支持 DRM 解密，自动回退到 ExoPlayer
            drmFallback = true;
            playerType = EXO;
            releasePlayer();
            setPlayer(view);
            setMediaItem(result, timeout);
        } else if (result.hasMsg()) {
            ErrorEvent.extract(tag, result.getMsg());
        } else if (result.getParse() == 1 || result.getJx() == 1) {
            startParse(result, useParse);
        } else if (isIllegal(result.getRealUrl())) {
            ErrorEvent.url(tag);
        } else {
            setMediaItem(result, timeout);
        }
    }

    private void startParse(Result result, boolean useParse) {
        stopParse();
        drm = result.getDrm();
        subs = result.getSubs();
        format = result.getFormat();
        danmakus = result.getDanmaku();
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
    }

    private Map<String, String> checkUa(Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) if (HttpHeaders.USER_AGENT.equalsIgnoreCase(header.getKey())) return headers;
        headers.put(HttpHeaders.USER_AGENT, Setting.getUa().isEmpty() ? ExoUtil.getUa() : Setting.getUa());
        return headers;
    }

    private List<Sub> checkSub(List<Sub> subs) {
        if (subs == null) subs = this.subs = new ArrayList<>();
        if (sub == null || subs.contains(sub)) return subs;
        subs.add(0, sub);
        return subs;
    }

    public void setMediaItem() {
        if (url != null) setMediaItem(headers, url, format, drm, subs, danmakus, Constant.TIMEOUT_PLAY);
    }

    public void setMediaItem(String url) {
        setMediaItem(new HashMap<>(), url);
    }

    private void setMediaItem(Map<String, String> headers, String url) {
        setMediaItem(headers, url, format, drm, subs, danmakus, Constant.TIMEOUT_PLAY);
    }

    private void setMediaItem(Result result, long timeout) {
        setMediaItem(result.getHeader(), result.getRealUrl(), result.getFormat(), result.getDrm(), result.getSubs(), result.getDanmaku(), timeout);
    }

    private void setMediaItem(Map<String, String> headers, String url, String format, Drm drm, List<Sub> subs, List<Danmaku> danmakus, long timeout) {
        this.headers = checkUa(headers != null ? headers : new HashMap<>());
        this.url = url;
        this.format = format;
        this.drm = drm;
        this.subs = checkSub(subs);
        if (isMpv()) {
            setMpvMediaItem(this.headers, url, format);
        } else {
            if (exoPlayer != null) exoPlayer.setMediaItem(ExoUtil.getMediaItem(this.headers, UrlUtil.uri(url), format, drm, this.subs, decode));
        }
        Logger.t(TAG).d("player=%s\nheaders=%s\nurl=%s\nformat=%s\ndrm=%s\nsubs=%s\ndanmakus=%s\ntimeout=%s", isMpv() ? "MPV" : "EXO", this.headers, url, format, drm, this.subs, danmakus, timeout);
        if (danPlayer != null) setDanmaku(this.danmakus = danmakus);
        App.post(runnable, timeout);
        PlayerEvent.prepare(tag);
        session.setActive(true);
        initTrack = false;
        if (isMpv()) {
            play(); // mpv: loadfile 后需要显式 pause=false 来启动播放
        } else {
            prepare();
        }
    }

    private void setMpvMediaItem(Map<String, String> headers, String url, String format) {
        // Surface 未就绪时缓存参数，等 onSurfaceTextureAvailable 回调后再执行
        if (!mpvSurfaceReady) {
            MpvUtil.log("setMpvMediaItem: surface not ready, queuing loadfile for: " + url);
            pendingMpvHeaders = headers != null ? new HashMap<>(headers) : new HashMap<>();
            pendingMpvUrl = url;
            pendingMpvFormat = format;
            return;
        }
        try {
            // 根据 format 或 URL 特征推断 demuxer 格式
            String lavfFormat = getLavfFormat(format, url);
            // 清除上一次的全局 demuxer-lavf-format（避免残留影响后续文件）
            // 注意：init() 后必须使用 setPropertyString 而非 setOptionString
            MpvUtil.get().setPropertyString("demuxer-lavf-format", "");
            // HLS/DASH 需要额外的协议白名单和缓存配置
            if (lavfFormat != null && ("hls".equals(lavfFormat) || "dash".equals(lavfFormat))) {
                MpvUtil.get().setPropertyString("demuxer-lavf-o", "protocol_whitelist=file,http,https,tcp,tls,crypto");
                MpvUtil.get().setPropertyString("cache", "yes");
                MpvUtil.get().setPropertyString("demuxer-readahead-secs", "30");
            } else {
                MpvUtil.get().setPropertyString("demuxer-lavf-o", "");
                MpvUtil.get().setPropertyString("cache", "auto");
                MpvUtil.get().setPropertyString("demuxer-readahead-secs", "1");
            }
            // 设置 User-Agent：通过 mpv 的 user-agent 属性独立设置
            String userAgent = null;
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (HttpHeaders.USER_AGENT.equalsIgnoreCase(entry.getKey())) {
                    userAgent = entry.getValue();
                    break;
                }
            }
            if (userAgent != null) {
                MpvUtil.get().setPropertyString("user-agent", userAgent);
            }
            // 设置 Referrer（如有）
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (HttpHeaders.REFERER.equalsIgnoreCase(entry.getKey())) {
                    MpvUtil.get().setPropertyString("referrer", entry.getValue());
                    break;
                }
            }
            // 设置其余 HTTP headers（排除 User-Agent 和 Referer）
            StringBuilder headerStr = new StringBuilder();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (HttpHeaders.USER_AGENT.equalsIgnoreCase(entry.getKey())) continue;
                if (HttpHeaders.REFERER.equalsIgnoreCase(entry.getKey())) continue;
                if (headerStr.length() > 0) headerStr.append("\r\n");
                headerStr.append(entry.getKey()).append(": ").append(entry.getValue());
            }
            if (headerStr.length() > 0) {
                MpvUtil.get().setPropertyString("http-header-fields", headerStr.toString());
            } else {
                MpvUtil.get().setPropertyString("http-header-fields", "");
            }
            // 加载文件：demuxer-lavf-format 通过 per-file options 传递（确保仅对此文件生效）
            // loadfile 语法：loadfile <url> [<flags> [<index> [<options>]]]
            // index=-1 表示默认位置
            if (lavfFormat != null) {
                MpvUtil.get().command(new String[]{"loadfile", url, "replace", "-1", "demuxer-lavf-format=" + lavfFormat});
            } else {
                MpvUtil.get().command(new String[]{"loadfile", url});
            }
        } catch (Exception e) {
            e.printStackTrace();
            ErrorEvent.extract(tag, "MPV loadfile failed: " + e.getMessage());
        }
    }

    /**
     * 根据 MIME type 或 URL 特征推断 lavf demuxer 格式
     * 解决 mpv 无法通过 URL 扩展名识别流媒体格式的问题（如查询参数中包含 .m3u8）
     */
    private String getLavfFormat(String format, String url) {
        // 优先使用显式的 format（MIME type）
        if (!TextUtils.isEmpty(format)) {
            if (format.contains("m3u") || format.contains("hls")) return "hls";
            if (format.contains("mpd") || format.contains("dash")) return "dash";
            if (format.contains("mp2t") || format.contains("mpegts")) return "mpegts";
            if (format.contains("rtsp")) return "rtsp";
        }
        // 从 URL 路径部分推断（不含查询参数，避免误匹配）
        if (!TextUtils.isEmpty(url)) {
            try {
                Uri uri = Uri.parse(url);
                String path = uri.getPath();
                if (path != null) {
                    String lowerPath = path.toLowerCase();
                    if (lowerPath.endsWith(".m3u8") || lowerPath.endsWith(".m3u")) return "hls";
                    if (lowerPath.endsWith(".mpd")) return "dash";
                    if (lowerPath.endsWith(".ts")) return "mpegts";
                    if (lowerPath.contains("/hls/")) return "hls";
                }
                // 路径无法判断时，检查查询参数值中的扩展名
                // 例如 url=xxx.m3u8 表明此 API 返回 HLS 内容
                String query = uri.getQuery();
                if (query != null) {
                    String lowerQuery = query.toLowerCase();
                    if (lowerQuery.contains(".m3u8") || lowerQuery.contains(".m3u")) return "hls";
                    if (lowerQuery.contains(".mpd")) return "dash";
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void setDanmaku(List<Danmaku> items) {
        setDanmaku(items == null || items.isEmpty() ? Danmaku.empty() : items.get(0));
    }

    public void setDanmaku(Danmaku item) {
        danPlayer.setDanmaku(item);
        if (danmakus == null) danmakus = new ArrayList<>();
        if (!item.isEmpty() && !danmakus.contains(item)) danmakus.add(0, item);
        danmakus.forEach(d -> d.setSelected(d.getUrl().equals(item.getUrl())));
    }

    public void setDanmakuSize(float size) {
        if (danPlayer != null) danPlayer.setTextSize(size);
    }

    public void resetTrack() {
        if (isMpv()) {
            MpvTrackUtil.reset();
        } else {
            if (exoPlayer != null) TrackUtil.reset(exoPlayer);
        }
    }

    public void setTrack(List<Track> tracks) {
        if (isMpv()) {
            if (!tracks.isEmpty()) MpvTrackUtil.setTrackSelection(tracks);
        } else {
            if (exoPlayer != null && !tracks.isEmpty()) TrackUtil.setTrackSelection(exoPlayer, tracks);
        }
    }

    private void setPlaybackState(int state) {
        long actions = PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        session.setPlaybackState(new PlaybackStateCompat.Builder().setActions(actions).setState(state, getPosition(), getSpeed()).build());
    }

    private boolean isIllegal(String url) {
        Uri uri = UrlUtil.uri(url);
        String host = UrlUtil.host(uri);
        String scheme = UrlUtil.scheme(uri);
        if ("data".equals(scheme)) return false;
        return scheme.isEmpty() || "file".equals(scheme) ? !Path.exists(url) : host.isEmpty();
    }

    public void setMetadata(String title, String artist, String artUri) {
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder();
        builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title);
        builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
        builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artUri);
        builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artUri);
        builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUri);
        builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDuration());
        putBitmap(builder, artUri);
    }

    private void putBitmap(MediaMetadataCompat.Builder builder, String artUri) {
        ImgUtil.load(artUri, new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, resource);
                session.setMetadata(builder.build());
                ActionEvent.update();
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, ((BitmapDrawable) errorDrawable).getBitmap());
                session.setMetadata(builder.build());
                ActionEvent.update();
            }
        });
    }

    public void share(Activity activity, CharSequence title) {
        try {
            if (isEmpty()) return;
            Bundle bundle = new Bundle();
            for (Map.Entry<String, String> entry : getHeaders().entrySet()) bundle.putString(entry.getKey(), entry.getValue());
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_TEXT, getUrl());
            intent.putExtra("extra_headers", bundle);
            intent.putExtra("title", title);
            intent.putExtra("name", title);
            intent.setType("text/plain");
            activity.startActivity(Util.getChooser(intent));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void choose(Activity activity, CharSequence title) {
        try {
            if (isEmpty()) return;
            List<String> list = new ArrayList<>();
            for (Map.Entry<String, String> entry : getHeaders().entrySet()) list.addAll(Arrays.asList(entry.getKey(), entry.getValue()));
            Uri data = getUrl().startsWith("file://") || getUrl().startsWith("/") ? FileUtil.getShareUri(getUrl()) : Uri.parse(getUrl());
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(data, "video/*");
            intent.putExtra("title", title);
            intent.putExtra("return_result", isVod());
            intent.putExtra("headers", list.toArray(new String[0]));
            if (isVod()) intent.putExtra("position", (int) getPosition());
            activity.startActivityForResult(Util.getChooser(intent), 1001);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void checkData(Intent data) {
        try {
            if (data == null || data.getExtras() == null) return;
            int position = data.getExtras().getInt("position", 0);
            String endBy = data.getExtras().getString("end_by", "");
            if ("playback_completion".equals(endBy)) ActionEvent.next();
            if ("user".equals(endBy)) seekTo(position);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==================== ExoPlayer Listener 回调 ====================

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        setMediaItem(headers, url);
    }

    @Override
    public void onParseError() {
        ErrorEvent.parse(tag);
    }

    @Override
    public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
        if (!events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_PLAYER_ERROR)) return;
        switch (player.getPlaybackState()) {
            case Player.STATE_IDLE:
                setPlaybackState(events.contains(Player.EVENT_PLAYER_ERROR) ? PlaybackStateCompat.STATE_ERROR : PlaybackStateCompat.STATE_NONE);
                break;
            case Player.STATE_READY:
                setPlaybackState(player.isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED);
                break;
            case Player.STATE_BUFFERING:
                setPlaybackState(PlaybackStateCompat.STATE_BUFFERING);
                break;
            case Player.STATE_ENDED:
                setPlaybackState(PlaybackStateCompat.STATE_STOPPED);
                break;
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (isPlaying() && audioManager != null && audioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION) pause();
        PlayerEvent.playing(tag);
        ActionEvent.update();
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (danPlayer != null) danPlayer.check(state);
        PlayerEvent.state(tag, state);
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
        this.size = videoSize;
        PlayerEvent.size(tag);
    }

    @Override
    public void onTracksChanged(@NonNull Tracks tracks) {
        if (tracks.isEmpty() || initTrack) return;
        setTrack(Track.find(getKey()));
        PlayerEvent.track(tag);
        initTrack = true;
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException e) {
        if (++retry > 2) ErrorEvent.extract(tag, provider.get(e));
        else switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW:
                seekToDefaultPosition();
                break;
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
                toggleDecode();
                break;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
                setFormat(ExoUtil.getMimeType(e.errorCode));
                break;
            default:
                ErrorEvent.extract(tag, provider.get(e));
                break;
        }
    }

    // ==================== MPV.EventObserver 回调 ====================

    @Override
    public void eventProperty(@NonNull String property) {
        // MPV_FORMAT_NONE 回调，忽略
    }

    @Override
    public void eventProperty(@NonNull String property, long value) {
        App.post(() -> {
            switch (property) {
                case "time-pos":
                    mpvPosition = value * 1000; // 秒转毫秒
                    break;
                case "duration":
                    mpvDuration = value * 1000;
                    break;
                case "video-params/w":
                    mpvWidth = (int) value;
                    if (mpvWidth > 0 && mpvHeight > 0) PlayerEvent.size(tag);
                    break;
                case "video-params/h":
                    mpvHeight = (int) value;
                    if (mpvWidth > 0 && mpvHeight > 0) PlayerEvent.size(tag);
                    break;
                case "track-list/count":
                    if (!initTrack && value > 0) {
                        setTrack(Track.find(getKey()));
                        PlayerEvent.track(tag);
                        initTrack = true;
                    }
                    break;
            }
        });
    }

    @Override
    public void eventProperty(@NonNull String property, boolean value) {
        App.post(() -> {
            switch (property) {
                case "pause":
                    mpvPaused = value;
                    mpvPlaying = !value;
                    if (value) {
                        setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                    } else {
                        setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    }
                    PlayerEvent.playing(tag);
                    ActionEvent.update();
                    break;
                case "paused-for-cache":
                    if (value) {
                        setPlaybackState(PlaybackStateCompat.STATE_BUFFERING);
                    } else if (!mpvPaused) {
                        setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    }
                    break;
            }
        });
    }

    @Override
    public void eventProperty(@NonNull String property, @NonNull String value) {
        // 字符串属性回调，暂不处理
    }

    @Override
    public void eventProperty(@NonNull String property, @NonNull MPVNode value) {
        // MPVNode 属性回调，暂不处理
    }

    @Override
    public void eventProperty(@NonNull String property, double value) {
        App.post(() -> {
            if ("speed".equals(property)) {
                mpvSpeed = (float) value;
            }
        });
    }

    @Override
    public void event(int eventId, @NonNull MPVNode data) {
        App.post(() -> {
            switch (eventId) {
                case is.xyz.mpv.MPV.mpvEvent.MPV_EVENT_START_FILE:
                    MpvUtil.log("[DEBUG-EVENT] MPV_EVENT_START_FILE, mpvSurfaceReady=" + mpvSurfaceReady);
                    mpvIdle = false;
                    mpvPlaying = false;
                    mpvFileLoaded = false;
                    // 不再显示 exo view 作为遮罩层：
                    // TextureView 切集时 mpv 自动保留上一帧直到新帧渲染，无需 exo view 覆盖
                    // 之前的 exo view 显示→隐藏切换是闪烁的根因
                    removeTimeoutCheck();
                    setPlaybackState(PlaybackStateCompat.STATE_BUFFERING);
                    PlayerEvent.state(tag, Player.STATE_BUFFERING);
                    break;
                case is.xyz.mpv.MPV.mpvEvent.MPV_EVENT_FILE_LOADED:
                    MpvUtil.log("[DEBUG-EVENT] MPV_EVENT_FILE_LOADED");
                    mpvPlaying = true;
                    mpvFileLoaded = true;
                    mpvIdle = false;
                    removeTimeoutCheck();
                    // exo view 已在 setMpvPlayer() 中隐藏，此处无需再操作
                    setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    PlayerEvent.state(tag, Player.STATE_READY);
                    PlayerEvent.playing(tag);
                    ActionEvent.update();
                    loadMpvSubtitles();
                    // 执行缓存的 seek（进度恢复）
                    if (pendingSeekPosition != C.TIME_UNSET && pendingSeekPosition > 0) {
                        mpvSeekingToResume = true; // 标记正在恢复进度，第一次 PLAYBACK_RESTART 可能是开头帧
                        MpvUtil.log("[DEBUG-EVENT] FILE_LOADED: seeking to resume position " + pendingSeekPosition + "ms");
                        try {
                            MpvUtil.get().command(new String[]{"seek", String.valueOf(pendingSeekPosition / 1000.0), "absolute"});
                            if (danPlayer != null) danPlayer.seekTo(pendingSeekPosition);
                        } catch (Exception e) { e.printStackTrace(); }
                        pendingSeekPosition = C.TIME_UNSET;
                    }
                    retry = 0;
                    break;
                case is.xyz.mpv.MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART:
                    // seek 完成或恢复播放
                    if (!mpvPaused) {
                        mpvPlaying = true;
                        setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    }
                    // PLAYBACK_RESTART = mpv 首帧已渲染完成（或 seek 后恢复）
                    // 如果正在执行进度恢复 seek，此次 PLAYBACK_RESTART 可能是开头帧
                    // 跳过显示，等 seek 完成后的下一次 PLAYBACK_RESTART 再显示
                    // 同时设置超时保护：如果 seek 距离太近没有触发第二次 PLAYBACK_RESTART，500ms 后强制显示
                    if (mpvSeekingToResume) {
                        MpvUtil.log("[DEBUG-EVENT] PLAYBACK_RESTART: skipping (waiting for resume seek to complete)");
                        mpvSeekingToResume = false;
                        // 超时保护：如果 seek 距离过近不触发 MPV_EVENT_SEEK，则不会有第二次 PLAYBACK_RESTART
                        // 500ms 后强制显示 TextureView 避免永久黑屏
                        if (mpvSurface != null) {
                            final TextureView surface = mpvSurface;
                            App.post(() -> {
                                if (!mpvFirstFrameRendered && surface.getAlpha() == 0f) {
                                    mpvFirstFrameRendered = true;
                                    MpvUtil.log("[DEBUG-EVENT] Resume seek timeout: force showing TextureView");
                                    surface.setAlpha(1f);
                                }
                            }, 500);
                        }
                    } else if (!mpvFirstFrameRendered && mpvSurface != null) {
                        mpvFirstFrameRendered = true;
                        MpvUtil.log("[DEBUG-EVENT] PLAYBACK_RESTART: first frame ready, showing TextureView");
                        mpvSurface.setAlpha(1f);
                    }
                    break;
                case is.xyz.mpv.MPV.mpvEvent.MPV_EVENT_SEEK:
                    setPlaybackState(PlaybackStateCompat.STATE_BUFFERING);
                    break;
                case is.xyz.mpv.MPV.mpvEvent.MPV_EVENT_END_FILE:
                    mpvPlaying = false;
                    mpvIdle = true;
                    handleMpvEndFile(data);
                    break;
            }
        });
    }

    // mpv END_FILE 事件的 reason 常量
    private static final int MPV_END_FILE_REASON_EOF = 0;
    private static final int MPV_END_FILE_REASON_STOP = 2;
    private static final int MPV_END_FILE_REASON_QUIT = 3;
    private static final int MPV_END_FILE_REASON_ERROR = 4;
    private static final int MPV_END_FILE_REASON_REDIRECT = 5;

    private void handleMpvEndFile(@NonNull MPVNode data) {
        int reason = -1;
        try {
            MPVNode reasonNode = data.get("reason");
            if (reasonNode != null) {
                Long val = reasonNode.asInt();
                if (val != null) reason = val.intValue();
            }
        } catch (Exception e) {
            // MPVNode 解析失败，当作普通结束处理
        }
        if (reason == MPV_END_FILE_REASON_ERROR) {
            // 错误结束：尝试重试
            if (++retry > 2) {
                // 超过重试次数，报告错误
                String errorMsg = "MPV playback error";
                try {
                    MPVNode errorNode = data.get("error");
                    if (errorNode != null) {
                        String errStr = errorNode.asString();
                        if (errStr != null) errorMsg = MpvErrorHandler.getErrorMessage(errStr);
                    }
                } catch (Exception e) {
                    // 忽略解析错误
                }
                setPlaybackState(PlaybackStateCompat.STATE_STOPPED);
                ErrorEvent.extract(tag, errorMsg);
            } else {
                // 重试：重新加载当前 URL
                if (url != null) {
                    setMpvMediaItem(headers != null ? headers : new HashMap<>(), url, format);
                    if (!mpvPaused) play();
                } else {
                    setPlaybackState(PlaybackStateCompat.STATE_STOPPED);
                    ErrorEvent.extract(tag, "MPV playback error: no URL to retry");
                }
            }
        } else if (reason == MPV_END_FILE_REASON_STOP || reason == MPV_END_FILE_REASON_QUIT) {
            // 用户主动停止，不报错
            setPlaybackState(PlaybackStateCompat.STATE_STOPPED);
        } else {
            // EOF 或其他（正常结束）
            setPlaybackState(PlaybackStateCompat.STATE_STOPPED);
            PlayerEvent.state(tag, Player.STATE_ENDED);
        }
    }

    private void loadMpvSubtitles() {
        if (subs == null || subs.isEmpty()) {
            MpvUtil.log("loadMpvSubtitles: no subs to load");
            return;
        }
        MpvUtil.log("loadMpvSubtitles: loading " + subs.size() + " subtitle(s)");
        for (int i = 0; i < subs.size(); i++) {
            Sub sub = subs.get(i);
            try {
                String rawUrl = sub.getUrl();
                if (rawUrl == null || rawUrl.isEmpty()) {
                    MpvUtil.log("loadMpvSubtitles[" + i + "]: skipping empty URL");
                    continue;
                }
                // 与 ExoPlayer 保持一致：将 proxy://、file://、assets:// 等协议转换为本地服务器地址
                String subUrl = UrlUtil.convert(rawUrl);
                String subTitle = sub.getName();
                String subLang = sub.getLang();
                // sub-add 语法：sub-add <url> [<flags> [<title> [<lang>]]]
                String flag = (i == 0) ? "select" : "auto";
                MpvUtil.log("loadMpvSubtitles[" + i + "]: rawUrl=" + rawUrl + " convertedUrl=" + subUrl + " flag=" + flag + " title=" + subTitle + " lang=" + subLang + " format=" + sub.getFormat());
                // 构建命令：避免传入空字符串参数（mpv 可能无法正确解析）
                String[] cmd;
                if (!subTitle.isEmpty() && !subLang.isEmpty()) {
                    cmd = new String[]{"sub-add", subUrl, flag, subTitle, subLang};
                } else if (!subTitle.isEmpty()) {
                    cmd = new String[]{"sub-add", subUrl, flag, subTitle};
                } else {
                    cmd = new String[]{"sub-add", subUrl, flag};
                }
                MpvUtil.get().command(cmd);
                MpvUtil.log("loadMpvSubtitles[" + i + "]: sub-add command sent successfully");
            } catch (Exception e) {
                MpvUtil.log("loadMpvSubtitles[" + i + "]: FAILED - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        // 记录当前字幕轨道数量
        try {
            String trackCount = MpvUtil.get().getPropertyString("track-list/count");
            String subTrack = MpvUtil.get().getPropertyString("sid");
            MpvUtil.log("loadMpvSubtitles: done, track-list/count=" + trackCount + " sid=" + subTrack);
        } catch (Exception e) {
            MpvUtil.log("loadMpvSubtitles: failed to read track info: " + e.getMessage());
        }
    }

}
