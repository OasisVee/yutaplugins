package com.github.yutaplug.keyintercept;

import android.content.Context;

import com.aliucord.Logger;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.CommandsAPI;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.PreHook;
import com.discord.api.commands.ApplicationCommandType;
import com.discord.stores.StoreStream;
import com.discord.widgets.chat.input.WidgetChatInput;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@AliucordPlugin
public class KeyIntercept extends Plugin {
    public static final Logger logger = new Logger("KeyIntercept");

    public String relayUrl = KeyInterceptConfig.DEFAULT_RELAY_URL;
    public String currentUserId = "";
    public String targetUserId = "";
    public int localRevision = 0;
    public KeyInterceptConfig activeConfig = new KeyInterceptConfig();
    public List<String> allowedEditors = new ArrayList<>();
    public List<String> pendingRequests = new ArrayList<>();

    public KeyIntercept() {
        settingsTab = new SettingsTab(KeyInterceptSettings.class, SettingsTab.Type.BOTTOM_SHEET).withArgs(this);
    }

    @Override
    public void load(Context context) {
        relayUrl = settings.getString("relay_url", KeyInterceptConfig.DEFAULT_RELAY_URL);
        String savedConfig = settings.getString("config_json", null);
        if (savedConfig != null) {
            try {
                activeConfig = KeyInterceptConfig.fromJson(new JSONObject(savedConfig));
            } catch (Exception e) {
                logger.error("Failed to parse saved config", e);
            }
        }
        localRevision = settings.getInt("local_revision", 0);
    }

    @Override
    public void start(Context context) throws Throwable {
        resolveCurrentUserId();
        if (targetUserId.isEmpty()) {
            targetUserId = currentUserId;
        }

        patchSendMessage();
        registerCommands();

        Utils.mainThread.postDelayed(() -> {
            resolveCurrentUserId();
            syncConfigAsync(null);
        }, 2000L);
    }

    private void resolveCurrentUserId() {
        try {
            long myId = StoreStream.getUsers().getMe().getId();
            if (myId != 0) {
                currentUserId = String.valueOf(myId);
                if (targetUserId.isEmpty()) targetUserId = currentUserId;
            }
        } catch (Throwable ignored) {
            try {
                long authId = StoreStream.getAuthentication().getId();
                if (authId != 0) {
                    currentUserId = String.valueOf(authId);
                    if (targetUserId.isEmpty()) targetUserId = currentUserId;
                }
            } catch (Throwable ignored2) {}
        }
    }

    private void patchSendMessage() {
        // 1. Patch RestAPI.sendMessage
        try {
            Class<?> restApiClass = Class.forName("com.discord.utilities.rest.RestAPI");
            for (Method method : restApiClass.getDeclaredMethods()) {
                if (method.getName().equals("sendMessage")) {
                    patcher.patch(method, new PreHook(cf -> {
                        try {
                            if (cf.args.length >= 2 && cf.args[0] instanceof Long) {
                                long channelId = (Long) cf.args[0];
                                Object msgObj = cf.args[1];
                                if (msgObj != null) {
                                    interceptMessageObject(msgObj, channelId);
                                }
                            }
                        } catch (Throwable t) {
                            logger.error("RestAPI interception error", t);
                        }
                    }));
                }
            }
        } catch (Throwable t) {
            logger.error("Failed patching RestAPI.sendMessage", t);
        }

        // 2. Patch WidgetChatInput methods
        try {
            for (Method method : WidgetChatInput.class.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (name.contains("sendmessage") || name.equals("send")) {
                    patcher.patch(method, new PreHook(cf -> {
                        try {
                            for (int i = 0; i < cf.args.length; i++) {
                                if (cf.args[i] instanceof String) {
                                    cf.args[i] = transformOutgoing((String) cf.args[i], 0L);
                                } else if (cf.args[i] != null && cf.args[i].getClass().getName().contains("Message")) {
                                    interceptMessageObject(cf.args[i], 0L);
                                }
                            }
                        } catch (Throwable t) {
                            logger.error("WidgetChatInput interception error", t);
                        }
                    }));
                }
            }
        } catch (Throwable t) {
            logger.error("Failed patching WidgetChatInput", t);
        }
    }

