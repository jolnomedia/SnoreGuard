package com.local.snoreguard;
import android.content.Context;
import android.content.SharedPreferences;
public final class Prefs {
    private Prefs() {}
    public static SharedPreferences get(Context c) { return c.getSharedPreferences("snore_guard_settings", Context.MODE_PRIVATE); }
    public static int sensitivity(Context c) { return get(c).getInt("sensitivity", 3); }
    public static int step(Context c) { return get(c).getInt("step", 2); }
    public static int cap(Context c) { return get(c).getInt("cap", 70); }
    public static int restoreSeconds(Context c) { return get(c).getInt("restore", 90); }
    public static boolean builtInMic(Context c) { return get(c).getBoolean("built_in_mic", true); }
}
