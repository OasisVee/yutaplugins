package com.github.yutaplug.irc;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Guideline;

import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.discord.api.user.User;
import com.discord.models.member.GuildMember;
import com.discord.utilities.color.ColorCompat;
import com.discord.views.ReactionView;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage;
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemReactions;
import com.discord.widgets.chat.list.adapter.WidgetChatListItem;
import com.discord.widgets.chat.list.entries.ChatListEntry;
import com.discord.widgets.chat.list.entries.MessageEntry;
import com.lytefast.flexinput.R;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Makes the mobile chat list resemble Discord desktop's IRC compact mode.
 *
 * <p>Discord's normal mobile message layouts put the body below the author
 * header. IRC mode keeps the author on the same line as the body and repeats
 * that author on minimal/grouped messages. The original text and image views
 * are reused so Discord's message spans, click handlers, and accessibility
 * behavior remain intact.</p>
 */
@SuppressWarnings("unused")
@AliucordPlugin(requiresRestart = true)
public final class IRC extends Plugin {
    private static final int NO_CONSTRAINT = -1;

    // These are deliberately fixed columns, like desktop compact mode. The
    // same body start is used for normal and minimal rows so grouped messages
    // line up with the first message in their group.
    private static final int DEFAULT_TIMESTAMP_WIDTH_DP = 32;
    private static final int TIMESTAMP_END_PADDING_DP = 4;
    private static final int AVATAR_GAP_DP = 6;
    private static final int AVATAR_SIZE_DP = 24;
    private static final int NAME_GAP_DP = 6;
    private static final int BODY_GAP_DP = 4;
    private static final int BODY_BOTTOM_PADDING_DP = 2;
    private static final int ROW_VERTICAL_PADDING_DP = 1;
    private static final int SPINE_WIDTH_DP = 1;
    private static final int REACTION_HEIGHT_DP = 20;
    private static final int REACTION_HORIZONTAL_PADDING_DP = 4;
    private static final int REACTION_VERTICAL_MARGIN_DP = 2;
    private static final int REACTION_AVATAR_SIZE_DP = 16;
    private static final float REACTION_EMOJI_SCALE = 0.8f;
    private static final float REACTION_COUNTER_TEXT_SP = 12f;

    private final Map<WidgetChatListAdapterItemMessage, RowState> rows = new WeakHashMap<>();
    private final Map<ReactionView, Boolean> compactReactionViews = new WeakHashMap<>();

    private int itemTextId;
    private int itemAvatarId;
    private int itemNameId;
    private int itemTimestampId;
    private int headerId;
    private int loadingTextId;
    private int sendErrorId;
    private int replyHolderId;
    private int threadHeaderId;
    private int threadSpineId;
    private int guidelineId;
    private int reactionContainerId;
    private int quickAddReactionId;
    private Integer avatarDecorationId;
    private int timestampWidthPx;

