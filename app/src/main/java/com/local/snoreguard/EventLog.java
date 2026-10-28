package com.local.snoreguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Persistent event metadata only. Never stores audio or raw samples. */
public final class EventLog {
    private static final String PREFS = "night_event_log";
    private static final String KEY = "entries";
    private static final int MAX_ENTRIES = 500;
    private static final String SEPARATOR = "\u001e";

    private EventLog() {}

    public static synchronized void add(Context context, String category, String detail) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String entry = time + SEPARATOR + category + SEPARATOR + detail.replace(SEPARATOR, " ");
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> entries = readEntries(prefs);
        entries.add(0, entry);
        if (entries.size() > MAX_ENTRIES) entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
        prefs.edit().putString(KEY, join(entries)).apply();
    }

    public static synchronized List<String> formatted(Context context) {
        List<String> result = new ArrayList<>();
        for (String entry : readEntries(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))) {
            String[] parts = entry.split(SEPARATOR, -1);
            if (parts.length == 3) result.add(parts[0] + "\n" + parts[1] + " · " + parts[2]);
        }
        return result;
    }

    public static synchronized String export(Context context) {
        StringBuilder text = new StringBuilder("鼾声守护 · 夜间日志\n仅含事件与音量元数据，不含录音。\n\n");
        for (String line : formatted(context)) text.append(line).append("\n\n");
        return text.toString();
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static List<String> readEntries(SharedPreferences prefs) {
        String stored = prefs.getString(KEY, "");
        List<String> entries = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return entries;
        for (String entry : stored.split("\n")) if (!entry.isEmpty()) entries.add(entry);
        return entries;
    }

    private static String join(List<String> entries) {
        StringBuilder stored = new StringBuilder();
        for (String entry : entries) {
            if (stored.length() > 0) stored.append('\n');
            stored.append(entry.replace('\n', ' ').replace('\r', ' '));
        }
        return stored.toString();
    }
}
