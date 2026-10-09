package com.hvxino.nixoplay;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Visualizer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Background playback, lock-screen controls, equalizer, visualizer data aur sleep timer. */
public class PlayerService extends Service implements AudioManager.OnAudioFocusChangeListener {

    public interface Listener {
        void onState();
        void onFft(byte[] fft);
        void onWave(byte[] wave);
    }

    public class LocalBinder extends Binder {
        public PlayerService get() {
            return PlayerService.this;
        }
    }

    public static final String A_TOGGLE = "com.hvxino.nixoplay.TOGGLE";
    public static final String A_NEXT = "com.hvxino.nixoplay.NEXT";
    public static final String A_PREV = "com.hvxino.nixoplay.PREV";
    public static final String A_STOP = "com.hvxino.nixoplay.STOP";
    private static final String CH = "nixo_play";
    private static final int NID = 77;
    public static final String[] EQ_PRESETS = {"Flat", "Bass Boost", "Vocal", "Rock", "Pop"};

    private final IBinder binder = new LocalBinder();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayList<Song> queue = new ArrayList<Song>();
    private final Random rnd = new Random();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private int index = -1;
    private MediaPlayer mp;
    private boolean prepared = false;
    private int repeat = 0;
    private boolean shuffle = false;
    private int errCount = 0;
    private boolean pausedByFocus = false;

    private Visualizer viz;
    private boolean vizWanted = false;
    private Equalizer eq;
    private BassBoost bass;
    private MediaSession session;
    private Listener listener;
    private Bitmap art;
    private int artToken = 0;
    private long sleepEnd = 0;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private AudioAttributes attrs;
    private Store store;
    private NotificationManager nm;

