package com.github.yutaplug.customrpc;

import android.content.Context;

import androidx.annotation.NonNull;

import com.aliucord.Http;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.patcher.PreHook;
import com.discord.app.AppActivity;
import com.discord.api.activity.Activity;
import com.discord.api.activity.ActivityAssets;
import com.discord.api.activity.ActivityType;
import com.discord.api.presence.ClientStatus;
import com.discord.gateway.GatewaySocket;
import com.discord.models.domain.ModelPayload;
import com.discord.models.domain.ModelUserSettings;
import com.discord.models.presence.Presence;
import com.discord.stores.Dispatcher;
import com.discord.stores.StoreGatewayConnection;
import com.discord.stores.StoreConnectionOpen;
import com.discord.stores.StoreStream;
import com.discord.stores.StoreUserPresence;
import com.discord.utilities.icon.IconUtils;
import com.discord.utilities.rest.RestAPI;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import kotlin.Unit;

@SuppressWarnings({"unused", "unchecked"})
@AliucordPlugin
public final class CustomRPC extends Plugin {
    public static final String ENABLED = "enabled";
    public static final String ACTIVITY_TYPE = "activityType";
    public static final String ACTIVITY_FLAGS = "activityFlags";
    public static final String APPLICATION_ID = "applicationId";
    public static final String NAME = "name";
    public static final String DETAILS = "details";
    public static final String STATE = "state";
    public static final String LARGE_IMAGE = "largeImage";
    public static final String LARGE_IMAGE_TEXT = "largeImageText";
    public static final String SMALL_IMAGE = "smallImage";
    public static final String SMALL_IMAGE_TEXT = "smallImageText";
    public static final String LARGE_IMAGE_URL = "largeImageUrl";
    public static final String SMALL_IMAGE_URL = "smallImageUrl";

    private static final String DEFAULT_NAME = "Custom RPC";
    private static final long EXTERNAL_IMAGE_RETRY_DELAY_MS = 60_000L;
    private static final ActivityType DEFAULT_ACTIVITY_TYPE = ActivityType.PLAYING;
    // Match Vencord's known-working custom RPC payload for all local and gateway paths.
    private static final int DEFAULT_ACTIVITY_FLAGS = ActivityFlags.INSTANCE | ActivityFlags.EMBEDDED;

    private boolean updatingPresence;
    private boolean presenceUpdatePending;
    private boolean activitySharingEnabled;
    private WeakReference<AppActivity> lastActivity;
    private final Map<String, String> externalImagePaths = new ConcurrentHashMap<>();
    private final Map<String, Long> externalImageFailures = new ConcurrentHashMap<>();
    private final Set<String> externalImageRequests = ConcurrentHashMap.newKeySet();

    public CustomRPC() {
        settingsTab = new SettingsTab(CustomRPCSettings.class, SettingsTab.Type.BOTTOM_SHEET)
                .withArgs(settings, this);
    }

    @Override
    public void start(@NonNull Context context) {
        if (isEnabled()) enableActivitySharing(context);

        // Discord's native renderer resolves every activity image as an application
        // asset when the value is not an already-proxied "mp:" image. Keep raw public
        // URLs working locally while external URLs are being proxied for other clients.
        patcher.patch(
                IconUtils.class,
                "getAssetImage",
                new Class<?>[]{Long.class, String.class, int.class},
                new PreHook(param -> {
                    if (param.args[1] instanceof String) {
                        String url = publicImageUrl((String) param.args[1]);
                        if (url != null) param.setResult(url);
                    }
                })
        );

        patcher.patch(
                AppActivity.class,
                "onResume",
                new Class<?>[]{},
                new Hook(param -> {
                    if (isEnabled()) {
                        enableActivitySharing((AppActivity) param.thisObject);
                        applyActivity();
                    }
                })
        );

        // Keep the custom activity in the exact payload that Discord sends through the gateway.
        patcher.patch(
                StoreGatewayConnection.class,
                "presenceUpdate",
                new Class<?>[]{ClientStatus.class, Long.class, List.class, Boolean.class},
                new PreHook(param -> {
                    if (!isEnabled()) return;
                    param.args[2] = addGatewayActivity(param.args[2]);
                })
        );
        patcher.patch(
                GatewaySocket.class,
                "presenceUpdate",
                new Class<?>[]{ClientStatus.class, Long.class, List.class, Boolean.class},
                new PreHook(param -> {
                    if (!isEnabled()) return;
                    param.args[2] = addGatewayActivity(param.args[2]);
                })
        );

        patcher.patch(
                StoreUserPresence.class,
                "updateActivity",
                new Class<?>[]{ActivityType.class, Activity.class, boolean.class},
                new Hook(param -> reapplyIfEnabled())
        );

        // StoreUserPresence rebuilds local activities after these events. Reapply afterwards.
        patcher.patch(
                StoreUserPresence.class,
                "handleConnectionOpen",
                new Class<?>[]{ModelPayload.class},
                new Hook(param -> reapplyIfEnabled())
        );
        patcher.patch(
                StoreUserPresence.class,
                "handleUserSettingsUpdate",
                new Class<?>[]{ModelUserSettings.class},
                new Hook(param -> reapplyIfEnabled())
        );
        patcher.patch(
                StoreUserPresence.class,
                "handleSessionsReplace",
                new Class<?>[]{List.class},
                new Hook(param -> reapplyIfEnabled())
        );
        patcher.patch(
                StoreConnectionOpen.class,
                "handleConnectionOpen",
                new Class<?>[]{},
                new Hook(param -> reapplyIfEnabled())
        );

        if (isEnabled()) applyActivity();
    }

