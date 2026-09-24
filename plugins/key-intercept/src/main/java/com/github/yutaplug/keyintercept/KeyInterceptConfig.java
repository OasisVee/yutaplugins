package com.github.yutaplug.keyintercept;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class KeyInterceptConfig {
    public static final String FAR_FUTURE = "9999-12-31T23:59:59.000Z";
    public static final String EPOCH = "1970-01-01T00:00:00.000Z";
    public static final String DEFAULT_RELAY_URL = "https://kirelay.thomaslower.com";

    public static final Map<Integer, List<String>> PET_WORDS_BY_TYPE = new HashMap<>();
    static {
        PET_WORDS_BY_TYPE.put(1, Arrays.asList("woof", "ruff", "wruff", "arf"));
        PET_WORDS_BY_TYPE.put(2, Arrays.asList("meow", "mrow", "nya", "mreow", "mew"));
        PET_WORDS_BY_TYPE.put(3, Arrays.asList("moo", "mmmooo"));
        PET_WORDS_BY_TYPE.put(4, Arrays.asList("yip", "eeeekkkk", "waaaaaaaahh", "eeeee", "grrrrr", "grr-uff", "eeeek"));
        PET_WORDS_BY_TYPE.put(5, Arrays.asList("tweet", "squark", "chirp", "caw"));
        PET_WORDS_BY_TYPE.put(6, Arrays.asList("bzzzz", "buzz"));
        PET_WORDS_BY_TYPE.put(7, Arrays.asList("squeak", "pyon"));
    }

    public static class CoreSettings {
        public String rulesEnd = FAR_FUTURE;
        public String gagEnd = EPOCH;
        public String petEnd = EPOCH;
        public double petAmount = 0.0;
        public int petType = 1;
        public String bimboEnd = EPOCH;
        public String hornyEnd = EPOCH;
        public int bimboWordLength = 12;
        public String droneEnd = EPOCH;
        public String uwuEnd = EPOCH;
        public String censoredEnd = EPOCH;
        public String censoredReplacement = "*";
        public boolean debug = false;

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("rules_end", rulesEnd);
                obj.put("gag_end", gagEnd);
                obj.put("pet_end", petEnd);
                obj.put("pet_amount", petAmount);
                obj.put("pet_type", petType);
                obj.put("bimbo_end", bimboEnd);
                obj.put("horny_end", hornyEnd);
                obj.put("bimbo_word_length", bimboWordLength);
                obj.put("drone_end", droneEnd);
                obj.put("uwu_end", uwuEnd);
                obj.put("censored_end", censoredEnd);
                obj.put("censored_replacement", censoredReplacement);
                obj.put("debug", debug);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static CoreSettings fromJson(JSONObject obj) {
            CoreSettings cs = new CoreSettings();
            if (obj == null) return cs;
            cs.rulesEnd = obj.optString("rules_end", FAR_FUTURE);
            cs.gagEnd = obj.optString("gag_end", EPOCH);
            cs.petEnd = obj.optString("pet_end", EPOCH);
            cs.petAmount = obj.optDouble("pet_amount", 0.0);
            cs.petType = obj.optInt("pet_type", 1);
            cs.bimboEnd = obj.optString("bimbo_end", EPOCH);
            cs.hornyEnd = obj.optString("horny_end", EPOCH);
            cs.bimboWordLength = obj.optInt("bimbo_word_length", 12);
            cs.droneEnd = obj.optString("drone_end", EPOCH);
            cs.uwuEnd = obj.optString("uwu_end", EPOCH);
            cs.censoredEnd = obj.optString("censored_end", EPOCH);
            cs.censoredReplacement = obj.optString("censored_replacement", "*");
            cs.debug = obj.optBoolean("debug", false);
            return cs;
        }
    }

    public static class Rule {
        public String ruleRegex = "";
        public String ruleReplacement = "";
        public boolean regexNormalize = false;
        public boolean enabled = true;
        public double chanceToApply = 1.0;
        public int order = 0;
        public int groupId = 1;

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("rule_regex", ruleRegex);
                obj.put("rule_replacement", ruleReplacement);
                obj.put("regex_normalize", regexNormalize);
                obj.put("enabled", enabled);
                obj.put("chance_to_apply", chanceToApply);
                obj.put("order", order);
                obj.put("group_id", groupId);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static Rule fromJson(JSONObject obj, int fallbackIndex) {
            Rule r = new Rule();
            if (obj == null) return r;
            r.ruleRegex = obj.optString("rule_regex", "");
            r.ruleReplacement = obj.optString("rule_replacement", "");
            r.regexNormalize = obj.optBoolean("regex_normalize", false);
            r.enabled = obj.optBoolean("enabled", true);
            r.chanceToApply = Math.max(0.0, Math.min(1.0, obj.optDouble("chance_to_apply", 1.0)));
            r.order = obj.optInt("order", fallbackIndex);
            r.groupId = obj.optInt("group_id", 1);
            return r;
        }
    }

    public static class RuleGroup {
        public int id = 1;
        public String timeoutEnd = FAR_FUTURE;
        public boolean enabled = true;
        public int order = 0;
        public String disabledAt = null;

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("timeout_end", timeoutEnd);
                obj.put("enabled", enabled);
                obj.put("order", order);
                if (disabledAt != null) obj.put("disabled_at", disabledAt);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static RuleGroup fromJson(JSONObject obj, int fallbackIndex) {
            RuleGroup rg = new RuleGroup();
            if (obj == null) return rg;
            rg.id = obj.optInt("id", fallbackIndex + 1);
            String fallbackTimeout = obj.optString("disabled_at", FAR_FUTURE);
            rg.timeoutEnd = obj.optString("timeout_end", fallbackTimeout);
            rg.enabled = obj.optBoolean("enabled", true);
            rg.order = obj.optInt("order", fallbackIndex);
            if (obj.has("disabled_at")) rg.disabledAt = obj.optString("disabled_at", null);
            return rg;
        }
    }

    public static class ScopeItem {
        public String serverName = "";
        public String discordId = "";

        public ScopeItem() {}

        public ScopeItem(String serverName, String discordId) {
            this.serverName = serverName != null ? serverName : "";
            this.discordId = discordId != null ? discordId : "";
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("server_name", serverName);
                obj.put("discord_id", discordId);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static ScopeItem fromJson(JSONObject obj) {
            ScopeItem si = new ScopeItem();
            if (obj == null) return si;
            si.serverName = obj.optString("server_name", "").trim();
            si.discordId = obj.optString("discord_id", "").trim();
            return si;
        }
    }

    public static class DroneConfig {
        public int droneHealth = 100;
        public String speechHeader = "Acknowledged";
        public String speechFooter = "Compliance complete";
        public String actionHeader = "ACTION";
        public String actionFooter = "ACTION COMPLETE";
        public String whisperHeader = "WHISPER";
        public String whisperFooter = "WHISPER COMPLETE";
        public String loudHeader = "LOUD";
        public String loudFooter = "LOUD COMPLETE";
        public String droneTerm = "Drone";

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("drone_health", droneHealth);
                obj.put("speech_header", speechHeader);
                obj.put("speech_footer", speechFooter);
                obj.put("action_header", actionHeader);
                obj.put("action_footer", actionFooter);
                obj.put("whisper_header", whisperHeader);
                obj.put("whisper_footer", whisperFooter);
                obj.put("loud_header", loudHeader);
                obj.put("loud_footer", loudFooter);
                obj.put("drone_term", droneTerm);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static DroneConfig fromJson(JSONObject obj) {
            DroneConfig dc = new DroneConfig();
            if (obj == null) return dc;
            dc.droneHealth = obj.optInt("drone_health", 100);
            dc.speechHeader = obj.optString("speech_header", "Acknowledged");
            dc.speechFooter = obj.optString("speech_footer", "Compliance complete");
            dc.actionHeader = obj.optString("action_header", "ACTION");
            dc.actionFooter = obj.optString("action_footer", "ACTION COMPLETE");
            dc.whisperHeader = obj.optString("whisper_header", "WHISPER");
            dc.whisperFooter = obj.optString("whisper_footer", "WHISPER COMPLETE");
            dc.loudHeader = obj.optString("loud_header", "LOUD");
            dc.loudFooter = obj.optString("loud_footer", "LOUD COMPLETE");
            dc.droneTerm = obj.optString("drone_term", "Drone");
            return dc;
        }
    }

    public CoreSettings config = new CoreSettings();
    public List<Rule> rules = new ArrayList<>();
    public List<RuleGroup> rulesGroups = new ArrayList<>();
    public List<ScopeItem> whitelist = new ArrayList<>();
    public List<ScopeItem> blacklist = new ArrayList<>();
    public String filterMode = "whitelist";
    public List<String> petWords = new ArrayList<>(PET_WORDS_BY_TYPE.get(1));
    public List<String> censoredWords = new ArrayList<>();
    public DroneConfig droneConfig = new DroneConfig();

    public KeyInterceptConfig() {}

    public JSONObject toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("config", config.toJson());

            JSONArray rulesArr = new JSONArray();
            for (Rule r : rules) rulesArr.put(r.toJson());
            obj.put("rules", rulesArr);

            JSONArray groupsArr = new JSONArray();
            for (RuleGroup rg : rulesGroups) groupsArr.put(rg.toJson());
            obj.put("rules_groups", groupsArr);

            JSONArray wlArr = new JSONArray();
            for (ScopeItem si : whitelist) wlArr.put(si.toJson());
            obj.put("whitelist", wlArr);

            JSONArray blArr = new JSONArray();
            for (ScopeItem si : blacklist) blArr.put(si.toJson());
            obj.put("blacklist", blArr);

            obj.put("filter_mode", filterMode);

            JSONArray petWordsArr = new JSONArray();
            for (String pw : petWords) petWordsArr.put(pw);
            obj.put("pet_words", petWordsArr);

            JSONArray censoredArr = new JSONArray();
            for (String cw : censoredWords) censoredArr.put(cw);
            obj.put("censored_words", censoredArr);

            obj.put("drone_config", droneConfig.toJson());
            return obj;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static KeyInterceptConfig fromJson(JSONObject root) {
        KeyInterceptConfig kic = new KeyInterceptConfig();
        if (root == null) return kic;

        JSONObject source = root;
        if (root.has("config") && root.optJSONObject("config") != null) {
            JSONObject nested = root.optJSONObject("config");
            if (nested.has("rules") || nested.has("rules_groups") || nested.has("whitelist")) {
                source = nested;
            }
        }

        if (source.has("config") && source.optJSONObject("config") != null) {
            kic.config = CoreSettings.fromJson(source.optJSONObject("config"));
        } else {
            kic.config = CoreSettings.fromJson(source);
        }

        JSONArray rulesArr = source.optJSONArray("rules");
        if (rulesArr != null) {
            kic.rules.clear();
            for (int i = 0; i < rulesArr.length(); i++) {
                kic.rules.add(Rule.fromJson(rulesArr.optJSONObject(i), i));
            }
        }

        JSONArray groupsArr = source.optJSONArray("rules_groups");
        if (groupsArr != null) {
            kic.rulesGroups.clear();
            for (int i = 0; i < groupsArr.length(); i++) {
                kic.rulesGroups.add(RuleGroup.fromJson(groupsArr.optJSONObject(i), i));
            }
        }

        JSONArray wlArr = source.optJSONArray("whitelist");
        if (wlArr != null) {
            kic.whitelist.clear();
            for (int i = 0; i < wlArr.length(); i++) {
                kic.whitelist.add(ScopeItem.fromJson(wlArr.optJSONObject(i)));
            }
        }

        JSONArray blArr = source.optJSONArray("blacklist");
        if (blArr != null) {
            kic.blacklist.clear();
            for (int i = 0; i < blArr.length(); i++) {
                kic.blacklist.add(ScopeItem.fromJson(blArr.optJSONObject(i)));
            }
        }

        kic.filterMode = "blacklist".equalsIgnoreCase(source.optString("filter_mode", "whitelist")) ? "blacklist" : "whitelist";

        JSONArray petWordsArr = source.optJSONArray("pet_words");
        if (petWordsArr != null) {
            kic.petWords.clear();
            for (int i = 0; i < petWordsArr.length(); i++) {
                String w = petWordsArr.optString(i, null);
                if (w != null) kic.petWords.add(w);
            }
        } else {
            List<String> def = PET_WORDS_BY_TYPE.get(kic.config.petType);
            kic.petWords = def != null ? new ArrayList<>(def) : new ArrayList<>();
        }

        JSONArray censoredArr = source.optJSONArray("censored_words");
        if (censoredArr != null) {
            kic.censoredWords.clear();
            for (int i = 0; i < censoredArr.length(); i++) {
                String w = censoredArr.optString(i, null);
                if (w != null) kic.censoredWords.add(w);
            }
        }

        if (source.has("drone_config") && source.optJSONObject("drone_config") != null) {
            kic.droneConfig = DroneConfig.fromJson(source.optJSONObject("drone_config"));
        }

        return kic;
    }

    public static long parseIsoTimestamp(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.parse(iso).getTime();
        } catch (ParseException e) {
            try {
                SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                sdf2.setTimeZone(TimeZone.getTimeZone("UTC"));
                return sdf2.parse(iso).getTime();
            } catch (ParseException e2) {
                return 0L;
            }
        }
    }

    public static String formatIsoTimestamp(long ms) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(ms));
    }

    public static boolean isTimeActive(String iso) {
        long endMs = parseIsoTimestamp(iso);
        return System.currentTimeMillis() <= endMs;
    }
}