    private final Runnable sleepRun = new Runnable() {
        @Override
        public void run() {
            sleepEnd = 0;
            pause();
            toast("Sleep timer khatam, gaana band");
        }
    };

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            pause();
        }
    };

    // ---------------- lifecycle ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        nm.createNotificationChannel(new NotificationChannel(CH, "Nixo Play ▶️ playback",
                NotificationManager.IMPORTANCE_LOW));

        session = new MediaSession(this, "NixoPlay");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(false); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
        });
        session.setActive(true);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        if (A_STOP.equals(a)) {
            stopAll();
            return START_NOT_STICKY;
        }
        startFg();
        if (A_TOGGLE.equals(a)) toggle();
        else if (A_NEXT.equals(a)) next(false);
        else if (A_PREV.equals(a)) previous();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        releasePlayer();
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        session.release();
        io.shutdownNow();
        super.onDestroy();
    }

    private void startFg() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NID, n);
        }
    }

    public void stopAll() {
        sleepEnd = 0;
        h.removeCallbacks(sleepRun);
        releasePlayer();
        index = -1;
        queue.clear();
        art = null;
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        notifyListener();
        stopSelf();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    // ---------------- public API ----------------

    public void setListener(Listener l) { listener = l; }
    public Song current() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }
    public List<Song> getQueue() { return Collections.unmodifiableList(queue); }
    public int getIndex() { return index; }
    public Bitmap getArt() { return art; }
    public int getRepeat() { return repeat; }
    public boolean isShuffle() { return shuffle; }

    public boolean isPlaying() {
        try {
            return mp != null && prepared && mp.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public int getPosition() {
        try {
            return mp != null && prepared ? mp.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public int getDuration() {
        try {
            return mp != null && prepared ? mp.getDuration() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public void seekTo(int ms) {
        try {
            if (mp != null && prepared) mp.seekTo(ms);
        } catch (IllegalStateException ignored) { }
        updateAll();
    }

    public void setQueue(List<Song> list, int start) {
        queue.clear();
        queue.addAll(list);
        index = start;
        errCount = 0;
        playCurrent();
    }

    public void jumpTo(int i) {
        if (i < 0 || i >= queue.size()) return;
        index = i;
        errCount = 0;
        playCurrent();
    }

    public void addToQueue(Song s) {
        if (queue.isEmpty()) {
            setQueue(Collections.singletonList(s), 0);
        } else {
            queue.add(s);
            toast("Queue me joda gaya");
            notifyListener();
        }
    }

    public void playNext(Song s) {
        if (queue.isEmpty()) {
            setQueue(Collections.singletonList(s), 0);
        } else {
            queue.add(Math.min(queue.size(), index + 1), s);
            toast("Agla gaana set");
            notifyListener();
        }
    }

    public void removeAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        queue.remove(i);
        if (queue.isEmpty()) {
            releasePlayer();
            index = -1;
            updateAll();
        } else if (i == index) {
            if (index >= queue.size()) index = 0;
            playCurrent();
        } else {
            if (i < index) index--;
            notifyListener();
        }
    }

    public void toggle() {
        if (isPlaying()) pause();
        else resume();
    }

    public void pause() {
        try {
            if (mp != null && prepared && mp.isPlaying()) mp.pause();
        } catch (IllegalStateException ignored) { }
        pausedByFocus = false;
        updateAll();
    }

    public void resume() {
        try {
            if (mp != null && prepared && !mp.isPlaying()) {
                requestFocus();
                mp.start();
            }
        } catch (IllegalStateException ignored) { }
        updateAll();
    }

    public void next(boolean auto) {
        if (queue.isEmpty()) return;
        int n;
        if (shuffle && queue.size() > 1) {
            do {
                n = rnd.nextInt(queue.size());
            } while (n == index);
        } else {
            n = index + 1;
            if (n >= queue.size()) {
                if (repeat == 1 || !auto) {
                    n = 0;
                } else {
                    try {
                        if (mp != null && prepared) {
                            mp.pause();
                            mp.seekTo(0);
                        }
                    } catch (IllegalStateException ignored) { }
                    updateAll();
                    return;
                }
            }
        }
        index = n;
        playCurrent();
    }

    public void previous() {
        if (queue.isEmpty()) return;
        if (getPosition() > 3000) {
            seekTo(0);
            return;
        }
        index = index - 1 < 0 ? queue.size() - 1 : index - 1;
        playCurrent();
    }

    public void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        notifyListener();
    }

    public void toggleShuffle() {
        shuffle = !shuffle;
        notifyListener();
    }

    // ---------------- sleep timer ----------------

    public void setSleep(int minutes) {
        h.removeCallbacks(sleepRun);
        if (minutes <= 0) {
            sleepEnd = 0;
            return;
        }
        sleepEnd = SystemClock.elapsedRealtime() + minutes * 60000L;
        h.postDelayed(sleepRun, minutes * 60000L);
    }

    public long getSleepLeftMs() {
        return sleepEnd == 0 ? 0 : Math.max(0, sleepEnd - SystemClock.elapsedRealtime());
    }

    // ---------------- equalizer ----------------

    public boolean hasEq() { return eq != null; }

    public int eqBands() {
        return eq == null ? 0 : eq.getNumberOfBands();
    }

    public int eqMin() { return eq == null ? -1500 : eq.getBandLevelRange()[0]; }
    public int eqMax() { return eq == null ? 1500 : eq.getBandLevelRange()[1]; }

    public int eqCenterHz(int b) {
        return eq == null ? 0 : eq.getCenterFreq((short) b) / 1000;
    }

    public int eqLevel(int b) {
        try {
            return eq == null ? 0 : eq.getBandLevel((short) b);
        } catch (Exception e) {
            return 0;
        }
    }

    public void setEqLevel(int b, int mB) {
        if (eq == null) return;
        try {
            eq.setBandLevel((short) b, (short) mB);
            store.putInt("eq_" + b, mB);
            store.putString("eq_preset", "Custom");
        } catch (Exception ignored) { }
    }

    private static float presetDb(String name, int hz) {
        switch (name) {
            case "Bass Boost":
                return hz <= 150 ? 6f : hz <= 400 ? 3f : 0f;
            case "Vocal":
                return hz < 150 ? -3f : hz < 400 ? -1f : hz <= 4000 ? 4f : 0f;
            case "Rock":
                return hz <= 150 ? 5f : hz <= 500 ? 2f : hz <= 2000 ? -2f : hz <= 6000 ? 2f : 5f;
            case "Pop":
                return hz <= 150 ? -1f : hz <= 500 ? 2f : hz <= 2000 ? 4f : hz <= 6000 ? 2f : -1f;
            default:
                return 0f;
        }
    }

    public void applyEqPreset(String name) {
        if (eq == null) return;
        int min = eqMin(), max = eqMax();
        for (int b = 0; b < eqBands(); b++) {
            int mB = Math.max(min, Math.min(max, (int) (presetDb(name, eqCenterHz(b)) * 100)));
            try {
                eq.setBandLevel((short) b, (short) mB);
            } catch (Exception ignored) { }
            store.putInt("eq_" + b, mB);
        }
        store.putString("eq_preset", name);
    }

    public int getBass() { return store.getInt("bass", 0); }

    public void setBass(int s) {
        store.putInt("bass", s);
        try {
            if (bass != null && bass.getStrengthSupported()) bass.setStrength((short) s);
        } catch (Exception ignored) { }
    }

    // ---------------- visualizer ----------------

    public void setVisualizerWanted(boolean w) {
        vizWanted = w;
        if (w && viz == null && mp != null && prepared) attachViz(mp.getAudioSessionId());
        syncViz();
    }

    private void syncViz() {
        if (viz == null) return;
        try {
            viz.setEnabled(vizWanted && isPlaying());
        } catch (Exception ignored) { }
    }

    private void attachViz(int sid) {
        releaseViz();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            viz = new Visualizer(sid);
            viz.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] w, int sr) {
                    if (listener != null) listener.onWave(w);
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] f, int sr) {
                    if (listener != null) listener.onFft(f);
                }
            }, Visualizer.getMaxCaptureRate(), true, true);
            viz.setEnabled(vizWanted && isPlaying());
        } catch (Exception e) {
            viz = null;
        }
    }

    private void releaseViz() {
        if (viz != null) {
            try { viz.setEnabled(false); } catch (Exception ignored) { }
            try { viz.release(); } catch (Exception ignored) { }
            viz = null;
        }
    }

    private void attachEffects(int sid) {
        releaseEffects();
        try {
            eq = new Equalizer(0, sid);
            eq.setEnabled(true);
            for (int b = 0; b < eq.getNumberOfBands(); b++) {
                int lvl = Math.max(eqMin(), Math.min(eqMax(), store.getInt("eq_" + b, 0)));
                eq.setBandLevel((short) b, (short) lvl);
            }
        } catch (Exception e) {
            eq = null;
        }
        try {
            bass = new BassBoost(0, sid);
            bass.setEnabled(true);
            if (bass.getStrengthSupported()) bass.setStrength((short) store.getInt("bass", 0));
        } catch (Exception e) {
            bass = null;
        }
        attachViz(sid);
    }

    private void releaseEffects() {
        releaseViz();
        if (eq != null) {
            try { eq.release(); } catch (Exception ignored) { }
            eq = null;
        }
        if (bass != null) {
            try { bass.release(); } catch (Exception ignored) { }
            bass = null;
        }
    }

    // ---------------- player core ----------------

    private void playCurrent() {
        Song s = current();
        if (s == null) return;
        requestFocus();
        releasePlayer();
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(this, Uri.parse(s.uri));
            mp.setOnPreparedListener(m -> {
                prepared = true;
                errCount = 0;
                m.start();
                attachEffects(m.getAudioSessionId());
                updateAll();
            });
            mp.setOnCompletionListener(m -> onEnded());
            mp.setOnErrorListener((m, what, extra) -> {
                toast("Ye file chal nahi paayi: " + s.title);
                errCount++;
                if (errCount > queue.size()) {
                    errCount = 0;
                    releasePlayer();
                    updateAll();
                } else {
                    h.post(() -> next(false));
                }
                return true;
            });
            prepared = false;
            mp.prepareAsync();
        } catch (Exception e) {
            toast("File khul nahi paayi: " + s.title);
            releasePlayer();
        }
        loadArt(s);
        updateAll();
    }

    private void onEnded() {
        if (repeat == 2) {
            try {
                mp.seekTo(0);
                mp.start();
            } catch (Exception ignored) { }
        } else {
            next(true);
        }
    }

    private void releasePlayer() {
        releaseEffects();
        prepared = false;
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
    }

    private void requestFocus() {
        if (focusReq == null) {
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(this).build();
        }
        am.requestAudioFocus(focusReq);
    }

    @Override
    public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPlaying()) {
                    pause();
                    pausedByFocus = true;
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                try { if (mp != null) mp.setVolume(0.25f, 0.25f); } catch (Exception ignored) { }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                try { if (mp != null) mp.setVolume(1f, 1f); } catch (Exception ignored) { }
                if (pausedByFocus) {
                    pausedByFocus = false;
                    resume();
                }
                break;
            default:
                break;
        }
    }

    // ---------------- artwork ----------------

    private void loadArt(Song s) {
        final int tok = ++artToken;
        art = null;
        try {
            io.execute(() -> {
                Bitmap bmp = null;
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(this, Uri.parse(s.uri));
                    byte[] b = r.getEmbeddedPicture();
                    if (b != null) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(b, 0, b.length, o);
                        int sample = 1;
                        while (o.outWidth / sample > 700) sample *= 2;
                        o.inJustDecodeBounds = false;
                        o.inSampleSize = sample;
                        bmp = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                    }
                } catch (Exception ignored) {
                } finally {
                    try { r.release(); } catch (Exception ignored) { }
                }
                final Bitmap fb = bmp;
                h.post(() -> {
                    if (tok == artToken) {
                        art = fb;
                        updateAll();
                    }
                });
            });
        } catch (Exception ignored) { }
    }

    // ---------------- session + notification ----------------

    private void notifyListener() {
        if (listener != null) listener.onState();
    }

    private void updateAll() {
        boolean playing = isPlaying();
        Song s = current();
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s != null ? s.title : "Nixo Play ▶️")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s != null ? s.artist : "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s != null ? s.album : "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, getDuration());
        if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(mb.build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        getPosition(), playing ? 1f : 0f)
                .build());
        try {
            nm.notify(NID, buildNotification());
        } catch (Exception ignored) { }
        syncViz();
        notifyListener();
    }

    private PendingIntent pi(String action, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        Song s = current();
        boolean playing = isPlaying();
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(s != null ? s.title : "Nixo Play ▶️")
                .setContentText(s != null ? s.artist : "Koi gaana chuno")
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (art != null) b.setLargeIcon(art);
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_prev), "Previous", pi(A_PREV, 1)).build());
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                playing ? "Pause" : "Play", pi(A_TOGGLE, 2)).build());
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_next), "Next", pi(A_NEXT, 3)).build());
        b.setStyle(new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }
}
