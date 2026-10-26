package com.steelballrun.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

/**
 * Foreground service that owns the Android MediaSession.
 * It shows the system "now playing" card (cover, seek bar, heart / previous / play / next)
 * on the notification shade and the lock screen, and keeps the app alive while music plays
 * with the screen off. The audio itself is still played by the web app inside the WebView;
 * this service only mirrors its state and forwards button presses back to it.
 */
public class MediaService extends Service {

    static final String CHANNEL = "sbr_playback";
    static final int NOTIF_ID = 7;

    static final String A_PLAY = "com.steelballrun.player.PLAY";
    static final String A_PAUSE = "com.steelballrun.player.PAUSE";
    static final String A_NEXT = "com.steelballrun.player.NEXT";
    static final String A_PREV = "com.steelballrun.player.PREV";
    static final String A_FAV = "com.steelballrun.player.FAV";
    static final String A_STOP = "com.steelballrun.player.STOP";

    static volatile MediaService inst;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MediaSessionCompat session;
    private Bitmap defaultArt;
    private Bitmap art;
    private PowerManager.WakeLock wakeLock;
    private boolean inForeground = false;

    private String id = "";
    private String title = "STEEL BALL RUN";
    private String artist = "";
    private String album = "";
    private boolean playing = false;
    private boolean fav = false;
    private long pos = 0;
    private long dur = 0;
    private float rate = 1f;

    // ------------------------------------------------------------------ entry points (any thread)

    static void post(final Context ctx, final String json) {
        final Context app = ctx.getApplicationContext();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                MediaService s = inst;
                if (s != null) {
                    s.apply(json);
                    return;
                }
                // Service not running: only start it when something is really playing.
                try {
                    if (!new JSONObject(json).optBoolean("playing", false)) return;
                } catch (Exception e) {
                    return;
                }
                Intent i = new Intent(app, MediaService.class).putExtra("json", json);
                try {
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
                    else app.startService(i);
                } catch (Exception ignored) {
                }
            }
        });
    }

    static void setArt(final String trackId, final Bitmap bmp) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                MediaService s = inst;
                if (s != null) s.applyArt(trackId, bmp);
            }
        });
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        createChannel();
        defaultArt = BitmapFactory.decodeResource(getResources(), R.drawable.cover_sq);
        art = defaultArt;

        session = new MediaSessionCompat(this, "SteelBallRun");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { cmd("play", 0); }
            @Override public void onPause() { cmd("pause", 0); }
            @Override public void onSkipToNext() { cmd("next", 0); }
            @Override public void onSkipToPrevious() { cmd("prev", 0); }
            @Override public void onSeekTo(long p) { cmd("seek", p); }
            @Override public void onStop() { cmd("pause", 0); shutdown(); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if ("fav".equals(action)) cmd("fav", 0);
            }
        });
        session.setActive(true);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SteelBallRun:playback");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in != null) {
            String a = in.getAction();
            if (a != null) {
                switch (a) {
                    case A_PLAY: cmd("play", 0); break;
                    case A_PAUSE: cmd("pause", 0); break;
                    case A_NEXT: cmd("next", 0); break;
                    case A_PREV: cmd("prev", 0); break;
                    case A_FAV: cmd("fav", 0); break;
                    case A_STOP: shutdown(); return START_NOT_STICKY;
                    default: break;
                }
            } else if (in.hasExtra("json")) {
                apply(in.getStringExtra("json"));
            }
        }
        // A service started with startForegroundService() MUST call startForeground() quickly.
        boolean freshStart = in == null || in.getAction() == null;
        if (freshStart && !inForeground) {
            try {
                startFg(buildNotification());
                inForeground = true;
            } catch (Exception ignored) {
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        inst = null;
        releaseWakeLock();
        try {
            session.setActive(false);
            session.release();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    /** Called by the activity when the user really closes the app. */
    void closeAll() {
        shutdown();
    }

    private void shutdown() {
        playing = false;
        releaseWakeLock();
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) {
        }
        inForeground = false;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    // ------------------------------------------------------------------ state

    private void apply(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String nid = o.optString("id", "");
            if (!nid.equals(id)) {
                id = nid;
                art = defaultArt;
            }
            title = o.optString("title", "STEEL BALL RUN");
            artist = o.optString("artist", "");
            album = o.optString("album", "");
            playing = o.optBoolean("playing", false);
            fav = o.optBoolean("fav", false);
            pos = Math.max(0, o.optLong("pos", 0));
            dur = Math.max(0, o.optLong("dur", 0));
            rate = (float) o.optDouble("rate", 1.0);
            if (rate <= 0f) rate = 1f;
        } catch (Exception e) {
            return;
        }
        refresh();
    }

    private void applyArt(String trackId, Bitmap bmp) {
        if (bmp != null && trackId != null && trackId.equals(id)) {
            art = bmp;
            refresh();
        }
    }

    private void refresh() {
        updateSession();
        Notification n = buildNotification();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (playing) {
            acquireWakeLock();
            if (!inForeground) {
                try {
                    startFg(n);
                    inForeground = true;
                    return;
                } catch (Exception ignored) {
                    // not allowed right now: just post it as a normal notification
                }
            }
            if (nm != null) nm.notify(NOTIF_ID, n);
        } else {
            releaseWakeLock();
            if (inForeground) {
                try {
                    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
                    else stopForeground(false);
                } catch (Exception ignored) {
                }
                inForeground = false;
            }
            if (nm != null) nm.notify(NOTIF_ID, n);
        }
    }

    private void startFg(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
    }

    private void updateSession() {
        MediaMetadataCompat.Builder m = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur > 0 ? dur : -1L)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art);
        session.setMetadata(m.build());

        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;
        PlaybackStateCompat.Builder ps = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        pos, playing ? rate : 0f, SystemClock.elapsedRealtime())
                .addCustomAction("fav", "Favorite", fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        session.setPlaybackState(ps.build());
    }

    // ------------------------------------------------------------------ notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
    }

    private PendingIntent svc(String action, int code) {
        Intent i = new Intent(this, MediaService.class).setAction(action);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, code, i, f);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, f);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(art)
                .setContentIntent(content)
                .setDeleteIntent(svc(A_STOP, 9))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .addAction(fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart, "Favorite", svc(A_FAV, 1))
                .addAction(R.drawable.ic_prev, "Previous", svc(A_PREV, 2))
                .addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        playing ? "Pause" : "Play", svc(playing ? A_PAUSE : A_PLAY, 3))
                .addAction(R.drawable.ic_next, "Next", svc(A_NEXT, 4))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(1, 2, 3));
        return b.build();
    }

    // ------------------------------------------------------------------ back to the web app

    private void cmd(String name, long arg) {
        MainActivity.runJs("window.__sbrCmd&&window.__sbrCmd('" + name + "'," + arg + ")");
    }
}
