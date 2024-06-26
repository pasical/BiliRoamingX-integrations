package app.revanced.bilibili.patches;

import androidx.annotation.Keep;

import app.revanced.bilibili.settings.Settings;

public class FavFolderDialogPatch {
    @Keep
    public static boolean shouldChecked(boolean original) {
        if (Settings.DisableAutoSubscribe.get())
            return false;
        return original;
    }
}
