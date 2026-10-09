package com.hvxino.nixoplay;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Favorites, playlists, visualizer presets aur settings ka chhota storage. */
public class Store {
    private final SharedPreferences sp;
    private final HashSet<String> favs = new HashSet<String>();

    public Store(Context c) {
        sp = c.getSharedPreferences("nixo", Context.MODE_PRIVATE);
        favs.addAll(sp.getStringSet("favs", new HashSet<String>()));
    }

    // ----- favorites -----
    public boolean isFav(String uri) {
        return favs.contains(uri);
    }

    public boolean toggleFav(String uri) {
        boolean now;
        if (favs.contains(uri)) {
            favs.remove(uri);
            now = false;
        } else {
            favs.add(uri);
            now = true;
        }
        sp.edit().putStringSet("favs", new HashSet<String>(favs)).apply();
        return now;
    }

    // ----- playlists -----
    public ArrayList<String> playlistNames() {
        ArrayList<String> l = new ArrayList<String>(sp.getStringSet("pl_names", new HashSet<String>()));
        Collections.sort(l, String.CASE_INSENSITIVE_ORDER);
        return l;
    }

    public void createPlaylist(String name) {
        Set<String> s = new HashSet<String>(sp.getStringSet("pl_names", new HashSet<String>()));
        s.add(name);
        SharedPreferences.Editor e = sp.edit().putStringSet("pl_names", s);
        if (!sp.contains("pl:" + name)) e.putString("pl:" + name, "");
        e.apply();
    }

    public ArrayList<String> playlist(String name) {
        String raw = sp.getString("pl:" + name, "");
        ArrayList<String> out = new ArrayList<String>();
        if (raw != null && !raw.isEmpty()) out.addAll(Arrays.asList(raw.split("\n")));
        return out;
    }

    private void savePlaylist(String name, ArrayList<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(l.get(i));
        }
        sp.edit().putString("pl:" + name, sb.toString()).apply();
    }

    public void addToPlaylist(String name, String uri) {
        createPlaylist(name);
        ArrayList<String> l = playlist(name);
        if (!l.contains(uri)) {
            l.add(uri);
            savePlaylist(name, l);
        }
    }

    public void removeFromPlaylist(String name, String uri) {
        ArrayList<String> l = playlist(name);
        l.remove(uri);
        savePlaylist(name, l);
    }

    public void deletePlaylist(String name) {
        Set<String> s = new HashSet<String>(sp.getStringSet("pl_names", new HashSet<String>()));
        s.remove(name);
        sp.edit().putStringSet("pl_names", s).remove("pl:" + name).apply();
    }

    // ----- visualizer presets -----
    public ArrayList<String> presetNames() {
        ArrayList<String> l = new ArrayList<String>(sp.getStringSet("vp_names", new HashSet<String>()));
        Collections.sort(l, String.CASE_INSENSITIVE_ORDER);
        return l;
    }

    public void savePreset(String name, String json) {
        Set<String> s = new HashSet<String>(sp.getStringSet("vp_names", new HashSet<String>()));
        s.add(name);
        sp.edit().putStringSet("vp_names", s).putString("vp:" + name, json).apply();
    }

    public String getPreset(String name) {
        return sp.getString("vp:" + name, null);
    }

    public void deletePreset(String name) {
        Set<String> s = new HashSet<String>(sp.getStringSet("vp_names", new HashSet<String>()));
        s.remove(name);
        sp.edit().putStringSet("vp_names", s).remove("vp:" + name).apply();
    }

    // ----- simple settings -----
    public int getInt(String k, int d) {
        return sp.getInt(k, d);
    }

    public void putInt(String k, int v) {
        sp.edit().putInt(k, v).apply();
    }

    public String getString(String k, String d) {
        return sp.getString(k, d);
    }

    public void putString(String k, String v) {
        sp.edit().putString(k, v).apply();
    }
}
