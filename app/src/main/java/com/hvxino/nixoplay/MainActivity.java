package com.hvxino.nixoplay;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

public class MainActivity extends Activity implements PlayerService.Listener {

    static final int BG = 0xFF0B0A1A, CARD = 0xFF17162E, ACC = 0xFF22E5FF,
            PINK = 0xFFFF3DCB, SUB = 0xFFA8A6C6;
    static final String APP = "Nixo Play ▶️";
    static final String NEW_KEY = "\u0000new";
    static final String[] MODES = {"Songs", "Albums", "Artists", "Genres", "Folders",
            "Playlists", "Favorites", "Queue"};

    static class Row {
        String title, sub, key;
        Song song;
    }

    private Store store;
    private PlayerService svc;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ArrayList<Song> all = new ArrayList<Song>();
    private final HashMap<String, Song> byUri = new HashMap<String, Song>();
    private final ArrayList<Row> rows = new ArrayList<Row>();
    private int mode = 0;
    private String group = null;
    private String query = "";
    private int page = 0;
    private boolean askedRec = false;
    private boolean dragging = false;

    private LinearLayout pageLib, pageNow, toolsRoot, mini;
    private ScrollView pageTools;
    private final TextView[] navs = new TextView[3];
    private final TextView[] chips = new TextView[MODES.length];
    private TextView backRow, emptyView, miniTitle, miniSub;
    private ImageButton miniPlay, nowPlay;
    private ListView list;
    private RowAdapter adapter;
    private VisualizerView vv;
    private ImageView artView;
    private TextView nowTitle, nowArtist, tCur, tTot, btnRepeat, btnShuffle, btnFav, sleepStatus;
    private SeekBar seek;