    @Override
    public void stop(@NonNull Context context) {
        patcher.unpatchAll();
        activitySharingEnabled = false;
        presenceUpdatePending = false;
        externalImagePaths.clear();
        externalImageFailures.clear();
        externalImageRequests.clear();
        clearActivity();
    }

    void setEnabled(boolean enabled) {
        settings.setBool(ENABLED, enabled);
        if (enabled) {
            enableActivitySharing(null);
            applyActivity();
        } else {
            activitySharingEnabled = false;
            clearActivity();
        }
    }

    void setActivityType(ActivityType activityType) {
        if (!isSupportedActivityType(activityType)) return;
        ActivityType previous = getActivityType();
        settings.setString(ACTIVITY_TYPE, activityType.name());
        if (isEnabled()) {
            if (previous != activityType) updatePresence(previous, null);
            applyActivity();
        }
    }

    ActivityType getActivityType() {
        String saved = settings.getString(ACTIVITY_TYPE, DEFAULT_ACTIVITY_TYPE.name());
        if (saved != null) {
            try {
                ActivityType activityType = ActivityType.valueOf(saved);
                if (isSupportedActivityType(activityType)) return activityType;
            } catch (IllegalArgumentException ignored) {
                // Use Playing for old or invalid settings.
            }
        }
        return DEFAULT_ACTIVITY_TYPE;
    }

    String getActivityTypeLabel() {
        return activityTypeLabel(getActivityType());
    }

    int getActivityFlags() {
        return settings.getInt(ACTIVITY_FLAGS, DEFAULT_ACTIVITY_FLAGS);
    }

    void setActivityFlags(int flags) {
        settings.setInt(ACTIVITY_FLAGS, flags);
        if (isEnabled()) applyActivity();
    }

    String getActivityFlagsLabel() {
        return ActivityFlags.label(getActivityFlags());
    }

    boolean saveAndApply(String applicationId, String name, String details, String state,
            String largeImage, String largeImageText, String smallImage, String smallImageText,
            String largeImageUrl, String smallImageUrl) {
        settings.setString(APPLICATION_ID, clean(applicationId));
        settings.setString(NAME, clean(name));
        settings.setString(DETAILS, clean(details));
        settings.setString(STATE, clean(state));
        settings.setString(LARGE_IMAGE, clean(largeImage));
        settings.setString(LARGE_IMAGE_TEXT, clean(largeImageText));
        settings.setString(SMALL_IMAGE, clean(smallImage));
        settings.setString(SMALL_IMAGE_TEXT, clean(smallImageText));
        settings.setString(LARGE_IMAGE_URL, clean(largeImageUrl));
        settings.setString(SMALL_IMAGE_URL, clean(smallImageUrl));
        clearExternalImageCache();
        enableActivitySharing(null);
        setEnabled(true);
        return true;
    }

    void saveField(String key, String value) {
        settings.setString(key, clean(value));
        if (APPLICATION_ID.equals(key)
                || LARGE_IMAGE_URL.equals(key)
                || SMALL_IMAGE_URL.equals(key)) {
            clearExternalImageCache();
        }
        if (isEnabled()) applyActivity();
    }

    private boolean isEnabled() {
        return settings.getBool(ENABLED, false);
    }

    private void reapplyIfEnabled() {
        if (isEnabled() && !updatingPresence) {
            applyActivity();
        }
    }

    void enableActivitySharing(Context context) {
        AppActivity activity = context instanceof AppActivity ? (AppActivity) context : null;
        if (activity != null) {
            lastActivity = new WeakReference<>(activity);
        } else if (lastActivity != null) {
            activity = lastActivity.get();
        }
        if (activitySharingEnabled && activity == null) return;
        StoreStream.getUserSettings().setIsShowCurrentGameEnabled(activity, true);
        if (activity != null) activitySharingEnabled = true;
    }

