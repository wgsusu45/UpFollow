package com.upfollow.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct HTTP runner: page khole bina follow / like bhejta hai.
 * Alag file hai, MainActivity me sirf chhota hook lagta hai.
 */
public class DirectRunner {

    public interface Callback {
        void onStarted(String target, String type, long maxMs);
        void onResult(boolean success, String message, String target);

        // default method: MainActivity me onLog na ho to bhi compile error nahi aayega
        default void onLog(String line) {}
    }

    // ---- settings (chaho to badal sakte ho) ----
    public static int DAILY_FOLLOW_CAP = 150;   // ek account, ek din
    public static int DAILY_LIKE_CAP = 300;
    private static final int MAX_PER_ACCOUNT = 2;           // ek account par ek saath max requests
    private static final long MIN_GAP_MS = 800;             // ek account ki do requests ke beech minimum gap
    private static final long BLOCK_PAUSE_MS = 30L * 60 * 1000; // action block ke baad 30 min pause
    private static final long LOOKUP_GAP_MS = 4000;             // do profile lookups ke beech minimum gap (sab accounts mila ke)
    private static final long LOOKUP_PAUSE_MS = 2L * 60 * 1000; // lookup 429 ke baad 2 min tak naye lookup nahi

    private static final String APP_ID = "936619743392459";
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final Pattern MEDIA_RE = Pattern.compile("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)");

    private final SharedPreferences prefs;
    private final String ua;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final Map<String, String> idCache = new ConcurrentHashMap<String, String>();
    private final Map<String, Long> pausedUntil = new ConcurrentHashMap<String, Long>();
    private final ConcurrentHashMap<String, Acct> accts = new ConcurrentHashMap<String, Acct>();
    private final Random rnd = new Random();
    private volatile Callback logCb;
    private final Object lookupLock = new Object();
    private long lastLookup = 0;
    private volatile long lookupPausedUntil = 0;

    private void log(String s) {
        Callback c = logCb;
        if (c != null) {
            try { c.onLog(s); } catch (Exception ignored) {}
        }
    }

    private static class Acct {
        final Semaphore sem = new Semaphore(MAX_PER_ACCOUNT, true);
        long lastStart = 0;
    }

    private static class Http {
        int code;
        String body = "";
        List<String> setCookies = new ArrayList<String>();
        JSONObject json;
    }

    private static class Res {
        final boolean ok;
        final String msg;
        Res(boolean ok, String msg) { this.ok = ok; this.msg = msg; }
    }

    public DirectRunner(Context ctx, String userAgent) {
        this.prefs = ctx.getSharedPreferences("direct_runner", Context.MODE_PRIVATE);
        this.ua = userAgent == null ? "" : userAgent;
    }

