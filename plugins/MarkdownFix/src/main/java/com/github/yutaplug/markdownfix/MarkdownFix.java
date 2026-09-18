package com.github.yutaplug.markdownfix;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.entities.Plugin.SettingsTab;
import com.aliucord.api.SettingsAPI;
import com.aliucord.patcher.PreHook;
import com.discord.api.application.Application;
import com.discord.simpleast.core.node.Node;
import com.discord.simpleast.code.CodeNode;
import com.discord.utilities.rest.RestAPI;
import com.discord.simpleast.core.parser.ParseSpec;
import com.discord.simpleast.core.parser.Parser;
import com.discord.simpleast.core.parser.Rule;
import com.discord.utilities.color.ColorCompat;
import com.discord.utilities.spans.ClickableSpan;
import com.discord.utilities.spans.BulletSpan;
import com.discord.utilities.spans.QuoteSpan;
import com.discord.utilities.spans.VerticalPaddingSpan;
import com.discord.utilities.textprocessing.AstRenderer;
import com.discord.utilities.textprocessing.DiscordParser;
import com.discord.utilities.textprocessing.MessageParseState;
import com.discord.utilities.textprocessing.MessagePreprocessor;
import com.discord.utilities.textprocessing.MessageRenderContext;
import com.discord.utilities.textprocessing.Rules;
import com.discord.utilities.textprocessing.node.BasicRenderContext;
import com.discord.utilities.textprocessing.node.BlockQuoteNode;
import com.discord.utilities.textprocessing.node.EditedMessageNode;
import com.discord.utilities.textprocessing.node.UrlNode;
import com.discord.utilities.textprocessing.node.ZeroSpaceWidthNode;
import com.discord.utilities.string.StringUtilsKt;
import com.facebook.drawee.span.DraweeSpanStringBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rx.functions.Action1;

import b.a.t.b.b.e;
import kotlin.Unit;

/** Enables Discord's newer block-level Markdown rules in chat and forum messages. */
@AliucordPlugin
@SuppressWarnings({"rawtypes", "unchecked"})
public final class MarkdownFix extends Plugin {
    static final String HEADER_1_SCALE = "header1Scale";
    static final String HEADER_2_SCALE = "header2Scale";
    static final String HEADER_3_SCALE = "header3Scale";
    static final String SUBTEXT_SCALE = "subtextScale";
    static final String COMPACT_BULLETS = "compactBullets";
    static final String CUSTOM_BULLET_COLOR = "customBulletColor";
    static final String BULLET_COLOR = "bulletColor";

    static final float DEFAULT_HEADER_1_SCALE = 1.35f;
    static final float DEFAULT_HEADER_2_SCALE = 1.20f;
    static final float DEFAULT_HEADER_3_SCALE = 1.10f;
    static final float DEFAULT_SUBTEXT_SCALE = 0.75f;
    static final String DEFAULT_BULLET_COLOR = "#5865F2";

    private static final Pattern SUBTEXT_PATTERN =
            Pattern.compile("^[ \\t]*-#[ \\t]+(.*?)[ \\t]*(?=\\n|$)");
    private static final Pattern HEADER_PATTERN =
            Pattern.compile("^[ \\t]*(#{1,3})[ \\t]+(.*?)[ \\t]*(?=\\n|$)");
    private static final Pattern ESCAPE_PATTERN =
            Pattern.compile("^\\\\([^0-9A-Za-z\\s])");
    private static final Pattern LIST_PATTERN =
            Pattern.compile("^([^\\S\\r\\n]*)[*-][ \\t]+(.*)([\\n|$])?");
    private static final Pattern FORUM_LIST_PATTERN =
            Pattern.compile("^([^\\S\\r\\n]*)[*-][ \\t]+([^\\r\\n]*?)[ \\t]*(\\r?\\n|$)");
    private static final Pattern BLOCK_LIST_BODY_PATTERN =
            Pattern.compile("^(?:#{1,3}[ \\t]+|-#[ \\t]+).*");
    private static final Pattern BLOCK_QUOTE_PATTERN =
            Pattern.compile("^(?: *>>> +(.*)| *>(?!>>) +([^\\n]*\\n?))", Pattern.DOTALL);
    private static final Pattern GAME_PROFILE_MENTION_PATTERN =
            Pattern.compile("^<@\\$([0-9]{1,20})>");
    private static final Pattern ANSI_ESCAPE_PATTERN =
            Pattern.compile("\\u001B\\[([0-9;]*)m");
    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> parser;
    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> forumParser;
    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> embedTitlesParser;
    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> embedValuesParser;
    private GameProfileResolver gameProfileResolver = new GameProfileResolver();

