package com.github.yutaplug.searchhistoryfix;

import android.content.Context;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.PreHook;
import com.discord.utilities.persister.Persister;

/** Makes Discord's search-history clear operation survive an app restart. */
@AliucordPlugin
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
public final class SearchHistoryFix extends Plugin {
    private static final String SEARCH_HISTORY_KEY = "SEARCH_HISTORY_V2";

    @Override
    public void start(Context context) throws Throwable {
        // Discord calls Persister.set(..., false) for SEARCH_HISTORY_V2. That only
        // marks the value dirty; if the app is restarted before the next global
        // persistence pass, clearing history is lost and the old file comes back.
        patcher.patch(
                Persister.class,
                "set",
                new Class<?>[]{Object.class, boolean.class},
                new PreHook(frame -> {
                    Persister persister = (Persister) frame.thisObject;
                    if (SEARCH_HISTORY_KEY.equals(persister.getKey())) {
                        frame.args[1] = true;
                    }
                })
        );
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}
