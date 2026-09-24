package com.github.yutaplug.instantmessages;

import android.content.Context;
import android.view.WindowInsetsAnimation;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.patcher.PreHook;
import com.discord.models.message.Message;
import com.discord.utilities.view.text.SimpleDraweeSpanTextView;
import com.discord.widgets.chat.input.SmoothKeyboardReactionHelper;
import com.discord.widgets.chat.list.WidgetChatList;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage;
import com.discord.widgets.chat.list.entries.ChatListEntry;
import com.discord.widgets.chat.list.entries.MessageEntry;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Makes outgoing messages appear immediately without chat-list animations. */
@AliucordPlugin
public final class InstantMessages extends Plugin {
    private static final int PENDING_MESSAGE_TYPE = -1;
    private static final long ACK_TIMESTAMP_TOLERANCE_MS = 5000L;
    private static final long TRANSIENT_DATA_DELAY_MS = 500L;

    private static final class DeferredData {
        private final WidgetChatListAdapter.Data data;
        private final Runnable callback;

        private DeferredData(WidgetChatListAdapter.Data data, Runnable callback) {
            this.data = data;
            this.callback = callback;
        }
    }

    private final WeakHashMap<WidgetChatListAdapter, Long> newestMessageIds = new WeakHashMap<>();
    private final WeakHashMap<WidgetChatListAdapter, DeferredData> deferredData = new WeakHashMap<>();
    private WidgetChatListAdapter applyingDeferredAdapter;

    @Override
    public void start(Context context) throws Throwable {
        patcher.patch(
                SmoothKeyboardReactionHelper.Callback.class,
                "onStart",
                new Class<?>[]{WindowInsetsAnimation.class, WindowInsetsAnimation.Bounds.class},
                new PreHook(frame -> {
                    // Do not translate the chat/input views while the IME opens
                    // or closes; the normal insets update can happen instantly.
                    frame.setResult(frame.args[1]);
                })
        );
        patcher.patch(
                WidgetChatList.class,
                "onViewBoundOrOnResume",
                new Hook(frame -> disableItemAnimations((WidgetChatList) frame.thisObject))
        );
        patcher.patch(
                WidgetChatList.class,
                "enableItemAnimations",
                new Hook(frame -> disableItemAnimations((WidgetChatList) frame.thisObject))
        );
        patcher.patch(
                WidgetChatListAdapter.class,
                "setData",
                new Class<?>[]{WidgetChatListAdapter.Data.class},
                new PreHook(frame -> {
                    if (!(frame.thisObject instanceof WidgetChatListAdapter)
                            || !(frame.args[0] instanceof WidgetChatListAdapter.Data)) return;

                    WidgetChatListAdapter adapter = (WidgetChatListAdapter) frame.thisObject;
                    if (applyingDeferredAdapter == adapter) {
                        keepOutgoingMessageAtBottom(adapter, (WidgetChatListAdapter.Data) frame.args[0]);
                        return;
                    }
                    if (deferTransientLocalRemoval(adapter, (WidgetChatListAdapter.Data) frame.args[0])) {
                        // Discord publishes local-message removal before the acknowledged
                        // server message. Keep the old row until the replacement arrives.
                        frame.setResult(null);
                        return;
                    }
                    keepOutgoingMessageAtBottom(adapter, (WidgetChatListAdapter.Data) frame.args[0]);
                })
        );
        patcher.patch(
                WidgetChatListAdapterItemMessage.class,
                "processMessageText",
                new Class<?>[]{SimpleDraweeSpanTextView.class, MessageEntry.class},
                new Hook(frame -> {
                    if (!(frame.args[0] instanceof SimpleDraweeSpanTextView)
                            || !(frame.args[1] instanceof MessageEntry)) return;

                    Message message = ((MessageEntry) frame.args[1]).getMessage();
                    if (isPending(message)) {
                        ((SimpleDraweeSpanTextView) frame.args[0]).setAlpha(1.0f);
                    }
                })
        );
    }

    private void keepOutgoingMessageAtBottom(
            WidgetChatListAdapter adapter,
            WidgetChatListAdapter.Data data
    ) {
        List<ChatListEntry> entries = data.getList();
        MessageEntry newestEntry = findNewestMessage(entries);
        boolean hasPendingMessage = containsPendingMessage(entries);
        if (newestEntry == null) {
            newestMessageIds.remove(adapter);
            return;
        }

        Message newestMessage = newestEntry.getMessage();
        long newestMessageId = newestMessage.getId();
        Long previousMessageId = newestMessageIds.put(adapter, newestMessageId);
        boolean newestMessageChanged = previousMessageId == null
                || previousMessageId.longValue() != newestMessageId;
        boolean newestMessageIsOutgoing = isFromCurrentUser(newestMessage, data.getUserId());
        if (!hasPendingMessage && (!newestMessageChanged || !newestMessageIsOutgoing)) return;

        forceChatBottom(adapter);
    }

    private boolean deferTransientLocalRemoval(
            WidgetChatListAdapter adapter,
            WidgetChatListAdapter.Data incomingData
    ) {
        WidgetChatListAdapter.Data currentData = adapter.getData();
        if (currentData == null || currentData.getChannelId() != incomingData.getChannelId()) {
            cancelDeferredData(adapter);
            return false;
        }

        boolean transientUpdate = containsPendingAcknowledgedDuplicate(incomingData.getList())
                || hasRemovedPendingMessage(currentData.getList(), incomingData.getList());
        if (!transientUpdate) {
            cancelDeferredData(adapter);
            return false;
        }

        deferData(adapter, incomingData);
        return true;
    }

