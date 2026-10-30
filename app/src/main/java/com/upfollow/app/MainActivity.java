package com.upfollow.app;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// =========================================================
//  HIDDEN HTTP STACK: okhttp3 (HTTP/2 + ALPN, bina WebView)
//  Gradle me add karo: implementation 'com.squareup.okhttp3:okhttp:4.12.0'
// =========================================================
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;

public class MainActivity extends Activity {

    private static final String APP_SECRET_KEY = "brohu2580";
    private static final int APP_VERSION_CODE = 6;

    private String USER_AGENT_APP = "";
    private String USER_AGENT = "";

    // ---- WebViews: sirf main dashboard ke liye ----
    private WebView mainWebView;

    private static final String HOSTING_BASE = "https://follow2follow.shop/";
    private static final String HOSTING_DASHBOARD = HOSTING_BASE + "index.php";
    private static final String IG_URL = "https://www.instagram.com";
    private static final String IG_LOGIN_URL = "https://www.instagram.com/accounts/login/";

    private static final String CHANNEL_ID = "upfollow_automation_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long TASK_TIMEOUT_MS = 20000L;

    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private volatile String mainUrl = "";
    private String deviceKey = "";
    private SharedPreferences prefs;

    // Instagram login capture
    private boolean captureBusy = false;
    private boolean addMode = false;
    private String captureNonce = "";
    private String captureCookies = "";

    // =========================================================
    //  OKHTTP CLIENT SETUP
    //  - HTTP/2 + HTTP/1.1 fallback (ALPN se negotiate hota hai)
    //  - Custom TLS + CookieJar: cookies string se inject
    //  - User-Agent: normal phone UA (WebView se match)
    //  - Ye sab CDN / firewall ke liye unseen HTTP stack hai
    // =========================================================
    private OkHttpClient httpClient;
    private final ExecutorService ioPool = Executors.newCachedThreadPool();

    // In-memory cookie store per-account (session cookies thread-safe)
    private final ConcurrentHashMap<String, List<Cookie>> cookieStore = new ConcurrentHashMap<>();