    private void applyActivity() {
        if (!isEnabled()) return;
        requestExternalImages();
        updatePresence(getActivityType(), createActivity());
    }

    private void clearActivity() {
        updatePresence(getActivityType(), null);
    }

    private void updatePresence(ActivityType activityType, Activity activity) {
        try {
            Dispatcher dispatcher = StoreStream.getDispatcherYesThisIsIntentional();
            dispatcher.schedule(() -> {
                synchronized (CustomRPC.this) {
                    if (updatingPresence) {
                        presenceUpdatePending = true;
                        return Unit.a;
                    }
                    updatingPresence = true;
                }
                try {
                    StoreUserPresence presences = StoreStream.getPresences();
                    presences.updateActivity(activityType, activity, true);

                    // StoreUserPresence normally sends this during its snapshot. Send the
                    // current local presence immediately too, so a custom activity is not
                    // left visible only in the local UI when no snapshot is scheduled.
                    Presence localPresence = presences.getLocalPresence$app_productionGoogleRelease();
                    if (localPresence != null) {
                        StoreStream.getGatewaySocket().presenceUpdate(
                                localPresence.getStatus(),
                                System.currentTimeMillis(),
                                localPresence.getActivities(),
                                null
                        );
                    }
                } catch (Throwable error) {
                    logger.error("Failed to update CustomRPC presence", error);
                } finally {
                    boolean retry;
                    synchronized (CustomRPC.this) {
                        updatingPresence = false;
                        retry = presenceUpdatePending;
                        presenceUpdatePending = false;
                    }
                    if (retry && isEnabled()) {
                        updatePresence(getActivityType(), createActivity());
                    }
                }
                return Unit.a;
            });
        } catch (Throwable error) {
            logger.error("Failed to schedule CustomRPC presence update", error);
        }
    }

    private Activity createActivity() {
        return createActivity(getActivityFlags());
    }

    private ArrayList<Activity> addGatewayActivity(Object value) {
        List<Activity> existing = value instanceof List
                ? (List<Activity>) value
                : null;
        ArrayList<Activity> activities = existing == null
                ? new ArrayList<>()
                : new ArrayList<>(existing);
        ActivityType customType = getActivityType();
        Iterator<Activity> iterator = activities.iterator();
        while (iterator.hasNext()) {
            Activity activity = iterator.next();
            if (activity != null && activity.p() == customType) iterator.remove();
        }
        activities.add(createActivity());
        return activities;
    }

