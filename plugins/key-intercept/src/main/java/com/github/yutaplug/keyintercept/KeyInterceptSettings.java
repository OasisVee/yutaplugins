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
import com.aliucord.api.SettingsAPI;
import com.aliucord.widgets.BottomSheet;
import com.discord.utilities.color.ColorCompat;
import com.discord.views.CheckedSetting;

import java.util.Locale;

public class KeyInterceptSettings extends BottomSheet {
    private final KeyIntercept plugin;
    private TextView statusView;

    public KeyInterceptSettings(KeyIntercept plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        Context context = requireContext();
        getLinearLayout().setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 24));

        addIntro(context, "Key Intercept control center for mobile. View, configure, and sync your distortions and permissions.");

        statusView = new TextView(context);
        statusView.setTextColor(Color.LTGRAY);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusView.setPadding(0, dp(context, 4), 0, dp(context, 8));
        statusView.setText("Target: " + (plugin.targetUserId.equals(plugin.currentUserId) ? "My Profile" : plugin.targetUserId));
        getLinearLayout().addView(statusView);

        addSectionHeader(context, "Profile Target", true);
        addAction(context, "Switch Target User", "Current: " + plugin.targetUserId, () -> showTargetUserDialog(context));

        addSectionHeader(context, "Relay Server", false);
        addAction(context, "Relay URL", plugin.relayUrl, () -> showRelayUrlDialog(context));
        addAction(context, "Sync with Relay", "Upload or fetch latest configuration", () -> {
            updateStatus("Syncing...");
            plugin.syncConfigAsync(() -> updateStatus("Sync complete!"));
        });

        addSectionHeader(context, "Timeouts & Distortions", false);
        addTimeoutRow(context, "Gag", "gag_end");
        addTimeoutRow(context, "Pet", "pet_end");
        addAction(context, "Pet Type", "Current: " + getPetTypeName(plugin.activeConfig.config.petType), () -> showPetTypeDialog(context));
        addAction(context, "Pet Amount", Math.round(plugin.activeConfig.config.petAmount * 100) + "%", () -> showPetAmountDialog(context));

        addTimeoutRow(context, "Bimbo", "bimbo_end");
        addAction(context, "Bimbo Word Length", String.valueOf(plugin.activeConfig.config.bimboWordLength), () -> showBimboLengthDialog(context));

        addTimeoutRow(context, "Horny", "horny_end");
        addTimeoutRow(context, "UWU", "uwu_end");
        addTimeoutRow(context, "Censored", "censored_end");
        addAction(context, "Censored Replacement", plugin.activeConfig.config.censoredReplacement, () -> showCensoredReplacementDialog(context));
        addAction(context, "Censored Words", plugin.activeConfig.censoredWords.size() + " words", () -> showCensoredWordsDialog(context));

        addTimeoutRow(context, "Drone", "drone_end");
        addAction(context, "Drone Term", plugin.activeConfig.droneConfig.droneTerm, () -> showDroneTermDialog(context));
        addAction(context, "Drone Health", plugin.activeConfig.droneConfig.droneHealth + "%", () -> showDroneHealthDialog(context));

        addSectionHeader(context, "Scope Filter", false);
        addToggle(context, "Filter Mode (Blacklist)", "Filter mode: " + plugin.activeConfig.filterMode,
                "blacklist".equalsIgnoreCase(plugin.activeConfig.filterMode),
                val -> {
                    plugin.activeConfig.filterMode = val ? "blacklist" : "whitelist";
                    plugin.saveAndPushAsync();
                    updateStatus("Filter mode: " + plugin.activeConfig.filterMode);
                });
        addAction(context, "Add Server/Channel ID", "Whitelist/blacklist entries", () -> showAddScopeDialog(context));

        addSectionHeader(context, "Permissions & Debug", false);
        addToggle(context, "Debug Mode", "Appends original message to sent messages",
                plugin.activeConfig.config.debug,
                val -> {
                    plugin.activeConfig.config.debug = val;
                    plugin.saveAndPushAsync();
                });
        addAction(context, "Allowed Editors", plugin.allowedEditors.size() + " editors", () -> showAllowedEditorsDialog(context));
        addAction(context, "Pending Access Requests", plugin.pendingRequests.size() + " requests", () -> showAccessRequestsDialog(context));
    }

    private void updateStatus(String msg) {
        if (statusView != null) {
            Utils.mainThread.post(() -> statusView.setText(msg));
        }
    }

    private void addTimeoutRow(Context context, String label, String field) {
        String currentEnd = getTimeoutField(field);
        String status = formatTimeout(currentEnd);
        addAction(context, label + " Timeout", status, () -> showTimeoutOptions(context, label, field));
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
        }
        plugin.saveAndPushAsync();
    }

    private void showTimeoutOptions(Context context, String label, String field) {
        String[] options = new String[]{"+1 Minute", "+10 Minutes", "+1 Hour", "+24 Hours", "Permanent", "Off"};
        new AlertDialog.Builder(context)
                .setTitle(label + " Timeout")
                .setItems(options, (dialog, which) -> {
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
                    updateStatus(label + " timeout updated!");
                })
                .show();
    }

    private void showTargetUserDialog(Context context) {
        EditText input = createEditText(context, "Discord User ID", plugin.targetUserId);
        new AlertDialog.Builder(context)
                .setTitle("Target Discord User ID")
                .setView(input)
                .setNeutralButton("My Profile", (d, w) -> {
                    plugin.targetUserId = plugin.currentUserId;
                    plugin.loadConfigForTarget();
                    updateStatus("Switched to own profile");
                })
                .setPositiveButton("Load", (d, w) -> {
                    String id = input.getText().toString().trim();
                    if (!id.isEmpty()) {
                        plugin.targetUserId = id;
                        plugin.loadConfigForTarget();
                        updateStatus("Loading " + id + "...");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRelayUrlDialog(Context context) {
        EditText input = createEditText(context, "Relay URL", plugin.relayUrl);
        new AlertDialog.Builder(context)
                .setTitle("Relay Server URL")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        plugin.relayUrl = url;
                        plugin.settings.setString("relay_url", url);
                        updateStatus("Relay URL saved");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showPetTypeDialog(Context context) {
        String[] types = new String[]{"1. Puppy", "2. Kitty", "3. Cow", "4. Fox", "5. Birb", "6. Bee", "7. Bun"};
        new AlertDialog.Builder(context)
                .setTitle("Select Pet Type")
                .setItems(types, (d, which) -> {
                    plugin.activeConfig.config.petType = which + 1;
                    plugin.activeConfig.petWords = KeyInterceptConfig.PET_WORDS_BY_TYPE.get(which + 1);
                    plugin.saveAndPushAsync();
                    updateStatus("Pet type: " + getPetTypeName(which + 1));
                })
                .show();
    }

    private void showPetAmountDialog(Context context) {
        EditText input = createEditText(context, "Percentage (0-100)", String.valueOf(Math.round(plugin.activeConfig.config.petAmount * 100)));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(context)
                .setTitle("Pet Amount (%)")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        int pct = Integer.parseInt(input.getText().toString().trim());
                        plugin.activeConfig.config.petAmount = Math.max(0, Math.min(100, pct)) / 100.0;
                        plugin.saveAndPushAsync();
                        updateStatus("Pet amount: " + pct + "%");
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showBimboLengthDialog(Context context) {
        EditText input = createEditText(context, "Word length", String.valueOf(plugin.activeConfig.config.bimboWordLength));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(context)
                .setTitle("Bimbo Max Word Length")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        int len = Integer.parseInt(input.getText().toString().trim());
                        plugin.activeConfig.config.bimboWordLength = Math.max(1, len);
                        plugin.saveAndPushAsync();
                        updateStatus("Bimbo max word length: " + len);
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCensoredReplacementDialog(Context context) {
        EditText input = createEditText(context, "Replacement char", plugin.activeConfig.config.censoredReplacement);
        new AlertDialog.Builder(context)
                .setTitle("Censored Replacement Character")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String rep = input.getText().toString().trim();
                    if (!rep.isEmpty()) {
                        plugin.activeConfig.config.censoredReplacement = rep;
                        plugin.saveAndPushAsync();
                        updateStatus("Censored replacement: " + rep);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCensoredWordsDialog(Context context) {
        StringBuilder sb = new StringBuilder();
        for (String w : plugin.activeConfig.censoredWords) sb.append(w).append("\n");
        EditText input = createEditText(context, "One word per line", sb.toString().trim());
        input.setSingleLine(false);
        input.setLines(5);
        new AlertDialog.Builder(context)
                .setTitle("Censored Words (one per line)")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    plugin.activeConfig.censoredWords.clear();
                    for (String line : input.getText().toString().split("\n")) {
                        String t = line.trim();
                        if (!t.isEmpty()) plugin.activeConfig.censoredWords.add(t);
                    }
                    plugin.saveAndPushAsync();
                    updateStatus("Saved " + plugin.activeConfig.censoredWords.size() + " censored words");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDroneTermDialog(Context context) {
        EditText input = createEditText(context, "Drone term (e.g. Drone, Unit)", plugin.activeConfig.droneConfig.droneTerm);
        new AlertDialog.Builder(context)
                .setTitle("Drone Term")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String term = input.getText().toString().trim();
                    if (!term.isEmpty()) {
                        plugin.activeConfig.droneConfig.droneTerm = term;
                        plugin.saveAndPushAsync();
                        updateStatus("Drone term: " + term);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDroneHealthDialog(Context context) {
        EditText input = createEditText(context, "Health (0-100)", String.valueOf(plugin.activeConfig.droneConfig.droneHealth));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(context)
                .setTitle("Drone Health (%)")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        int h = Integer.parseInt(input.getText().toString().trim());
                        plugin.activeConfig.droneConfig.droneHealth = Math.max(0, Math.min(100, h));
                        plugin.saveAndPushAsync();
                        updateStatus("Drone health: " + h + "%");
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddScopeDialog(Context context) {
        EditText input = createEditText(context, "Server or Channel ID", "");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(context)
                .setTitle("Add Scope ID")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String id = input.getText().toString().trim();
                    if (!id.isEmpty()) {
                        KeyInterceptConfig.ScopeItem item = new KeyInterceptConfig.ScopeItem("", id);
                        plugin.activeConfig.whitelist.add(item);
                        plugin.activeConfig.blacklist.add(item);
                        plugin.saveAndPushAsync();
                        updateStatus("Added scope ID: " + id);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAllowedEditorsDialog(Context context) {
        EditText input = createEditText(context, "Discord ID to allow", "");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(context)
                .setTitle("Allowed Editors (" + plugin.allowedEditors.size() + ")")
                .setView(input)
                .setPositiveButton("Add Editor", (d, w) -> {
                    String id = input.getText().toString().trim();
                    if (!id.isEmpty() && !plugin.allowedEditors.contains(id)) {
                        plugin.allowedEditors.add(id);
                        plugin.saveAndPushAsync();
                        updateStatus("Added editor: " + id);
                    }
                })
                .setNeutralButton("Clear All", (d, w) -> {
                    plugin.allowedEditors.clear();
                    plugin.saveAndPushAsync();
                    updateStatus("Cleared editors");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showAccessRequestsDialog(Context context) {
        if (plugin.pendingRequests.isEmpty()) {
            Utils.showToast("No pending access requests");
            return;
        }
        String[] requests = plugin.pendingRequests.toArray(new String[0]);
        new AlertDialog.Builder(context)
                .setTitle("Pending Requests")
                .setItems(requests, (d, which) -> {
                    String reqId = requests[which];
                    new AlertDialog.Builder(context)
                            .setTitle("Request from " + reqId)
                            .setPositiveButton("Approve", (d2, w2) -> {
                                plugin.approveRequestAsync(reqId);
                                updateStatus("Approved: " + reqId);
                            })
                            .setNegativeButton("Deny", (d2, w2) -> {
                                plugin.denyRequestAsync(reqId);
                                updateStatus("Denied: " + reqId);
                            })
                            .show();
                })
                .show();
    }

    private EditText createEditText(Context context, String hint, String initialText) {
        EditText et = new EditText(context);
        et.setHint(hint);
        et.setText(initialText);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(Color.LTGRAY);
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
        if (endMs <= now) return "Off / Expired";
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
        addView(setting);
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
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitleView = new TextView(context);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(Color.LTGRAY);
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
        intro.setTextColor(Color.LTGRAY);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        intro.setPadding(0, 0, 0, dp(context, 6));
        getLinearLayout().addView(intro, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addSectionHeader(Context context, String title, boolean first) {
        TextView header = new TextView(context);
        header.setText(title.toUpperCase(Locale.ROOT));
        header.setTextColor(Color.rgb(88, 101, 242));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setLetterSpacing(0.08f);
        header.setPadding(0, first ? dp(context, 8) : dp(context, 16), 0, dp(context, 6));
        getLinearLayout().addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
