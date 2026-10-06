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

import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.MessageDigest;

public class MainActivity extends Activity {

    // 1. Security Secret Key & Version Code (Purane APK block karne aur website hide karne ke liye)
    private static final String APP_SECRET_KEY = "brohu2580";
    private static final int APP_VERSION_CODE = 2;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 " + APP_SECRET_KEY + " v/" + APP_VERSION_CODE;

    private WebView mainWebView;
    private WebView workerWebView;
    private WebView searchWebView;
    private String pendingSearchUser = null;

    private static final String HOSTING_BASE = "https://follow2follow.shop/";
    private static final String HOSTING_DASHBOARD = HOSTING_BASE + "index.php";
    private static final String IG_URL = "https://www.instagram.com";
    private static final String IG_LOGIN_URL = "https://www.instagram.com/accounts/login/";

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

        // Search WebView (target profile search ke liye, worker se alag)
        searchWebView = new WebView(this);
        setupWebView(searchWebView);
        searchWebView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        searchWebView.setAlpha(0.01f);
        if (rootView != null) {
            rootView.addView(searchWebView, 0);
        }
        searchWebView.addJavascriptInterface(new SearchBridge(), "SearchBridge");
        searchWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (pendingSearchUser != null && url != null && url.startsWith(IG_URL)) {
                    runSearchJs(pendingSearchUser);
                }
                if (pendingPostsUser != null && url != null && url.startsWith(IG_URL)) {
                    String pp = "";
                    try { pp = String.valueOf(Uri.parse(url).getPath()).toLowerCase(); } catch (Exception ignored) {}
                    if (pp.startsWith("/accounts/login")) {
                        deliverPosts("{\"success\":false,\"message\":\"Instagram session expired. Please log in again.\"}");
                    } else if (pp.startsWith("/" + pendingPostsUser.toLowerCase())) {
                        runPostsJs(pendingPostsUser);
                    }
                }
            }
        });

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

        // App start: Dashboard load karte waqt Version code (?v=2) aur Device Key dono pass honge
        mainWebView.loadUrl(HOSTING_DASHBOARD + "?v=" + APP_VERSION_CODE + "&dk=" + deviceKey);
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
            mainWebView.loadUrl(HOSTING_DASHBOARD + "?v=" + APP_VERSION_CODE + "&dk=" + deviceKey);
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
    // LIVE PROFILE SEARCH
    // ------------------------------------------------------------------
    private boolean searchRunning = false;

    private final Runnable searchTimeout = new Runnable() {
        @Override
        public void run() {
            deliverSearch("{\"success\":false,\"message\":\"Search timeout. Dobara try karo.\"}");
        }
    };

    private void startSearch(String u) {
        pendingSearchUser = u;
        searchRunning = false;
        uiHandler.removeCallbacks(searchTimeout);
        uiHandler.postDelayed(searchTimeout, 20000);
        String cur = searchWebView.getUrl();
        if (cur != null && cur.startsWith(IG_URL)) {
            runSearchJs(u);
        } else {
            searchWebView.loadUrl(IG_URL + "/");
        }
    }

    private void runSearchJs(String u) {
        if (searchRunning) return;
        searchRunning = true;
        searchWebView.evaluateJavascript(buildSearchJs(u), null);
    }

    private void deliverSearch(String json) {
        if (pendingSearchUser == null) return;
        pendingSearchUser = null;
        searchRunning = false;
        uiHandler.removeCallbacks(searchTimeout);
        mainWebView.evaluateJavascript(
                "if(window.onInstagramLiveSearchResult) window.onInstagramLiveSearchResult(true, "
                        + JSONObject.quote(json) + ");", null);
    }

    private static String buildSearchJs(String u) {
        return js(
            "(function() {",
            "  var U = '" + u + "';",
            "  var done = false;",
            "  var H = { 'X-IG-App-ID': '936619743392459', 'X-Requested-With': 'XMLHttpRequest', 'Accept': '*/*' };",
            "  function out(o) { if (done) return; done = true; SearchBridge.onResult(JSON.stringify(o)); }",
            "  function fmt(n) {",
            "    n = Number(n) || 0;",
            "    return n.toLocaleString('en-US');",
            "  }",
            "  function numAfter(t, key) {",
            "    var k = t.indexOf(key);",
            "    if (k < 0) return 0;",
            "    var s = k + key.length, e = s;",
            "    while (e < t.length && t.charAt(e) >= '0' && t.charAt(e) <= '9') e++;",
            "    return Number(t.substring(s, e)) || 0;",
            "  }",
            "  function good(r) {",
            "    out({ success: true, username: r.username || U, full_name: r.full_name || '', numeric_id: '' + (r.pk || ''),",
            "          follower_count: r.fc, followers_formatted: fmt(r.fc) + ' followers', is_verified: !!r.verified, profile_pic: r.pic || '' });",
            "  }",
            "  function tryApi() {",
            "    return fetch('/api/v1/users/web_profile_info/?username=' + encodeURIComponent(U), { credentials: 'include', headers: H })",
            "    .then(function(r) { return r.text().then(function(t) { return { s: r.status, t: t }; }); })",
            "    .then(function(x) {",
            "      try {",
            "        var d = JSON.parse(x.t);",
            "        var u = (d.data && d.data.user) ? d.data.user : (d.user || null);",
            "        if (u && u.username) {",
            "          var fc = (u.edge_followed_by && u.edge_followed_by.count !== undefined) ? u.edge_followed_by.count : (u.follower_count || 0);",
            "          return { username: u.username, full_name: u.full_name, pk: u.id || u.pk, fc: fc, verified: u.is_verified, pic: u.profile_pic_url_hd || u.profile_pic_url };",
            "        }",
            "        if (d && (d.message === 'login_required' || d.require_login)) return { err: 'login' };",
            "      } catch (e) {}",
            "      return null;",
            "    }).catch(function() { return null; });",
            "  }",
            "  function tryHtml() {",
            "    return fetch('/' + encodeURIComponent(U) + '/', { credentials: 'include', headers: { 'Accept': 'text/html' } })",
            "    .then(function(r) { return r.text().then(function(t) { return { s: r.status, t: t }; }); })",
            "    .then(function(x) {",
            "      var low = x.t.toLowerCase();",
            "      if (x.s === 404 || (low.indexOf('page isn') > -1 && low.indexOf('available') > -1 && low.indexOf('og:title') < 0)) return { notfound: true };",
            "      var doc = new DOMParser().parseFromString(x.t, 'text/html');",
            "      function meta(p) {",
            "        var m = Array.prototype.slice.call(doc.querySelectorAll('meta')).filter(function(e) { return e.getAttribute('property') === p || e.getAttribute('name') === p; });",
            "        return m.length ? (m[0].getAttribute('content') || '') : '';",
            "      }",
            "      var desc = meta('og:description') || meta('description');",
            "      var i = desc.toLowerCase().indexOf(' followers');",
            "      if (i < 0) return null;",
            "      var tok = desc.substring(0, i).trim().split(' ').pop().split(',').join('');",
            "      var mult = 1, last = tok.charAt(tok.length - 1).toUpperCase();",
            "      if (last === 'K') { mult = 1000; tok = tok.slice(0, -1); }",
            "      else if (last === 'M') { mult = 1000000; tok = tok.slice(0, -1); }",
            "      else if (last === 'B') { mult = 1000000000; tok = tok.slice(0, -1); }",
            "      var fc = Math.round(parseFloat(tok) * mult);",
            "      if (isNaN(fc)) return null;",
            "      var exact = numAfter(x.t, '\"edge_followed_by\":{\"count\":') || numAfter(x.t, '\"follower_count\":');",
            "      if (exact > 0 && Math.abs(exact - fc) <= Math.max(fc * 0.1, 1)) fc = exact;",
            "      var title = meta('og:title');",
            "      var fn = title.indexOf(' (@') > -1 ? title.split(' (@')[0] : '';",
            "      var pk = '', keys = ['\"profile_id\":\"', '\"target_id\":\"', '\"user_id\":\"'];",
            "      for (var j = 0; j < keys.length && !pk; j++) {",
            "        var k = x.t.indexOf(keys[j]);",
            "        if (k > -1) { var e = x.t.indexOf('\"', k + keys[j].length); pk = x.t.substring(k + keys[j].length, e); if (isNaN(Number(pk))) pk = ''; }",
            "      }",
            "      return { username: U, full_name: fn, pk: pk, fc: fc, verified: x.t.indexOf('\"is_verified\":true') > -1, pic: meta('og:image') };",
            "    }).catch(function() { return null; });",
            "  }",
            "  tryApi().then(function(a) {",
            "    if (a && a.username) return a;",
            "    return tryHtml().then(function(h) { return (h && (h.username || h.notfound)) ? h : (a || h); });",
            "  }).then(function(r) {",
            "    if (r && r.username) { good(r); }",
            "    else if (r && r.notfound) { out({ success: false, message: 'Account not found on Instagram' }); }",
            "    else if (r && r.err === 'login') { out({ success: false, message: 'Instagram login expire. Account re-login karo.' }); }",
            "    else { out({ success: false, message: 'Instagram ne response nahi diya. Thodi der baad try karo.' }); }",
            "  }).catch(function(e) { out({ success: false, message: 'Search error: ' + e.message }); });",
            "})();"
        );
    }

    // ------------------------------------------------------------------
    // TARGET ACCOUNT KE POSTS + REELS
    // ------------------------------------------------------------------
    private String pendingPostsUser = null;
    private boolean postsRunning = false;

    private final Runnable postsTimeout = new Runnable() {
        @Override
        public void run() {
            deliverPosts("{\"success\":false,\"message\":\"Request timed out. Please try again.\"}");
        }
    };

    private void startPosts(String u) {
        pendingPostsUser = u;
        postsRunning = false;
        uiHandler.removeCallbacks(postsTimeout);
        uiHandler.postDelayed(postsTimeout, 40000);
        searchWebView.loadUrl(IG_URL + "/" + u + "/");
    }

    private void runPostsJs(String u) {
        if (postsRunning) return;
        postsRunning = true;
        searchWebView.evaluateJavascript(buildPostsJs(u), null);
    }

    private void deliverPosts(String json) {
        if (pendingPostsUser == null) return;
        pendingPostsUser = null;
        postsRunning = false;
        uiHandler.removeCallbacks(postsTimeout);
        mainWebView.evaluateJavascript(
                "if(window.onInstagramPostsResult) window.onInstagramPostsResult(true, "
                        + JSONObject.quote(json) + ");", null);
    }

    private static String buildPostsJs(String u) {
        return js(
            "(function() {",
            "  var U = '" + u + "';",
            "  var done = false, loginErr = false, missing = false;",
            "  var H = { 'X-IG-App-ID': '936619743392459', 'X-Requested-With': 'XMLHttpRequest', 'Accept': '*/*' };",
            "  function out(o) { if (done) return; done = true; SearchBridge.onPosts(JSON.stringify(o)); }",
            "  function cookie(name) {",
            "    var c = (document.cookie || '').split(';');",
            "    for (var i = 0; i < c.length; i++) { var p = c[i].trim(); if (p.indexOf(name + '=') === 0) return p.substring(name.length + 1); }",
            "    return '';",
            "  }",
            "  function numAfter(t, key) {",
            "    var k = t.indexOf(key);",
            "    if (k < 0) return 0;",
            "    var s = k + key.length, e = s;",
            "    while (e < t.length && t.charAt(e) >= '0' && t.charAt(e) <= '9') e++;",
            "    return Number(t.substring(s, e)) || 0;",
            "  }",
            "  function fromNode(n) {",
            "    return { code: n.shortcode, thumb: n.thumbnail_src || n.display_url || '', likes: (n.edge_liked_by && n.edge_liked_by.count) || 0, video: !!n.is_video, reel: n.product_type === 'clips', ts: n.taken_at_timestamp || 0 };",
            "  }",
            "  function fromItem(i) {",
            "    var th = '';",
            "    try { th = i.image_versions2.candidates[0].url; } catch (e) {}",
            "    if (!th) { try { th = i.carousel_media[0].image_versions2.candidates[0].url; } catch (e) {} }",
            "    return { code: i.code, thumb: th, likes: i.like_count || 0, video: i.media_type === 2, reel: i.product_type === 'clips', ts: i.taken_at || 0 };",
            "  }",
            "  function apiProfile() {",
            "    return fetch('/api/v1/users/web_profile_info/?username=' + encodeURIComponent(U), { credentials: 'include', headers: H })",
            "    .then(function(r) { return r.text(); })",
            "    .then(function(t) {",
            "      try {",
            "        var d = JSON.parse(t);",
            "        if (d && (d.message === 'login_required' || d.require_login)) { loginErr = true; return null; }",
            "        var u = d.data && d.data.user;",
            "        if (!u) return null;",
            "        var edges = (u.edge_owner_to_timeline_media && u.edge_owner_to_timeline_media.edges) || [];",
            "        return { id: u.id, fc: (u.edge_followed_by && u.edge_followed_by.count !== undefined) ? u.edge_followed_by.count : null,",
            "                 pic: u.profile_pic_url_hd || u.profile_pic_url || '', name: u.full_name || '',",
            "                 priv: !!(u.is_private && !u.followed_by_viewer), posts: edges.map(function(e) { return fromNode(e.node); }) };",
            "      } catch (e) { return null; }",
            "    }).catch(function() { return null; });",
            "  }",
            "  function htmlInfo() {",
            "    return fetch('/' + encodeURIComponent(U) + '/', { credentials: 'include', headers: { 'Accept': 'text/html' } })",
            "    .then(function(r) { return r.text().then(function(t) { return { s: r.status, t: t }; }); })",
            "    .then(function(x) {",
            "      var low = x.t.toLowerCase();",
            "      if (x.s === 404 || (low.indexOf('page isn') > -1 && low.indexOf('available') > -1 && low.indexOf('og:title') < 0)) { missing = true; return null; }",
            "      var doc = new DOMParser().parseFromString(x.t, 'text/html');",
            "      function meta(p) {",
            "        var m = Array.prototype.slice.call(doc.querySelectorAll('meta')).filter(function(e) { return e.getAttribute('property') === p || e.getAttribute('name') === p; });",
            "        return m.length ? (m[0].getAttribute('content') || '') : '';",
            "      }",
            "      var desc = meta('og:description') || meta('description');",
            "      var i = desc.toLowerCase().indexOf(' followers');",
            "      if (i < 0) return null;",
            "      var tok = desc.substring(0, i).trim().split(' ').pop().split(',').join('');",
            "      var mult = 1, last = tok.charAt(tok.length - 1).toUpperCase();",
            "      if (last === 'K') { mult = 1000; tok = tok.slice(0, -1); }",
            "      else if (last === 'M') { mult = 1000000; tok = tok.slice(0, -1); }",
            "      else if (last === 'B') { mult = 1000000000; tok = tok.slice(0, -1); }",
            "      var fc = Math.round(parseFloat(tok) * mult);",
            "      if (isNaN(fc)) return null;",
            "      var exact = numAfter(x.t, '\"edge_followed_by\":{\"count\":') || numAfter(x.t, '\"follower_count\":');",
            "      if (exact > 0 && Math.abs(exact - fc) <= Math.max(fc * 0.1, 1)) fc = exact;",
            "      var title = meta('og:title');",
            "      var fn = title.indexOf(' (@') > -1 ? title.split(' (@')[0] : '';",
            "      var pk = '', keys = ['\"profile_id\":\"', '\"target_id\":\"', '\"user_id\":\"'];",
            "      for (var j = 0; j < keys.length && !pk; j++) {",
            "        var k = x.t.indexOf(keys[j]);",
            "        if (k > -1) { var e = x.t.indexOf('\"', k + keys[j].length); pk = x.t.substring(k + keys[j].length, e); if (isNaN(Number(pk))) pk = ''; }",
            "      }",
            "      return { id: pk, fc: fc, pic: meta('og:image'), name: fn, posts: [] };",
            "    }).catch(function() { return null; });",
            "  }",
            "  function viaFeed() {",
            "    return fetch('/api/v1/feed/user/' + encodeURIComponent(U) + '/username/?count=18', { credentials: 'include', headers: H })",
            "    .then(function(r) { return r.text(); })",
            "    .then(function(t) {",
            "      try {",
            "        var d = JSON.parse(t);",
            "        if (d && d.items) return d.items.map(fromItem);",
            "        if (d && (d.message === 'login_required' || d.require_login)) loginErr = true;",
            "      } catch (e) {}",
            "      return [];",
            "    }).catch(function() { return []; });",
            "  }",
            "  function viaClips(id) {",
            "    var h = { 'X-IG-App-ID': '936619743392459', 'X-Requested-With': 'XMLHttpRequest', 'x-csrftoken': cookie('csrftoken'), 'content-type': 'application/x-www-form-urlencoded' };",
            "    return fetch('/api/v1/clips/user/', { method: 'POST', credentials: 'include', headers: h,",
            "      body: 'target_user_id=' + encodeURIComponent(id) + '&page_size=18&include_feed_video=true' })",
            "    .then(function(r) { return r.text(); })",
            "    .then(function(t) {",
            "      try {",
            "        var d = JSON.parse(t);",
            "        if (d && d.items) return d.items.map(function(x) { var m = fromItem(x.media || x); m.reel = true; return m; });",
            "      } catch (e) {}",
            "      return [];",
            "    }).catch(function() { return []; });",
            "  }",
            "  function merge(all) {",
            "    var m = {};",
            "    all.forEach(function(x) {",
            "      if (!x.code) return;",
            "      var o = m[x.code];",
            "      if (!o) { m[x.code] = x; return; }",
            "      if (x.reel) o.reel = true;",
            "      if (!o.thumb) o.thumb = x.thumb;",
            "      if (x.likes > o.likes) o.likes = x.likes;",
            "      if (!o.ts) o.ts = x.ts;",
            "    });",
            "    return Object.keys(m).map(function(k) { return m[k]; }).sort(function(a, b) { return (b.ts || 0) - (a.ts || 0); });",
            "  }",
            "  function scrape() {",
            "    var root = document.querySelector('main') || document;",
            "    var as = root.querySelectorAll('a[href*=\"/p/\"], a[href*=\"/reel/\"]');",
            "    var map = {}, list = [];",
            "    for (var i = 0; i < as.length; i++) {",
            "      var a = as[i];",
            "      var m = (a.getAttribute('href') || '').match(/\\/(p|reel)\\/([A-Za-z0-9_-]+)/);",
            "      if (!m) continue;",
            "      var clip = !!a.querySelector('svg[aria-label=\"Clip\"], svg[aria-label=\"Reel\"]');",
            "      var o = map[m[2]];",
            "      if (o) { if (m[1] === 'reel' || clip) o.reel = true; continue; }",
            "      var img = a.querySelector('img');",
            "      o = { code: m[2], thumb: img ? (img.currentSrc || img.src || '') : '', likes: 0, video: (m[1] === 'reel' || clip || !!a.querySelector('svg[aria-label=\"Video\"]')), reel: (m[1] === 'reel' || clip), ts: 0 };",
            "      map[m[2]] = o; list.push(o);",
            "    }",
            "    return list;",
            "  }",
            "  function domPosts() {",
            "    return new Promise(function(resolve) {",
            "      var t0 = Date.now(), last = -1, stable = 0;",
            "      function poll() {",
            "        var body = (document.body ? document.body.innerText : '').toLowerCase();",
            "        var list = scrape();",
            "        if (!list.length && body.indexOf('this account is private') > -1) { resolve({ list: [], priv: true }); return; }",
            "        if (!list.length && body.indexOf('page isn') > -1 && body.indexOf('available') > -1) { resolve({ list: [], missing: true }); return; }",
            "        if (list.length > 0 && list.length === last) stable++; else stable = 0;",
            "        last = list.length;",
            "        if ((list.length > 0 && stable >= 2) || Date.now() - t0 > 15000) { resolve({ list: list }); return; }",
            "        try { window.scrollTo(0, document.body.scrollHeight); } catch (e) {}",
            "        setTimeout(poll, 700);",
            "      }",
            "      poll();",
            "    });",
            "  }",
            "  function domFollowers() {",
            "    try {",
            "      var as = document.querySelectorAll('a[href*=\"/followers\"]');",
            "      for (var i = 0; i < as.length; i++) {",
            "        var sp = as[i].querySelector('span[title]');",
            "        var s = sp ? sp.getAttribute('title') : '';",
            "        var n = Number((s || '').split(',').join(''));",
            "        if (s && !isNaN(n)) return n;",
            "      }",
            "    } catch (e) {}",
            "    return null;",
            "  }",
            "  function finish(info, list, priv, miss) {",
            "    var fc = (info && info.fc !== undefined && info.fc !== null) ? info.fc : domFollowers();",
            "    if (!list.length && !priv && miss && fc === null) { out({ success: false, message: 'Account not found on Instagram.' }); return; }",
            "    if (!list.length && !priv && loginErr && fc === null) { out({ success: false, message: 'Instagram session expired. Please log in again.' }); return; }",
            "    var o = { success: true, username: U, is_private: !!priv, posts: list.slice(0, 30) };",
            "    if (fc !== null) o.follower_count = fc;",
            "    if (info && info.pic) o.profile_pic = info.pic;",
            "    if (info && info.name) o.full_name = info.name;",
            "    out(o);",
            "  }",
            "  if ((location.pathname || '').indexOf('/accounts/login') === 0) { out({ success: false, message: 'Instagram session expired. Please log in again.' }); return; }",
            "  apiProfile().then(function(p) {",
            "    return (p ? Promise.resolve(null) : htmlInfo()).then(function(h) {",
            "      var info = p || h || null;",
            "      var id = (info && info.id) ? info.id : '';",
            "      return Promise.all([viaFeed(), id ? viaClips(id) : Promise.resolve([])]).then(function(res) {",
            "        if (p && p.priv) { finish(info, [], true, false); return; }",
            "        var list = merge((p ? p.posts : []).concat(res[0] || []).concat(res[1] || []));",
            "        if (list.length) { finish(info, list, false, false); return; }",
            "        return domPosts().then(function(dl) { finish(info, dl.list || [], !!dl.priv, !!dl.missing || missing); });",
            "      });",
            "    });",
            "  }).catch(function(e) { out({ success: false, message: 'Posts error: ' + e.message }); });",
            "})();"
        );
    }

    // Search WebView se result lene ke liye
    public class SearchBridge {
        @JavascriptInterface
        public void onResult(final String json) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    deliverSearch(json);
                }
            });
        }

        @JavascriptInterface
        public void onPosts(final String json) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    deliverPosts(json);
                }
            });
        }
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
        public void fetchInstagramPostsLive(final String queryUsername) {
            if (!trusted()) return;
            final String u = queryUsername == null ? "" : queryUsername.replace("@", "").replaceAll("[^A-Za-z0-9._]", "");
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (u.isEmpty()) {
                        pendingPostsUser = "_";
                        deliverPosts("{\"success\":false,\"message\":\"Invalid username\"}");
                    } else {
                        startPosts(u);
                    }
                }
            });
        }

        @JavascriptInterface
        public void applySession(final String cookieStr) {
            if (!trusted() || cookieStr == null || cookieStr.isEmpty()) return;
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    applyInstagramCookies(cookieStr);
                }
            });
        }

        @JavascriptInterface
        public void searchInstagramProfileLive(final String queryUsername) {
            if (!trusted()) return;
            final String u = queryUsername == null ? "" : queryUsername.replace("@", "").replaceAll("[^A-Za-z0-9._]", "");
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (u.isEmpty()) {
                        pendingSearchUser = "_";
                        deliverSearch("{\"success\":false,\"message\":\"Invalid username\"}");
                    } else {
                        startSearch(u);
                    }
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
    }

    // Instagram probe
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