    private void buildHttpClient() {
        CookieJar jar = new CookieJar() {
            @Override
            public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                // Dynamic cookies save (API se jo milein)
                List<Cookie> existing = cookieStore.get(url.host());
                if (existing == null) existing = new ArrayList<>();
                for (Cookie c : cookies) {
                    existing.removeIf(o -> o.name().equals(c.name()));
                    existing.add(c);
                }
                cookieStore.put(url.host(), existing);
            }

            @Override
            public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> c = cookieStore.get(url.host());
                return c != null ? c : new ArrayList<>();
            }
        };

        httpClient = new OkHttpClient.Builder()
                // HTTP/2 preferred, HTTP/1.1 fallback (ALPN)
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .cookieJar(jar)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                // Redirects follow karo (login redirect handle)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    // cookieStr (Instagram cookie string) se OkHttp store me inject karo
    private void injectCookiesToHttp(String cookieStr) {
        if (cookieStr == null || cookieStr.isEmpty()) return;
        HttpUrl igUrl = HttpUrl.parse(IG_URL + "/");
        if (igUrl == null) return;
        List<Cookie> list = new ArrayList<>();
        for (String part : cookieStr.split(";")) {
            String p = part.trim();
            int i = p.indexOf('=');
            if (i <= 0) continue;
            String name = p.substring(0, i).trim();
            String value = p.substring(i + 1).trim();
            list.add(new Cookie.Builder()
                    .domain("instagram.com")
                    .path("/")
                    .name(name)
                    .value(value)
                    .build());
        }
        cookieStore.put("www.instagram.com", list);
        cookieStore.put("instagram.com", list);
    }

    // OkHttp cookie store se csrftoken nikalo
    private String getCsrfFromStore() {
        List<Cookie> list = cookieStore.get("www.instagram.com");
        if (list == null) return "";
        for (Cookie c : list) {
            if ("csrftoken".equals(c.name())) return c.value();
        }
        return "";
    }

    // =========================================================
    //  IG HEADERS: asli Instagram app jaise headers
    //  (sabse badi trick yahi hai — common HTTP client yahi nahi karta)
    // =========================================================
    private Map<String, String> igHeaders(String csrf) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", USER_AGENT);
        h.put("X-IG-App-ID", "936619743392459");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("Accept", "*/*");
        h.put("Accept-Language", "en-US,en;q=0.9");
        h.put("Accept-Encoding", "gzip, deflate, br");
        h.put("Origin", IG_URL);
        h.put("Referer", IG_URL + "/");
        h.put("Sec-Fetch-Site", "same-origin");
        h.put("Sec-Fetch-Mode", "cors");
        h.put("Sec-Fetch-Dest", "empty");
        if (csrf != null && !csrf.isEmpty()) {
            h.put("X-CSRFToken", csrf);
        }
        return h;
    }

    private Request.Builder addIgHeaders(Request.Builder b, String csrf) {
        for (Map.Entry<String, String> e : igHeaders(csrf).entrySet()) {
            b.header(e.getKey(), e.getValue());
        }
        return b;
    }

    // =========================================================
    //  TASK SYSTEM: follow + like seedha OkHttp se
    // =========================================================
    private static final boolean SINGLE_QUEUE_ENABLED = true;
    private static final boolean RESULT_RETRY_ENABLED = true;
    private static final long RESULT_MAX_AGE_MS = 120000L;

    private final ArrayDeque<PTask> singleQueue = new ArrayDeque<>();
    private long singleLastEnd = 0;
    private long singleGap = 0;
    private boolean taskActive = false;
    private boolean singlePumpScheduled = false;
    private String currentTaskType = "follow";

    private final Runnable singlePumpRunnable = new Runnable() {
        @Override public void run() {
            singlePumpScheduled = false;
            singlePump();
        }
    };

    private final Runnable taskTimeout = new Runnable() {
        @Override public void run() {
            if (taskActive) {
                deliverResult(false, "Timeout");
            }
        }
    };

    private static final long MIN_GAP_MS = 7000L;
    private volatile long startGapMs = 7000L;
    private volatile long gapJitterMs = 0L;
    private long curGapMs = 7000L;
    private final Random rnd = new Random();

    private long nextGap() {
        long j = gapJitterMs;
        long g = startGapMs;
        if (j > 0) g += (long)(rnd.nextDouble() * j);
        return Math.max(MIN_GAP_MS, g);
    }

    private void singleEnqueue(String target, String taskType, String mediaId, String cookieStr) {
        final PTask t = new PTask();
        t.target = target == null ? "" : target;
        t.taskType = taskType == null ? "follow" : taskType;
        t.mediaId = mediaId == null ? "" : mediaId;
        t.cookieStr = cookieStr == null ? "" : cookieStr;
        t.uid = cookieValue(t.cookieStr, "ds_user_id").replaceAll("[^A-Za-z0-9]", "");
        t.key = taskKey(t.taskType, t.target, t.mediaId);
        uiHandler.post(new Runnable() {
            @Override public void run() {
                singleQueue.add(t);
                singlePump();
            }
        });
    }

    private void singlePump() {
        if (taskActive || singleQueue.isEmpty()) return;
        long wait = singleLastEnd + singleGap - System.currentTimeMillis();
        if (wait > 0) {
            if (!singlePumpScheduled) {
                singlePumpScheduled = true;
                uiHandler.postDelayed(singlePumpRunnable, wait);
            }
            return;
        }
        PTask t = singleQueue.poll();
        if (t == null) return;
        startApiTask(t);
    }

    private void startApiTask(final PTask t) {
        taskActive = true;
        currentTaskType = t.taskType;
        uiHandler.removeCallbacks(taskTimeout);
        uiHandler.postDelayed(taskTimeout, TASK_TIMEOUT_MS);
        dbg("api task start uid=" + t.uid + " type=" + t.taskType + " target=" + cleanName(t.target));
        notifyStarted(t.uid, cleanName(t.target), t.taskType);

        // Cookies inject karo is task ke liye
        injectCookiesToHttp(t.cookieStr);

        ioPool.execute(new Runnable() {
            @Override public void run() {
                try {
                    if ("like".equalsIgnoreCase(t.taskType)) {
                        runLikeApi(t);
                    } else {
                        runFollowApi(t);
                    }
                } catch (Exception e) {
                    deliverResult(false, "Network error: " + e.getMessage());
                }
            }
        });
    }

    // =========================================================
    //  FOLLOW API: seedha Instagram friendship/create endpoint
    //  Step 1: web_profile_info se user_id nikalo (cached)
    //  Step 2: /api/v1/friendships/create/{id}/ POST
    // =========================================================
    private final ConcurrentHashMap<String, String> fastIdCache = new ConcurrentHashMap<>();

    private void runFollowApi(PTask t) throws IOException {
        String name = cleanName(t.target);
        if (name.isEmpty()) { deliverResult(false, "Invalid target"); return; }

        String csrf = getCsrfFromStore();
        if (csrf.isEmpty()) { deliverResult(false, "Session expired"); return; }

        // Cached ID check
        String cachedId = fastIdCache.get(name.toLowerCase());
        if (cachedId != null) {
            followWithId(name, cachedId, csrf, true, t);
            return;
        }

        // Step 1: User ID fetch
        Request req = addIgHeaders(new Request.Builder()
                .url(IG_URL + "/api/v1/users/web_profile_info/?username=" + URLEncoder.encode(name, "UTF-8")), csrf)
                .get().build();

        try (Response r = httpClient.newCall(req).execute()) {
            String body = r.body() != null ? r.body().string() : "";
            String low = body.toLowerCase();

            if (low.contains("checkpoint_required") || low.contains("challenge_required")) {
                deliverResult(false, "Blocked: Challenge / Checkpoint"); return;
            }
            if (low.contains("login_required")) {
                deliverResult(false, "Session expired"); return;
            }
            if (r.code() == 429) {
                deliverResult(false, "Blocked: Action Limit / Challenge"); return;
            }

            JSONObject d = null;
            try { d = new JSONObject(body); } catch (Exception ignored) {}
            if (d == null) { deliverResult(false, "API parse error"); return; }

            JSONObject user = null;
            try {
                user = d.optJSONObject("data") != null
                        ? d.getJSONObject("data").optJSONObject("user")
                        : d.optJSONObject("user");
            } catch (Exception ignored) {}

            if (user == null || !user.has("id")) {
                deliverResult(false, "User not found"); return;
            }

            String userId = user.optString("id", "");
            if (userId.isEmpty()) { deliverResult(false, "User not found"); return; }

            // Cache kar lo
            fastIdCache.put(name.toLowerCase(), userId);

            boolean alreadyFollowing = user.optBoolean("followed_by_viewer", false)
                    || user.optBoolean("requested_by_viewer", false);
            if (alreadyFollowing) {
                deliverResult(true, "Already Following"); return;
            }

            followWithId(name, userId, csrf, false, t);
        }
    }

    private void followWithId(String name, String userId, String csrf, boolean fromCache, PTask t) throws IOException {
        // Step 2: friendship create
        FormBody body = new FormBody.Builder().build(); // empty body
        Request req = addIgHeaders(new Request.Builder()
                .url(IG_URL + "/api/v1/friendships/create/" + userId + "/"), csrf)
                .post(body).build();

        try (Response r = httpClient.newCall(req).execute()) {
            String resp = r.body() != null ? r.body().string() : "";
            String low = resp.toLowerCase().replace(" ", "");

            if (r.code() == 200 && low.contains("\"status\":\"ok\"")) {
                deliverResult(true, "Followed Successfully"); return;
            }
            if (low.contains("checkpoint") || low.contains("challenge")) {
                deliverResult(false, "Blocked: Challenge / Checkpoint"); return;
            }
            if (low.contains("login_required")) {
                deliverResult(false, "Session expired"); return;
            }
            if (low.contains("feedback_required") || r.code() == 429
                    || low.contains("\"spam\":true") || low.contains("trylater")) {
                deliverResult(false, "Blocked: Action Limit / Challenge"); return;
            }
            if (fromCache && (r.code() == 400 || r.code() == 404)) {
                fastIdCache.remove(name.toLowerCase());
            }
            deliverResult(false, "Follow failed: HTTP " + r.code());
        }
    }

    // =========================================================
    //  LIKE API: shortcode -> media_id -> /api/v1/web/likes/{id}/like/
    // =========================================================
    private void runLikeApi(PTask t) throws IOException {
        String mediaId = t.mediaId;
        String csrf = getCsrfFromStore();
        if (csrf.isEmpty()) { deliverResult(false, "Session expired"); return; }

        // Shortcode se numeric ID nikalo (Instagram ka base64-like encoding)
        String numericId = shortcodeToId(mediaId);
        if (numericId == null || numericId.isEmpty()) {
            deliverResult(false, "Invalid media ID"); return;
        }

        FormBody body = new FormBody.Builder().build();
        Request req = addIgHeaders(new Request.Builder()
                .url(IG_URL + "/api/v1/web/likes/" + numericId + "/like/"), csrf)
                .post(body).build();

        try (Response r = httpClient.newCall(req).execute()) {
            String resp = r.body() != null ? r.body().string() : "";
            String low = resp.toLowerCase().replace(" ", "");

            if (r.code() == 200 && low.contains("\"status\":\"ok\"")) {
                deliverResult(true, "Post Liked Successfully"); return;
            }
            if (low.contains("checkpoint") || low.contains("challenge")) {
                deliverResult(false, "Blocked: Challenge / Checkpoint"); return;
            }
            if (low.contains("login_required")) {
                deliverResult(false, "Session expired"); return;
            }
            if (low.contains("feedback_required") || r.code() == 429
                    || low.contains("\"spam\":true") || low.contains("trylater")) {
                deliverResult(false, "Blocked: Action Limit / Challenge"); return;
            }
            if (r.code() == 404) { deliverResult(false, "Post unavailable"); return; }
            deliverResult(false, "Like failed: HTTP " + r.code());
        }
    }

    // Instagram shortcode -> numeric media_id (BigInt logic Java me)
    private String shortcodeToId(String sc) {
        if (sc == null) return null;
        sc = sc.trim();
        // Numeric pehle se hai
        if (sc.matches("[0-9]+")) return sc;
        // URL se shortcode extract
        if (sc.contains("/p/") || sc.contains("/reel/")) {
            String[] parts = sc.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("p".equals(parts[i]) || "reel".equals(parts[i])
                        || "reels".equals(parts[i]) || "tv".equals(parts[i])) {
                    sc = parts[i + 1]; break;
                }
            }
        }
        sc = sc.replaceAll("[^A-Za-z0-9_-]", "");
        if (sc.isEmpty() || sc.length() > 12) return sc.isEmpty() ? null : sc;
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        try {
            long id = 0;
            for (char c : sc.toCharArray()) {
                int idx = alpha.indexOf(c);
                if (idx < 0) return null;
                id = id * 64 + idx;
            }
            return String.valueOf(id);
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================
    //  PROFILE SEARCH: OkHttp se seedha
    // =========================================================
    private String pendingSearchUser = null;
    private boolean searchRunning = false;

    private final Runnable searchTimeout = new Runnable() {
        @Override public void run() {
            deliverSearch("{\"success\":false,\"message\":\"Search timeout. Dobara try karo.\"}");
        }
    };

    private void startSearch(final String u) {
        pendingSearchUser = u;
        searchRunning = false;
        uiHandler.removeCallbacks(searchTimeout);
        uiHandler.postDelayed(searchTimeout, 20000);
        ioPool.execute(new Runnable() {
            @Override public void run() {
                runSearchApi(u);
            }
        });
    }

    private void runSearchApi(String u) {
        try {
            String csrf = getCsrfFromStore();
            Request.Builder b = new Request.Builder()
                    .url(IG_URL + "/api/v1/users/web_profile_info/?username=" + URLEncoder.encode(u, "UTF-8"))
                    .get();
            addIgHeaders(b, csrf);
            try (Response r = httpClient.newCall(b.build()).execute()) {
                String body = r.body() != null ? r.body().string() : "";
                JSONObject d = null;
                try { d = new JSONObject(body); } catch (Exception ignored) {}
                if (d == null) { deliverSearch("{\"success\":false,\"message\":\"Parse error\"}"); return; }

                String low = body.toLowerCase();
                if (low.contains("login_required")) {
                    deliverSearch("{\"success\":false,\"message\":\"Instagram login expire. Account re-login karo.\"}"); return;
                }

                JSONObject user = null;
                try {
                    user = d.optJSONObject("data") != null
                            ? d.getJSONObject("data").optJSONObject("user")
                            : d.optJSONObject("user");
                } catch (Exception ignored) {}

                if (user == null) {
                    deliverSearch("{\"success\":false,\"message\":\"Account not found on Instagram\"}"); return;
                }

                String username = user.optString("username", u);
                String fullName = user.optString("full_name", "");
                String pk = user.optString("id", user.optString("pk", ""));
                long fc = 0;
                try {
                    JSONObject efb = user.optJSONObject("edge_followed_by");
                    fc = efb != null ? efb.optLong("count", 0) : user.optLong("follower_count", 0);
                } catch (Exception ignored) {}
                boolean verified = user.optBoolean("is_verified", false);
                String pic = user.optString("profile_pic_url_hd", user.optString("profile_pic_url", ""));

                JSONObject out = new JSONObject();
                out.put("success", true);
                out.put("username", username);
                out.put("full_name", fullName);
                out.put("numeric_id", pk);
                out.put("follower_count", fc);
                out.put("followers_formatted", String.format("%,d", fc) + " followers");
                out.put("is_verified", verified);
                out.put("profile_pic", pic);
                deliverSearch(out.toString());
            }
        } catch (Exception e) {
            deliverSearch("{\"success\":false,\"message\":\"Search error: " + e.getMessage() + "\"}");
        }
    }

    private void deliverSearch(final String json) {
        if (pendingSearchUser == null) return;
        pendingSearchUser = null;
        searchRunning = false;
        uiHandler.removeCallbacks(searchTimeout);
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (mainWebView != null) {
                    mainWebView.evaluateJavascript(
                            "if(window.onInstagramLiveSearchResult) window.onInstagramLiveSearchResult(true, "
                                    + JSONObject.quote(json) + ");", null);
                }
            }
        });
    }

    // =========================================================
    //  POSTS FETCH: OkHttp se seedha
    // =========================================================
    private String pendingPostsUser = null;

    private final Runnable postsTimeout = new Runnable() {
        @Override public void run() {
            deliverPosts("{\"success\":false,\"message\":\"Request timed out. Please try again.\"}");
        }
    };

    private void startPosts(final String u) {
        pendingPostsUser = u;
        uiHandler.removeCallbacks(postsTimeout);
        uiHandler.postDelayed(postsTimeout, 40000);
        ioPool.execute(new Runnable() {
            @Override public void run() {
                runPostsApi(u);
            }
        });
    }

    private void runPostsApi(String u) {
        try {
            String csrf = getCsrfFromStore();

            // Step 1: profile info + first page posts
            Request req = addIgHeaders(new Request.Builder()
                    .url(IG_URL + "/api/v1/users/web_profile_info/?username=" + URLEncoder.encode(u, "UTF-8")), csrf)
                    .get().build();

            try (Response r = httpClient.newCall(req).execute()) {
                String body = r.body() != null ? r.body().string() : "";
                JSONObject d = null;
                try { d = new JSONObject(body); } catch (Exception ignored) {}
                if (d == null) { deliverPosts("{\"success\":false,\"message\":\"Parse error\"}"); return; }

                String low = body.toLowerCase();
                if (low.contains("login_required")) {
                    deliverPosts("{\"success\":false,\"message\":\"Instagram session expired. Please log in again.\"}"); return;
                }

                JSONObject user = null;
                try {
                    user = d.optJSONObject("data") != null
                            ? d.getJSONObject("data").optJSONObject("user")
                            : d.optJSONObject("user");
                } catch (Exception ignored) {}

                if (user == null) {
                    deliverPosts("{\"success\":false,\"message\":\"Account not found on Instagram.\"}"); return;
                }

                // Build result
                JSONObject out = new JSONObject();
                out.put("success", true);
                out.put("username", u);
                out.put("is_private", user.optBoolean("is_private", false));
                out.put("profile_pic", user.optString("profile_pic_url_hd", user.optString("profile_pic_url", "")));
                out.put("full_name", user.optString("full_name", ""));
                JSONObject efb = user.optJSONObject("edge_followed_by");
                if (efb != null) out.put("follower_count", efb.optLong("count", 0));

                // Posts
                org.json.JSONArray posts = new org.json.JSONArray();
                try {
                    JSONObject etm = user.optJSONObject("edge_owner_to_timeline_media");
                    if (etm != null) {
                        org.json.JSONArray edges = etm.optJSONArray("edges");
                        if (edges != null) {
                            for (int i = 0; i < Math.min(edges.length(), 30); i++) {
                                JSONObject node = edges.getJSONObject(i).optJSONObject("node");
                                if (node == null) continue;
                                JSONObject p = new JSONObject();
                                p.put("code", node.optString("shortcode", ""));
                                p.put("thumb", node.optString("thumbnail_src", node.optString("display_url", "")));
                                JSONObject elb = node.optJSONObject("edge_liked_by");
                                p.put("likes", elb != null ? elb.optLong("count", 0) : 0);
                                p.put("video", node.optBoolean("is_video", false));
                                p.put("reel", "clips".equals(node.optString("product_type", "")));
                                p.put("ts", node.optLong("taken_at_timestamp", 0));
                                posts.put(p);
                            }
                        }
                    }
                } catch (Exception ignored) {}
                out.put("posts", posts);
                deliverPosts(out.toString());
            }
        } catch (Exception e) {
            deliverPosts("{\"success\":false,\"message\":\"Posts error: " + e.getMessage() + "\"}");
        }
    }

    private void deliverPosts(final String json) {
        if (pendingPostsUser == null) return;
        pendingPostsUser = null;
        uiHandler.removeCallbacks(postsTimeout);
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (mainWebView != null) {
                    mainWebView.evaluateJavascript(
                            "if(window.onInstagramPostsResult) window.onInstagramPostsResult(true, "
                                    + JSONObject.quote(json) + ");", null);
                }
            }
        });
    }

    // =========================================================
    //  RESULT DELIVERY
    // =========================================================
    private void deliverResult(final boolean success, final String message) {
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (!taskActive) return;
                taskActive = false;
                uiHandler.removeCallbacks(taskTimeout);
                dbg("task result ok=" + success + " msg=" + message);
                if (RESULT_RETRY_ENABLED) {
                    enqueueResultCall("window.onWorkerResult(" + success + ", "
                            + JSONObject.quote(message == null ? "" : message) + ")");
                    onApiTaskEnded();
                } else {
                    String safe = (message == null ? "" : message).replace("\\", "\\\\").replace("'", "\\'");
                    if (mainWebView != null) {
                        mainWebView.evaluateJavascript(
                                "if(window.onWorkerResult) window.onWorkerResult(" + success + ", '" + safe + "');", null);
                    }
                    onApiTaskEnded();
                }
            }
        });
    }

    private void onApiTaskEnded() {
        singleLastEnd = System.currentTimeMillis();
        singleGap = nextGap();
        singlePump();
    }

    // =========================================================
    //  RESULT RETRY SYSTEM (page reload ke baad bhi result mile)
    // =========================================================
    private final ArrayDeque<String> pendingResultJs = new ArrayDeque<>();
    private final ArrayDeque<Long> pendingResultAt = new ArrayDeque<>();
    private boolean resultFlushBusy = false;
    private long resultFlushBusyAt = 0;
    private boolean resultRetryScheduled = false;

    private void enqueueResultCall(String call) {
        pendingResultJs.add(call);
        pendingResultAt.add(System.currentTimeMillis());
        flushResults();
    }

    private void flushResults() {
        if (mainWebView == null) return;
        long now = System.currentTimeMillis();
        while (!pendingResultAt.isEmpty() && now - pendingResultAt.peek() > RESULT_MAX_AGE_MS) {
            pendingResultAt.poll(); pendingResultJs.poll();
        }
        if (pendingResultJs.isEmpty()) return;
        if (resultFlushBusy && now - resultFlushBusyAt < 5000L) return;
        resultFlushBusy = true;
        resultFlushBusyAt = now;
        final String call = pendingResultJs.peek();
        try {
            mainWebView.evaluateJavascript(
                    "(function(){if(typeof window.onWorkerResult!=='function')return 'NOHANDLER';"
                            + "try{" + call + ";}catch(e){return 'E:'+(e&&e.message);}return 'OK';})()",
                    new ValueCallback<String>() {
                        @Override public void onReceiveValue(String v) {
                            resultFlushBusy = false;
                            boolean noHandler = v == null || "null".equals(v) || v.contains("NOHANDLER");
                            if (!noHandler) {
                                if (pendingResultJs.peek() == call) {
                                    pendingResultJs.poll(); pendingResultAt.poll();
                                }
                                flushResults();
                            } else {
                                scheduleResultRetry();
                            }
                        }
                    });
        } catch (Exception e) {
            resultFlushBusy = false;
            scheduleResultRetry();
        }
    }

    private void scheduleResultRetry() {
        if (resultRetryScheduled) return;
        resultRetryScheduled = true;
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                resultRetryScheduled = false;
                flushResults();
            }
        }, 1000);
    }

    // =========================================================
    //  PARALLEL ENGINE (multi-account): OkHttp thread pool
    // =========================================================
    private volatile int maxParallel = 1;
    private int activeSlots = 0;
    private final ArrayDeque<PTask> pendingTasks = new ArrayDeque<>();
    private final Map<String, Long> doneMap = new HashMap<>();
    private final Map<String, Long> deadMap = new HashMap<>();
    private final Map<String, HashSet<String>> unavailVotes = new HashMap<>();
    private static final long DONE_TTL_MS = 30L * 24 * 60 * 60 * 1000L;
    private static final long DEAD_TTL_MS = 7L * 24 * 60 * 60 * 1000L;

    private long lastEndAt = 0;
    private long lastStartAt = 0;
    private String lastStartedUid = "";
    private boolean pumpScheduled = false;

    private final Runnable pumpRunnable = new Runnable() {
        @Override public void run() { pumpScheduled = false; pumpQueue(); }
    };

    private void parallelEnqueue(String target, String taskType, String mediaId, String cookieStr) {
        final PTask t = new PTask();
        t.target = target == null ? "" : target;
        t.taskType = taskType == null ? "follow" : taskType;
        t.mediaId = mediaId == null ? "" : mediaId;
        t.cookieStr = cookieStr == null ? "" : cookieStr;
        t.uid = cookieValue(t.cookieStr, "ds_user_id").replaceAll("[^A-Za-z0-9]", "");
        t.key = taskKey(t.taskType, t.target, t.mediaId);
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (isDead(t.key)) {
                    sendParallelResult(t.uid, t.target, false, "Skipped: dead profile"); return;
                }
                if (isDone(t.uid + "|" + t.key)) {
                    boolean like = "like".equalsIgnoreCase(t.taskType);
                    sendParallelResult(t.uid, t.target, true, like ? "Already Liked" : "Already Following"); return;
                }
                pendingTasks.add(t);
                pumpQueue();
            }
        });
    }

    private void pumpQueue() {
        if (pendingTasks.isEmpty() || activeSlots >= maxParallel) return;
        long wait = lastEndAt + curGapMs - System.currentTimeMillis();
        if (wait > 0) {
            if (!pumpScheduled) { pumpScheduled = true; uiHandler.postDelayed(pumpRunnable, wait); }
            return;
        }
        PTask t = pendingTasks.poll();
        if (t == null) return;
        lastStartAt = System.currentTimeMillis();
        lastStartedUid = t.uid;
        curGapMs = nextGap();
        activeSlots++;
        startParallelApiTask(t);
    }

    private void startParallelApiTask(final PTask t) {
        dbg("parallel api start uid=" + t.uid + " type=" + t.taskType + " target=" + cleanName(t.target));
        notifyStarted(t.uid, cleanName(t.target), t.taskType);
        injectCookiesToHttp(t.cookieStr);
        ioPool.execute(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                String msg = "Unknown error";
                try {
                    if ("like".equalsIgnoreCase(t.taskType)) {
                        Object[] res = doLikeSync(t);
                        ok = (Boolean) res[0]; msg = (String) res[1];
                    } else {
                        Object[] res = doFollowSync(t);
                        ok = (Boolean) res[0]; msg = (String) res[1];
                    }
                } catch (Exception e) {
                    msg = "Network error: " + e.getMessage();
                }
                final boolean fok = ok; final String fmsg = msg;
                uiHandler.post(new Runnable() {
                    @Override public void run() {
                        activeSlots = Math.max(0, activeSlots - 1);
                        lastEndAt = System.currentTimeMillis();
                        if (fok) {
                            doneMap.put(t.uid + "|" + t.key, lastEndAt);
                            saveMap("done_map", doneMap, DONE_TTL_MS, 1500);
                        }
                        sendParallelResult(t.uid, t.target, fok, fmsg);
                        pumpQueue();
                    }
                });
            }
        });
    }

    private Object[] doFollowSync(PTask t) throws IOException {
        String name = cleanName(t.target);
        if (name.isEmpty()) return new Object[]{false, "Invalid target"};
        String csrf = getCsrfFromStore();
        if (csrf.isEmpty()) return new Object[]{false, "Session expired"};

        String cachedId = fastIdCache.get(name.toLowerCase());
        if (cachedId == null) {
            // Fetch user info
            Request req = addIgHeaders(new Request.Builder()
                    .url(IG_URL + "/api/v1/users/web_profile_info/?username=" + URLEncoder.encode(name, "UTF-8")), csrf)
                    .get().build();
            try (Response r = httpClient.newCall(req).execute()) {
                String body = r.body() != null ? r.body().string() : "";
                String low = body.toLowerCase();
                if (low.contains("login_required")) return new Object[]{false, "Session expired"};
                if (r.code() == 429) return new Object[]{false, "Blocked: Action Limit / Challenge"};
                if (low.contains("checkpoint_required") || low.contains("challenge_required"))
                    return new Object[]{false, "Blocked: Challenge / Checkpoint"};
                JSONObject d = null; try { d = new JSONObject(body); } catch (Exception ignored) {}
                JSONObject user = null;
                if (d != null) {
                    try { user = d.optJSONObject("data") != null
                            ? d.getJSONObject("data").optJSONObject("user")
                            : d.optJSONObject("user"); } catch (Exception ignored) {}
                }
                if (user == null || !user.has("id")) return new Object[]{false, "User not found"};
                cachedId = user.optString("id", "");
                fastIdCache.put(name.toLowerCase(), cachedId);
                if (user.optBoolean("followed_by_viewer", false) || user.optBoolean("requested_by_viewer", false))
                    return new Object[]{true, "Already Following"};
            }
        }

        // Follow call
        Request req2 = addIgHeaders(new Request.Builder()
                .url(IG_URL + "/api/v1/friendships/create/" + cachedId + "/"), csrf)
                .post(new FormBody.Builder().build()).build();
        try (Response r2 = httpClient.newCall(req2).execute()) {
            String resp = r2.body() != null ? r2.body().string() : "";
            String low = resp.toLowerCase().replace(" ", "");
            if (r2.code() == 200 && low.contains("\"status\":\"ok\"")) return new Object[]{true, "Followed Successfully"};
            if (low.contains("checkpoint") || low.contains("challenge")) return new Object[]{false, "Blocked: Challenge / Checkpoint"};
            if (low.contains("login_required")) return new Object[]{false, "Session expired"};
            if (low.contains("feedback_required") || r2.code() == 429) return new Object[]{false, "Blocked: Action Limit / Challenge"};
            return new Object[]{false, "Follow failed: HTTP " + r2.code()};
        }
    }

    private Object[] doLikeSync(PTask t) throws IOException {
        String csrf = getCsrfFromStore();
        if (csrf.isEmpty()) return new Object[]{false, "Session expired"};
        String numId = shortcodeToId(t.mediaId);
        if (numId == null) return new Object[]{false, "Invalid media ID"};
        Request req = addIgHeaders(new Request.Builder()
                .url(IG_URL + "/api/v1/web/likes/" + numId + "/like/"), csrf)
                .post(new FormBody.Builder().build()).build();
        try (Response r = httpClient.newCall(req).execute()) {
            String resp = r.body() != null ? r.body().string() : "";
            String low = resp.toLowerCase().replace(" ", "");
            if (r.code() == 200 && low.contains("\"status\":\"ok\"")) return new Object[]{true, "Post Liked Successfully"};
            if (low.contains("checkpoint") || low.contains("challenge")) return new Object[]{false, "Blocked: Challenge / Checkpoint"};
            if (low.contains("login_required")) return new Object[]{false, "Session expired"};
            if (low.contains("feedback_required") || r.code() == 429) return new Object[]{false, "Blocked: Action Limit / Challenge"};
            if (r.code() == 404) return new Object[]{false, "Post unavailable"};
            return new Object[]{false, "Like failed: HTTP " + r.code()};
        }
    }

    private void sendParallelResult(String uid, String target, boolean ok, String msg) {
        if (RESULT_RETRY_ENABLED) {
            enqueueResultCall("window.onWorkerResult(" + ok + ", "
                    + JSONObject.quote(msg == null ? "" : msg) + ", "
                    + JSONObject.quote(uid == null ? "" : uid) + ", "
                    + JSONObject.quote(target == null ? "" : target) + ")");
        } else {
            String safe = (msg == null ? "" : msg).replace("\\", "\\\\").replace("'", "\\'");
            if (mainWebView != null) {
                mainWebView.evaluateJavascript(
                        "if(window.onWorkerResult) window.onWorkerResult(" + ok + ", '" + safe + "', '" + uid + "', '" + target + "');", null);
            }
        }
    }

    // =========================================================
    //  onCreate
    // =========================================================
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String baseUa = WebSettings.getDefaultUserAgent(this);
        if (baseUa == null) baseUa = "";
        baseUa = baseUa.replace("; wv", "").replace(" Version/4.0", "");
        USER_AGENT = baseUa;
        USER_AGENT_APP = USER_AGENT + " " + APP_SECRET_KEY + " UFV/" + APP_VERSION_CODE;

        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("upfollow_prefs", MODE_PRIVATE);
        deviceKey = computeDeviceKey();
        loadMap("done_map", doneMap);
        loadMap("dead_map", deadMap);

        // OkHttp client init
        buildHttpClient();

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UpFollow:WakeLock");
        } catch (Exception ignored) {}

        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        mainWebView = findViewById(R.id.webView);
        setupWebView(mainWebView, USER_AGENT_APP);
        mainWebView.addJavascriptInterface(new MainAppInterface(), "Android");
        mainWebView.addJavascriptInterface(new IgProbeInterface(), "IgProbe");

        mainWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNav(request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNav(url);
            }

            @TargetApi(Build.VERSION_CODES.O)
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                onRendererGone("main"); return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                mainUrl = url == null ? "" : url;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null) mainUrl = url;
                checkInstagramLogin(url);
                flushResults();
                if (url != null && url.startsWith(HOSTING_BASE) && prefs != null && prefs.getBoolean("auto_run", false)) {
                    tryAutoStart(0);
                }
            }
        });

        mainWebView.loadUrl(HOSTING_DASHBOARD + "?dk=" + deviceKey);
    }

    // =========================================================
    //  MAIN WebView SETUP (sirf dashboard ke liye)
    // =========================================================
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView wv, String ua) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        if (ua != null) s.setUserAgentString(ua);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
    }

    // =========================================================
    //  BRIDGES
    // =========================================================
    public class MainAppInterface {

        @JavascriptInterface
        public void runTask(final String target, final String taskType, final String mediaId, final String cookieStr) {
            if (!trusted()) return;
            // Multi-account parallel mode
            if (cookieStr != null && !cookieStr.isEmpty()
                    && !cookieValue(cookieStr, "ds_user_id").isEmpty()) {
                try {
                    boolean parallelOk = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
                    if (parallelOk) {
                        parallelEnqueue(target, taskType, mediaId, cookieStr);
                        return;
                    }
                } catch (Throwable ignored) {}
            }
            // Single queue mode
            if (SINGLE_QUEUE_ENABLED) {
                singleEnqueue(target, taskType, mediaId, cookieStr);
            }
        }

        @JavascriptInterface
        public void executeBrowserAction(String target, String taskType, String mediaId) {
            runTask(target, taskType, mediaId, "");
        }

        @JavascriptInterface
        public void executeBrowserFollow(String target) {
            runTask(target, "follow", "", "");
        }

        @JavascriptInterface
        public void setAccountDelay(final int seconds) {
            if (!trusted()) return;
            startGapMs = Math.max(MIN_GAP_MS, Math.min(60, seconds) * 1000L);
        }

        @JavascriptInterface
        public void setMaxParallel(final int n) {
            if (!trusted()) return;
            maxParallel = Math.max(1, Math.min(3, n));
        }

        @JavascriptInterface
        public void setFastMode(boolean on) {}

        @JavascriptInterface
        public boolean isFastMode() { return false; }

        @JavascriptInterface
        public void setGapJitter(final int seconds) {
            if (!trusted()) return;
            gapJitterMs = Math.max(0, Math.min(10, seconds)) * 1000L;
        }

        @JavascriptInterface
        public void clearDeadProfiles() {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override public void run() {
                    deadMap.clear(); unavailVotes.clear();
                    saveMap("dead_map", deadMap, DEAD_TTL_MS, 1500);
                }
            });
        }

        @JavascriptInterface
        public void clearDoneCache() {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override public void run() {
                    doneMap.clear();
                    saveMap("done_map", doneMap, DONE_TTL_MS, 1500);
                }
            });
        }

        @JavascriptInterface
        public void fetchInstagramPostsLive(final String queryUsername) {
            if (!trusted()) return;
            final String u = queryUsername == null ? "" : queryUsername.replace("@", "").replaceAll("[^A-Za-z0-9._]", "");
            uiHandler.post(new Runnable() {
                @Override public void run() {
                    if (u.isEmpty()) {
                        pendingPostsUser = "_";
                        deliverPosts("{\"success\":false,\"message\":\"Invalid username\"}");
                    } else { startPosts(u); }
                }
            });
        }

        @JavascriptInterface
        public void applySession(final String cookieStr) {
            if (!trusted() || cookieStr == null || cookieStr.isEmpty()) return;
            uiHandler.post(new Runnable() {
                @Override public void run() {
                    applyInstagramCookies(cookieStr);
                    injectCookiesToHttp(cookieStr); // OkHttp me bhi lagao
                }
            });
        }

        @JavascriptInterface
        public void searchInstagramProfileLive(final String queryUsername) {
            if (!trusted()) return;
            final String u = queryUsername == null ? "" : queryUsername.replace("@", "").replaceAll("[^A-Za-z0-9._]", "");
            uiHandler.post(new Runnable() {
                @Override public void run() {
                    if (u.isEmpty()) {
                        pendingSearchUser = "_";
                        deliverSearch("{\"success\":false,\"message\":\"Invalid username\"}");
                    } else { startSearch(u); }
                }
            });
        }

        @JavascriptInterface
        public String getIgCookies() {
            if (!trusted()) return "";
            String c = CookieManager.getInstance().getCookie(IG_URL);
            return c == null ? "" : c;
        }

        @JavascriptInterface
        public String getQueueInfo() {
            if (!trusted()) return "{}";
            try {
                return "{\"queued\":" + (pendingTasks.size() + singleQueue.size())
                        + ",\"active\":" + (activeSlots > 0 || taskActive)
                        + ",\"gap_ms\":" + curGapMs + "}";
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public String getDebugLog() {
            if (!trusted()) return "";
            StringBuilder sb = new StringBuilder();
            synchronized (dbgLog) { for (String l : dbgLog) sb.append(l).append('\n'); }
            return sb.toString();
        }

        @JavascriptInterface
        public int getAppVersion() { return APP_VERSION_CODE; }

        @JavascriptInterface
        public boolean isParallelSupported() {
            try { return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE); }
            catch (Throwable t) { return false; }
        }

        @JavascriptInterface
        public void clearPendingTasks() {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override public void run() {
                    PTask t;
                    while ((t = pendingTasks.poll()) != null) sendParallelResult(t.uid, t.target, false, "Cancelled");
                    PTask st;
                    while ((st = singleQueue.poll()) != null) enqueueResultCall("window.onWorkerResult(false, \"Cancelled\")");
                }
            });
        }

        @JavascriptInterface
        public void setAutoRun(final boolean on) {
            if (!trusted()) return;
            prefs.edit().putBoolean("auto_run", on).apply();
        }

        @JavascriptInterface
        public boolean isAutoRun() { return trusted() && prefs.getBoolean("auto_run", false); }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean keepOn) {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override public void run() {
                    if (keepOn) {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        startAutomationBackground();
                        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(12 * 60 * 60 * 1000L);
                    } else {
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        stopAutomationBackground();
                        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                    }
                }
            });
        }

        @JavascriptInterface
        public void updateNotification(final int accounts, final int tasks, final int coins) {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override public void run() { showOrUpdateNotification(accounts, tasks, coins); }
            });
        }

        @JavascriptInterface
        public void stopNotification() {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override public void run() { clearAutomationNotification(); }
            });
        }
    }

    public class IgProbeInterface {
        @JavascriptInterface
        public void onIgInfo(final String nonce, final String username) {
            uiHandler.post(new Runnable() {
                @Override public void run() {
                    if (captureBusy && nonce != null && nonce.equals(captureNonce))
                        finishCapture(username);
                }
            });
        }
    }

    // =========================================================
    //  INSTAGRAM LOGIN CAPTURE (main WebView me rehta hai)
    // =========================================================
    private void checkInstagramLogin(String url) {
        if (url == null || captureBusy) return;
        if (!url.startsWith(IG_URL)) return;
        String path = "";
        try { path = Uri.parse(url).getPath().toLowerCase(); } catch (Exception ignored) {}
        String[] bad = {"login","challenge","checkpoint","suspended","disabled","signup","two_factor","password","recover","auth_platform"};
        for (String b : bad) { if (path.contains(b)) return; }
        String cookies = CookieManager.getInstance().getCookie(IG_URL);
        if (cookieValue(cookies, "sessionid").isEmpty()) return;
        captureBusy = true;
        captureCookies = cookies;
        captureNonce = Long.toHexString(System.nanoTime());
        final String nonce = captureNonce;
        String uid = cookieValue(cookies, "ds_user_id");
        if (uid.matches("\\d+")) {
            String js = "(function(){var n='" + nonce + "';"
                    + "function d(u){try{IgProbe.onIgInfo(n,u||'');}catch(e){}}"
                    + "try{fetch('/api/v1/users/" + uid + "/info/',{credentials:'include',"
                    + "headers:{'x-ig-app-id':'936619743392459','x-requested-with':'XMLHttpRequest'}})"
                    + ".then(function(r){return r.json();})"
                    + ".then(function(j){d(j&&j.user&&j.user.username);})"
                    + ".catch(function(){d('');});}catch(e){d('');}})();";
            mainWebView.evaluateJavascript(js, null);
        } else { finishCapture(""); return; }
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (captureBusy && nonce.equals(captureNonce)) finishCapture("");
            }
        }, 6000);
    }

    private void finishCapture(String username) {
        if (!captureBusy) return;
        captureBusy = false;
        captureNonce = "";
        mainWebView.getSettings().setUserAgentString(USER_AGENT_APP);
        // Captured cookies ko OkHttp me bhi inject karo
        injectCookiesToHttp(captureCookies);
        try {
            String clean = username == null ? "" : username.replaceAll("[^A-Za-z0-9._]", "");
            String body = "ig_cookies=" + URLEncoder.encode(captureCookies, "UTF-8")
                    + "&ig_name=" + URLEncoder.encode(clean, "UTF-8")
                    + "&dk=" + deviceKey
                    + "&mode=" + (addMode ? "add" : "login");
            addMode = false;
            mainWebView.postUrl(HOSTING_DASHBOARD, body.getBytes("UTF-8"));
        } catch (Exception e) {
            addMode = false;
            mainWebView.loadUrl(HOSTING_DASHBOARD + "?dk=" + deviceKey);
        }
    }

    private boolean handleNav(String url) {
        if (url == null) return false;
        if (url.startsWith(HOSTING_BASE) || url.startsWith(IG_URL)) return false;
        if (url.contains("force_authentication=1")) {
            uiHandler.post(new Runnable() {
                @Override public void run() {
                    addMode = true;
                    captureBusy = false;
                    applyInstagramCookies("");
                    mainWebView.getSettings().setUserAgentString(USER_AGENT);
                    mainWebView.loadUrl(IG_LOGIN_URL);
                }
            });
            return true;
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        return true;
    }

    // =========================================================
    //  HELPERS
    // =========================================================
    private static String cookieValue(String cookies, String name) {
        if (cookies == null) return "";
        for (String part : cookies.split(";")) {
            part = part.trim();
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).equals(name)) return part.substring(i + 1);
        }
        return "";
    }

    private void applyInstagramCookies(String cookieStr) {
        CookieManager cm = CookieManager.getInstance();
        String existing = cm.getCookie(IG_URL);
        if (existing != null) {
            for (String part : existing.split(";")) {
                String p = part.trim(); int i = p.indexOf('=');
                String name = i > 0 ? p.substring(0, i) : p;
                if (name.isEmpty()) continue;
                cm.setCookie(IG_URL, name + "=; Max-Age=0; Path=/; Domain=.instagram.com");
                cm.setCookie(IG_URL, name + "=; Max-Age=0; Path=/");
            }
        }
        if (cookieStr != null && !cookieStr.isEmpty()) {
            for (String part : cookieStr.split(";")) {
                String p = part.trim(); int i = p.indexOf('=');
                if (i <= 0) continue;
                String name = p.substring(0, i);
                String extra = "; Domain=.instagram.com; Path=/; Secure";
                if ("sessionid".equals(name)) extra += "; HttpOnly";
                cm.setCookie(IG_URL, p + extra);
            }
        }
        cm.flush();
    }

    private boolean trusted() {
        String u = mainUrl;
        return u != null && u.startsWith(HOSTING_BASE);
    }

    private static String cleanName(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9._]", "");
    }

    private static String taskKey(String type, String target, String media) {
        if ("like".equalsIgnoreCase(type)) {
            String m = media == null ? "" : media.trim();
            if (!m.isEmpty()) return "like|" + m.toLowerCase();
            return "like|" + cleanName(target).toLowerCase();
        }
        return "follow|" + cleanName(target).toLowerCase();
    }

    private boolean isDone(String k) {
        Long t = doneMap.get(k);
        return t != null && System.currentTimeMillis() - t < DONE_TTL_MS;
    }

    private boolean isDead(String k) {
        Long t = deadMap.get(k);
        return t != null && System.currentTimeMillis() - t < DEAD_TTL_MS;
    }

    private void notifyStarted(String uid, String target, String type) {
        try {
            long maxMs = TASK_TIMEOUT_MS + 15000L;
            mainWebView.evaluateJavascript(
                    "try{if(typeof window.onWorkerStarted==='function')window.onWorkerStarted("
                            + JSONObject.quote(uid == null ? "" : uid) + ","
                            + JSONObject.quote(target == null ? "" : target) + ","
                            + JSONObject.quote(type == null ? "" : type) + "," + maxMs + ");}catch(e){}",
                    null);
        } catch (Exception ignored) {}
    }

    // Device fingerprint
    private String computeDeviceKey() {
        try {
            @SuppressLint("HardwareIds")
            String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id == null) id = "";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((id + APP_SECRET_KEY).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 32);
        } catch (Exception e) { return "unknown_device"; }
    }

    // Map persistence
    private void loadMap(String pref, Map<String, Long> m) {
        try {
            Set<String> set = prefs.getStringSet(pref, null);
            if (set == null) return;
            for (String e : set) {
                int i = e.lastIndexOf('\t');
                if (i < 1) continue;
                try { m.put(e.substring(0, i), Long.parseLong(e.substring(i + 1))); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void saveMap(String pref, Map<String, Long> m, long ttl, int cap) {
        try {
            long now = System.currentTimeMillis();
            java.util.Iterator<Map.Entry<String, Long>> it = m.entrySet().iterator();
            while (it.hasNext()) { if (now - it.next().getValue() > ttl) it.remove(); }
            while (m.size() > cap) {
                String oldest = null; long ot = Long.MAX_VALUE;
                for (Map.Entry<String, Long> e : m.entrySet()) { if (e.getValue() < ot) { ot = e.getValue(); oldest = e.getKey(); } }
                if (oldest == null) break;
                m.remove(oldest);
            }
            Set<String> out = new HashSet<>();
            for (Map.Entry<String, Long> e : m.entrySet()) out.add(e.getKey() + "\t" + e.getValue());
            prefs.edit().putStringSet(pref, out).apply();
        } catch (Exception ignored) {}
    }

    // Auto-start
    private void tryAutoStart(final int attempt) {
        if (attempt > 20) return;
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (mainWebView == null) return;
                mainWebView.evaluateJavascript(
                        "(function(){if(window.__autoStarted)return 'done';"
                                + "if(typeof window.onAppAutoStart==='function'){window.__autoStarted=true;window.onAppAutoStart();return 'done';}"
                                + "return 'wait';})()",
                        new ValueCallback<String>() {
                            @Override public void onReceiveValue(String v) {
                                if (v != null && v.contains("wait")) tryAutoStart(attempt + 1);
                            }
                        });
            }
        }, attempt == 0 ? 1500 : 1000);
    }

    // =========================================================
    //  NOTIFICATION
    // =========================================================
    private void createNotificationChannel() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Automation Service", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shows live automation stats in status bar");
            ch.setShowBadge(false);
            if (notificationManager != null) notificationManager.createNotificationChannel(ch);
        }
    }

    private void showOrUpdateNotification(int accounts, int tasks, int coins) {
        if (notificationManager == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);
        b.setContentTitle("UpFollow Running")
                .setContentText("Accounts: " + accounts + " Active  |  Tasks: " + tasks + "  |  Coins: +" + coins)
                .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pf = Build.VERSION.SDK_INT >= 23
                ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.FLAG_UPDATE_CURRENT;
        b.setContentIntent(PendingIntent.getActivity(this, 0, open, pf));
        notificationManager.notify(NOTIFICATION_ID, b.build());
    }

    private void clearAutomationNotification() {
        if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
    }

    // =========================================================
    //  BACKGROUND / WAKELOCK
    // =========================================================
    private boolean automationOn = false;

    private void startAutomationBackground() {
        automationOn = true;
        try {
            Intent svc = new Intent(this, AutomationService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Exception ignored) {}
        requestBatteryExemptionOnce();
    }

    private void stopAutomationBackground() {
        automationOn = false;
        try { stopService(new Intent(this, AutomationService.class)); } catch (Exception ignored) {}
    }

    private void requestBatteryExemptionOnce() {
        try {
            if (Build.VERSION.SDK_INT < 23 || prefs == null) return;
            if (prefs.getBoolean("battery_asked", false)) return;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                prefs.edit().putBoolean("battery_asked", true).apply();
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception ignored) {}
    }

    // =========================================================
    //  RENDERER CRASH
    // =========================================================
    private long lastRecreateAt = 0;
    private void onRendererGone(String who) {
        dbg("WebView renderer gone: " + who);
        long now = System.currentTimeMillis();
        if (now - lastRecreateAt < 20000L) return;
        lastRecreateAt = now;
        uiHandler.post(new Runnable() {
            @Override public void run() { try { recreate(); } catch (Exception ignored) {} }
        });
    }

    // =========================================================
    //  DEBUG LOG
    // =========================================================
    private final ArrayDeque<String> dbgLog = new ArrayDeque<>();
    private void dbg(String m) {
        try {
            String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " " + m;
            synchronized (dbgLog) { dbgLog.add(line); while (dbgLog.size() > 150) dbgLog.poll(); }
            Log.d("UpFollow", m);
        } catch (Exception ignored) {}
    }

    // =========================================================
    //  DATA CLASSES
    // =========================================================
    private class PTask {
        String uid = "", target = "", taskType = "follow", mediaId = "", cookieStr = "", key = "";
    }

    // =========================================================
    //  LIFECYCLE
    // =========================================================
    @Override
    protected void onPause() {
        super.onPause();
        if (automationOn) { try { mainWebView.resumeTimers(); } catch (Exception ignored) {} }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { mainWebView.resumeTimers(); } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        if (mainWebView.canGoBack()) mainWebView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        automationOn = false;
        try { stopService(new Intent(this, AutomationService.class)); } catch (Exception ignored) {}
        uiHandler.removeCallbacksAndMessages(null);
        ioPool.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