    @Override
    public void start(Context context) throws Throwable {
        gameProfileResolver = new GameProfileResolver();
        settingsTab = new SettingsTab(MarkdownFixSettings.class, SettingsTab.Type.BOTTOM_SHEET)
                .withArgs(settings);

        Method parseChannelMessage = DiscordParser.class.getDeclaredMethod(
                "parseChannelMessage",
                Context.class,
                String.class,
                MessageRenderContext.class,
                MessagePreprocessor.class,
                DiscordParser.ParserOptions.class,
                boolean.class
        );

        patcher.patch(parseChannelMessage, new PreHook(frame -> {
            try {
                Context messageContext = (Context) frame.args[0];
                String content = (String) frame.args[1];
                MessageRenderContext renderContext = (MessageRenderContext) frame.args[2];
                MessagePreprocessor preprocessor = (MessagePreprocessor) frame.args[3];
                boolean appendEditedLabel = Boolean.TRUE.equals(frame.args[5]);
                boolean isForumPost =
                        frame.args[4] == DiscordParser.ParserOptions.FORUM_POST_FIRST_MESSAGE;

                List<Node<MessageRenderContext>> ast = (isForumPost ? getForumParser() : getParser()).parse(
                        content == null ? "" : content,
                        MessageParseState.Companion.getInitialState()
                );
                preprocessor.process(ast);
                if (appendEditedLabel) ast.add(new EditedMessageNode(messageContext));
                ast.add(new ZeroSpaceWidthNode());

                DraweeSpanStringBuilder rendered = AstRenderer.render(ast, renderContext);
                frame.setResult(rendered);
            } catch (Throwable error) {
                // Keep Discord's original parser as a safe fallback on an unexpected client change.
                logger.error("MarkdownFix could not render a message", error);
            }
        }));

        try {
            installEmbedParserHook();
        } catch (Throwable error) {
            // Embed parsers are private Discord implementation details; keep the
            // normal message and forum fixes available if they move in a future build.
            logger.error("MarkdownFix could not hook embed Markdown", error);
        }

        try {
            installRichLinkHook();
        } catch (Throwable error) {
            // The URL renderer is an implementation detail of the Discord build.
            logger.error("MarkdownFix could not hook Markdown hyperlinks", error);
        }

        try {
            installModernSpacingHooks();
        } catch (Throwable error) {
            // These block renderer methods are implementation details of the Discord build.
            logger.error("MarkdownFix could not hook modern Markdown block renderers", error);
        }

        try {
            installAnsiCodeBlockHook();
        } catch (Throwable error) {
            // CodeNode is part of Discord's internal parser. Leave ordinary
            // fenced blocks untouched if its implementation changes.
            logger.error("MarkdownFix could not render ANSI code blocks", error);
        }

    }

    private void installAnsiCodeBlockHook() throws Throwable {
        Method render = CodeNode.class.getDeclaredMethod(
                "render", SpannableStringBuilder.class, Object.class);
        patcher.patch(render, new PreHook(frame -> {
            if (!(frame.thisObject instanceof CodeNode)
                    || !(frame.args[0] instanceof SpannableStringBuilder)
                    || !(frame.args[1] instanceof BasicRenderContext)) return;

            try {
                CodeNode<?> node = (CodeNode<?>) frame.thisObject;
                if (!"ansi".equalsIgnoreCase(node.a)) return;
                renderAnsiCodeBlock(
                        node,
                        (SpannableStringBuilder) frame.args[0],
                        (BasicRenderContext) frame.args[1]
                );
                frame.setResult(null);
            } catch (Throwable error) {
                logger.error("MarkdownFix could not apply ANSI colors", error);
            }
        }));
    }

