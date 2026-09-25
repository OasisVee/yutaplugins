package com.github.yutaplug.keyintercept;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aliucord.Utils;
import com.aliucord.widgets.BottomSheet;
import com.discord.utilities.color.ColorCompat;
import com.discord.views.CheckedSetting;

import java.util.List;
import java.util.Locale;

public class KeyInterceptSettings extends BottomSheet {
    private final KeyIntercept plugin;
    private String statusMessage = "";

    public KeyInterceptSettings(KeyIntercept plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        rebuildUI();
    }

    public void rebuildUI() {
        Context context = getContext();
        if (context == null) return;

        LinearLayout layout = getLinearLayout();
        layout.removeAllViews();
        layout.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 24));

        boolean isOwnProfile = plugin.targetUserId.isEmpty() || plugin.targetUserId.equals(plugin.currentUserId);
        String targetLabel = isOwnProfile ? "Your Profile" : "User " + plugin.targetUserId;

        // Intro & Banner
        addIntro(context, "Key Intercept distortion control center. Instantly toggle modes, configure timeouts, and sync permissions.");

        TextView bannerView = new TextView(context);
        bannerView.setText("Active Profile: " + targetLabel + (statusMessage.isEmpty() ? "" : " | " + statusMessage));
        bannerView.setTextColor(themeColor(context, "colorTextMuted", Color.LTGRAY));
        bannerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bannerView.setPadding(0, dp(context, 4), 0, dp(context, 12));
        layout.addView(bannerView);

        // Section 1: Main Distortions (with explicit ON/OFF switches)
        addSectionHeader(context, "Speech Distortions", true);

        addDistortionToggle(context, "Gag", "gag_end",
                "Replaces letters with g/h sounds while preserving vowels & emotes");

        addDistortionToggle(context, "Pet", "pet_end",
                "Replaces words with pet noises (" + getPetTypeName(plugin.activeConfig.config.petType) + ", " + Math.round(plugin.activeConfig.config.petAmount * 100) + "%)");
        if (isDistortionActive("pet_end")) {
            addAction(context, "  ↳ Pet Type", "Current: " + getPetTypeName(plugin.activeConfig.config.petType), () -> showPetTypeDialog(context));
            addAction(context, "  ↳ Pet Amount", "Current: " + Math.round(plugin.activeConfig.config.petAmount * 100) + "%", () -> showPetAmountDialog(context));
        }

        addDistortionToggle(context, "Bimbo", "bimbo_end",
                "Adds 'like totally', gargle words, and caps max word length (" + plugin.activeConfig.config.bimboWordLength + " chars)");
        if (isDistortionActive("bimbo_end")) {
            addAction(context, "  ↳ Max Word Length", plugin.activeConfig.config.bimboWordLength + " characters", () -> showBimboLengthDialog(context));
        }

        addDistortionToggle(context, "Horny", "horny_end",
                "Randomly inserts horny sounds throughout messages");

        addDistortionToggle(context, "UWU", "uwu_end",
                "Transforms letters to uwu/nyan style");

        addDistortionToggle(context, "Censored", "censored_end",
                "Censors specified words using '" + plugin.activeConfig.config.censoredReplacement + "'");
        if (isDistortionActive("censored_end")) {
            addAction(context, "  ↳ Censored Replacement Char", "'" + plugin.activeConfig.config.censoredReplacement + "'", () -> showCensoredReplacementDialog(context));
            addAction(context, "  ↳ Censored Words List", plugin.activeConfig.censoredWords.size() + " word(s) configured", () -> showCensoredWordsDialog(context));
        }

        addDistortionToggle(context, "Drone", "drone_end",
                "Appends speech/action/whisper/loud drone headers & footers (Health: " + plugin.activeConfig.droneConfig.droneHealth + "%)");
        if (isDistortionActive("drone_end")) {
            addAction(context, "  ↳ Drone Term", plugin.activeConfig.droneConfig.droneTerm, () -> showDroneTermDialog(context));
            addAction(context, "  ↳ Drone Health", plugin.activeConfig.droneConfig.droneHealth + "%", () -> showDroneHealthDialog(context));
        }

        addDistortionToggle(context, "Custom Rules", "rules_end",
                "Applies custom regex replacement rules (" + plugin.activeConfig.rules.size() + " rule(s))");

        // Section 2: Scope Filter
        addSectionHeader(context, "Scope Filter", false);
        boolean isBlacklist = "blacklist".equalsIgnoreCase(plugin.activeConfig.filterMode);
        addToggle(context, "Filter Mode: " + (isBlacklist ? "Blacklist" : "Whitelist"),
                isBlacklist ? "Distorts everywhere EXCEPT listed servers/channels" : "ONLY distorts inside listed servers/channels",
                isBlacklist,
                val -> {
                    plugin.activeConfig.filterMode = val ? "blacklist" : "whitelist";
                    plugin.saveAndPushAsync();
                    setStatus("Filter mode updated to " + plugin.activeConfig.filterMode);
                    rebuildUI();
                });
        addAction(context, "Add Server / Channel ID", "Add ID to scope list", () -> showAddScopeDialog(context));

        List<KeyInterceptConfig.ScopeItem> scopeList = isBlacklist ? plugin.activeConfig.blacklist : plugin.activeConfig.whitelist;
        if (!scopeList.isEmpty()) {
            for (int i = 0; i < scopeList.size(); i++) {
                final int idx = i;
                KeyInterceptConfig.ScopeItem item = scopeList.get(i);
                String label = item.serverName.isEmpty() ? ("ID: " + item.discordId) : (item.serverName + " (" + item.discordId + ")");
                addAction(context, "  • " + label, "Tap to remove from scope", () -> {
                    plugin.activeConfig.whitelist.remove(idx);
                    if (idx < plugin.activeConfig.blacklist.size()) {
                        plugin.activeConfig.blacklist.remove(idx);
                    }
                    plugin.saveAndPushAsync();
                    setStatus("Removed scope item");
                    rebuildUI();
                });
            }
        }

        // Section 3: Target & Relay Settings
        addSectionHeader(context, "Relay & Synchronization", false);
        addAction(context, "Switch Target Profile", "Current target: " + targetLabel, () -> showTargetUserDialog(context));
        addAction(context, "Relay Server URL", plugin.relayUrl, () -> showRelayUrlDialog(context));
        addAction(context, "Sync with Relay Now", "Push local changes and fetch latest remote config", () -> {
            setStatus("Syncing...");
            plugin.syncConfigAsync(() -> {
                setStatus("Sync complete!");
                rebuildUI();
            });
        });

        // Section 4: Permissions & Requests
        addSectionHeader(context, "Permissions & Editors", false);
        addToggle(context, "Debug Mode", "Appends (original message: ...) to sent messages",
                plugin.activeConfig.config.debug,
                val -> {
                    plugin.activeConfig.config.debug = val;
                    plugin.saveAndPushAsync();
                    rebuildUI();
                });

        addAction(context, "Allowed Editors (" + plugin.allowedEditors.size() + ")", "Manage users who can edit your config", () -> showAllowedEditorsDialog(context));
        if (!plugin.pendingRequests.isEmpty()) {
            addAction(context, "Pending Access Requests (" + plugin.pendingRequests.size() + ")", "Approve or deny remote edit requests", () -> showAccessRequestsDialog(context));
        }
    }

    private boolean isDistortionActive(String field) {
        String endIso = getTimeoutField(field);
        return KeyInterceptConfig.isTimeActive(endIso);
    }

    private void addDistortionToggle(Context context, String label, String field, String description) {
        boolean active = isDistortionActive(field);
        String timeStatus = formatTimeout(getTimeoutField(field));

        addToggle(context, label + " Distortion [" + (active ? "ON" : "OFF") + "]",
                description + " • " + timeStatus,
                active,
                enabled -> {
                    if (enabled) {
                        setTimeoutField(field, KeyInterceptConfig.FAR_FUTURE);
                        if ("pet_end".equals(field) && plugin.activeConfig.config.petAmount <= 0) {
                            plugin.activeConfig.config.petAmount = 0.75;
                        }
                    } else {
                        setTimeoutField(field, KeyInterceptConfig.EPOCH);
                    }
                    plugin.saveAndPushAsync();
                    setStatus(label + (enabled ? " Enabled" : " Disabled"));
                    rebuildUI();
                });

        addAction(context, "  ↳ " + label + " Timeout Options", "Current: " + timeStatus + " (Tap to change duration)", () -> showTimeoutOptions(context, label, field));
    }

    private void setStatus(String msg) {
        this.statusMessage = msg;
    }

    private String getTimeoutField(String field) {
        KeyInterceptConfig.CoreSettings c = plugin.activeConfig.config;
        switch (field) {
            case "gag_end": return c.gagEnd;
            case "pet_end": return c.petEnd;
            case "bimbo_end": return c.bimboEnd;
            case "horny_end": return c.hornyEnd;
            case "uwu_end": return c.uwuEnd;
            case "censored_end": return c.censoredEnd;
            case "drone_end": return c.droneEnd;
            case "rules_end": return c.rulesEnd;
            default: return KeyInterceptConfig.EPOCH;
        }
    }

    private void setTimeoutField(String field, String val) {
        KeyInterceptConfig.CoreSettings c = plugin.activeConfig.config;
        switch (field) {
            case "gag_end": c.gagEnd = val; break;
            case "pet_end": c.petEnd = val; break;
            case "bimbo_end": c.bimboEnd = val; break;
            case "horny_end": c.hornyEnd = val; break;
            case "uwu_end": c.uwuEnd = val; break;
            case "censored_end": c.censoredEnd = val; break;
            case "drone_end": c.droneEnd = val; break;
            case "rules_end": c.rulesEnd = val; break;
        }
    }

    private void showTimeoutOptions(Context context, String label, String field) {
        String[] options = new String[]{"+1 Minute", "+10 Minutes", "+1 Hour", "+24 Hours", "Permanent (Always On)", "Turn Off"};
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(label + " Duration Options")
                .setItems(options, (d, which) -> {
                    long now = System.currentTimeMillis();
                    long current = KeyInterceptConfig.parseIsoTimestamp(getTimeoutField(field));
                    long base = Math.max(now, current);
                    switch (which) {
                        case 0: setTimeoutField(field, KeyInterceptConfig.formatIsoTimestamp(base + 60_000L)); break;
                        case 1: setTimeoutField(field, KeyInterceptConfig.formatIsoTimestamp(base + 600_000L)); break;
                        case 2: setTimeoutField(field, KeyInterceptConfig.formatIsoTimestamp(base + 3600_000L)); break;
                        case 3: setTimeoutField(field, KeyInterceptConfig.formatIsoTimestamp(base + 86400_000L)); break;
                        case 4: setTimeoutField(field, KeyInterceptConfig.FAR_FUTURE); break;
                        case 5: setTimeoutField(field, KeyInterceptConfig.EPOCH); break;
                    }
                    plugin.saveAndPushAsync();
                    setStatus(label + " timeout updated!");
                    rebuildUI();
                })
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, null));
        dialog.show();
    }

    private void showTargetUserDialog(Context context) {
        EditText input = createEditText(context, "Discord User ID", plugin.targetUserId);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Target Discord User ID")
                .setView(input)
                .setNeutralButton("My Profile", (d, w) -> {
                    plugin.targetUserId = plugin.currentUserId;
                    plugin.loadConfigForTarget();
                    setStatus("Switched to own profile");
                    rebuildUI();
                })
                .setPositiveButton("Load Target", (d, w) -> {
                    String id = input.getText().toString().trim();
                    if (!id.isEmpty()) {
                        plugin.targetUserId = id;
                        plugin.loadConfigForTarget();
                        setStatus("Loading profile for " + id + "...");
                        rebuildUI();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, input));
        dialog.show();
    }

    private void showRelayUrlDialog(Context context) {
        EditText input = createEditText(context, "Relay URL", plugin.relayUrl);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Relay Server URL")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        plugin.relayUrl = url;
                        plugin.settings.setString("relay_url", url);
                        setStatus("Relay URL saved");
                        rebuildUI();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, input));
        dialog.show();
    }

    private void showPetTypeDialog(Context context) {
        String[] types = new String[]{"1. Puppy", "2. Kitty", "3. Cow", "4. Fox", "5. Birb", "6. Bee", "7. Bun"};
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Select Pet Type")
                .setItems(types, (d, which) -> {
                    plugin.activeConfig.config.petType = which + 1;
                    plugin.activeConfig.petWords = KeyInterceptConfig.PET_WORDS_BY_TYPE.get(which + 1);
                    plugin.saveAndPushAsync();
                    setStatus("Pet type: " + getPetTypeName(which + 1));
                    rebuildUI();
                })
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, null));
        dialog.show();
    }

    private void showPetAmountDialog(Context context) {
        EditText input = createEditText(context, "Percentage (0-100)", String.valueOf(Math.round(plugin.activeConfig.config.petAmount * 100)));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Pet Amount (%)")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        int pct = Integer.parseInt(input.getText().toString().trim());
                        plugin.activeConfig.config.petAmount = Math.max(0, Math.min(100, pct)) / 100.0;
                        plugin.saveAndPushAsync();
                        setStatus("Pet amount set to " + pct + "%");
                        rebuildUI();
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, input));
        dialog.show();
    }

    private void showBimboLengthDialog(Context context) {
        EditText input = createEditText(context, "Word length", String.valueOf(plugin.activeConfig.config.bimboWordLength));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Bimbo Max Word Length")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        int len = Integer.parseInt(input.getText().toString().trim());
                        plugin.activeConfig.config.bimboWordLength = Math.max(1, len);
                        plugin.saveAndPushAsync();
                        setStatus("Max word length set to " + len);
                        rebuildUI();
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, input));
        dialog.show();
    }

    private void showCensoredReplacementDialog(Context context) {
        EditText input = createEditText(context, "Replacement char", plugin.activeConfig.config.censoredReplacement);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Censored Replacement Character")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String rep = input.getText().toString().trim();
                    if (!rep.isEmpty()) {
                        plugin.activeConfig.config.censoredReplacement = rep;
                        plugin.saveAndPushAsync();
                        setStatus("Replacement char set to '" + rep + "'");
                        rebuildUI();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, input));
        dialog.show();
    }

    private void showCensoredWordsDialog(Context context) {
        StringBuilder sb = new StringBuilder();
        for (String w : plugin.activeConfig.censoredWords) sb.append(w).append("\n");
        EditText input = createEditText(context, "One word per line", sb.toString().trim());
        input.setSingleLine(false);
        input.setLines(5);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Censored Words (one per line)")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    plugin.activeConfig.censoredWords.clear();
                    for (String line : input.getText().toString().split("\n")) {
                        String t = line.trim();
                        if (!t.isEmpty()) plugin.activeConfig.censoredWords.add(t);
                    }
                    plugin.saveAndPushAsync();
                    setStatus("Saved " + plugin.activeConfig.censoredWords.size() + " censored word(s)");
                    rebuildUI();
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, input));
        dialog.show();
    }

    private void showDroneTermDialog(Context context) {
        EditText input = createEditText(context, "Drone term (e.g. Drone, Unit)", plugin.activeConfig.droneConfig.droneTerm);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Drone Term")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String term = input.getText().toString().trim();
                    if (!term.isEmpty()) {
                        plugin.activeConfig.droneConfig.droneTerm = term;
                        plugin.saveAndPushAsync();
                        setStatus("Drone term: " + term);
                        rebuildUI();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, input));
        dialog.show();
    }

    private void showDroneHealthDialog(Context context) {
        EditText input = createEditText(context, "Health (0-100)", String.valueOf(plugin.activeConfig.droneConfig.droneHealth));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Drone Health (%)")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        int h = Integer.parseInt(input.getText().toString().trim());
                        plugin.activeConfig.droneConfig.droneHealth = Math.max(0, Math.min(100, h));
                        plugin.saveAndPushAsync();
                        setStatus("Drone health: " + h + "%");
                        rebuildUI();
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, input));
        dialog.show();
    }

    private void showAddScopeDialog(Context context) {
        EditText input = createEditText(context, "Server or Channel ID", "");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Add Scope ID")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String id = input.getText().toString().trim();
                    if (!id.isEmpty()) {
                        KeyInterceptConfig.ScopeItem item = new KeyInterceptConfig.ScopeItem("", id);
                        plugin.activeConfig.whitelist.add(item);
                        plugin.activeConfig.blacklist.add(item);
                        plugin.saveAndPushAsync();
                        setStatus("Added scope ID: " + id);
                        rebuildUI();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, input));
        dialog.show();
    }

    private void showAllowedEditorsDialog(Context context) {
        EditText input = createEditText(context, "Discord ID to allow", "");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Allowed Editors (" + plugin.allowedEditors.size() + ")")
                .setView(input)
                .setPositiveButton("Add Editor", (d, w) -> {
                    String id = input.getText().toString().trim();
                    if (!id.isEmpty() && !plugin.allowedEditors.contains(id)) {
                        plugin.allowedEditors.add(id);
                        plugin.saveAndPushAsync();
                        setStatus("Added editor: " + id);
                        rebuildUI();
                    }
                })
                .setNeutralButton("Clear All", (d, w) -> {
                    plugin.allowedEditors.clear();
                    plugin.saveAndPushAsync();
                    setStatus("Cleared editors");
                    rebuildUI();
                })
                .setNegativeButton("Close", null)
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, input));
        dialog.show();
    }

    private void showAccessRequestsDialog(Context context) {
        if (plugin.pendingRequests.isEmpty()) {
            Utils.showToast("No pending access requests");
            return;
        }
        String[] requests = plugin.pendingRequests.toArray(new String[0]);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Pending Requests")
                .setItems(requests, (d, which) -> {
                    String reqId = requests[which];
                    AlertDialog subDialog = new AlertDialog.Builder(context)
                            .setTitle("Request from " + reqId)
                            .setPositiveButton("Approve", (d2, w2) -> {
                                plugin.approveRequestAsync(reqId);
                                setStatus("Approved: " + reqId);
                                rebuildUI();
                            })
                            .setNegativeButton("Deny", (d2, w2) -> {
                                plugin.denyRequestAsync(reqId);
                                setStatus("Denied: " + reqId);
                                rebuildUI();
                            })
                            .create();
                    subDialog.setOnShowListener(subD -> styleDialog(subDialog, null));
                    subDialog.show();
                })
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog, null));
        dialog.show();
    }

    private void styleDialog(AlertDialog dialog, EditText input) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.rgb(43, 45, 49)));
        }

        int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title == null) title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) title.setTextColor(Color.WHITE);

        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextColor(Color.WHITE);
        if (input != null) {
            input.setTextColor(Color.WHITE);
            input.setHintTextColor(Color.LTGRAY);
            input.setBackgroundColor(Color.rgb(30, 31, 34));
            input.setPadding(dp(dialog.getContext(), 12), dp(dialog.getContext(), 8), dp(dialog.getContext(), 12), dp(dialog.getContext(), 8));
        }

        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            TextView button = dialog.getButton(which);
            if (button != null) button.setTextColor(Color.rgb(88, 101, 242));
        }
    }

    private EditText createEditText(Context context, String hint, String initialText) {
        EditText et = new EditText(context);
        et.setHint(hint);
        et.setText(initialText);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(Color.LTGRAY);
        et.setBackgroundColor(Color.rgb(30, 31, 34));
        et.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        return et;
    }

    private String getPetTypeName(int petType) {
        switch (petType) {
            case 1: return "Puppy";
            case 2: return "Kitty";
            case 3: return "Cow";
            case 4: return "Fox";
            case 5: return "Birb";
            case 6: return "Bee";
            case 7: return "Bun";
            default: return "Unknown";
        }
    }

    private String formatTimeout(String endIso) {
        long endMs = KeyInterceptConfig.parseIsoTimestamp(endIso);
        long now = System.currentTimeMillis();
        if (endMs <= now) return "OFF";
        if (endMs >= KeyInterceptConfig.parseIsoTimestamp(KeyInterceptConfig.FAR_FUTURE) - 5000) {
            return "Permanent";
        }
        long diffSec = (endMs - now) / 1000;
        long days = diffSec / 86400;
        long hours = (diffSec % 86400) / 3600;
        long mins = (diffSec % 3600) / 60;
        long secs = diffSec % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (mins > 0) sb.append(mins).append("m ");
        sb.append(secs).append("s");
        return sb.toString();
    }

    public interface OnToggleListener {
        void onToggle(boolean checked);
    }

    private void addToggle(Context context, String title, String subtitle, boolean initial, OnToggleListener listener) {
        CheckedSetting setting = Utils.createCheckedSetting(
                context, CheckedSetting.ViewType.SWITCH, title, subtitle);
        setting.setChecked(initial);
        setting.setOnCheckedListener(listener::onToggle);
        getLinearLayout().addView(setting);
    }

    private void addAction(Context context, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 50));
        row.setPaddingRelative(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));

        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true) && value.resourceId != 0) {
            row.setBackground(context.getDrawable(value.resourceId));
        }

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(themeColor(context, "colorHeaderPrimary", Color.WHITE));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitleView = new TextView(context);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(themeColor(context, "colorTextMuted", Color.LTGRAY));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitleView.setPadding(0, dp(context, 2), 0, 0);
        row.addView(subtitleView, new LinearLayout.LayoutParams(-1, -2));

        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 4);
        getLinearLayout().addView(row, params);
    }

    private void addIntro(Context context, String text) {
        TextView intro = new TextView(context);
        intro.setText(text);
        intro.setTextColor(themeColor(context, "colorTextMuted", Color.LTGRAY));
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        intro.setPadding(0, 0, 0, dp(context, 6));
        getLinearLayout().addView(intro, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addSectionHeader(Context context, String title, boolean first) {
        TextView header = new TextView(context);
        header.setText(title.toUpperCase(Locale.ROOT));
        header.setTextColor(themeColor(context, "colorBrand", Color.rgb(88, 101, 242)));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setLetterSpacing(0.08f);
        header.setPadding(0, first ? dp(context, 8) : dp(context, 16), 0, dp(context, 6));
        getLinearLayout().addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private int themeColor(Context context, String attribute, int fallback) {
        int id = Utils.getResId(attribute, "attr");
        return id == 0 ? fallback : ColorCompat.getThemedColor(context, id);
    }

    private int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
