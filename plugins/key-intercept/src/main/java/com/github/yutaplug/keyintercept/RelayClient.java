package com.github.yutaplug.keyintercept;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RelayClient {
    public static class HttpResponse {
        public final int statusCode;
        public final String body;

        public HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    public static String cleanBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return KeyInterceptConfig.DEFAULT_RELAY_URL;
        }
        return url.trim().replaceAll("/+$", "");
    }

    public static HttpResponse sendRequest(String urlStr, String method, String jsonBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setUseCaches(false);

        if (jsonBody != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder responseSb = new StringBuilder();
        if (is != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    responseSb.append(line).append('\n');
                }
            }
        }
        conn.disconnect();
        return new HttpResponse(code, responseSb.toString().trim());
    }

    public static KeyInterceptConfig readRemoteConfig(String relayUrl, String requesterId, String targetUserId) throws Exception {
        String base = cleanBaseUrl(relayUrl);
        String encRequester = URLEncoder.encode(requesterId, "UTF-8");
        String urlProfile = base + "/users/" + targetUserId + "/profile-state?requester_id=" + encRequester;

        HttpResponse res = sendRequest(urlProfile, "GET", null);
        if (res.isSuccess()) {
            JSONObject json = new JSONObject(res.body);
            return KeyInterceptConfig.fromJson(json.has("config") ? json.getJSONObject("config") : json);
        }

        // Fallback to legacy config endpoint
        String urlLegacy = base + "/users/" + targetUserId + "/config?requester_id=" + encRequester;
        HttpResponse legacyRes = sendRequest(urlLegacy, "GET", null);
        if (legacyRes.isSuccess()) {
            JSONObject json = new JSONObject(legacyRes.body);
            return KeyInterceptConfig.fromJson(json.has("config") ? json.getJSONObject("config") : json);
        }

        throw new Exception("Relay error: " + legacyRes.statusCode + " " + legacyRes.body);
    }

    public static void pushRemoteConfig(String relayUrl, String editorId, String targetUserId, KeyInterceptConfig config, Integer expectedRevision) throws Exception {
        String base = cleanBaseUrl(relayUrl);
        String url = base + "/users/" + targetUserId + "/config";

        JSONObject body = new JSONObject();
        body.put("editor_id", editorId);
        body.put("config", config.toJson());
        if (expectedRevision != null) {
            body.put("expected_revision", expectedRevision.intValue());
        }

        HttpResponse res = sendRequest(url, "PUT", body.toString());
        if (!res.isSuccess()) {
            throw new Exception("Push config failed: " + res.statusCode + " " + res.body);
        }
    }

    public static void uploadMobileSnapshot(String relayUrl, String ownerId, int revision, String lastWriterId, KeyInterceptConfig config, List<String> allowedEditors) throws Exception {
        String base = cleanBaseUrl(relayUrl);
        String url = base + "/users/" + ownerId + "/mobile/snapshot";

        JSONObject body = new JSONObject();
        body.put("owner_id", ownerId);
        body.put("revision", revision);
        body.put("last_writer_id", lastWriterId);
        body.put("config", config.toJson());

        JSONArray editorsArr = new JSONArray();
        if (allowedEditors != null) {
            for (String ed : allowedEditors) editorsArr.put(ed);
        }
        body.put("allowed_editors", editorsArr);

        HttpResponse res = sendRequest(url, "POST", body.toString());
        if (!res.isSuccess() && res.statusCode != 404) {
            throw new Exception("Upload snapshot failed: " + res.statusCode + " " + res.body);
        }
    }

    public static JSONObject syncInAppLoopback(String relayUrl, String ownerId, int revision) throws Exception {
        String base = cleanBaseUrl(relayUrl);
        String url = base + "/users/" + ownerId + "/mobile/sync?requester_id=" + URLEncoder.encode(ownerId, "UTF-8") + "&after_revision=" + revision;

        HttpResponse res = sendRequest(url, "GET", null);
        if (res.statusCode == 404) return null;
        if (!res.isSuccess()) {
            throw new Exception("Sync failed: " + res.statusCode + " " + res.body);
        }
        return new JSONObject(res.body);
    }

    public static void requestRemoteAccess(String relayUrl, String requesterId, String targetUserId) throws Exception {
        String base = cleanBaseUrl(relayUrl);
        String url = base + "/users/" + targetUserId + "/access-requests";

        JSONObject body = new JSONObject();
        body.put("requester_id", requesterId);

        HttpResponse res = sendRequest(url, "POST", body.toString());
        if (!res.isSuccess()) {
            throw new Exception("Request access failed: " + res.statusCode);
        }
    }

    public static List<String> getAccessRequests(String relayUrl, String ownerId) throws Exception {
        String base = cleanBaseUrl(relayUrl);
        String url = base + "/users/" + ownerId + "/access-requests?requester_id=" + URLEncoder.encode(ownerId, "UTF-8");

        HttpResponse res = sendRequest(url, "GET", null);
        if (!res.isSuccess()) {
            throw new Exception("Get requests failed: " + res.statusCode);
        }
        JSONObject json = new JSONObject(res.body);
        JSONArray arr = json.optJSONArray("requests");
        List<String> list = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, null);
                if (id != null && !id.isEmpty()) list.add(id);
            }
        }
        return list;
    }

    public static void approveAccessRequest(String relayUrl, String ownerId, String requesterId) throws Exception {
        String base = cleanBaseUrl(relayUrl);
        String url = base + "/users/" + ownerId + "/access-requests/" + URLEncoder.encode(requesterId, "UTF-8") + "/approve";

        JSONObject body = new JSONObject();
        body.put("owner_id", ownerId);

        HttpResponse res = sendRequest(url, "POST", body.toString());
        if (!res.isSuccess()) {
            throw new Exception("Approve failed: " + res.statusCode);
        }
    }

    public static void denyAccessRequest(String relayUrl, String ownerId, String requesterId) throws Exception {
        String base = cleanBaseUrl(relayUrl);
        String url = base + "/users/" + ownerId + "/access-requests/" + URLEncoder.encode(requesterId, "UTF-8") + "?requester_id=" + URLEncoder.encode(ownerId, "UTF-8");

        HttpResponse res = sendRequest(url, "DELETE", null);
        if (!res.isSuccess()) {
            throw new Exception("Deny failed: " + res.statusCode);
        }
    }
}