    @Override
    public void start(Context context) throws Throwable {
        itemTextId = Utils.getResId("chat_list_adapter_item_text", "id");
        itemAvatarId = Utils.getResId("chat_list_adapter_item_text_avatar", "id");
        itemNameId = Utils.getResId("chat_list_adapter_item_text_name", "id");
        itemTimestampId = Utils.getResId("chat_list_adapter_item_text_timestamp", "id");
        headerId = Utils.getResId("chat_list_adapter_item_text_header", "id");
        loadingTextId = Utils.getResId("chat_list_adapter_item_text_loading", "id");
        sendErrorId = Utils.getResId("chat_list_adapter_item_text_error", "id");
        replyHolderId = Utils.getResId("chat_list_adapter_item_text_decorator", "id");
        threadHeaderId = Utils.getResId("thread_starter_message_header", "id");
        threadSpineId = Utils.getResId("chat_list_adapter_item_thread_embed_spine", "id");
        guidelineId = Utils.getResId("uikit_chat_guideline", "id");
        reactionContainerId = Utils.getResId("chat_list_item_reactions", "id");
        quickAddReactionId = Utils.getResId("reaction_quick_add", "id");
        avatarDecorationId = findAvatarDecorationId();

        // Non-message entries (attachments, embeds, and similar rows) use the
        // same chat guideline. Move that content to the IRC body column too.
        patcher.patch(
                WidgetChatListItem.class,
                "onConfigure",
                new Class<?>[]{int.class, ChatListEntry.class},
                new Hook(frame -> {
                    if (!(frame.thisObject instanceof WidgetChatListItem)
                            || frame.thisObject instanceof WidgetChatListAdapterItemMessage) {
                        return;
                    }

                    WidgetChatListItem item = (WidgetChatListItem) frame.thisObject;
                    View guideline = item.itemView.findViewById(guidelineId);
                    if (guideline instanceof Guideline) {
                        ((Guideline) guideline).setGuidelineBegin(
                                bodyStartDp(item.itemView.getContext())
                        );
                    }
                })
        );

        patcher.patch(
                WidgetChatListAdapterItemMessage.class,
                "onConfigure",
                new Class<?>[]{int.class, ChatListEntry.class},
                new Hook(frame -> {
                    if (!(frame.thisObject instanceof WidgetChatListAdapterItemMessage)
                            || !(frame.args[1] instanceof MessageEntry)) {
                        return;
                    }

                    WidgetChatListAdapterItemMessage item =
                            (WidgetChatListAdapterItemMessage) frame.thisObject;
                    MessageEntry entry = (MessageEntry) frame.args[1];
                    configureMessage(item, entry);
                })
        );

        // Reactions are a separate RecyclerView item. Keep their pills
        // compact, and attach a layout listener so WhoReacted's asynchronously
        // inserted reactor avatars receive the same sizing as the pill.
        patcher.patch(
                WidgetChatListAdapterItemReactions.class,
                "onConfigure",
                new Class<?>[]{int.class, ChatListEntry.class},
                new Hook(frame -> {
                    if (!(frame.thisObject instanceof WidgetChatListAdapterItemReactions)) {
                        return;
                    }

                    WidgetChatListAdapterItemReactions item =
                            (WidgetChatListAdapterItemReactions) frame.thisObject;
                    View container = item.itemView.findViewById(reactionContainerId);
                    if (!(container instanceof ViewGroup)) return;

                    compactReactions((ViewGroup) container);
                    container.post(() -> {
                        if (container.getParent() != null) {
                            compactReactions((ViewGroup) container);
                        }
                    });
                })
        );
    }

    private void configureMessage(WidgetChatListAdapterItemMessage item, MessageEntry entry) {
        View itemView = item.itemView;
        if (!(itemView instanceof ConstraintLayout)) return;

        ConstraintLayout root = (ConstraintLayout) itemView;
        View messageText = root.findViewById(itemTextId);
        if (messageText == null) return;

        View header = root.findViewById(headerId);
        View avatar = root.findViewById(itemAvatarId);
        View avatarDecoration = findAvatarDecoration(root);
        View name = root.findViewById(itemNameId);
        View timestamp = root.findViewById(itemTimestampId);

        if (timestamp instanceof TextView) {
            setShortTimestamp((TextView) timestamp, entry);
            updateTimestampWidth(root.getContext(), (TextView) timestamp);
        }

        RowState state = rows.get(item);
        // A recycled holder may have been expanded for a previous long
        // message. Clear our previous minimums before measuring the new one.
        root.setMinimumHeight(0);
        if (state != null) state.row.setMinimumHeight(0);
        if (header != null && avatar != null && name instanceof TextView
                && timestamp instanceof TextView) {
            if (state == null || !state.regular) {
                state = createRegularRow(root, (ImageView) avatar, (TextView) name,
                        (TextView) timestamp, messageText, avatarDecoration);
                rows.put(item, state);
            }
        } else {
            if (state == null || state.regular) {
                state = createMinimalRow(root, messageText);
                rows.put(item, state);
            }

            updateMinimalName(state.name, entry, root);
        }

        View replyHolder = root.findViewById(replyHolderId);
        View threadHeader = root.findViewById(threadHeaderId);
        applyRootConstraints(root, state, replyHolder, threadHeader);

        // Discord's thread spine is positioned relative to the original
        // avatar. It would be detached from the row after reparenting it.
        // The IRC spine below replaces it in the compact layout.
        View threadSpine = root.findViewById(threadSpineId);
        if (threadSpine != null) threadSpine.setVisibility(View.GONE);

        // A normal message's guideline is no longer used by the row, but
        // keeping it at the body column helps any optional child views that
        // Discord adds to this layout in a later 126.21 patch.
        View guideline = root.findViewById(guidelineId);
        if (guideline instanceof androidx.constraintlayout.widget.Guideline) {
            ((androidx.constraintlayout.widget.Guideline) guideline)
                    .setGuidelineBegin(bodyStartDp(root.getContext()));
        }

        ensureRowHeight(root, state, messageText);
    }