    private void interceptMessageObject(Object messageObj, long channelId) {
        try {
            Field contentField = null;
            Class<?> clazz = messageObj.getClass();
            while (clazz != null && contentField == null) {
                try {
                    contentField = clazz.getDeclaredField("content");
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }

            if (contentField != null) {
                contentField.setAccessible(true);
                Object val = contentField.get(messageObj);
                if (val instanceof String) {
                    String orig = (String) val;
                    String transformed = transformOutgoing(orig, channelId);
                    contentField.set(messageObj, transformed);
                }
            }
        } catch (Throwable t) {
            logger.error("Failed intercepting message object", t);
        }
    }

    public String transformOutgoing(String originalText, long channelId) {
        if (originalText == null || originalText.isEmpty()) return originalText;

        long guildId = 0L;
        String channelName = "";
        String guildName = "";

        try {
            if (channelId != 0L) {
                var channel = StoreStream.getChannels().getChannel(channelId);
                if (channel != null) {
                    guildId = channel.getGuildId();
                    channelName = channel.getName();
                }
                if (guildId != 0L) {
                    var guild = StoreStream.getGuilds().getGuild(guildId);
                    if (guild != null) {
                        guildName = guild.getName();
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (!MessageTransformer.shouldApplyToScope(activeConfig, channelId, guildId, channelName, guildName)) {
            return originalText;
        }

        return MessageTransformer.applyReplacements(originalText, activeConfig, channelId);
    }

    private void registerCommands() {
        commands.registerCommand(
                "keyintercept",
                "Key Intercept configuration and status",
                List.of(
                        Utils.createCommandOption(ApplicationCommandType.STRING, "action", "Action: status, sync, or reset")
                ),
                ctx -> {
                    String action = ctx.getString("action");
                    if ("sync".equalsIgnoreCase(action)) {
                        syncConfigAsync(() -> Utils.showToast("Key Intercept synced!"));
                        return new CommandsAPI.CommandResult("Syncing Key Intercept config with relay...");
                    } else if ("reset".equalsIgnoreCase(action)) {
                        activeConfig = new KeyInterceptConfig();
                        saveAndPushAsync();
                        return new CommandsAPI.CommandResult("Key Intercept configuration reset to defaults.");
                    }

                    return new CommandsAPI.CommandResult("Key Intercept is active. Target user: " + targetUserId + "\nRelay: " + relayUrl);
                }
        );
    }

    public void saveAndPushAsync() {
        saveLocally();
        new Thread(() -> {
            try {
                if (currentUserId.isEmpty()) resolveCurrentUserId();
                if (currentUserId.isEmpty()) return;

                if (targetUserId.equals(currentUserId)) {
                    localRevision++;
                    settings.setInt("local_revision", localRevision);
                    RelayClient.pushRemoteConfig(relayUrl, currentUserId, currentUserId, activeConfig, null);
                    RelayClient.uploadMobileSnapshot(relayUrl, currentUserId, localRevision, currentUserId, activeConfig, allowedEditors);
                } else {
                    RelayClient.pushRemoteConfig(relayUrl, currentUserId, targetUserId, activeConfig, null);
                }
            } catch (Throwable t) {
                logger.error("Failed pushing config", t);
            }
        }).start();
    }

    public void saveLocally() {
        try {
            settings.setString("config_json", activeConfig.toJson().toString());
        } catch (Exception ignored) {}
    }

    public void syncConfigAsync(Runnable onComplete) {
        new Thread(() -> {
            try {
                if (currentUserId.isEmpty()) resolveCurrentUserId();
                if (currentUserId.isEmpty()) {
                    if (onComplete != null) Utils.mainThread.post(onComplete);
                    return;
                }

                // 1. Fetch remote config
                KeyInterceptConfig remote = RelayClient.readRemoteConfig(relayUrl, currentUserId, targetUserId);
                if (remote != null) {
                    activeConfig = remote;
                    saveLocally();
                }

                // 2. If own profile, fetch requests and sync loopback
                if (targetUserId.equals(currentUserId)) {
                    try {
                        pendingRequests = RelayClient.getAccessRequests(relayUrl, currentUserId);
                    } catch (Throwable ignored) {}

                    try {
                        JSONObject syncRes = RelayClient.syncInAppLoopback(relayUrl, currentUserId, localRevision);
                        if (syncRes != null && syncRes.has("revision")) {
                            localRevision = Math.max(localRevision, syncRes.optInt("revision", localRevision));
                            settings.setInt("local_revision", localRevision);
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                logger.error("Failed sync", t);
            } finally {
                if (onComplete != null) {
                    Utils.mainThread.post(onComplete);
                }
            }
        }).start();
    }

    public void loadConfigForTarget() {
        syncConfigAsync(() -> Utils.showToast("Loaded config for " + targetUserId));
    }

    public void approveRequestAsync(String requesterId) {
        new Thread(() -> {
            try {
                RelayClient.approveAccessRequest(relayUrl, currentUserId, requesterId);
                pendingRequests.remove(requesterId);
                if (!allowedEditors.contains(requesterId)) {
                    allowedEditors.add(requesterId);
                }
                saveAndPushAsync();
            } catch (Throwable t) {
                logger.error("Failed approving access request", t);
            }
        }).start();
    }

    public void denyRequestAsync(String requesterId) {
        new Thread(() -> {
            try {
                RelayClient.denyAccessRequest(relayUrl, currentUserId, requesterId);
                pendingRequests.remove(requesterId);
            } catch (Throwable t) {
                logger.error("Failed denying access request", t);
            }
        }).start();
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
        commands.unregisterAll();
    }
}
