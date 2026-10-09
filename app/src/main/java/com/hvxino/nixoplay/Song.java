package com.hvxino.nixoplay;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;

public class Song {
    public long id;
    public String title = "Unknown";
    public String artist = "Unknown artist";
    public String album = "";
    public String genre = "Unknown";
    public String folder = "Unknown";
    public String uri = "";
    public long duration;

    private static String nz(String s, String d) {
        if (s == null || s.trim().isEmpty() || s.equals("<unknown>")) return d;
        return s;
    }

    /** Device ke saare music MediaStore se ek baar me padhta hai (background thread me chalao). */
    public static ArrayList<Song> loadAll(Context c) {
        ArrayList<Song> out = new ArrayList<>();
        ArrayList<String> proj = new ArrayList<>();
        proj.add(MediaStore.Audio.Media._ID);
        proj.add(MediaStore.Audio.Media.TITLE);
        proj.add(MediaStore.Audio.Media.ARTIST);
        proj.add(MediaStore.Audio.Media.ALBUM);
        proj.add(MediaStore.Audio.Media.DURATION);
        proj.add(MediaStore.Audio.Media.DATA);
        if (Build.VERSION.SDK_INT >= 30) proj.add(MediaStore.Audio.Media.GENRE);
        String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND "
                + MediaStore.Audio.Media.DURATION + " >= 10000";
        try (Cursor cur = c.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                proj.toArray(new String[0]), sel, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (cur == null) return out;
            int iId = cur.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int iTitle = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int iArtist = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int iAlbum = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int iDur = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int iData = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            int iGenre = Build.VERSION.SDK_INT >= 30
                    ? cur.getColumnIndex(MediaStore.Audio.Media.GENRE) : -1;
            while (cur.moveToNext()) {
                Song s = new Song();
                s.id = cur.getLong(iId);
                s.title = nz(cur.getString(iTitle), "Unknown");
                s.artist = nz(cur.getString(iArtist), "Unknown artist");
                s.album = nz(cur.getString(iAlbum), "");
                s.duration = cur.getLong(iDur);
                if (iGenre >= 0) s.genre = nz(cur.getString(iGenre), "Unknown");
                String path = cur.getString(iData);
                if (path != null) {
                    File dir = new File(path).getParentFile();
                    if (dir != null) {
                        File up = dir.getParentFile();
                        s.folder = (up != null ? up.getName() + "/" : "") + dir.getName();
                    }
                }
                s.uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id).toString();
                out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