    private static boolean containsPendingAcknowledgedDuplicate(List<ChatListEntry> entries) {
        for (ChatListEntry entry : entries) {
            if (!(entry instanceof MessageEntry)) continue;

            Message message = ((MessageEntry) entry).getMessage();
            if (isPending(message) && isAcknowledgedReplacement(entries, message)) {
                return true;
            }
        }
        return false;
    }

    private void deferData(
            WidgetChatListAdapter adapter,
            WidgetChatListAdapter.Data data
    ) {
        DeferredData previous = deferredData.remove(adapter);
        if (previous != null) adapter.getRecycler().removeCallbacks(previous.callback);

        Runnable callback = () -> applyDeferredData(adapter);
        deferredData.put(adapter, new DeferredData(data, callback));
        adapter.getRecycler().postDelayed(callback, TRANSIENT_DATA_DELAY_MS);
    }

    private void applyDeferredData(WidgetChatListAdapter adapter) {
        DeferredData deferred = deferredData.remove(adapter);
        if (deferred == null) return;

        adapter.getRecycler().removeCallbacks(deferred.callback);
        applyingDeferredAdapter = adapter;
        try {
            adapter.setData(deferred.data);
        } finally {
            applyingDeferredAdapter = null;
        }
    }

    private void cancelDeferredData(WidgetChatListAdapter adapter) {
        DeferredData deferred = deferredData.remove(adapter);
        if (deferred != null) adapter.getRecycler().removeCallbacks(deferred.callback);
    }

    private static boolean hasRemovedPendingMessage(
            List<ChatListEntry> currentEntries,
            List<ChatListEntry> incomingEntries
    ) {
        for (ChatListEntry currentEntry : currentEntries) {
            if (!(currentEntry instanceof MessageEntry)) continue;

            Message pendingMessage = ((MessageEntry) currentEntry).getMessage();
            if (!isPending(pendingMessage)) continue;
            if (!containsMessage(incomingEntries, pendingMessage)
                    && !isAcknowledgedReplacement(incomingEntries, pendingMessage)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMessage(List<ChatListEntry> entries, Message target) {
        String targetNonce = target.getNonce();
        for (ChatListEntry entry : entries) {
            if (!(entry instanceof MessageEntry)) continue;

            Message message = ((MessageEntry) entry).getMessage();
            if (targetNonce != null && targetNonce.equals(message.getNonce())) return true;
            if (target.getId() == message.getId()) return true;
        }
        return false;
    }

    private static boolean isAcknowledgedReplacement(
            List<ChatListEntry> entries,
            Message pendingMessage
    ) {
        for (ChatListEntry entry : entries) {
            if (!(entry instanceof MessageEntry)) continue;

            Message candidate = ((MessageEntry) entry).getMessage();
            if (isPending(candidate)) continue;
            String pendingNonce = pendingMessage.getNonce();
            if (pendingNonce != null && pendingNonce.equals(candidate.getNonce())) return true;
            if (candidate.getId() == pendingMessage.getId()) return true;
            if (candidate.getChannelId() != pendingMessage.getChannelId()) continue;
            if (candidate.getAuthor() == null || pendingMessage.getAuthor() == null
                    || candidate.getAuthor().getId() != pendingMessage.getAuthor().getId()) continue;

            long candidateTimestamp = candidate.getTimestamp() != null
                    ? candidate.getTimestamp().g() : 0L;
            long pendingTimestamp = pendingMessage.getTimestamp() != null
                    ? pendingMessage.getTimestamp().g() : 0L;
            if (candidateTimestamp > 0L && pendingTimestamp > 0L
                    && candidateTimestamp + ACK_TIMESTAMP_TOLERANCE_MS < pendingTimestamp) continue;

            String candidateContent = candidate.getContent();
            String pendingContent = pendingMessage.getContent();
            if (candidateContent == null ? pendingContent == null : candidateContent.equals(pendingContent)) {
                return true;
            }
        }
        return false;
    }

    private static MessageEntry findNewestMessage(List<ChatListEntry> entries) {
        for (ChatListEntry entry : entries) {
            if (entry instanceof MessageEntry) return (MessageEntry) entry;
        }
        return null;
    }

    private static boolean containsPendingMessage(List<ChatListEntry> entries) {
        for (ChatListEntry entry : entries) {
            if (!(entry instanceof MessageEntry)) continue;

            if (isPending(((MessageEntry) entry).getMessage())) return true;
        }
        return false;
    }

    private static boolean isPending(Message message) {
        Integer type = message.getType();
        return type != null && type.intValue() == PENDING_MESSAGE_TYPE;
    }

    private static boolean isFromCurrentUser(Message message, long userId) {
        return message.getAuthor() != null && message.getAuthor().getId() == userId;
    }

    private static void forceChatBottom(WidgetChatListAdapter adapter) {
        LinearLayoutManager layoutManager = adapter.getLayoutManager();
        if (layoutManager == null) return;

        adapter.getRecycler().stopScroll();
        adapter.getRecycler().setItemAnimator(null);
        layoutManager.scrollToPositionWithOffset(0, 0);
    }

    private static void disableItemAnimations(WidgetChatList chatList) {
        chatList.disableItemAnimations();
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
        for (Map.Entry<WidgetChatListAdapter, DeferredData> entry : deferredData.entrySet()) {
            entry.getKey().getRecycler().removeCallbacks(entry.getValue().callback);
        }
        deferredData.clear();
        applyingDeferredAdapter = null;
        newestMessageIds.clear();
    }
}