    private Activity createActivity(int flags) {
        String name = value(NAME, DEFAULT_NAME);
        String details = optional(DETAILS);
        String state = optional(STATE);
        Long applicationId = parseApplicationId();
        String largeImageKey = optional(LARGE_IMAGE);
        String largeImageUrl = publicImageUrl(optional(LARGE_IMAGE_URL));
        String largeImage = largeImageUrl != null
                ? activityImage(largeImageUrl, applicationId)
                : applicationId != null ? largeImageKey : null;
        String largeImageText = largeImage == null ? null : optional(LARGE_IMAGE_TEXT);
        String smallImageKey = optional(SMALL_IMAGE);
        String smallImageUrl = publicImageUrl(optional(SMALL_IMAGE_URL));
        String smallImage = smallImageUrl != null
                ? activityImage(smallImageUrl, applicationId)
                : applicationId != null ? smallImageKey : null;
        String smallImageText = smallImage == null ? null : optional(SMALL_IMAGE_TEXT);

        ActivityAssets assets = largeImage != null || smallImage != null
                ? new ActivityAssets(largeImage, largeImageText, smallImage, smallImageText)
                : null;

        return new Activity(
                name,
                getActivityType(),
                null,
                System.currentTimeMillis(),
                null,
                applicationId,
                details,
                state,
                null,
                null,
                assets,
                flags,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private Long parseApplicationId() {
        String raw = optional(APPLICATION_ID);
        if (raw == null) return null;
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void requestExternalImages() {
        Long applicationId = parseApplicationId();
        if (applicationId == null) return;

        ArrayList<String> urls = new ArrayList<>(2);
        addExternalImageUrl(urls, optional(LARGE_IMAGE_URL));
        addExternalImageUrl(urls, optional(SMALL_IMAGE_URL));
        if (urls.isEmpty()) return;

        ArrayList<String> pending = new ArrayList<>(urls.size());
        long now = System.currentTimeMillis();
        for (String url : urls) {
            String key = externalImageKey(applicationId, url);
            if (externalImagePaths.containsKey(key)) continue;

            Long failedAt = externalImageFailures.get(key);
            if (failedAt != null && now - failedAt < EXTERNAL_IMAGE_RETRY_DELAY_MS) continue;
            if (externalImageRequests.add(key)) pending.add(url);
        }
        if (pending.isEmpty()) return;

        Utils.threadPool.execute(() -> fetchExternalImagePaths(applicationId, pending));
    }

    private void fetchExternalImagePaths(long applicationId, List<String> urls) {
        Set<String> resolved = new java.util.HashSet<>();
        boolean changed = false;
        try {
            JSONArray requestedUrls = new JSONArray();
            for (String url : urls) requestedUrls.put(url);
            JSONObject request = new JSONObject().put("urls", requestedUrls);
            String route = "/applications/" + applicationId + "/external-assets";
            try (Http.Request httpRequest = Http.Request.newDiscordRNRequest(route, "POST")) {
                httpRequest.setRequestTimeout(10_000);
                httpRequest.setHeader("Content-Type", "application/json");
                String fingerprint = RestAPI.AppHeadersProvider.INSTANCE.getFingerprint();
                if (fingerprint != null) httpRequest.setHeader("X-Fingerprint", fingerprint);
                Http.Response response = httpRequest.executeWithBody(request.toString());
                if (!response.ok()) {
                    throw new IOException("Discord external image request failed with HTTP "
                            + response.statusCode);
                }

                JSONArray assets = new JSONArray(response.text());
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;

                    String sourceUrl = asset.optString("url", "").trim();
                    String externalPath = asset.optString("external_asset_path", "").trim();
                    if (assets.length() == urls.size()
                            && (sourceUrl.isEmpty() || !urls.contains(sourceUrl))) {
                        sourceUrl = urls.get(i);
                    }
                    if (!urls.contains(sourceUrl)) continue;

                    String mediaProxyImage = mediaProxyImage(externalPath);
                    if (mediaProxyImage == null) continue;
                    externalImagePaths.put(externalImageKey(applicationId, sourceUrl), mediaProxyImage);
                    resolved.add(sourceUrl);
                    changed = true;
                }
            }
        } catch (Throwable error) {
            logger.error("Failed to proxy CustomRPC image URLs", error);
        } finally {
            long failedAt = System.currentTimeMillis();
            for (String url : urls) {
                String key = externalImageKey(applicationId, url);
                externalImageRequests.remove(key);
                if (resolved.contains(url)) {
                    externalImageFailures.remove(key);
                } else {
                    externalImageFailures.put(key, failedAt);
                }
            }
        }

        if (changed) {
            Utils.mainThread.post(() -> {
                if (isEnabled()) applyActivity();
            });
        }
    }

    private static void addExternalImageUrl(List<String> urls, String value) {
        String url = publicImageUrl(value);
        if (url != null && !urls.contains(url)) urls.add(url);
    }

    private String activityImage(String url, Long applicationId) {
        if (applicationId != null) {
            String proxied = externalImagePaths.get(externalImageKey(applicationId, url));
            if (proxied != null) return proxied;
        }
        return url;
    }

    private static String externalImageKey(long applicationId, String url) {
        return applicationId + ":" + url;
    }

    private static String mediaProxyImage(String path) {
        if (path == null || path.isEmpty()) return null;
        String mediaProxyPrefix = "https://media.discordapp.net/";
        if (path.regionMatches(true, 0, mediaProxyPrefix, 0, mediaProxyPrefix.length())) {
            path = path.substring(mediaProxyPrefix.length());
        }
        while (path.startsWith("/")) path = path.substring(1);
        return path.isEmpty() ? null : path.startsWith("mp:") ? path : "mp:" + path;
    }

    private void clearExternalImageCache() {
        externalImagePaths.clear();
        externalImageFailures.clear();
        externalImageRequests.clear();
    }

    @SuppressWarnings("SameParameterValue")
    private String value(String key, String fallback) {
        String value = optional(key);
        return value == null ? fallback : value;
    }

    private String optional(String key) {
        String value = settings.getString(key, "");
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String publicImageUrl(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.regionMatches(true, 0, "http://", 0, 7)
                || trimmed.regionMatches(true, 0, "https://", 0, 8)
                ? trimmed : null;
    }

    private static boolean isSupportedActivityType(ActivityType activityType) {
        return activityType == ActivityType.PLAYING
                || activityType == ActivityType.STREAMING
                || activityType == ActivityType.LISTENING
                || activityType == ActivityType.WATCHING
                || activityType == ActivityType.COMPETING;
    }

    private static String activityTypeLabel(ActivityType activityType) {
        return switch (activityType) {
            case STREAMING -> "Streaming";
            case LISTENING -> "Listening to";
            case WATCHING -> "Watching";
            case COMPETING -> "Competing in";
            default -> "Playing";
        };
    }
}