    private static void renderAnsiCodeBlock(
            CodeNode node, SpannableStringBuilder builder, BasicRenderContext context) {
        int start = builder.length();
        String raw = node.getContent();
        AnsiStyle style = new AnsiStyle();
        StringBuilder plainText = new StringBuilder(raw.length());
        List<AnsiSegment> segments = new ArrayList<>();
        Matcher matcher = ANSI_ESCAPE_PATTERN.matcher(raw);
        int cursor = 0;
        while (matcher.find()) {
            appendAnsiSegment(plainText, segments, raw, cursor, matcher.start(), style);
            applyAnsiCodes(style, matcher.group(1), context.getContext());
            cursor = matcher.end();
        }
        appendAnsiSegment(plainText, segments, raw, cursor, raw.length(), style);
        builder.append(plainText);

        // Keep the same default monospace/code styling that Discord's CodeNode
        // supplies for every other fenced language. ANSI spans must be added
        // afterward because Android resolves equal-priority color spans by order.
        Iterable<?> codeStyles = node.b.get(context);
        for (Object codeStyle : codeStyles) {
            builder.setSpan(codeStyle, start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for (AnsiSegment segment : segments) {
            applyAnsiStyle(builder, start + segment.start, start + segment.end, segment.style);
        }
    }

    private static void appendAnsiSegment(
            StringBuilder builder, List<AnsiSegment> segments,
            String text, int start, int end, AnsiStyle style) {
        if (end <= start) return;
        int segmentStart = builder.length();
        builder.append(text, start, end);
        int segmentEnd = builder.length();
        segments.add(new AnsiSegment(segmentStart, segmentEnd, new AnsiStyle(style)));
    }

    private static void applyAnsiStyle(
            SpannableStringBuilder builder, int start, int end, AnsiStyle style) {
        if (style.foreground != null) {
            builder.setSpan(new ForegroundColorSpan(style.foreground), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (style.background != null) {
            builder.setSpan(new BackgroundColorSpan(style.background), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (style.bold) {
            builder.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (style.underline) {
            builder.setSpan(new android.text.style.UnderlineSpan(), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void applyAnsiCodes(AnsiStyle style, String rawCodes, Context context) {
        if (rawCodes == null || rawCodes.isEmpty()) {
            style.reset();
            return;
        }
        String[] codes = rawCodes.split(";", -1);
        for (String rawCode : codes) {
            int code;
            try {
                code = rawCode.isEmpty() ? 0 : Integer.parseInt(rawCode);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (code == 0) style.reset();
            else if (code == 1) style.bold = true;
            else if (code == 4) style.underline = true;
            else if (code >= 30 && code <= 37) {
                style.foreground = ansiColor(code - 30, context);
            }
            else if (code == 39) style.foreground = null;
            else if (code >= 40 && code <= 47) {
                style.background = ansiColor(code - 40, context);
            }
            else if (code == 49) style.background = null;
        }
    }

    // August 2026 Discord ANSI palettes: Light, Ash, Dark, and Onyx.
    // Background codes 40-47 intentionally mirror the foreground row.
    private static final int[][] ANSI_PALETTES = {
            {0xFF000000, 0xFFE75858, 0xFF399B5D, 0xFFC07600,
                    0xFF3789EA, 0xFFE444BB, 0xFF0098A3, 0xFFB6B7BC},
            {0xFF000000, 0xFFEC6361, 0xFF45A366, 0xFFCE8100,
                    0xFF4591EC, 0xFFF549C9, 0xFF049FAA, 0xFFB6B7BC},
            {0xFF000000, 0xFFDE464A, 0xFF1B8D4D, 0xFFA56100,
                    0xFF1A7CE6, 0xFFD53FAE, 0xFF008995, 0xFFB6B7BC},
            {0xFF000000, 0xFFD22D39, 0xFF008043, 0xFFA56100,
                    0xFF006DD4, 0xFFBC3699, 0xFF007C87, 0xFFB6B7BC}
    };

    private static int ansiColor(int index, Context context) {
        return ANSI_PALETTES[ansiTheme(context)][index];
    }

    private static int ansiTheme(Context context) {
        try {
            int attr = Utils.getResId("theme_chat_code", "attr");
            if (attr == 0) return 1; // Ash fallback for older Discord themes.
            int color = ColorCompat.getThemedColor(context, attr);
            int luminance = (299 * Color.red(color) + 587 * Color.green(color)
                    + 114 * Color.blue(color)) / 1000;
            if (luminance > 150) return 0; // Light
            if (luminance < 28) return 3; // Onyx
            if (luminance < 47) return 2; // Dark
        } catch (Throwable ignored) {
            // Use Ash when the theme resource is unavailable on an older client.
        }
        return 1; // Ash
    }

    private static final class AnsiStyle {
        private Integer foreground;
        private Integer background;
        private boolean bold;
        private boolean underline;

        private AnsiStyle() {}

        private AnsiStyle(AnsiStyle other) {
            foreground = other.foreground;
            background = other.background;
            bold = other.bold;
            underline = other.underline;
        }

        private void reset() {
            foreground = null;
            background = null;
            bold = false;
            underline = false;
        }
    }

    private static final class AnsiSegment {
        private final int start;
        private final int end;
        private final AnsiStyle style;

        private AnsiSegment(int start, int end, AnsiStyle style) {
            this.start = start;
            this.end = end;
            this.style = style;
        }
    }

    @SuppressWarnings("unchecked")
    private void installEmbedParserHook() throws Throwable {
        Class<?> embedClass = Class.forName(
                "com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed");
        Field titlesField = embedClass.getDeclaredField("UI_THREAD_TITLES_PARSER");
        Field valuesField = embedClass.getDeclaredField("UI_THREAD_VALUES_PARSER");
        titlesField.setAccessible(true);
        valuesField.setAccessible(true);
        embedTitlesParser = (Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>)
                titlesField.get(null);
        embedValuesParser = (Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>)
                valuesField.get(null);

        Method parse = Parser.class.getDeclaredMethod(
                "parse", CharSequence.class, Object.class, List.class);
        patcher.patch(parse, new PreHook(frame -> {
            boolean isEmbedParser = frame.thisObject == embedTitlesParser
                    || frame.thisObject == embedValuesParser;
            if (!isEmbedParser) return;

            try {
                CharSequence content = (CharSequence) frame.args[0];
                MessageParseState state = (MessageParseState) frame.args[1];
                List<Node<MessageRenderContext>> ast = getParser().parse(content, state);
                frame.setResult(ast);
            } catch (Throwable error) {
                logger.error("MarkdownFix could not parse embed Markdown", error);
            }
        }));
    }

    private void installRichLinkHook() throws Throwable {
        Field maskField = UrlNode.class.getDeclaredField("mask");
        maskField.setAccessible(true);
        Method typedRender = UrlNode.class.getDeclaredMethod(
                "render", SpannableStringBuilder.class, UrlNode.RenderContext.class);
        Method bridgeRender = UrlNode.class.getDeclaredMethod(
                "render", SpannableStringBuilder.class, Object.class);
        patchRichLinkRender(typedRender, maskField);
        patchRichLinkRender(bridgeRender, maskField);
    }

    private void patchRichLinkRender(Method render, Field maskField) {
        patcher.patch(render, new PreHook(frame -> {
            if (!(frame.thisObject instanceof UrlNode)
                    || !(frame.args[1] instanceof MessageRenderContext)) return;

            try {
                String label = (String) maskField.get(frame.thisObject);
                if (label == null) return;

                renderRichMaskedLink(
                        (UrlNode<?>) frame.thisObject,
                        (SpannableStringBuilder) frame.args[0],
                        (MessageRenderContext) frame.args[1],
                        label
                );
                frame.setResult(null);
            } catch (Throwable error) {
                logger.error("MarkdownFix could not render a Markdown hyperlink", error);
            }
        }));
    }

    private void renderRichMaskedLink(
            UrlNode<?> node,
            SpannableStringBuilder builder,
            MessageRenderContext context,
            String label) {
        String safeUrl = node.getUrl();
        try {
            safeUrl = StringUtilsKt.toPunyCodeASCIIUrl(node.getUrl());
        } catch (Throwable ignored) {
            // Keep the original URL if punycode conversion is unavailable.
        }
        final String linkUrl = safeUrl;

        int start = builder.length();
        try {
            List<Node<MessageRenderContext>> labelAst = getParser().parse(
                    label,
                    MessageParseState.Companion.getInitialState()
            );
            for (Node<MessageRenderContext> child : labelAst) child.render(builder, context);
        } catch (Throwable ignored) {
            builder.append(label);
        }

        if (builder.length() > start) {
            ClickableSpan clickable = new ClickableSpan(
                    Integer.valueOf(ColorCompat.getThemedColor(
                            context.getContext(), context.getLinkColorAttrResId())),
                    false,
                    view -> {
                        context.getOnLongPressUrl().invoke(linkUrl);
                        return Unit.a;
                    },
                    view -> {
                        context.getOnClickUrl().invoke(view.getContext(), linkUrl, label);
                        return Unit.a;
                    }
            );
            builder.setSpan(clickable, start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void installModernSpacingHooks() throws Throwable {
        Method quoteRender = BlockQuoteNode.class.getDeclaredMethod(
                "render", SpannableStringBuilder.class, BasicRenderContext.class);
        Method quoteBridgeRender = BlockQuoteNode.class.getDeclaredMethod(
                "render", SpannableStringBuilder.class, Object.class);
        patchModernQuoteRender(quoteRender);
        patchModernQuoteRender(quoteBridgeRender);
    }

    private void patchModernQuoteRender(Method render) {
        patcher.patch(render, new PreHook(frame -> {
            if (!(frame.thisObject instanceof BlockQuoteNode)
                    || !(frame.args[0] instanceof SpannableStringBuilder)
                    || !(frame.args[1] instanceof BasicRenderContext)) return;

            try {
                renderModernBlockQuote(
                        (BlockQuoteNode<?>) frame.thisObject,
                        (SpannableStringBuilder) frame.args[0],
                        (BasicRenderContext) frame.args[1]
                );
                frame.setResult(null);
            } catch (Throwable error) {
                logger.error("MarkdownFix could not render a compact block quote", error);
            }
        }));
    }

    private static void renderModernBlockQuote(
            BlockQuoteNode<?> node,
            SpannableStringBuilder builder,
            BasicRenderContext renderContext) {
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }

        // EmojiNode expects Discord's DraweeSpanStringBuilder when it renders
        // custom emoji spans; a plain SpannableStringBuilder causes a cast crash.
        DraweeSpanStringBuilder content = new DraweeSpanStringBuilder();
        Iterable<? extends Node> children = node.getChildren();
        if (children != null) {
            for (Node child : children) child.render(content, renderContext);
        }
        while (content.length() > 0 && content.charAt(content.length() - 1) == '\n') {
            content.delete(content.length() - 1, content.length());
        }
        if (content.length() == 0) content.append(' ');

        Context context = renderContext.getContext();
        int quoteColor = defaultQuoteColor(context);
        int lineStart = 0;
        while (lineStart < content.length()) {
            int lineEnd = lineStart;
            while (lineEnd < content.length() && content.charAt(lineEnd) != '\n') {
                lineEnd++;
            }

            int quoteStart = builder.length();
            if (lineEnd == lineStart) {
                builder.append(' ');
            } else {
                builder.append(content, lineStart, lineEnd);
            }
            builder.setSpan(
                    new QuoteSpan(quoteColor, dp(context, 2), dp(context, 6)),
                    quoteStart,
                    builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            if (lineEnd < content.length()) {
                builder.append('\n');
                lineStart = lineEnd + 1;
            } else {
                lineStart = lineEnd;
            }
        }

        if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '\n') {
            int boundaryStart = builder.length();
            builder.append('\n');
            builder.setSpan(
                    new AbsoluteSizeSpan(dp(context, 4)),
                    boundaryStart,
                    builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
    }

    private static int defaultQuoteColor(Context context) {
        return themedColor(context, "theme_chat_block_quote_divider", Color.rgb(79, 84, 92));
    }

    private static final class ModernBlockQuoteRule
            extends Rule.BlockRule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private ModernBlockQuoteRule() {
            super(BLOCK_QUOTE_PATTERN);
        }

        @Override
        public Matcher match(CharSequence source, String previousMatch, MessageParseState state) {
            if (state.isInQuote()) return null;
            return super.match(source, previousMatch, state);
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            int group = matcher.group(1) != null ? 1 : 2;
            ModernBlockQuoteNode node = new ModernBlockQuoteNode();
            return new ParseSpec<>(node, state.newBlockQuoteState(true),
                    matcher.start(group), matcher.end(group));
        }
    }

    private static final class ModernBlockQuoteNode extends Node<MessageRenderContext> {
        @Override
        public void render(SpannableStringBuilder builder, MessageRenderContext renderContext) {
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                builder.append('\n');
            }
            int start = builder.length();
            if (getChildren() != null) {
                for (Node<MessageRenderContext> child : getChildren()) child.render(builder, renderContext);
            }
            if (builder.length() == start) builder.append(' ');

            Context context = renderContext.getContext();
            builder.setSpan(
                    new QuoteSpan(
                            defaultQuoteColor(context),
                            dp(context, 2),
                            dp(context, 5)
                    ),
                    start,
                    builder.length(),
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE
            );
            if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '\n') {
                builder.append('\n');
            }
        }
    }

    private static int themedColor(Context context, String attribute, int fallback) {
        int id = Utils.getResId(attribute, "attr");
        return id == 0 ? fallback : ColorCompat.getThemedColor(context, id);
    }

    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> getParser() {
        if (parser == null) parser = createParser(settings, gameProfileResolver);
        return parser;
    }

    private Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> getForumParser() {
        if (forumParser == null) forumParser = createForumParser(settings, gameProfileResolver);
        return forumParser;
    }

    private static Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> createParser(
            SettingsAPI settings, GameProfileResolver gameProfileResolver) {
        Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> parser =
                new Parser<>(false);
        Rules rules = Rules.INSTANCE;

        // Keep the same rule order as DiscordParser. Block rules must be before the
        // catch-all text rule, otherwise the text rule consumes the entire message.
        parser.addRule(rules.createSoftHyphenRule());
        parser.addRule(new EscapeRule());
        parser.addRule(rules.createBlockQuoteRule());
        parser.addRule(rules.createCodeBlockRule());
        parser.addRule(rules.createInlineCodeRule());
        parser.addRule(rules.createSpoilerRule());
        parser.addRule(rules.createMaskedLinkRule());
        parser.addRule(rules.createUrlNoEmbedRule());
        parser.addRule(rules.createUrlRule());
        parser.addRule(rules.createCustomEmojiRule());
        parser.addRule(rules.createNamedEmojiRule());
        parser.addRule(rules.createUnescapeEmoticonRule());
        parser.addRule(rules.createChannelMentionRule());
        parser.addRule(rules.createRoleMentionRule());
        parser.addRule(rules.createUserMentionRule());
        parser.addRule(new GameProfileMentionRule(gameProfileResolver));
        Rule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> nativeUnicodeRule =
                rules.createUnicodeEmojiRule();
        parser.addRule(new DynamicUnicodeEmojiRule(nativeUnicodeRule));
        parser.addRule(rules.createTimestampRule());
        parser.addRule(new HeaderRule(settings));
        parser.addRule(new SubtextRule(settings));
        parser.addRule(new ListRule(settings, gameProfileResolver));
        parser.addRules(e.a(false, false));
        parser.addRule(rules.createTextReplacementRule());
        return parser;
    }

    private static Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>
            createForumParser(SettingsAPI settings, GameProfileResolver gameProfileResolver) {
        Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> parser =
                new Parser<>(false);
        Rules rules = Rules.INSTANCE;

        // This matches DiscordParser's FORUM_POST_FIRST_MESSAGE rule order. The
        // list rule is corrected so bold text is not mistaken for a list item.
        parser.addRule(rules.createSoftHyphenRule());
        parser.addRule(new EscapeRule());
        parser.addRule(rules.createCodeBlockRule());
        parser.addRule(rules.createInlineCodeRule());
        parser.addRule(rules.createSpoilerRule());
        parser.addRule(rules.createMaskedLinkRule());
        parser.addRule(rules.createUrlNoEmbedRule());
        parser.addRule(rules.createUrlRule());
        parser.addRule(rules.createCustomEmojiRule());
        parser.addRule(rules.createNamedEmojiRule());
        parser.addRule(rules.createUnescapeEmoticonRule());
        parser.addRule(rules.createChannelMentionRule());
        parser.addRule(rules.createRoleMentionRule());
        parser.addRule(rules.createUserMentionRule());
        parser.addRule(new GameProfileMentionRule(gameProfileResolver));
        Rule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> nativeUnicodeRule =
                rules.createUnicodeEmojiRule();
        parser.addRule(new DynamicUnicodeEmojiRule(nativeUnicodeRule));
        parser.addRule(rules.createTimestampRule());
        parser.addRule(new HeaderRule(settings));
        parser.addRule(new ForumListRule(settings, gameProfileResolver));
        parser.addRules(e.a(false, false));
        parser.addRule(rules.createTextReplacementRule());
        return parser;
    }

    /**
     * Reads Discord's live emoji pattern rather than Rules' lazily cached one.
     * NewEmojis updates that live pattern to include newer ZWJ sequences.
     */
    private static final class DynamicUnicodeEmojiRule
            extends Rule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private final Rule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> nativeRule;
        private Pattern providerPattern;
        private Pattern anchoredPattern;

        private DynamicUnicodeEmojiRule(
                Rule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> nativeRule) {
            super(Pattern.compile("(?!x)x"));
            this.nativeRule = nativeRule;
        }

        @Override
        public Matcher match(CharSequence source, String previousMatch, MessageParseState state) {
            // Prefer Discord's native rule for ordinary emoji so EmojiNode rendering
            // remains exactly the same as in the unpatched message parser.
            Matcher nativeMatcher = nativeRule.match(source, previousMatch, state);
            Rules.EmojiDataProvider provider = Rules.access$getEmojiDataProvider$p(Rules.INSTANCE);
            Pattern currentProviderPattern = provider.getUnicodeEmojisPattern();
            if (currentProviderPattern != null) {
                // TextEmoji intentionally disables Unicode emoji parsing with this
                // unmatchable pattern so the original text remains visible.
                if ("$a".equals(currentProviderPattern.pattern())) return null;
                if (currentProviderPattern != providerPattern) {
                    providerPattern = currentProviderPattern;
                    anchoredPattern = Pattern.compile("^(" + currentProviderPattern.pattern() + ")");
                }

                Matcher liveMatcher = anchoredPattern.matcher(source);
                if (liveMatcher.find()
                        && (nativeMatcher == null || liveMatcher.end() > nativeMatcher.end())) {
                    return liveMatcher;
                }
            }

            return nativeMatcher;
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            // The native parser reads the current provider map, so it can render
            // both built-in emoji and sequences added by NewEmojis.
            return nativeRule.parse(matcher, parser, state);
        }
    }

    private static final class EscapeRule
            extends Rule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private EscapeRule() {
            super(ESCAPE_PATTERN);
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            return new ParseSpec<>(new TextNode(matcher.group(1)), state);
        }
    }

    private static final class TextNode extends Node<MessageRenderContext> {
        private final String text;

        private TextNode(String text) {
            this.text = text;
        }

        @Override
        public void render(SpannableStringBuilder builder, MessageRenderContext context) {
            builder.append(text);
        }
    }

    /** Renders Discord's {@code <@$GAME_ID>} game-profile mention syntax. */
    private static final class GameProfileMentionRule
            extends Rule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private final GameProfileResolver resolver;

        private GameProfileMentionRule(GameProfileResolver resolver) {
            super(GAME_PROFILE_MENTION_PATTERN);
            this.resolver = resolver;
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            return new ParseSpec<>(new GameProfileMentionNode(matcher.group(1), resolver), state);
        }
    }

    private static final class GameProfileMentionNode extends Node<MessageRenderContext> {
        private final String gameId;
        private final GameProfileResolver resolver;

        private GameProfileMentionNode(String gameId, GameProfileResolver resolver) {
            this.gameId = gameId;
            this.resolver = resolver;
        }

        @Override
        public void render(SpannableStringBuilder builder, MessageRenderContext context) {
            int start = builder.length();
            String gameName = resolver.getName(gameId);
            String unresolvedText = "@" + gameId;
            builder.append(gameName == null ? unresolvedText : "@" + gameName);
            int end = builder.length();
            styleGameProfileMention(builder, context, start, end);
            if (gameName == null) resolver.fetchName(
                    gameId, builder, context, start, end, unresolvedText);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GameProfileMentionNode
                    && gameId.equals(((GameProfileMentionNode) other).gameId);
        }
    }

    private static void styleGameProfileMention(
            SpannableStringBuilder builder, MessageRenderContext context, int start, int end) {
        Context androidContext = context.getContext();
        builder.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new ForegroundColorSpan(themedColor(
                androidContext, "theme_chat_mention_foreground", Color.WHITE)), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new BackgroundColorSpan(themedColor(
                androidContext, "theme_chat_mention_background", Color.TRANSPARENT)), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Resolves public Discord game/application records away from the main thread. */
    private static final class GameProfileResolver {
        private final Map<String, String> names = new ConcurrentHashMap<>();
        private final Set<String> requests = ConcurrentHashMap.newKeySet();
        private final Map<String, List<GameMentionTarget>> targets = new ConcurrentHashMap<>();
        private volatile boolean active = true;

        private String getName(String gameId) {
            return names.get(gameId);
        }

        private void fetchName(
                String gameId, SpannableStringBuilder builder, MessageRenderContext context,
                int start, int end, String unresolvedText) {
            if (!active) return;
            targets.computeIfAbsent(gameId, ignored -> new CopyOnWriteArrayList<>())
                    .add(new GameMentionTarget(builder, context, start, end, unresolvedText));
            if (!requests.add(gameId)) return;

            Utils.threadPool.execute(() -> requestGameName(gameId));
        }

        private void requestGameName(String gameId) {
            try {
                long applicationId = Long.parseLong(gameId);
                RestAPI.Companion.getApi().getApplications(applicationId).W(
                        (Action1<List<Application>>) applications -> {
                            String name = null;
                            if (applications != null) {
                                for (Application application : applications) {
                                    if (application != null && application.g() == applicationId) {
                                        name = application.h();
                                        break;
                                    }
                                }
                            }
                            resolveName(gameId, name == null ? null : name.trim());
                        },
                        (Action1<Throwable>) ignored -> resolveName(gameId, null)
                );
            } catch (Throwable ignored) {
                resolveName(gameId, null);
            }
        }

        private void resolveName(String gameId, String name) {
            if (name == null || name.isEmpty() || !active) {
                requests.remove(gameId);
                targets.remove(gameId);
                return;
            }
            names.put(gameId, name);
            requests.remove(gameId);
            Utils.mainThread.post(() -> replacePendingMentions(gameId, name));
        }

        private void replacePendingMentions(String gameId, String name) {
            List<GameMentionTarget> pending = targets.remove(gameId);
            if (!active || pending == null) return;
            for (GameMentionTarget target : pending) {
                SpannableStringBuilder builder = target.builder.get();
                if (builder == null || target.end > builder.length()) continue;
                if (!target.unresolvedText.contentEquals(
                        builder.subSequence(target.start, target.end))) continue;
                builder.replace(target.start, target.end, "@" + name);
                styleGameProfileMention(
                        builder, target.context, target.start, target.start + name.length() + 1);
            }
        }

        private void stop() {
            active = false;
            names.clear();
            requests.clear();
            targets.clear();
        }
    }

    private static final class GameMentionTarget {
        private final WeakReference<SpannableStringBuilder> builder;
        private final MessageRenderContext context;
        private final int start;
        private final int end;
        private final String unresolvedText;

        private GameMentionTarget(
                SpannableStringBuilder builder, MessageRenderContext context,
                int start, int end, String unresolvedText) {
            this.builder = new WeakReference<>(builder);
            this.context = context;
            this.start = start;
            this.end = end;
            this.unresolvedText = unresolvedText;
        }
    }

    private static final class HeaderRule
            extends Rule.BlockRule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private final SettingsAPI settings;

        private HeaderRule(SettingsAPI settings) {
            super(HEADER_PATTERN);
            this.settings = settings;
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            return new ParseSpec<>(
                    new HeaderNode(matcher.group(1).length(), settings),
                    state,
                    matcher.start(2),
                    matcher.end(2)
            );
        }
    }

    private static final class HeaderNode extends Node<MessageRenderContext> {
        private final int level;
        private final SettingsAPI settings;

        private HeaderNode(int level, SettingsAPI settings) {
            this.level = level;
            this.settings = settings;
        }

        @Override
        public void render(SpannableStringBuilder builder, MessageRenderContext context) {
            int start = builder.length();
            if (getChildren() != null) {
                for (Node<MessageRenderContext> child : getChildren()) child.render(builder, context);
            }
            int end = builder.length();
            if (end <= start) return;

            float size = level == 1
                    ? readScale(settings, HEADER_1_SCALE, DEFAULT_HEADER_1_SCALE)
                    : level == 2
                    ? readScale(settings, HEADER_2_SCALE, DEFAULT_HEADER_2_SCALE)
                    : readScale(settings, HEADER_3_SCALE, DEFAULT_HEADER_3_SCALE);
            builder.setSpan(new RelativeSizeSpan(size), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static final class SubtextRule
            extends Rule.BlockRule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private final SettingsAPI settings;

        private SubtextRule(SettingsAPI settings) {
            super(SUBTEXT_PATTERN);
            this.settings = settings;
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            return new ParseSpec<>(
                    new SubtextNode(settings),
                    state,
                    matcher.start(1),
                    matcher.end(1)
            );
        }
    }

    private static final class SubtextNode extends Node<MessageRenderContext> {
        private final SettingsAPI settings;

        private SubtextNode(SettingsAPI settings) {
            this.settings = settings;
        }

        @Override
        public void render(SpannableStringBuilder builder, MessageRenderContext context) {
            int start = builder.length();
            if (getChildren() != null) {
                for (Node<MessageRenderContext> child : getChildren()) child.render(builder, context);
            }
            int end = builder.length();
            if (end <= start) return;

            builder.setSpan(new RelativeSizeSpan(readScale(settings, SUBTEXT_SCALE, DEFAULT_SUBTEXT_SCALE)), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            applyMutedColorExceptLinks(builder, start, end, mutedTextColor(context));
        }

        private static int mutedTextColor(MessageRenderContext context) {
            try {
                int attr = context.getContext().getResources().getIdentifier(
                        "colorTextMuted", "attr", context.getContext().getPackageName());
                return attr == 0 ? Color.GRAY : ColorCompat.getThemedColor(context.getContext(), attr);
            } catch (Throwable ignored) {
                return Color.GRAY;
            }
        }

        private static void applyMutedColorExceptLinks(
                SpannableStringBuilder builder, int start, int end, int color) {
            ClickableSpan[] links = builder.getSpans(start, end, ClickableSpan.class);
            Arrays.sort(links, (left, right) ->
                    Integer.compare(builder.getSpanStart(left), builder.getSpanStart(right)));

            int cursor = start;
            for (ClickableSpan link : links) {
                int linkStart = Math.max(start, builder.getSpanStart(link));
                int linkEnd = Math.min(end, builder.getSpanEnd(link));
                if (linkStart < cursor || linkEnd <= linkStart) continue;
                if (cursor < linkStart) {
                    builder.setSpan(new ForegroundColorSpan(color), cursor, linkStart,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                cursor = linkEnd;
            }
            if (cursor < end) {
                builder.setSpan(new ForegroundColorSpan(color), cursor, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private static final class ListRule
            extends Rule.BlockRule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private final SettingsAPI settings;
        private final GameProfileResolver gameProfileResolver;

        private ListRule(SettingsAPI settings, GameProfileResolver gameProfileResolver) {
            super(LIST_PATTERN);
            this.settings = settings;
            this.gameProfileResolver = gameProfileResolver;
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            String indentation = matcher.group(1);
            int indentationWidth = indentation == null ? 0 : indentation.length();
            int nestedLevel = indentationWidth == 0 ? 1 : Math.min(4, 1 + (indentationWidth + 1) / 2);
            String newline = matcher.group(3);
            boolean includesNewline = newline != null && !newline.isEmpty();
            ConfigurableBulletNode<MessageRenderContext> node =
                    new ConfigurableBulletNode<>(nestedLevel, includesNewline, settings);

            String body = matcher.group(2);
            // Parse every item body with a fresh parser. This prevents the child
            // parser's last match from blocking the next consecutive list item,
            // while the BlockRule keeps hyphens in ordinary inline text intact.
            Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> bodyParser =
                    createParser(settings, gameProfileResolver);
            for (Node<MessageRenderContext> child : bodyParser.parse(body, state)) {
                node.addChild(child);
            }
            return new ParseSpec<>(node, state);
        }
    }

    private static final class ForumListRule
            extends Rule.BlockRule<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> {
        private final SettingsAPI settings;
        private final GameProfileResolver gameProfileResolver;

        private ForumListRule(SettingsAPI settings, GameProfileResolver gameProfileResolver) {
            super(FORUM_LIST_PATTERN);
            this.settings = settings;
            this.gameProfileResolver = gameProfileResolver;
        }

        @Override
        public ParseSpec<MessageRenderContext, MessageParseState> parse(
                Matcher matcher,
                Parser<MessageRenderContext, ? super Node<MessageRenderContext>, MessageParseState> parser,
                MessageParseState state) {
            String indentation = matcher.group(1);
            int nestedLevel = indentation == null || indentation.isEmpty() ? 1 : 2;
            String lineEnding = matcher.group(3);
            boolean includesNewline = lineEnding != null && lineEnding.indexOf('\n') >= 0;
            ConfigurableBulletNode<MessageRenderContext> node =
                    new ConfigurableBulletNode<>(nestedLevel, includesNewline, settings);

            String body = matcher.group(2);
            Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> bodyParser =
                    createParser(settings, gameProfileResolver);
            for (Node<MessageRenderContext> child : bodyParser.parse(body, state)) {
                node.addChild(child);
            }
            return new ParseSpec<>(node, state);
        }
    }

    private static final class ConfigurableBulletNode<T extends BasicRenderContext> extends Node<T> {
        private final int nestedLevel;
        private final boolean includesNewline;
        private final SettingsAPI settings;

        private ConfigurableBulletNode(int nestedLevel, boolean includesNewline, SettingsAPI settings) {
            super(null, 1, null);
            this.nestedLevel = nestedLevel;
            this.includesNewline = includesNewline;
            this.settings = settings;
        }

        @Override
        public void render(SpannableStringBuilder builder, T renderContext) {
            Context context = renderContext.getContext();
            int start = builder.length();
            if (getChildren() != null) {
                for (Node<T> child : getChildren()) child.render(builder, renderContext);
            }

            boolean compact = settings.getBool(COMPACT_BULLETS, false);
            int gap = compact
                    ? dp(context, 6)
                    : dimension(context, "markdown_bullet_gap", 4);
            int indentation = compact
                    ? dp(context, 4) * nestedLevel
                    : gap * nestedLevel;
            int radius = compact ? Math.max(1, dp(context, 2)) : 8;
            float strokeWidth = compact ? Math.max(1, dp(context, 1)) : 4.0f;
            int verticalPadding = compact
                    ? 0
                    : dimension(context, "markdown_bullet_vertical_padding", 2);
            Paint.Style style = nestedLevel > 1 ? Paint.Style.STROKE : Paint.Style.FILL;

            ArrayList<Object> spans = new ArrayList<>(3);
            spans.add(new VerticalPaddingSpan(verticalPadding, verticalPadding));
            spans.add(new LeadingMarginSpan.Standard(indentation));
            spans.add(new BulletSpan(gap, bulletColor(context), radius, strokeWidth, style));
            for (Object span : spans) {
                builder.setSpan(span, start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (includesNewline) builder.append("\n");
        }

        private int bulletColor(Context context) {
            if (settings.getBool(CUSTOM_BULLET_COLOR, false)) {
                try {
                    return Color.parseColor(settings.getString(BULLET_COLOR, DEFAULT_BULLET_COLOR));
                } catch (Throwable ignored) {
                    // Fall back to Discord's themed bullet color for invalid values.
                }
            }

            int primaryColor = Utils.getResId("primary_400", "attr");
            return primaryColor == 0
                    ? Color.LTGRAY
                    : ColorCompat.getThemedColor(context, primaryColor);
        }
    }

    static float readScale(SettingsAPI settings, String key, float fallback) {
        try {
            float value = Float.parseFloat(settings.getString(key, ""));
            return value >= 0.1f && value <= 3.0f ? value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int dimension(Context context, String name, int fallbackDp) {
        int id = Utils.getResId(name, "dimen");
        return id == 0
                ? dp(context, fallbackDp)
                : context.getResources().getDimensionPixelSize(id);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
        parser = null;
        forumParser = null;
        embedTitlesParser = null;
        embedValuesParser = null;
        gameProfileResolver.stop();
    }
}
