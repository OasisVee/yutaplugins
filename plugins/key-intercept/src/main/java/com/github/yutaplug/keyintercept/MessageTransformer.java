package com.github.yutaplug.keyintercept;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageTransformer {
    private static final Random RANDOM = new Random();

    private static final String[] HORNY_WORDS = {
        "hmmph", "nngh", "ahhh", "ooh", "oohh", "mmm", "hehe", "hehehe", "heheh",
        "eheh", "ehehe", "eheheh", "guhh", "pleasee", "need to cumm", "oh goshh",
        "ohhh", "ahhh", "cummm", "gggg"
    };

    private static final String[] BIMBO_PRONOUNS = {
        "i", "you", "he", "she", "it", "we", "they", "is"
    };

    private static final String[] GARGLE_WORDS = {
        "like", "hehe", "uhh", "totally", "so dumbb", "ummm", "hhhhh"
    };

    private static final Set<Character> PUNCTUATION = new HashSet<>(Arrays.asList(
        '.', ',', '!', '<', '>', '[', ']', '{', '}', '/', '?', ';', ':', '\'',
        '@', '#', '~', '-', '_', '"', ')', '(', '*', '&', '^', '%', '$', '+',
        '=', '`', '|', '\\'
    ));

    private static final Set<Character> GAG_REMAIN_CHARS = new HashSet<>(Arrays.asList(
        'a', 'e', 'i', 'o', 'u', 'g', 'h',
        'A', 'E', 'I', 'O', 'U', 'G', 'H',
        '?', '!', '.', ',', ':', ';', '#', '*', '-', '(', ')', '~'
    ));

    public static boolean isLink(String word) {
        return word.startsWith("http://") || word.startsWith("https://");
    }

    public static String applyReplacements(String msg, KeyInterceptConfig config, long channelId) {
        if (msg == null || config == null || config.config == null) return msg;
        String originalMsg = msg;

        msg = applyRules(msg, config);
        msg = applyUWU(msg, config);
        msg = applyHorny(msg, config);
        msg = applyPet(msg, config);
        msg = applyBimbo(msg, config);
        msg = applyCensored(msg, config);
        msg = applyGag(msg, config);
        msg = applyDrone(msg, config);

        if (config.config.debug) {
            msg = msg + "\n(original message: " + originalMsg + ")";
        }
        return msg;
    }

    public static boolean shouldApplyRules(KeyInterceptConfig config) {
        if (config == null || config.rules.isEmpty() || config.rulesGroups.isEmpty()) return false;
        for (KeyInterceptConfig.RuleGroup group : config.rulesGroups) {
            if (group.enabled && KeyInterceptConfig.isTimeActive(group.timeoutEnd)) {
                return true;
            }
        }
        return false;
    }

    public static String applyRules(String msg, KeyInterceptConfig config) {
        if (!shouldApplyRules(config)) return msg;

        List<KeyInterceptConfig.RuleGroup> activeGroups = new ArrayList<>();
        for (KeyInterceptConfig.RuleGroup g : config.rulesGroups) {
            if (g.enabled && KeyInterceptConfig.isTimeActive(g.timeoutEnd)) {
                activeGroups.add(g);
            }
        }
        activeGroups.sort(Comparator.comparingInt(a -> a.order));

        String output = msg;
        for (KeyInterceptConfig.RuleGroup group : activeGroups) {
            List<KeyInterceptConfig.Rule> groupRules = new ArrayList<>();
            for (KeyInterceptConfig.Rule r : config.rules) {
                if (r.groupId == group.id && r.enabled && r.ruleRegex != null && !r.ruleRegex.isEmpty()) {
                    groupRules.add(r);
                }
            }
            groupRules.sort(Comparator.comparingInt(a -> a.order));

            for (KeyInterceptConfig.Rule rule : groupRules) {
                try {
                    String patternStr = rule.ruleRegex.replace("\\\\", "\\");
                    Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);

                    if (rule.regexNormalize) {
                        NormalizedString ns = new NormalizedString(output);
                        output = ns.replace(pattern, match -> {
                            if (RANDOM.nextDouble() > rule.chanceToApply) return match;
                            return rule.ruleReplacement;
                        });
                    } else {
                        Matcher matcher = pattern.matcher(output);
                        StringBuffer sb = new StringBuffer();
                        while (matcher.find()) {
                            if (RANDOM.nextDouble() <= rule.chanceToApply) {
                                matcher.appendReplacement(sb, Matcher.quoteReplacement(rule.ruleReplacement));
                            } else {
                                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
                            }
                        }
                        matcher.appendTail(sb);
                        output = sb.toString();
                    }
                } catch (Exception ignored) {}
            }
        }
        return output;
    }

    public static String applyUWU(String msg, KeyInterceptConfig config) {
        if (!KeyInterceptConfig.isTimeActive(config.config.uwuEnd)) return msg;

        StringBuilder sb = new StringBuilder();
        String[] words = msg.split(" ");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (isLink(word)) {
                sb.append(word).append(" ");
                continue;
            }
            word = word.replaceAll("(?i)th", "d");
            word = word.replaceAll("(?i)[rl]", "w");
            word = word.replaceAll("(?i)u", "uw");
            word = word.replaceAll("(?i)n([aeiou])", "ny$1");
            word = word.replaceAll("(?i)ove", "uv");
            sb.append(word).append(" ");
        }
        return sb.toString().trim();
    }

    public static String applyHorny(String msg, KeyInterceptConfig config) {
        if (!KeyInterceptConfig.isTimeActive(config.config.hornyEnd)) return msg;

        StringBuilder sb = new StringBuilder();
        for (String word : msg.split(" ")) {
            if (!isLink(word) && RANDOM.nextDouble() < 0.75) {
                String hw = HORNY_WORDS[RANDOM.nextInt(HORNY_WORDS.length)];
                sb.append(hw).append(" ");
            }
            sb.append(word).append(" ");
        }
        return sb.toString().trim();
    }

    public static String applyPet(String msg, KeyInterceptConfig config) {
        if (!KeyInterceptConfig.isTimeActive(config.config.petEnd) || config.config.petAmount <= 0) {
            return msg;
        }

        List<String> petWords = config.petWords;
        if (petWords == null || petWords.isEmpty()) {
            petWords = KeyInterceptConfig.PET_WORDS_BY_TYPE.get(config.config.petType);
        }
        if (petWords == null || petWords.isEmpty()) return msg;

        StringBuilder sb = new StringBuilder();
        for (String word : msg.split(" ")) {
            if (isLink(word) || (word.startsWith(":") && word.endsWith(":"))) {
                sb.append(word).append(" ");
                continue;
            }
            if (RANDOM.nextDouble() < config.config.petAmount) {
                sb.append(petWords.get(RANDOM.nextInt(petWords.size())));
            } else {
                sb.append(word);
            }
            sb.append(" ");
        }
        return sb.toString().trim();
    }

    public static String applyBimbo(String msg, KeyInterceptConfig config) {
        if (!KeyInterceptConfig.isTimeActive(config.config.bimboEnd)) return msg;

        StringBuilder sb = new StringBuilder();
        int maxWordLength = config.config.bimboWordLength;

        for (String word : msg.split(" ")) {
            boolean changed = false;
            if (!isLink(word)) {
                String lower = word.toLowerCase(Locale.ROOT);
                for (String p : BIMBO_PRONOUNS) {
                    if (p.equals(lower)) {
                        sb.append(word).append(" like totally ");
                        changed = true;
                        break;
                    }
                }
                int pCount = 0;
                for (int i = 0; i < word.length(); i++) {
                    if (PUNCTUATION.contains(word.charAt(i))) pCount++;
                }
                if (word.length() - pCount > maxWordLength) {
                    sb.append(word.substring(0, Math.max(maxWordLength - 2, 1)))
                      .append("uhhhh long words harddd hehe");
                    return sb.toString().trim();
                }
            }
            if (!changed) {
                sb.append(word).append(" ");
            }
            if (RANDOM.nextDouble() < 0.1 && !isLink(word)) {
                sb.append(GARGLE_WORDS[RANDOM.nextInt(GARGLE_WORDS.length)]).append(" ");
            }
        }
        return sb.toString().trim();
    }

    public static String applyCensored(String msg, KeyInterceptConfig config) {
        if (!KeyInterceptConfig.isTimeActive(config.config.censoredEnd)) return msg;
        if (config.censoredWords == null || config.censoredWords.isEmpty()) return msg;

        String repChar = config.config.censoredReplacement != null && !config.config.censoredReplacement.isEmpty()
                ? config.config.censoredReplacement : "*";

        for (String target : config.censoredWords) {
            if (target == null || target.isEmpty()) continue;
            StringBuilder rep = new StringBuilder();
            while (rep.length() < target.length()) {
                rep.append(repChar);
            }
            String replacement = rep.substring(0, target.length());
            msg = msg.replaceAll("(?i)" + Pattern.quote(target), Matcher.quoteReplacement(replacement));
        }
        return msg;
    }

    public static String applyGag(String msg, KeyInterceptConfig config) {
        if (!KeyInterceptConfig.isTimeActive(config.config.gagEnd)) return msg;

        StringBuilder sb = new StringBuilder();
        boolean inEmote = false;

        for (String word : msg.split(" ")) {
            if (isLink(word)) {
                sb.append(word).append(" ");
                continue;
            }
            StringBuilder outWord = new StringBuilder();
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (c == ':' && !inEmote) {
                    inEmote = true;
                    outWord.append(c);
                    continue;
                } else if (c == ':' && inEmote) {
                    inEmote = false;
                    outWord.append(c);
                    continue;
                }
                if (inEmote) {
                    outWord.append(c);
                    continue;
                }
                if (GAG_REMAIN_CHARS.contains(c)) {
                    outWord.append(c);
                } else {
                    if (c >= 'a' && c <= 'z') {
                        outWord.append(RANDOM.nextBoolean() ? 'g' : 'h');
                    } else if (c >= 'A' && c <= 'Z') {
                        outWord.append(RANDOM.nextBoolean() ? 'G' : 'H');
                    } else {
                        outWord.append(c);
                    }
                }
            }
            sb.append(outWord).append(" ");
        }
        return sb.toString().trim();
    }

    public static String applyDrone(String msg, KeyInterceptConfig config) {
        if (!KeyInterceptConfig.isTimeActive(config.config.droneEnd)) return msg;
        KeyInterceptConfig.DroneConfig drone = config.droneConfig;
        if (drone == null) drone = new KeyInterceptConfig.DroneConfig();

        if (drone.droneHealth < 10) {
            return "`" + drone.droneTerm + " haaaaas receieved bzzzzt, ppplease provide repaiirs using beep '/repair', tthank youu. Returned Error: 0x7547372482`";
        }

        boolean containsLink = false;
        for (String word : msg.split(" ")) {
            if (isLink(word)) {
                containsLink = true;
                break;
            }
        }

        if (!containsLink) {
            msg = msg.replaceAll("(?i)\\bMe\\b", Matcher.quoteReplacement(drone.droneTerm))
                     .replaceAll("(?i)\\bMy\\b", "Its'")
                     .replaceAll("(?i)\\bI am\\b", "It is")
                     .replaceAll("(?i)\\bI(')?m\\b", "It is")
                     .replaceAll("(?i)\\bI\\b", Matcher.quoteReplacement(drone.droneTerm));
        }

        StringBuilder sb = new StringBuilder();
        for (String word : msg.split(" ")) {
            if (!isLink(word) && RANDOM.nextDouble() > (drone.droneHealth / 100.0)) {
                sb.append(RANDOM.nextBoolean() ? "`beep` " : "`bzzzt` ");
            }
            sb.append(word).append(" ");
        }

        String temp = sb.toString();
        sb = new StringBuilder();
        int lastTriggered = 0;
        for (String word : temp.split(" ")) {
            if (!isLink(word)) {
                for (int i = 0; i < word.length(); i++) {
                    char c = word.charAt(i);
                    sb.append(c);
                    lastTriggered++;
                    if (RANDOM.nextDouble() + (lastTriggered / 100.0) - 1.0 > (drone.droneHealth / 100.0) && c != '`') {
                        lastTriggered = 0;
                        int repeats = RANDOM.nextInt(10);
                        for (int r = 0; r < repeats; r++) sb.append(c);
                    }
                }
            } else {
                sb.append(word);
            }
            sb.append(" ");
        }

        String output = sb.toString().trim();
        String header = drone.speechHeader;
        String footer = drone.speechFooter;

        if (msg.startsWith("**")) {
            header = drone.loudHeader;
            footer = drone.loudFooter;
        } else if (msg.startsWith("*")) {
            header = drone.actionHeader;
            footer = drone.actionFooter;
        } else if (msg.startsWith("-#")) {
            header = drone.whisperHeader;
            footer = drone.whisperFooter;
        }

        return "`" + header + "`\n" + output + "\n`" + footer + "`";
    }

    public static boolean shouldApplyToScope(KeyInterceptConfig config, long channelId, long guildId, String channelName, String guildName) {
        if (config == null) return true;

        String chIdStr = channelId != 0 ? String.valueOf(channelId) : "";
        String gIdStr = guildId != 0 ? String.valueOf(guildId) : "";
        String sName = (guildName != null && !guildName.isEmpty()) ? guildName : (channelName != null ? channelName : "");

        List<KeyInterceptConfig.ScopeItem> scopeList = "blacklist".equalsIgnoreCase(config.filterMode)
                ? config.blacklist : config.whitelist;

        boolean matches = false;
        for (KeyInterceptConfig.ScopeItem item : scopeList) {
            if ((!item.discordId.isEmpty() && (item.discordId.equals(chIdStr) || item.discordId.equals(gIdStr)))
                    || (!item.serverName.isEmpty() && item.serverName.equalsIgnoreCase(sName))) {
                matches = true;
                break;
            }
        }

        if ("blacklist".equalsIgnoreCase(config.filterMode)) {
            if (matches) return false;
        } else if (!scopeList.isEmpty()) {
            if (!matches) return false;
        }

        if (channelName != null) {
            String lower = channelName.toLowerCase(Locale.ROOT);
            if (lower.contains("sfw") && !lower.contains("nsfw")) {
                return false;
            }
        }

        return true;
    }
}