    /**
     * true  = task hamne le liya (result Callback se aayega)
     * false = hamare paas kaam ka cookie nahi, purana tareeka chalao
     */
    public boolean run(final String target, final String type, final String mediaUrl,
                       final String cookie, final Callback cb) {
        if (target == null || target.isEmpty() || cookie == null) return false;
        final Map<String, String> ck = parseCookie(cookie);
        String sid = ck.get("sessionid");
        if (sid == null || sid.isEmpty()) return false;
        final String uid = ck.get("ds_user_id");
        final String key = (uid != null && !uid.isEmpty()) ? uid : Integer.toHexString(sid.hashCode());

        logCb = cb;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Acct a = accts.get(key);
                if (a == null) {
                    Acct n = new Acct();
                    Acct old = accts.putIfAbsent(key, n);
                    a = old != null ? old : n;
                }
                boolean got = false;
                try {
                    a.sem.acquire();
                    got = true;
                    gap(a);
                    cb.onStarted(target, type, 20000L);
                    Res r = doTask(key, target, type, mediaUrl, ck);
                    cb.onResult(r.ok, r.msg, target);
                } catch (Exception e) {
                    String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    cb.onResult(false, "Network error: " + m, target);
                } finally {
                    if (got) a.sem.release();
                }
            }
        });
        return true;
    }

    private void gap(Acct a) throws InterruptedException {
        synchronized (a) {
            long now = System.currentTimeMillis();
            long wait = a.lastStart + MIN_GAP_MS - now;
            if (wait > 0) {
                Thread.sleep(wait);
                now += wait;
            }
            a.lastStart = now;
        }
    }

    private void lookupGap() throws InterruptedException {
        synchronized (lookupLock) {
            long now = System.currentTimeMillis();
            long wait = lastLookup + LOOKUP_GAP_MS - now;
            if (wait > 0) {
                Thread.sleep(wait);
                now += wait;
            }
            lastLookup = now;
        }
    }

    // ------------------------------------------------------------------
    private Res doTask(String key, String target, String type, String mediaUrl,
                       Map<String, String> ck) throws Exception {
        boolean like = "like".equalsIgnoreCase(type);

        Long pu = pausedUntil.get(key);
        if (pu != null && System.currentTimeMillis() < pu.longValue()) {
            log("Cooldown active: " + ((pu.longValue() - System.currentTimeMillis()) / 60000L) + " min left");
            return new Res(false, "Action blocked: account on cooldown");
        }

        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        String capKey = "cap_" + key + "_" + day + "_" + (like ? "l" : "f");
        int used = prefs.getInt(capKey, 0);
        if (used >= (like ? DAILY_LIKE_CAP : DAILY_FOLLOW_CAP)) {
            return new Res(false, "Action blocked: daily cap reached");
        }

        // csrftoken cookie me na ho to ek bana ke cookie + header dono me lagate hain
        String csrf = ck.get("csrftoken");
        if (csrf == null || csrf.isEmpty()) {
            ck.put("csrftoken", randomToken(32));
        }

        Res res = like ? doLike(key, mediaUrl, target, ck) : doFollow(key, target, ck);
        if (res.ok) prefs.edit().putInt(capKey, used + 1).apply();
        return res;
    }

    private Res doFollow(String key, String target, Map<String, String> ck) throws Exception {
        String name = target.startsWith("@") ? target.substring(1) : target;
        String cacheKey = name.toLowerCase(Locale.US);
        String id = idCache.get(cacheKey);

        if (id == null) {
            // pehle phone me saved ID dekho (dobara lookup na karna pade)
            id = prefs.getString("uid_" + cacheKey, null);
            if (id != null) idCache.put(cacheKey, id);
        }

        if (id == null) {
            if (System.currentTimeMillis() < lookupPausedUntil) {
                return new Res(false, "Failed: profile lookup throttled (cooling down)");
            }
            String lookupUrl = "https://www.instagram.com/api/v1/users/web_profile_info/?username="
                    + URLEncoder.encode(name, "UTF-8");
            String lookupRef = "https://www.instagram.com/" + name + "/";
            lookupGap();
            Http p = httpR("GET", lookupUrl, ck, lookupRef);
            if (p.code == 429) {
                // ek baar thoda ruk ke dobara
                Thread.sleep(4000);
                lookupGap();
                p = httpR("GET", lookupUrl, ck, lookupRef);
            }
            if (p.code == 404) return new Res(false, "Profile unavailable");
            Res perr = commonError(key, p, false);
            if (perr != null) {
                if (perr.msg.startsWith("Failed: profile lookup throttled")) {
                    lookupPausedUntil = System.currentTimeMillis() + LOOKUP_PAUSE_MS;
                }
                return perr;
            }
            String found = "";
            if (p.json != null) {
                JSONObject data = p.json.optJSONObject("data");
                JSONObject user = data == null ? null : data.optJSONObject("user");
                if (user != null) found = user.optString("id", "");
            }
            if (found.isEmpty()) return new Res(false, "Profile unavailable");
            id = found;
            idCache.put(cacheKey, id);
            prefs.edit().putString("uid_" + cacheKey, id).apply();
        }

        Http h = httpR("POST", "https://www.instagram.com/api/v1/friendships/create/" + id + "/",
                ck, "https://www.instagram.com/" + name + "/");
        Res err = commonError(key, h, true);
        if (err != null) return err;

        if (h.json != null && "ok".equals(h.json.optString("status"))) {
            return new Res(true, "Followed Successfully");
        }
        String msg = h.json != null ? h.json.optString("message", "") : "";
        String ml = msg.toLowerCase(Locale.US);
        if (ml.contains("not found") || ml.contains("isn't available") || ml.contains("unavailable")) {
            return new Res(false, "Profile unavailable");
        }
        if (ml.contains("already")) return new Res(true, "Already followed");
        return new Res(false, "Failed: HTTP " + h.code + " " + cut(msg.isEmpty() ? h.body : msg));
    }

    private Res doLike(String key, String mediaUrl, String target, Map<String, String> ck) throws Exception {
        String code = null;
        if (mediaUrl != null) {
            Matcher mm = MEDIA_RE.matcher(mediaUrl);
            if (mm.find()) code = mm.group(1);
        }
        if (code == null) return new Res(false, "Failed: post link missing");
        String mediaId = shortcodeToId(code);
        if (mediaId == null) return new Res(false, "Failed: bad post code");

        Http h = httpR("POST", "https://www.instagram.com/api/v1/web/likes/" + mediaId + "/like/",
                ck, "https://www.instagram.com/p/" + code + "/");
        if (h.code == 404) return new Res(false, "Profile unavailable");
        Res err = commonError(key, h, true);
        if (err != null) return err;

        if (h.json != null && "ok".equals(h.json.optString("status"))) {
            return new Res(true, "Post Liked Successfully");
        }
        String msg = h.json != null ? h.json.optString("message", "") : "";
        String ml = msg.toLowerCase(Locale.US);
        if (ml.contains("already")) return new Res(true, "Already liked");
        if (ml.contains("not found") || ml.contains("unavailable")) return new Res(false, "Profile unavailable");
        return new Res(false, "Failed: HTTP " + h.code + " " + cut(msg.isEmpty() ? h.body : msg));
    }

    /** 403 + csrf error aaye to naya csrftoken lekar ek baar dobara try. */
    private Http httpR(String method, String url, Map<String, String> ck, String referer) throws IOException {
        Http h = http(method, url, ck, referer);
        if (h.code == 403 && h.body != null && h.body.toLowerCase(Locale.US).contains("csrf")) {
            refreshCsrf(ck);
            h = http(method, url, ck, referer);
        }
        return h;
    }

    private void refreshCsrf(Map<String, String> ck) {
        try {
            Http g = http("GET", "https://www.instagram.com/accounts/login/", ck, "https://www.instagram.com/");
            for (String sc : g.setCookies) {
                if (sc != null && sc.startsWith("csrftoken=")) {
                    int e = sc.indexOf(';');
                    String v = e > 0 ? sc.substring(10, e) : sc.substring(10);
                    if (!v.isEmpty()) ck.put("csrftoken", v);
                }
            }
        } catch (Exception ignored) {}
    }

    /** login / challenge / block jaise common errors. null = koi common error nahi. */
    private Res commonError(String key, Http h, boolean isAction) {
        String b = h.body == null ? "" : h.body;
        String bl = b.toLowerCase(Locale.US);
        if (h.code == 401 || h.code == 302 || h.code == 301 || bl.contains("login_required")
                || (h.code == 200 && bl.startsWith("<"))) {
            return new Res(false, "Session expired");
        }
        if (bl.contains("checkpoint_required") || bl.contains("challenge_required")) {
            return new Res(false, "Blocked: Challenge / Checkpoint");
        }
        if (h.code == 429 || bl.contains("feedback_required") || bl.contains("\"spam\":true")
                || bl.contains("please wait a few minutes")) {
            if (isAction) {
                // asli follow/like par block aaya, tabhi cooldown lagao
                pausedUntil.put(key, Long.valueOf(System.currentTimeMillis() + BLOCK_PAUSE_MS));
                log("COOLDOWN set (action): HTTP " + h.code + " :: " + cut(b));
                return new Res(false, "Action blocked: Instagram rate limit");
            }
            // profile lookup throttle hua: ye account block nahi hai, cooldown mat lagao
            log("Lookup throttled (no cooldown): HTTP " + h.code + " :: " + cut(b));
            return new Res(false, "Failed: profile lookup throttled (HTTP " + h.code + ")");
        }
        if (h.code == 403) return new Res(false, "Failed: HTTP 403 rejected by Instagram (see log)");
        return null;
    }

    // ------------------------------------------------------------------
    private Http http(String method, String url, Map<String, String> ck, String referer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setRequestProperty("x-ig-app-id", APP_ID);
            c.setRequestProperty("x-ig-www-claim", "0");
            c.setRequestProperty("x-requested-with", "XMLHttpRequest");
            String csrf = ck.get("csrftoken");
            c.setRequestProperty("x-csrftoken", csrf == null ? "" : csrf);
            c.setRequestProperty("Origin", "https://www.instagram.com");
            c.setRequestProperty("Referer", referer);
            c.setRequestProperty("Cookie", buildCookie(ck));
            if ("POST".equals(method)) {
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.getOutputStream().close();
            }
            Http h = new Http();
            h.code = c.getResponseCode();
            List<String> sc = c.getHeaderFields().get("Set-Cookie");
            if (sc != null) h.setCookies.addAll(sc);
            InputStream is = h.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0 && bo.size() < 200000) bo.write(buf, 0, n);
                is.close();
                h.body = bo.toString("UTF-8");
            }
            try { h.json = new JSONObject(h.body); } catch (Exception ignored) { h.json = null; }
            String shortUrl = url.indexOf('?') > 0 ? url.substring(0, url.indexOf('?')) : url;
            log(method + " " + shortUrl.replace("https://www.instagram.com", "") + " -> HTTP " + h.code + " :: " + cut(h.body));
            return h;
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------
    private static Map<String, String> parseCookie(String s) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String part : s.split(";")) {
            part = part.trim();
            int p = part.indexOf('=');
            if (p <= 0) continue;
            m.put(part.substring(0, p), part.substring(p + 1));
        }
        return m;
    }

    private static String buildCookie(Map<String, String> ck) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : ck.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private String randomToken(int n) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    /** Post code (shortcode) se numeric media id. */
    private static String shortcodeToId(String code) {
        BigInteger id = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(64);
        for (int i = 0; i < code.length(); i++) {
            int v = B64.indexOf(code.charAt(i));
            if (v < 0) return null;
            id = id.multiply(base).add(BigInteger.valueOf(v));
        }
        return id.toString();
    }

    private static String cut(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\r\\n]+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