    // =============== lifecycle ===============

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        String cfg = store.getString("vcfg", null);
        if (cfg != null) vv.fromJson(cfg);
        io.execute(() -> {
            File f = new File(getFilesDir(), "bg.jpg");
            if (f.exists()) {
                Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bm != null) runOnUiThread(() -> vv.setBackgroundBitmap(bm));
            }
        });
        if (hasAudio()) {
            loadSongs();
        } else {
            requestPermissions(Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
        showPage(0);
        refreshRows();
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder binder) {
            svc = ((PlayerService.LocalBinder) binder).get();
            svc.setListener(MainActivity.this);
            svc.setVisualizerWanted(page == 1);
            refreshRows();
            refreshNow();
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            svc = null;
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, PlayerService.class), conn, Context.BIND_AUTO_CREATE);
        h.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        h.removeCallbacks(tick);
        if (svc != null) {
            svc.setListener(null);
            svc.setVisualizerWanted(false);
        }
        vv.setActive(false);
        unbindService(conn);
        svc = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (page != 0) {
            showPage(0);
        } else if (group != null) {
            group = null;
            refreshRows();
        } else {
            super.onBackPressed();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (svc != null && svc.current() != null) {
                int dur = svc.getDuration(), pos = svc.getPosition();
                seek.setMax(Math.max(1, dur));
                if (!dragging) {
                    seek.setProgress(pos);
                    tCur.setText(fmt(pos));
                }
                tTot.setText(fmt(dur));
            }
            if (page == 2 && svc != null && sleepStatus != null) {
                long left = svc.getSleepLeftMs();
                sleepStatus.setText(left > 0 ? "Sleep timer: " + (left / 60000 + 1) + " min baaki"
                        : "Sleep timer band hai");
            }
            h.postDelayed(this, 400);
        }
    };

    // =============== permissions ===============

    private boolean hasAudio() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasRec() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 1) {
            if (hasAudio()) loadSongs();
            else toast("Gaane dikhane ke liye audio permission zaroori hai");
        } else if (code == 3) {
            if (hasRec() && svc != null) svc.setVisualizerWanted(page == 1);
            else toast("Visualizer ke liye mic permission chahiye (sirf music ki frequency padhne ke liye)");
        }
    }

    // =============== data ===============

    private void loadSongs() {
        emptyView.setText("Gaane dhoond raha hu...");
        io.execute(() -> {
            ArrayList<Song> l = Song.loadAll(this);
            runOnUiThread(() -> {
                all = l;
                byUri.clear();
                for (Song s : l) byUri.put(s.uri, s);
                refreshRows();
            });
        });
    }

    private String keyOf(Song s, int m) {
        switch (m) {
            case 1: return s.album.isEmpty() ? "Unknown album" : s.album;
            case 2: return s.artist;
            case 3: return s.genre;
            default: return s.folder;
        }
    }

    private boolean matches(Song s, String q) {
        return s.title.toLowerCase(Locale.ROOT).contains(q)
                || s.artist.toLowerCase(Locale.ROOT).contains(q)
                || s.album.toLowerCase(Locale.ROOT).contains(q);
    }

    private ArrayList<Song> songsFromUris(List<String> uris) {
        ArrayList<Song> out = new ArrayList<Song>();
        for (String u : uris) {
            Song s = byUri.get(u);
            if (s != null) out.add(s);
        }
        return out;
    }

    private void refreshRows() {
        rows.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        ArrayList<Song> src;
        boolean groupList = false;

        if (mode == 0) {
            src = all;
        } else if (mode >= 1 && mode <= 4) {
            src = new ArrayList<Song>();
            if (group == null) {
                groupList = true;
            } else {
                for (Song s : all) if (keyOf(s, mode).equalsIgnoreCase(group)) src.add(s);
            }
        } else if (mode == 5) {
            src = new ArrayList<Song>();
            if (group == null) {
                for (String n : store.playlistNames()) {
                    if (!q.isEmpty() && !n.toLowerCase(Locale.ROOT).contains(q)) continue;
                    Row r = new Row();
                    r.title = n;
                    r.key = n;
                    r.sub = store.playlist(n).size() + " gaane";
                    rows.add(r);
                }
                Row nr = new Row();
                nr.title = "＋ Naya playlist banao";
                nr.sub = "";
                nr.key = NEW_KEY;
                rows.add(nr);
            } else {
                src = songsFromUris(store.playlist(group));
            }
        } else if (mode == 6) {
            src = new ArrayList<Song>();
            for (Song s : all) if (store.isFav(s.uri)) src.add(s);
        } else {
            src = svc != null ? new ArrayList<Song>(svc.getQueue()) : new ArrayList<Song>();
            q = "";
        }

        if (groupList) {
            TreeMap<String, Integer> counts = new TreeMap<String, Integer>(String.CASE_INSENSITIVE_ORDER);
            for (Song s : all) {
                String k = keyOf(s, mode);
                if (!q.isEmpty() && !k.toLowerCase(Locale.ROOT).contains(q)) continue;
                Integer c = counts.get(k);
                counts.put(k, c == null ? 1 : c + 1);
            }
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                Row r = new Row();
                r.title = e.getKey();
                r.key = e.getKey();
                r.sub = e.getValue() + " gaane";
                rows.add(r);
            }
        } else {
            for (Song s : src) {
                if (q.isEmpty() || matches(s, q)) {
                    Row r = new Row();
                    r.song = s;
                    r.title = s.title;
                    r.sub = s.artist + (s.album.isEmpty() ? "" : " • " + s.album);
                    rows.add(r);
                }
            }
        }

        backRow.setVisibility(group != null ? View.VISIBLE : View.GONE);
        if (group != null) backRow.setText("◀  " + group);
        for (int i = 0; i < chips.length; i++) {
            chips[i].setTextColor(i == mode ? BG : Color.WHITE);
            chips[i].setBackground(round(i == mode ? ACC : CARD, 40));
        }
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (rows.isEmpty()) {
            emptyView.setText(!hasAudio() ? "Audio permission do, phir gaane dikhenge"
                    : mode == 6 ? "Abhi koi favorite nahi (gaane par der dabake ♥ lagao)"
                    : mode == 7 ? "Queue khali hai"
                    : "Kuch nahi mila");
        }
        adapter.notifyDataSetChanged();
    }

    // =============== playback helpers ===============

    private void play(List<Song> l, int pos) {
        if (svc == null) {
            toast("Thoda ruko, player taiyaar ho raha hai");
            return;
        }
        startForegroundService(new Intent(this, PlayerService.class));
        svc.setQueue(l, pos);
    }

    @Override
    public void onState() {
        refreshNow();
        if (mode == 7) refreshRows();
    }

    @Override
    public void onFft(byte[] f) {
        vv.feedFft(f);
    }

    @Override
    public void onWave(byte[] w) {
        vv.feedWave(w);
    }

    private void refreshNow() {
        if (svc == null) return;
        Song s = svc.current();
        boolean pl = svc.isPlaying(), has = s != null;
        mini.setVisibility(has && page != 1 ? View.VISIBLE : View.GONE);
        if (has) {
            miniTitle.setText(s.title);
            miniSub.setText(s.artist);
            nowTitle.setText(s.title);
            nowArtist.setText(s.artist + (s.album.isEmpty() ? "" : " • " + s.album));
        } else {
            nowTitle.setText("Koi gaana nahi chal raha");
            nowArtist.setText("Library se gaana chuno");
        }
        Bitmap a = svc.getArt();
        if (a != null) artView.setImageBitmap(a);
        else artView.setImageResource(R.drawable.ic_logo);
        int ic = pl ? R.drawable.ic_pause : R.drawable.ic_play;
        miniPlay.setImageResource(ic);
        nowPlay.setImageResource(ic);
        btnRepeat.setText(svc.getRepeat() == 2 ? "↻1" : "↻");
        btnRepeat.setTextColor(svc.getRepeat() == 0 ? SUB : ACC);
        btnShuffle.setTextColor(svc.isShuffle() ? ACC : SUB);
        boolean fav = has && store.isFav(s.uri);
        btnFav.setText(fav ? "♥ Favorite" : "♡ Favorite");
        btnFav.setTextColor(fav ? PINK : SUB);
        vv.setActive(page == 1 && pl);
        adapter.notifyDataSetChanged();
    }

    private void showPage(int p) {
        page = p;
        pageLib.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        pageNow.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        pageTools.setVisibility(p == 2 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 3; i++) navs[i].setTextColor(i == p ? ACC : SUB);
        if (p == 2) buildTools();
        if (svc != null) svc.setVisualizerWanted(p == 1);
        if (p == 1 && !hasRec() && !askedRec) {
            askedRec = true;
            toast("Visualizer ke liye mic permission do (sirf music ki frequency padhne ke liye)");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 3);
        }
        refreshNow();
    }

    // =============== UI building ===============

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView tv(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private TextView button(String t, View.OnClickListener l) {
        TextView v = tv(t, 14, ACC);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(14), dp(10), dp(14), dp(10));
        v.setBackground(round(CARD, 12));
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private ImageButton iconBtn(int res, int sizeDp, int padDp) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setBackgroundColor(0);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    private TextView glyph(String t, View.OnClickListener l) {
        TextView v = tv(t, 22, SUB);
        v.setGravity(Gravity.CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        v.setOnClickListener(l);
        return v;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(10), dp(16), dp(6));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        head.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView title = tv(APP, 20, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(10), 0, 0, 0);
        head.addView(title);
        root.addView(head);

        // content
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        buildLibrary();
        buildNow();
        pageTools = new ScrollView(this);
        toolsRoot = new LinearLayout(this);
        toolsRoot.setOrientation(LinearLayout.VERTICAL);
        toolsRoot.setPadding(dp(16), dp(8), dp(16), dp(24));
        pageTools.addView(toolsRoot);
        content.addView(pageLib);
        content.addView(pageNow);
        content.addView(pageTools);

        // mini player
        mini = new LinearLayout(this);
        mini.setOrientation(LinearLayout.HORIZONTAL);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setBackgroundColor(CARD);
        mini.setPadding(dp(16), dp(6), dp(8), dp(6));
        mini.setVisibility(View.GONE);
        LinearLayout mt = new LinearLayout(this);
        mt.setOrientation(LinearLayout.VERTICAL);
        miniTitle = tv("", 15, Color.WHITE);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniSub = tv("", 12, SUB);
        miniSub.setSingleLine(true);
        miniSub.setEllipsize(TextUtils.TruncateAt.END);
        mt.addView(miniTitle);
        mt.addView(miniSub);
        mini.addView(mt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        miniPlay = iconBtn(R.drawable.ic_play, 48, 10);
        miniPlay.setOnClickListener(v -> { if (svc != null) svc.toggle(); });
        mini.addView(miniPlay);
        mini.setOnClickListener(v -> showPage(1));
        root.addView(mini);

        // bottom nav
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(0xFF110F24);
        String[] names = {"📚 Library", "🎧 Now", "⚙️ Tools"};
        for (int i = 0; i < 3; i++) {
            final int pi = i;
            navs[i] = tv(names[i], 14, SUB);
            navs[i].setGravity(Gravity.CENTER);
            navs[i].setPadding(0, dp(14), 0, dp(14));
            navs[i].setOnClickListener(v -> showPage(pi));
            nav.addView(navs[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(nav);

        setContentView(root);
    }

    private void buildLibrary() {
        pageLib = new LinearLayout(this);
        pageLib.setOrientation(LinearLayout.VERTICAL);
        pageLib.setPadding(dp(12), 0, dp(12), 0);

        EditText search = new EditText(this);
        search.setHint("Gaane, artist, album khojo");
        search.setHintTextColor(SUB);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.setBackground(round(CARD, 12));
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                query = e.toString();
                refreshRows();
            }
        });
        pageLib.addView(search);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout cr = new LinearLayout(this);
        cr.setOrientation(LinearLayout.HORIZONTAL);
        cr.setPadding(0, dp(8), 0, dp(8));
        for (int i = 0; i < MODES.length; i++) {
            final int mi = i;
            chips[i] = tv(MODES[i], 14, Color.WHITE);
            chips[i].setPadding(dp(14), dp(7), dp(14), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chips[i].setOnClickListener(v -> {
                mode = mi;
                group = null;
                refreshRows();
            });
            cr.addView(chips[i], lp);
        }
        hs.addView(cr);
        pageLib.addView(hs);

        backRow = tv("", 15, ACC);
        backRow.setPadding(dp(4), dp(6), dp(4), dp(6));
        backRow.setVisibility(View.GONE);
        backRow.setOnClickListener(v -> {
            group = null;
            refreshRows();
        });
        pageLib.addView(backRow);

        emptyView = tv("", 14, SUB);
        emptyView.setPadding(dp(8), dp(16), dp(8), dp(16));
        pageLib.addView(emptyView);

        list = new ListView(this);
        list.setDivider(null);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> onRowClick(pos));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            onRowLong(pos);
            return true;
        });
        pageLib.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) {
                LinearLayout l = new LinearLayout(MainActivity.this);
                l.setOrientation(LinearLayout.VERTICAL);
                l.setPadding(dp(6), dp(10), dp(6), dp(10));
                TextView t1 = tv("", 16, Color.WHITE);
                t1.setSingleLine(true);
                t1.setEllipsize(TextUtils.TruncateAt.END);
                TextView t2 = tv("", 13, SUB);
                t2.setSingleLine(true);
                t2.setEllipsize(TextUtils.TruncateAt.END);
                l.addView(t1);
                l.addView(t2);
                l.setTag(new TextView[]{t1, t2});
                v = l;
            }
            TextView[] tt = (TextView[]) v.getTag();
            Row r = rows.get(i);
            Song cur = svc != null ? svc.current() : null;
            boolean now = r.song != null && cur != null && cur.uri.equals(r.song.uri);
            boolean fav = r.song != null && store.isFav(r.song.uri);
            tt[0].setText((fav ? "♥ " : "") + r.title);
            tt[0].setTextColor(now ? ACC : Color.WHITE);
            tt[1].setText(r.sub);
            tt[1].setVisibility(r.sub == null || r.sub.isEmpty() ? View.GONE : View.VISIBLE);
            return v;
        }
    }

    private void onRowClick(int pos) {
        Row r = rows.get(pos);
        if (r.song != null) {
            if (mode == 7) {
                if (svc != null) svc.jumpTo(pos);
            } else {
                ArrayList<Song> l = new ArrayList<Song>();
                for (Row x : rows) if (x.song != null) l.add(x.song);
                play(l, l.indexOf(r.song));
            }
        } else if (NEW_KEY.equals(r.key)) {
            askNewPlaylist(null);
        } else {
            group = r.key;
            refreshRows();
        }
    }

    private void onRowLong(int pos) {
        final Row r = rows.get(pos);
        if (r.song != null) {
            songMenu(r.song, pos);
        } else if (mode == 5 && r.key != null && !NEW_KEY.equals(r.key)) {
            new AlertDialog.Builder(this).setTitle(r.title)
                    .setItems(new CharSequence[]{"Playlist delete karo"}, (d, w) -> {
                        store.deletePlaylist(r.key);
                        refreshRows();
                    }).show();
        }
    }

    private void songMenu(final Song s, final int pos) {
        final ArrayList<String> items = new ArrayList<String>();
        items.add(store.isFav(s.uri) ? "Favorite se hatao" : "Favorite me daalo");
        items.add("Playlist me daalo");
        items.add("Agla chalao");
        items.add("Queue me jodo");
        if (mode == 7) items.add("Queue se hatao");
        if (mode == 5 && group != null) items.add("Playlist se hatao");
        new AlertDialog.Builder(this).setTitle(s.title)
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    String it = items.get(which);
                    if (it.startsWith("Favorite")) {
                        store.toggleFav(s.uri);
                        refreshRows();
                        refreshNow();
                    } else if (it.equals("Playlist me daalo")) {
                        pickPlaylist(s);
                    } else if (it.equals("Agla chalao")) {
                        if (svc != null) {
                            startForegroundService(new Intent(this, PlayerService.class));
                            svc.playNext(s);
                        }
                    } else if (it.equals("Queue me jodo")) {
                        if (svc != null) {
                            startForegroundService(new Intent(this, PlayerService.class));
                            svc.addToQueue(s);
                        }
                    } else if (it.equals("Queue se hatao")) {
                        if (svc != null) svc.removeAt(pos);
                    } else if (it.equals("Playlist se hatao")) {
                        store.removeFromPlaylist(group, s.uri);
                        refreshRows();
                    }
                }).show();
    }

    private void pickPlaylist(final Song s) {
        final ArrayList<String> names = store.playlistNames();
        if (names.isEmpty()) {
            askNewPlaylist(s);
            return;
        }
        ArrayList<String> items = new ArrayList<String>(names);
        items.add("＋ Naya playlist");
        new AlertDialog.Builder(this).setTitle("Kaunsa playlist?")
                .setItems(items.toArray(new CharSequence[0]), (d, w) -> {
                    if (w < names.size()) {
                        store.addToPlaylist(names.get(w), s.uri);
                        toast("Playlist me joda gaya");
                    } else {
                        askNewPlaylist(s);
                    }
                }).show();
    }

    private void askNewPlaylist(final Song add) {
        final EditText et = new EditText(this);
        et.setHint("Playlist ka naam");
        new AlertDialog.Builder(this).setTitle("Naya playlist").setView(et)
                .setPositiveButton("Banao", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (n.isEmpty()) return;
                    store.createPlaylist(n);
                    if (add != null) store.addToPlaylist(n, add.uri);
                    refreshRows();
                }).setNegativeButton("Raho", null).show();
    }

    private void buildNow() {
        pageNow = new LinearLayout(this);
        pageNow.setOrientation(LinearLayout.VERTICAL);
        pageNow.setGravity(Gravity.CENTER_HORIZONTAL);
        pageNow.setPadding(dp(14), dp(4), dp(14), dp(4));
        pageNow.setVisibility(View.GONE);

        ViewOutlineProvider rounded = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(22));
            }
        };

        artView = new ImageView(this);
        artView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artView.setImageResource(R.drawable.ic_logo);
        artView.setOutlineProvider(rounded);
        artView.setClipToOutline(true);
        pageNow.addView(artView, new LinearLayout.LayoutParams(dp(190), dp(190)));

        vv = new VisualizerView(this);
        vv.setOutlineProvider(rounded);
        vv.setClipToOutline(true);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        vlp.topMargin = dp(10);
        vlp.bottomMargin = dp(8);
        pageNow.addView(vv, vlp);

        nowTitle = tv("Koi gaana nahi chal raha", 19, Color.WHITE);
        nowTitle.setTypeface(Typeface.DEFAULT_BOLD);
        nowTitle.setGravity(Gravity.CENTER);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        pageNow.addView(nowTitle);
        nowArtist = tv("Library se gaana chuno", 14, SUB);
        nowArtist.setGravity(Gravity.CENTER);
        nowArtist.setSingleLine(true);
        nowArtist.setEllipsize(TextUtils.TruncateAt.END);
        pageNow.addView(nowArtist);

        LinearLayout sr = new LinearLayout(this);
        sr.setOrientation(LinearLayout.HORIZONTAL);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        tCur = tv("0:00", 12, SUB);
        tTot = tv("0:00", 12, SUB);
        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) tCur.setText(fmt(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (svc != null) svc.seekTo(s.getProgress());
            }
        });
        sr.addView(tCur);
        sr.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sr.addView(tTot);
        pageNow.addView(sr);

        LinearLayout cr = new LinearLayout(this);
        cr.setOrientation(LinearLayout.HORIZONTAL);
        cr.setGravity(Gravity.CENTER);
        btnShuffle = glyph("⇄", v -> { if (svc != null) svc.toggleShuffle(); });
        ImageButton prev = iconBtn(R.drawable.ic_prev, 52, 12);
        prev.setOnClickListener(v -> { if (svc != null) svc.previous(); });
        nowPlay = iconBtn(R.drawable.ic_play, 66, 16);
        nowPlay.setBackground(round(ACC, 33));
        nowPlay.setColorFilter(BG);
        nowPlay.setOnClickListener(v -> { if (svc != null) svc.toggle(); });
        ImageButton next = iconBtn(R.drawable.ic_next, 52, 12);
        next.setOnClickListener(v -> { if (svc != null) svc.next(false); });
        btnRepeat = glyph("↻", v -> { if (svc != null) svc.cycleRepeat(); });
        cr.addView(btnShuffle);
        cr.addView(prev);
        cr.addView(nowPlay);
        cr.addView(next);
        cr.addView(btnRepeat);
        pageNow.addView(cr);

        LinearLayout er = new LinearLayout(this);
        er.setOrientation(LinearLayout.HORIZONTAL);
        er.setGravity(Gravity.CENTER);
        btnFav = tv("♡ Favorite", 14, SUB);
        btnFav.setPadding(dp(12), dp(8), dp(12), dp(8));
        btnFav.setOnClickListener(v -> {
            Song s = svc != null ? svc.current() : null;
            if (s != null) {
                store.toggleFav(s.uri);
                refreshNow();
            }
        });
        TextView style = tv("◈ Style", 14, SUB);
        style.setPadding(dp(12), dp(8), dp(12), dp(8));
        style.setOnClickListener(v -> {
            vv.lStyle[0] = vv.lStyle[0] % 5 + 1;
            cfgChanged();
            toast("Layer 1: " + VisualizerView.STYLE_NAMES[vv.lStyle[0]]);
        });
        TextView qv = tv("☰ Queue", 14, SUB);
        qv.setPadding(dp(12), dp(8), dp(12), dp(8));
        qv.setOnClickListener(v -> {
            mode = 7;
            group = null;
            showPage(0);
            refreshRows();
        });
        er.addView(btnFav);
        er.addView(style);
        er.addView(qv);
        pageNow.addView(er);
    }

    // =============== tools page ===============

    private void cfgChanged() {
        vv.postInvalidateOnAnimation();
        store.putString("vcfg", vv.toJson());
    }

    private TextView section(String t) {
        TextView v = tv(t, 17, ACC);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(20), 0, dp(4));
        toolsRoot.addView(v);
        return v;
    }

    private void slider(String label, int max, int val, IntConsumer cb) {
        toolsRoot.addView(tv(label, 13, SUB));
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(val);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) cb.accept(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        toolsRoot.addView(sb);
    }

    private void chipRow(String[] names, int selected, IntConsumer cb) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] cs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int ci = i;
            cs[i] = tv(names[i], 14, i == selected ? BG : Color.WHITE);
            cs[i].setBackground(round(i == selected ? ACC : CARD, 40));
            cs[i].setPadding(dp(14), dp(7), dp(14), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            cs[i].setOnClickListener(v -> {
                for (int k = 0; k < cs.length; k++) {
                    cs[k].setTextColor(k == ci ? BG : Color.WHITE);
                    cs[k].setBackground(round(k == ci ? ACC : CARD, 40));
                }
                cb.accept(ci);
            });
            row.addView(cs[i], lp);
        }
        hs.addView(row);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(6);
        hl.bottomMargin = dp(6);
        toolsRoot.addView(hs, hl);
    }

    private void buildTools() {
        toolsRoot.removeAllViews();

        // ---- visualizer ----
        section("Visualizer");
        slider("Sensitivity", 100, vv.sens, v -> { vv.sens = v; cfgChanged(); });
        slider("Size", 100, vv.size, v -> { vv.size = v; cfgChanged(); });
        slider("Thickness", 100, vv.thick, v -> { vv.thick = v; cfgChanged(); });
        slider("Speed", 100, vv.speed, v -> { vv.speed = v; cfgChanged(); });
        slider("Intensity (glow)", 100, vv.inten, v -> { vv.inten = v; cfgChanged(); });
        toolsRoot.addView(tv("Color theme", 13, SUB));
        chipRow(VisualizerView.THEME_NAMES, vv.theme, t -> { vv.setTheme(t); cfgChanged(); });

        for (int i = 0; i < 3; i++) {
            final int li = i;
            TextView lt = tv("Layer " + (i + 1), 15, Color.WHITE);
            lt.setPadding(0, dp(14), 0, 0);
            toolsRoot.addView(lt);
            Spinner sp = new Spinner(this);
            ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_item, VisualizerView.STYLE_NAMES);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(ad);
            sp.setSelection(vv.lStyle[i]);
            sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    if (vv.lStyle[li] != pos) {
                        vv.lStyle[li] = pos;
                        cfgChanged();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> p) { }
            });
            toolsRoot.addView(sp);
            slider("Position left-right", 100, vv.lx[i] + 50, v -> { vv.lx[li] = v - 50; cfgChanged(); });
            slider("Position upar-neeche", 100, vv.ly[i] + 50, v -> { vv.ly[li] = v - 50; cfgChanged(); });
        }

        toolsRoot.addView(button("🖼  Background image chuno", v -> {
            Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            in.addCategory(Intent.CATEGORY_OPENABLE);
            in.setType("image/*");
            startActivityForResult(in, 10);
        }));
        toolsRoot.addView(button("Background hatao", v -> {
            vv.setBackgroundBitmap(null);
            new File(getFilesDir(), "bg.jpg").delete();
        }));

        // ---- presets ----
        section("Visualizer presets");
        toolsRoot.addView(button("Preset save karo", v -> {
            final EditText et = new EditText(this);
            et.setHint("Preset ka naam");
            new AlertDialog.Builder(this).setTitle("Preset save").setView(et)
                    .setPositiveButton("Save", (d, w) -> {
                        String n = et.getText().toString().trim();
                        if (!n.isEmpty()) {
                            store.savePreset(n, vv.toJson());
                            toast("Preset save ho gaya");
                        }
                    }).setNegativeButton("Raho", null).show();
        }));
        toolsRoot.addView(button("Mere presets", v -> presetsDialog()));
        toolsRoot.addView(button("Preset import (clipboard se)", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm.getPrimaryClip();
            String txt = cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).getText() != null
                    ? cd.getItemAt(0).getText().toString() : "";
            if (vv.fromJson(txt)) {
                cfgChanged();
                buildTools();
                toast("Preset import ho gaya");
            } else {
                toast("Clipboard me sahi preset nahi mila");
            }
        }));

        // ---- equalizer ----
        section("Equalizer");
        if (svc != null && svc.hasEq()) {
            chipRow(PlayerService.EQ_PRESETS, indexOf(PlayerService.EQ_PRESETS,
                    store.getString("eq_preset", "Flat")), i -> {
                svc.applyEqPreset(PlayerService.EQ_PRESETS[i]);
                buildTools();
            });
            final int min = svc.eqMin();
            for (int b = 0; b < svc.eqBands(); b++) {
                final int bi = b;
                int hz = svc.eqCenterHz(b);
                String lab = hz >= 1000 ? (hz / 1000f) + " kHz" : hz + " Hz";
                slider(lab, svc.eqMax() - min, svc.eqLevel(b) - min, v -> svc.setEqLevel(bi, v + min));
            }
            slider("Bass boost", 1000, svc.getBass(), v -> svc.setBass(v));
        } else {
            toolsRoot.addView(tv("Pehle koi gaana chalao, phir equalizer yahan dikhega.", 13, SUB));
        }

        // ---- sleep timer ----
        section("Sleep timer");
        sleepStatus = tv("Sleep timer band hai", 13, SUB);
        toolsRoot.addView(sleepStatus);
        final int[] mins = {15, 30, 45, 60, 0};
        chipRow(new String[]{"15 min", "30 min", "45 min", "60 min", "Off"}, 4, i -> {
            if (svc != null) {
                svc.setSleep(mins[i]);
                toast(mins[i] == 0 ? "Sleep timer band" : "Sleep timer " + mins[i] + " min ka laga");
            }
        });

        // ---- other ----
        section("Aur");
        toolsRoot.addView(button("📂  Koi bhi audio/video file kholo", v -> {
            Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            in.addCategory(Intent.CATEGORY_OPENABLE);
            in.setType("*/*");
            in.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
            startActivityForResult(in, 11);
        }));
        toolsRoot.addView(button("↻  Library dobara scan karo", v -> loadSongs()));
        toolsRoot.addView(button("■  Player band karo (notification hatao)", v -> {
            if (svc != null) svc.stopAll();
        }));
        TextView about = tv(APP + "\nA modern music player, audio visualizer and music-video creator.\n"
                + "Music Video Maker: agle version me aayega.", 12, SUB);
        about.setPadding(0, dp(20), 0, 0);
        toolsRoot.addView(about);
    }

    private int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
        return 0;
    }

    private void presetsDialog() {
        final ArrayList<String> names = store.presetNames();
        if (names.isEmpty()) {
            toast("Abhi koi preset save nahi hai");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Mere presets")
                .setItems(names.toArray(new CharSequence[0]), (d, w) -> {
                    final String n = names.get(w);
                    new AlertDialog.Builder(this).setTitle(n)
                            .setItems(new CharSequence[]{"Lagao", "Export (copy karo)", "Delete"}, (d2, w2) -> {
                                if (w2 == 0) {
                                    String js = store.getPreset(n);
                                    if (js != null && vv.fromJson(js)) {
                                        cfgChanged();
                                        buildTools();
                                    }
                                } else if (w2 == 1) {
                                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                    cm.setPrimaryClip(ClipData.newPlainText("nixo-preset", store.getPreset(n)));
                                    toast("Preset copy ho gaya, kahin bhi paste karke bhej do");
                                } else {
                                    store.deletePreset(n);
                                }
                            }).show();
                }).show();
    }

    // =============== file pickers ===============

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        if (req == 10) {
            io.execute(() -> {
                Bitmap bm = decodeSampled(u, 1080);
                if (bm == null) {
                    runOnUiThread(() -> toast("Image khul nahi paayi"));
                    return;
                }
                try (FileOutputStream fo = new FileOutputStream(new File(getFilesDir(), "bg.jpg"))) {
                    bm.compress(Bitmap.CompressFormat.JPEG, 90, fo);
                } catch (Exception ignored) { }
                runOnUiThread(() -> vv.setBackgroundBitmap(bm));
            });
        } else if (req == 11) {
            Song s = new Song();
            s.uri = u.toString();
            s.artist = "Device file";
            s.title = "File";
            try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (i >= 0 && c.getString(i) != null) s.title = c.getString(i);
                }
            } catch (Exception ignored) { }
            byUri.put(s.uri, s);
            play(Collections.singletonList(s), 0);
        }
    }

    private Bitmap decodeSampled(Uri u, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // =============== small helpers ===============

    private static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
