package com.github.yutaplug.instantmessages;

import android.content.Context;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.discord.models.message.Message;
import com.discord.utilities.view.text.SimpleDraweeSpanTextView;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage;
import com.discord.widgets.chat.list.entries.MessageEntry;

/** Removes Discord's translucent pending-message state from outgoing chat text. */
@SuppressWarnings("unused")
@AliucordPlugin
public final class InstantMessages extends Plugin {
    private static final int PENDING_MESSAGE_TYPE = -1;

    @Override
    public void start(Context context) throws Throwable {
        patcher.patch(
                WidgetChatListAdapterItemMessage.class,
                "processMessageText",
                new Class<?>[]{SimpleDraweeSpanTextView.class, MessageEntry.class},
                new Hook(frame -> {
                    if (!(frame.args[0] instanceof SimpleDraweeSpanTextView)
                            || !(frame.args[1] instanceof MessageEntry)) return;

                    Message message = ((MessageEntry) frame.args[1]).getMessage();
                    Integer type = message.getType();
                    if (type != null && type.intValue() == PENDING_MESSAGE_TYPE) {
                        // Discord sets pending local-message text to 0.5f until the
                        // acknowledgement arrives. Keep it visually instant while
                        // leaving the normal send/acknowledgement flow untouched.
                        ((SimpleDraweeSpanTextView) frame.args[0]).setAlpha(1.0f);
                    }
                })
        );
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}