    private RowState createRegularRow(
            ConstraintLayout root,
            ImageView avatar,
            TextView name,
            TextView timestamp,
            View messageText,
            View avatarDecoration
    ) {
        Context context = root.getContext();
        updateTimestampWidth(context, timestamp);
        int timestampWidth = timestampColumnWidth(context);
        int avatarGap = dp(context, AVATAR_GAP_DP);
        int avatarSize = dp(context, AVATAR_SIZE_DP);
        int nameGap = dp(context, NAME_GAP_DP);
        int bodyGap = dp(context, BODY_GAP_DP);

        LinearLayout row = createRow(context);
        row.setId(View.generateViewId());
        View spine = createSpine(context);

        View header = root.findViewById(headerId);
        if (header != null) header.setVisibility(View.GONE);
        root.setPadding(0, 0, 0, 0);
        root.setClipChildren(false);

        detach(name);
        detach(timestamp);
        detach(messageText);
        prepareMessageText(messageText);

        timestamp.setGravity(Gravity.TOP | Gravity.END);
        timestamp.setPadding(0, dp(context, ROW_VERTICAL_PADDING_DP), dp(context, 4), 0);

        avatar.setPadding(dp(context, 1), dp(context, 1), dp(context, 1), dp(context, 1));

        name.setGravity(Gravity.TOP);
        Typeface typeface = name.getTypeface();
        name.setTypeface(typeface == null ? Typeface.DEFAULT : typeface, Typeface.BOLD);

        row.addView(timestamp, new LinearLayout.LayoutParams(
                timestampWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                avatarSize,
                avatarSize
        );
        avatarParams.leftMargin = avatarGap;
        row.addView(createAvatarCell(context, avatar, avatarDecoration), avatarParams);

        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        nameParams.leftMargin = nameGap;
        nameParams.rightMargin = nameGap;
        row.addView(name, nameParams);

        row.addView(messageText, bodyParams(context, bodyGap));

        View loadingText = root.findViewById(loadingTextId);
        if (loadingText != null) {
            detach(loadingText);
            row.addView(loadingText, bodyParams(context, bodyGap));
        }

        View sendError = root.findViewById(sendErrorId);
        if (sendError != null) {
            detach(sendError);
            LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                    dp(context, 16),
                    dp(context, 16)
            );
            errorParams.rightMargin = bodyGap;
            row.addView(sendError, row.getChildCount() - (loadingText == null ? 0 : 1), errorParams);
        }

        root.addView(row, new ConstraintLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(spine, 0, new ConstraintLayout.LayoutParams(
                dp(context, SPINE_WIDTH_DP),
                0
        ));

        return new RowState(true, row, spine, null);
    }

    private View createAvatarCell(Context context, ImageView avatar, View decoration) {
        if (decoration == null) {
            detach(avatar);
            return avatar;
        }

        FrameLayout cell = new FrameLayout(context);
        cell.setId(View.generateViewId());
        cell.setClipChildren(false);
        cell.setClipToPadding(false);

        detach(avatar);
        detach(decoration);

        int avatarSize = dp(context, AVATAR_SIZE_DP);
        cell.addView(avatar, new FrameLayout.LayoutParams(
                avatarSize,
                avatarSize,
                Gravity.CENTER
        ));

        // Avatar decorations are normally constrained against the avatar in
        // Discord's stock ConstraintLayout. Resize and center one in the new
        // frame so its old root constraints cannot leave it full-sized at the
        // edge of the message row.
        int decorationSize = Math.max(
                dp(context, 1),
                Math.round(avatarSize * 12f / 11f) - dp(context, 4)
        );
        cell.addView(decoration, new FrameLayout.LayoutParams(
                decorationSize,
                decorationSize,
                Gravity.CENTER
        ));
        return cell;
    }

    private void compactReactions(ViewGroup container) {
        Context context = container.getContext();
        int reactionHeight = dp(context, REACTION_HEIGHT_DP);

        container.setMinimumHeight(reactionHeight);
        ViewGroup.LayoutParams containerParams = container.getLayoutParams();
        if (containerParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins =
                    (ViewGroup.MarginLayoutParams) containerParams;
            margins.topMargin = dp(context, REACTION_VERTICAL_MARGIN_DP);
            margins.bottomMargin = dp(context, REACTION_VERTICAL_MARGIN_DP);
            container.setLayoutParams(margins);
        }

        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof ReactionView) {
                compactReactionView((ReactionView) child);
            } else if (child.getId() == quickAddReactionId && child instanceof ImageView) {
                compactQuickAdd((ImageView) child);
            }
        }
    }

    private void compactQuickAdd(ImageView quickAdd) {
        Context context = quickAdd.getContext();
        ViewGroup.LayoutParams params = quickAdd.getLayoutParams();
        int height = dp(context, REACTION_HEIGHT_DP);
        if (params != null && params.height != height) {
            params.height = height;
            quickAdd.setLayoutParams(params);
        }
        int padding = dp(context, 2);
        quickAdd.setPadding(padding, padding, padding, padding);
        quickAdd.setMinimumHeight(0);
    }

    private void compactReactionView(ReactionView reaction) {
        Context context = reaction.getContext();
        int reactionHeight = dp(context, REACTION_HEIGHT_DP);
        boolean changed = false;

        ViewGroup.LayoutParams reactionParams = reaction.getLayoutParams();
        if (reactionParams != null && reactionParams.height != reactionHeight) {
            reactionParams.height = reactionHeight;
            reaction.setLayoutParams(reactionParams);
            changed = true;
        }

        int horizontalPadding = dp(context, REACTION_HORIZONTAL_PADDING_DP);
        if (reaction.getPaddingLeft() != horizontalPadding
                || reaction.getPaddingRight() != horizontalPadding
                || reaction.getPaddingTop() != 0
                || reaction.getPaddingBottom() != 0) {
            reaction.setPadding(horizontalPadding, 0, horizontalPadding, 0);
            changed = true;
        }
        reaction.setGravity(Gravity.CENTER_VERTICAL);
        reaction.setClipChildren(false);

        if (reaction.getChildCount() > 0) {
            View emoji = reaction.getChildAt(0);
            if (emoji.getScaleX() != REACTION_EMOJI_SCALE
                    || emoji.getScaleY() != REACTION_EMOJI_SCALE) {
                emoji.setScaleX(REACTION_EMOJI_SCALE);
                emoji.setScaleY(REACTION_EMOJI_SCALE);
                changed = true;
            }
            setEndMargin(emoji, dp(context, 2));
        }

        if (reaction.getChildCount() > 1) {
            View counter = reaction.getChildAt(1);
            setStartMargin(counter, dp(context, 2));
            if (counter instanceof ViewGroup) {
                ViewGroup counterGroup = (ViewGroup) counter;
                for (int i = 0; i < counterGroup.getChildCount(); i++) {
                    View counterChild = counterGroup.getChildAt(i);
                    if (counterChild instanceof TextView) {
                        TextView counterText = (TextView) counterChild;
                        if (counterText.getTextSize() != sp(context, REACTION_COUNTER_TEXT_SP)) {
                            counterText.setTextSize(REACTION_COUNTER_TEXT_SP);
                            changed = true;
                        }
                    }
                }
            }
        }

        // WhoReacted appends reactor avatars/chips after the stock emoji and
        // counter children. Resize those appended views without importing or
        // depending on that plugin, so both plugins remain independently
        // optional.
        int avatarSize = dp(context, REACTION_AVATAR_SIZE_DP);
        for (int i = 2; i < reaction.getChildCount(); i++) {
            View reactor = reaction.getChildAt(i);
            ViewGroup.LayoutParams rawParams = reactor.getLayoutParams();
            if (!(rawParams instanceof LinearLayout.LayoutParams)) continue;

            LinearLayout.LayoutParams reactorParams =
                    (LinearLayout.LayoutParams) rawParams;
            int targetWidth = reactor instanceof TextView
                    ? ViewGroup.LayoutParams.WRAP_CONTENT : avatarSize;
            int targetLeftMargin = i == 2
                    ? dp(context, 6)
                    : -dp(context, REACTION_AVATAR_SIZE_DP - 6);
            if (reactorParams.width != targetWidth
                    || reactorParams.height != avatarSize
                    || reactorParams.leftMargin != targetLeftMargin
                    || reactorParams.topMargin != 0
                    || reactorParams.bottomMargin != 0) {
                reactorParams.width = targetWidth;
                reactorParams.height = avatarSize;
                reactorParams.leftMargin = targetLeftMargin;
                reactorParams.topMargin = 0;
                reactorParams.bottomMargin = 0;
                reactor.setLayoutParams(reactorParams);
                changed = true;
            }

            reactor.setMinimumWidth(avatarSize);
            reactor.setMinimumHeight(avatarSize);
            if (reactor instanceof TextView) {
                TextView chip = (TextView) reactor;
                chip.setTextSize(sp(context, 10f));
                chip.setPadding(0, 0, 0, 0);
                chip.setGravity(Gravity.CENTER);
            }
        }

        if (compactReactionViews.put(reaction, Boolean.TRUE) == null) {
            reaction.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                 oldLeft, oldTop, oldRight, oldBottom) ->
                    compactReactionView((ReactionView) view));
        }

        if (changed) reaction.requestLayout();
    }

    private void setStartMargin(View view, int margin) {
        ViewGroup.LayoutParams rawParams = view.getLayoutParams();
        if (rawParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) rawParams;
            if (params.leftMargin != margin) {
                params.leftMargin = margin;
                view.setLayoutParams(params);
            }
        }
    }

    private void setEndMargin(View view, int margin) {
        ViewGroup.LayoutParams rawParams = view.getLayoutParams();
        if (rawParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) rawParams;
            if (params.rightMargin != margin) {
                params.rightMargin = margin;
                view.setLayoutParams(params);
            }
        }
    }

    private float sp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().scaledDensity;
    }

    private RowState createMinimalRow(ConstraintLayout root, View messageText) {
        Context context = root.getContext();
        int bodyStart = bodyStartDp(context);
        int bodyGap = dp(context, BODY_GAP_DP);

        LinearLayout row = createRow(context);
        row.setId(View.generateViewId());
        View spine = createSpine(context);
        TextView name = new TextView(context, null, 0, R.i.UiKit_TextView_Large_SingleLine);
        name.setGravity(Gravity.TOP);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setPadding(0, dp(context, ROW_VERTICAL_PADDING_DP), 0, 0);

        root.setPadding(0, 0, 0, 0);
        root.setClipChildren(false);
        detach(messageText);
        prepareMessageText(messageText);

        row.addView(new Space(context), new LinearLayout.LayoutParams(
                bodyStart,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        nameParams.rightMargin = dp(context, NAME_GAP_DP);
        row.addView(name, nameParams);
        row.addView(messageText, bodyParams(context, bodyGap));

        View loadingText = root.findViewById(loadingTextId);
        if (loadingText != null) {
            detach(loadingText);
            row.addView(loadingText, bodyParams(context, bodyGap));
        }

        root.addView(row, new ConstraintLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(spine, 0, new ConstraintLayout.LayoutParams(
                dp(context, SPINE_WIDTH_DP),
                0
        ));

        return new RowState(false, row, spine, name);
    }

    private void applyRootConstraints(
            ConstraintLayout root,
            RowState state,
            View replyHolder,
            View threadHeader
    ) {
        boolean replyVisible = isVisible(replyHolder);
        boolean threadHeaderVisible = isVisible(threadHeader);

        ConstraintLayout.LayoutParams rowParams = new ConstraintLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
        rowParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        if (replyVisible) {
            rowParams.topToBottom = replyHolder.getId();
        } else if (threadHeaderVisible) {
            rowParams.topToBottom = threadHeader.getId();
        } else {
            rowParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
        }
        root.updateViewLayout(state.row, rowParams);

        if (replyHolder != null && replyHolder.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
            ConstraintLayout.LayoutParams replyParams =
                    (ConstraintLayout.LayoutParams) replyHolder.getLayoutParams();
            replyParams.width = 0;
            replyParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            replyParams.leftToLeft = NO_CONSTRAINT;
            replyParams.leftToRight = NO_CONSTRAINT;
            replyParams.rightToLeft = NO_CONSTRAINT;
            replyParams.rightToRight = NO_CONSTRAINT;
            replyParams.startToEnd = NO_CONSTRAINT;
            replyParams.endToStart = NO_CONSTRAINT;
            replyParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            replyParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            if (threadHeaderVisible) {
                replyParams.topToTop = NO_CONSTRAINT;
                replyParams.topToBottom = threadHeader.getId();
            } else {
                replyParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
                replyParams.topToBottom = NO_CONSTRAINT;
            }
            // The row is already constrained below the reply. Adding the
            // reverse bottom-to-top constraint here would make a circular
            // vertical chain in ConstraintLayout.
            replyParams.bottomToBottom = NO_CONSTRAINT;
            replyParams.bottomToTop = NO_CONSTRAINT;
            replyParams.setMarginStart(bodyStartDp(root.getContext()) + dp(root.getContext(), 8));
            replyParams.setMarginEnd(dp(root.getContext(), 8));
            root.updateViewLayout(replyHolder, replyParams);
        }

        ConstraintLayout.LayoutParams spineParams = new ConstraintLayout.LayoutParams(
                dp(root.getContext(), SPINE_WIDTH_DP),
                0
        );
        spineParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
        spineParams.topToTop = state.row.getId();
        spineParams.bottomToBottom = state.row.getId();
        spineParams.setMarginStart(spineStartDp(root.getContext()));
        root.updateViewLayout(state.spine, spineParams);
    }

    private void updateMinimalName(TextView name, MessageEntry entry, View root) {
        name.setText(displayName(entry));
        name.setTextColor(authorColor(entry, root));
    }

    private void setShortTimestamp(TextView timestamp, MessageEntry entry) {
        if (entry.getMessage() == null || entry.getMessage().getTimestamp() == null) {
            return;
        }

        timestamp.setText(new SimpleDateFormat("HH:mm", Locale.ROOT).format(
                new Date(entry.getMessage().getTimestamp().g())
        ));
    }

    private String displayName(MessageEntry entry) {
        User author = entry.getMessage() == null ? null : entry.getMessage().getAuthor();
        if (author != null) {
            Map<Long, String> names = entry.getNickOrUsernames();
            String display = names == null ? null : names.get(author.getId());
            if (display != null && !display.isEmpty()) return display;
            if (author.getUsername() != null && !author.getUsername().isEmpty()) {
                return author.getUsername();
            }
        }

        GuildMember member = entry.getAuthor();
        if (member != null && member.getNick() != null && !member.getNick().isEmpty()) {
            return member.getNick();
        }
        return "Unknown user";
    }

    private int authorColor(MessageEntry entry, View root) {
        int fallback = ColorCompat.getThemedColor(root.getContext(), R.b.colorHeaderPrimary);
        GuildMember member = entry.getAuthor();
        return GuildMember.Companion.getColor(member, fallback);
    }

    private LinearLayout createRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setBaselineAligned(false);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(0, dp(context, ROW_VERTICAL_PADDING_DP), 0,
                dp(context, ROW_VERTICAL_PADDING_DP));
        return row;
    }

    private View createSpine(Context context) {
        View spine = new View(context);
        int muted = ColorCompat.getThemedColor(context, R.b.colorTextMuted);
        spine.setBackgroundColor(Color.argb(
                Math.round(Color.alpha(muted) * 0.45f),
                Color.red(muted),
                Color.green(muted),
                Color.blue(muted)
        ));
        return spine;
    }

    private LinearLayout.LayoutParams bodyParams(Context context, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.leftMargin = leftMargin;
        params.bottomMargin = dp(context, BODY_BOTTOM_PADDING_DP);
        return params;
    }

    private void prepareMessageText(View messageText) {
        if (!(messageText instanceof TextView)) return;

        TextView text = (TextView) messageText;
        text.setSingleLine(false);
        text.setMaxLines(Integer.MAX_VALUE);
        text.setEllipsize(null);
        text.setHorizontallyScrolling(false);
    }

    private void updateTimestampWidth(Context context, TextView timestamp) {
        int minimum = dp(context, DEFAULT_TIMESTAMP_WIDTH_DP);
        int measured = (int) Math.ceil(timestamp.getPaint().measureText("00:00"));
        int desired = measured + dp(context, TIMESTAMP_END_PADDING_DP);
        timestampWidthPx = Math.max(timestampWidthPx, Math.max(minimum, desired));
    }

    private int timestampColumnWidth(Context context) {
        if (timestampWidthPx == 0) {
            timestampWidthPx = dp(context, DEFAULT_TIMESTAMP_WIDTH_DP);
        }
        return timestampWidthPx;
    }

    private void ensureRowHeight(
            ConstraintLayout root,
            RowState state,
            View messageText
    ) {
        state.row.post(() -> {
            int messageHeight = messageText.getMeasuredHeight();
            if (messageText instanceof TextView) {
                android.text.Layout layout = ((TextView) messageText).getLayout();
                if (layout != null) {
                    messageHeight = Math.max(messageHeight, layout.getHeight()
                            + messageText.getPaddingTop()
                            + messageText.getPaddingBottom());
                }
            }

            ViewGroup.LayoutParams messageParams = messageText.getLayoutParams();
            if (messageParams instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins =
                        (ViewGroup.MarginLayoutParams) messageParams;
                messageHeight += margins.topMargin + margins.bottomMargin;
            }

            int requiredHeight = messageHeight
                    + state.row.getPaddingTop()
                    + state.row.getPaddingBottom();
            if (requiredHeight > state.row.getMinimumHeight()) {
                state.row.setMinimumHeight(requiredHeight);
                root.setMinimumHeight(Math.max(
                        root.getMinimumHeight(),
                        state.row.getTop() + requiredHeight
                                + dp(root.getContext(), BODY_BOTTOM_PADDING_DP)
                ));
                root.requestLayout();
            }
        });
    }

    private void detach(View view) {
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) parent.removeView(view);
    }

    private boolean isVisible(View view) {
        return view != null && view.getVisibility() != View.GONE;
    }

    private int bodyStartDp(Context context) {
        return timestampColumnWidth(context)
                + dp(context, AVATAR_GAP_DP + AVATAR_SIZE_DP + NAME_GAP_DP);
    }

    private int spineStartDp(Context context) {
        return timestampColumnWidth(context)
                + dp(context, AVATAR_GAP_DP + AVATAR_SIZE_DP / 2);
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private Integer findAvatarDecorationId() {
        try {
            Class<?> decoratorKt = Class.forName(
                    "com.aliucord.coreplugins.decorations.avatar.AvatarDecoratorKt"
            );

            try {
                Method accessor = decoratorKt.getDeclaredMethod("access$getDecoId$p");
                accessor.setAccessible(true);
                Object value = accessor.invoke(null);
                if (value instanceof Integer) return (Integer) value;
            } catch (Throwable ignored) {
                // Older Aliucord builds expose the property as a field.
            }

            try {
                Field field = decoratorKt.getDeclaredField("decoId");
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Integer) return (Integer) value;
            } catch (Throwable ignored) {
                // Avatar decorations are optional; the plugin works without it.
            }
        } catch (Throwable ignored) {
            // The decorations core plugin is not installed or not loaded.
        }
        return null;
    }

    private View findAvatarDecoration(ConstraintLayout root) {
        Integer id = avatarDecorationId;
        if (id == null || id <= 0) {
            id = findAvatarDecorationId();
            avatarDecorationId = id;
        }
        return id == null || id <= 0 ? null : root.findViewById(id);
    }

    @Override
    public void stop(Context context) {
        rows.clear();
        patcher.unpatchAll();
    }

    private static final class RowState {
        private final boolean regular;
        private final LinearLayout row;
        private final View spine;
        private final TextView name;

        private RowState(boolean regular, LinearLayout row, View spine, TextView name) {
            this.regular = regular;
            this.row = row;
            this.spine = spine;
            this.name = name;
        }
    }
}
