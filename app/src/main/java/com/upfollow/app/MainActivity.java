package com.upfollow.app;

import android.annotation.SuppressLint;
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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;


import java.net.URLEncoder;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

public class MainActivity extends Activity {
    private static final String APP_SECRET_KEY = "brohu2580";
    // APP VERSION: har naye APK me ise badhao (2, 3, 4...). Server ka min_version_code isse compare hota hai.
    private static final int APP_VERSION_CODE = 3;
    // Secret + version wala user agent: SIRF apni website (mainWebView) ke liye
    // onCreate me phone ke asli WebView user agent se set hota hai
    private String USER_AGENT_APP = "";
    private WebView mainWebView;
    private WebView workerWebView;
    private WebView searchWebView;
    private String pendingSearchUser = null;

    private static final String HOSTING_BASE = "https://follow2follow.shop/";
    private static final String HOSTING_DASHBOARD = HOSTING_BASE + "index.php";
    private static final String IG_URL = "https://www.instagram.com";
    private static final String IG_LOGIN_URL = "https://www.instagram.com/accounts/login/";
    // Normal user agent (bina secret ke): Instagram wale WebViews ke liye
    // Normal user agent (bina secret ke): asli device wala, onCreate me set hota hai
    private String USER_AGENT = "";

    private static final String CHANNEL_ID = "upfollow_automation_channel";
    private static final int NOTIFICATION_ID = 1001;
    // FIX: pehle 40 sec tha, jisse page dheere khulne par task jaldi fail ho jata tha
    private static final long TASK_TIMEOUT_MS = 90000L;

    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private volatile String mainUrl = "";
    private String deviceKey = "";
    // NAYA: chhoti settings yaad rakhne ke liye (auto-run ON/OFF, battery prompt)
    private SharedPreferences prefs;

    private String currentTaskType = "follow";
    private boolean taskActive = false;
    private boolean workerActed = false; // is task me hamne khud follow/like kiya ya nahi
    // FIX: single-worker mode me bhi retry (jab tak task poora na ho, aage mat jao)
    private int workerRetries = 0;
    private long workerStartedAt = 0;

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

        // Phone ka asli WebView user agent: Instagram ko mismatch nahi dikhega (unavailable / can't find account kam)
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
        // Main WebView: secret key + version wala user agent (website security ke liye)
        setupWebView(mainWebView, USER_AGENT_APP);

        // Worker WebView: full-size, main WebView ke neeche chhupa hua
        workerWebView = new WebView(this);
        setupWebView(workerWebView);
        // NAYA: hidden browser me photos/videos load nahi honge -> page jaldi khulega
        workerWebView.getSettings().setBlockNetworkImage(true);
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
                // FIX: Auto-run ON ho to website ka JS ready hone tak baar baar try karo
                if (url != null && url.startsWith(HOSTING_BASE) && prefs != null && prefs.getBoolean("auto_run", false)) {
                    tryAutoStart(0);
                }
            }
        });

        // ---------- Worker WebView client ----------
        workerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse blocked = blockHeavy(request);
                return blocked != null ? blocked : super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(final WebView view, String url) {
                super.onPageFinished(view, url);
                if (!taskActive || url == null || url.startsWith("about:")) return;

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
                }, 400);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (taskActive && request != null && request.isForMainFrame()) {
                    // FIX: page error par seedha fail nahi, pehle retry
                    if (workerShouldRetry("page error")) {
                        retryWorker();
                    } else {
                        deliverResult(false, "Page error");
                    }
                }
            }
        });

        // App start: hamesha dashboard (server decide karta hai: dashboard / saved accounts / login)
        mainWebView.loadUrl(HOSTING_DASHBOARD + "?dk=" + deviceKey);
    }

    // FIX: App khulte hi auto task. Website ka JS load hone tak (max ~20 sec) baar baar check karta hai
    private void tryAutoStart(final int attempt) {
        if (attempt > 20) return;
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mainWebView == null) return;
                mainWebView.evaluateJavascript(
                    "(function(){if(window.__autoStarted)return 'done';"
                    + "if(typeof window.onAppAutoStart==='function'){window.__autoStarted=true;window.onAppAutoStart();return 'done';}"
                    + "return 'wait';})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) {
                            if (v != null && v.contains("wait")) tryAutoStart(attempt + 1);
                        }
                    });
            }
        }, attempt == 0 ? 1500 : 1000);
    }

    // Normal WebView (Instagram wale): bina secret key ke user agent
    private void setupWebView(WebView wv) {
        setupWebView(wv, USER_AGENT);
    }

    private void setupWebView(WebView wv, String ua) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(ua);

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
        // NAYA: Jis link me open_external=1 ho, wo app se bahar Chrome (ya default browser) me khulega
        if (url != null && url.contains("open_external=1")) {
            final String real = url.replace("&open_external=1", "").replace("?open_external=1", "");
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(real));
                // Telegram link ho to Chrome force mat karo, Telegram app seedha khulegi
                if (!real.startsWith("https://t.me/") && !real.startsWith("tg://")) {
                    i.setPackage("com.android.chrome");
                }
                startActivity(i);
            } catch (Exception e) {
                try {
                    // Chrome na ho to phone ka default browser
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(real)));
                } catch (Exception ignored) {}
            }
            return true;
        }
        if (url != null && url.contains("force_authentication=1")) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    addMode = true;
                    captureBusy = false;
                    // Saare accounts ki cookies server par saved hain, isliye jar saaf karna safe hai.
                    // Isse Instagram naya login form dikhata hai aur purana session nahi ghusta.
                    applyInstagramCookies("");
                    // Instagram ko secret key nahi dikhani: normal user agent lagao
                    mainWebView.getSettings().setUserAgentString(USER_AGENT);
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

        // Agar username fetch atak jaye to bina username ke aage badho
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
        // Website par wapas jaane se pehle secret key + version wala user agent dobara lagao
        mainWebView.getSettings().setUserAgentString(USER_AGENT_APP);
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

    // cookieStr khali ho to sirf Instagram cookies clear hoti hain
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

    // FIX: single-worker mode retry (jab tak task poora na ho ya sach me error na ho, aage mat jao)
    private boolean workerShouldRetry(String msg) {
        if (!taskActive || workerRetries >= 2) return false;
        if (System.currentTimeMillis() - workerStartedAt > 70000) return false;
        String m = msg == null ? "" : msg.toLowerCase();
        return m.contains("not found") || m.contains("did not register")
                || m.contains("page error") || m.contains("js error")
                || m.contains("unavailable");
    }

    private void retryWorker() {
        workerRetries++;
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!taskActive) return;
                try {
                    workerWebView.reload();
                } catch (Exception e) {
                    deliverResult(false, "Page error");
                }
            }
        }, 3000);
    }

    private static String js(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Shared JS helpers (fatal state detection, popups)
    // FIX: fatal() ab poore page ka text nahi padhta (bio me "try again later" se false block hota tha)
    // FIX: gone() ab 8 sec tak lagatar "not available" dikhe tabhi maanta hai
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
        "    if (document.querySelector('input[name=password]')) return 'Session expired';",
        "    var hasHdr = !!document.querySelector('header');",
        "    var t = hasHdr ? '' : bodyText();",
        "    if (t.indexOf('account has been disabled') > -1 || t.indexOf('account was disabled') > -1) return 'Disabled: Instagram disabled this account';",
        "    if (t.indexOf('account has been suspended') > -1 || t.indexOf('suspended your account') > -1 || t.indexOf('account is suspended') > -1) return 'Suspended: Instagram suspended this account';",
        "    var dlg = document.querySelector('div[role=dialog]');",
        "    var dt = dlg ? (dlg.innerText || '').toLowerCase() : '';",
        "    if (dt.indexOf('action blocked') > -1 || dt.indexOf('try again later') > -1 || dt.indexOf('we restrict certain activity') > -1) return 'Blocked: Action Limit / Challenge';",
        "    return null;",
        "  }",
        "  var goneSince = 0;",
        "  function gone() {",
        "    var t = bodyText();",
        "    var m = (t.indexOf('page isn') > -1 && t.indexOf('available') > -1) || (t.indexOf('content isn') > -1 && t.indexOf('available') > -1);",
        "    if (!m || document.querySelector('header')) { goneSince = 0; return false; }",
        "    if (!goneSince) goneSince = Date.now();",
        "    return Date.now() - goneSince > 8000;",
        "  }",
        "  function dismiss() {",
        "    var w = ['not now', 'allow all cookies', 'allow essential and optional cookies', 'accept all'];",
        "    btns().forEach(function(b) { if (w.indexOf(txt(b)) > -1) b.click(); });",
        "  }"
    );

    // ------------------------------------------------------------------
    // FOLLOW SCRIPT
    // FIX: fail bhejne se pehle API se verify (follow sach me hua ho to coin milega)
    // ------------------------------------------------------------------
    private void injectFollowScript(WebView view) {
        String script = JS_HEAD + js(
            "  var FOLLOW = ['follow', 'follow back', '\u092b\u0949\u0932\u094b \u0915\u0930\u0947\u0902'];",
            "  var DONE = ['following', 'requested'];",
            "  var apiTried = false, apiBusy = false, apiAt = 0, verifyAt = 0;",
            "  window.__ufLast = Date.now();",
            "  function find(list, words) {",
            "    for (var i = 0; i < list.length; i++) { if (words.indexOf(txt(list[i])) > -1) return list[i]; }",
            "    return null;",
            "  }",
            "  function cookie(name) {",
            "    var c = (document.cookie || '').split(';');",
            "    for (var i = 0; i < c.length; i++) { var p = c[i].trim(); if (p.indexOf(name + '=') === 0) return p.substring(name.length + 1); }",
            "    return '';",
            "  }",
            "  function who() { return (location.pathname || '').split('/').filter(function(x) { return x; })[0] || ''; }",
            "  var didAct = false, softBusy = false;",
            "  function acted() { didAct = true; try { window.WorkerBridge.onActed(); } catch (e) {} }",
            "  function softFail(msg) {",
            "    if (softBusy) return; softBusy = true;",
            "    var name = who();",
            "    if (!name) { finish(false, msg); return; }",
            "    fetch('/api/v1/users/web_profile_info/?username=' + encodeURIComponent(name), { credentials: 'include', headers: { 'x-ig-app-id': '936619743392459', 'x-requested-with': 'XMLHttpRequest' } })",
            "    .then(function(r) { return r.text(); })",
            "    .then(function(t) {",
            "      var d = null; try { d = JSON.parse(t); } catch (e) {}",
            "      var u = d && d.data && d.data.user;",
            "      if (u && (u.followed_by_viewer || u.requested_by_viewer)) finish(true, didAct ? 'Followed Successfully' : 'Already Following');",
            "      else finish(false, msg);",
            "    }).catch(function() { finish(false, msg); });",
            "  }",
            // ---- Tez raasta: seedha API. Fail ho to neeche purana button-click chalega ----
            "  function apiFollow() {",
            "    apiTried = true;",
            "    var name = who(), csrf = cookie('csrftoken');",
            "    if (!name || !csrf) return;",
            "    apiBusy = true; apiAt = Date.now();",
            "    var H = { 'x-ig-app-id': '936619743392459', 'x-requested-with': 'XMLHttpRequest' };",
            "    fetch('/api/v1/users/web_profile_info/?username=' + encodeURIComponent(name), { credentials: 'include', headers: H })",
            "    .then(function(r) { return r.text(); })",
            "    .then(function(t) {",
            "      var d = null; try { d = JSON.parse(t); } catch (e) {}",
            "      var u = d && d.data && d.data.user;",
            "      if (!u || !u.id) { apiBusy = false; return; }",
            "      if (u.followed_by_viewer || u.requested_by_viewer) { finish(true, 'Already Following'); return; }",
            "      var h2 = { 'x-csrftoken': csrf, 'x-ig-app-id': '936619743392459', 'x-requested-with': 'XMLHttpRequest', 'content-type': 'application/x-www-form-urlencoded' };",
            "      acted();",
            "      return fetch('/api/v1/friendships/create/' + u.id + '/', { method: 'POST', credentials: 'include', headers: h2, body: '' })",
            "      .then(function(r) { return r.text().then(function(t2) { return { s: r.status, t: t2 }; }); })",
            "      .then(function(x) {",
            "        apiBusy = false;",
            "        var low = x.t.toLowerCase().split(' ').join('');",
            "        if (x.s === 200 && low.indexOf('\"status\":\"ok\"') > -1) { finish(true, 'Followed Successfully'); return; }",
            "        if (low.indexOf('checkpoint') > -1 || low.indexOf('challenge') > -1) { finish(false, 'Blocked: Challenge / Checkpoint'); return; }",
            "        if (low.indexOf('login_required') > -1) { finish(false, 'Session expired'); return; }",
            "        if (low.indexOf('feedback_required') > -1 || x.s === 429 || low.indexOf('\"spam\":true') > -1 || low.indexOf('trylater') > -1) { finish(false, 'Blocked: Action Limit / Challenge'); return; }",
            "      });",
            "    }).catch(function() { apiBusy = false; });",
            "  }",
            // ---- Click ke baad pakka karo ki follow sach me hua (button late badle to bhi galat 'fail' na jaye) ----
            "  function verifyFollow() {",
            "    verifyAt = Date.now();",
            "    var name = who();",
            "    if (!name) return;",
            "    apiBusy = true; apiAt = Date.now();",
            "    fetch('/api/v1/users/web_profile_info/?username=' + encodeURIComponent(name), { credentials: 'include', headers: { 'x-ig-app-id': '936619743392459', 'x-requested-with': 'XMLHttpRequest' } })",
            "    .then(function(r) { return r.text(); })",
            "    .then(function(t) {",
            "      apiBusy = false;",
            "      var d = null; try { d = JSON.parse(t); } catch (e) {}",
            "      var u = d && d.data && d.data.user;",
            "      if (u && (u.followed_by_viewer || u.requested_by_viewer)) { finish(true, 'Followed Successfully'); }",
            "    }).catch(function() { apiBusy = false; });",
            "  }",
            "  function tick() {",
            "    try {",
            "      if (finished) return;",
            "      window.__ufLast = Date.now();",
            "      tries++;",
            "      var f = fatal(); if (f) { finish(false, f); return; }",
            "      dismiss();",
            "      if (apiBusy) { if (Date.now() - apiAt > 12000) { apiBusy = false; } else { return; } }",
            "      if (!apiTried && !clicked) { apiFollow(); if (apiBusy) return; }",
            // 'unavailable' tabhi jab API bhi kaam na kare aur page 8 sec tak lagatar 'not available' dikhaye
            "      if (tries >= 3 && !clicked && gone()) { softFail('Profile unavailable'); return; }",
            "      var scope = (tries > 16) ? document : (document.querySelector('header') || document.querySelector('main') || document);",
            "      var list = btns(scope);",
            "      if (clicked) {",
            "        if (find(list, DONE)) { finish(true, 'Followed Successfully'); return; }",
            "        var waited = Date.now() - clickedAt;",
            "        if (waited > 20000) { softFail('Click did not register'); return; }",
            "        if (waited > 5000 && Date.now() - verifyAt > 3000) { verifyFollow(); }",
            "        return;",
            "      }",
            "      if (find(list, DONE)) { finish(true, 'Already Following'); return; }",
            "      var fb = find(list, FOLLOW);",
            "      if (fb) { acted(); fb.click(); clicked = true; clickedAt = Date.now(); return; }",
            "      if (tries >= 180) { softFail('Follow button not found'); }",
            "    } catch (err) { finish(false, 'JS Error: ' + err.message); }",
            "  }",
            "  window.__ufTick = tick;",
            "  timer = setInterval(tick, 350);",
            "  tick();",
            "})();"
        );
        view.evaluateJavascript(script, null);
    }

    // ------------------------------------------------------------------
    // LIKE SCRIPT: pehle Instagram web API (tez), na chale to DOM button (fallback)
    // ------------------------------------------------------------------
    private void injectLikeScript(WebView view) {
        String script = JS_HEAD + js(
            "  var LIKE = ['like', '\u092a\u0938\u0902\u0926 \u0915\u0930\u0947\u0902', 'me gusta', 'curtir', 'mi piace', 'suka', 'gef\u00e4llt mir'];",
            "  var UNLIKE = ['unlike', '\u092a\u0938\u0902\u0926 \u0930\u0926\u094d\u0926 \u0915\u0930\u0947\u0902', 'ya no me gusta', 'descurtir', 'batal suka', 'gef\u00e4llt mir nicht mehr'];",
            "  var apiTried = false, apiBusy = false, apiAt = 0;",
            "  window.__ufLast = Date.now();",
            "  function acted() { try { window.WorkerBridge.onActed(); } catch (e) {} }",
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
            "    apiBusy = true; apiAt = Date.now();",
            "    acted();",
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
            "      if (finished) return;",
            "      window.__ufLast = Date.now();",
            "      tries++;",
            "      var f = fatal(); if (f) { finish(false, f); return; }",
            "      dismiss();",
            "      if (apiBusy) { if (Date.now() - apiAt > 12000) { apiBusy = false; } else { return; } }",
            "      if (byLabel(UNLIKE)) { finish(true, clicked ? 'Post Liked Successfully' : 'Already Liked'); return; }",
            "      if (!apiTried && !clicked) { callApi(); if (apiBusy) return; }",
            "      if (tries >= 3 && !clicked && gone()) { finish(false, 'Post unavailable'); return; }",
            "      if (clicked) {",
            "        var waited = Date.now() - clickedAt;",
            "        if (waited > 20000) { finish(false, 'Like did not register'); return; }",
            "        if (waited > 6000 && !apiTried) { callApi(); }",
            "        return;",
            "      }",
            "      var lk = byLabel(LIKE);",
            "      if (lk) {",
            "        var b = lk.closest('button, [role=button]') || lk.parentElement;",
            "        acted(); b.click(); clicked = true; clickedAt = Date.now(); return;",
            "      }",
            "      if (tries >= 180) { finish(false, 'Like button not found'); }",
            "    } catch (err) { finish(false, 'JS Error: ' + err.message); }",
            "  }",
            "  window.__ufTick = tick;",
            "  timer = setInterval(tick, 350);",
            "  tick();",
            "})();"
        );
        view.evaluateJavascript(script, null);
    }

    // ------------------------------------------------------------------
    // LIVE PROFILE SEARCH (followers.php target search)
    // Alag hidden WebView use hota hai, taaki running task disturb na ho.
    // Pehle Instagram API, fail ho to profile page ke meta tags se data nikalta hai.
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
            // ---- Strategy 1: web_profile_info API (kisi bhi 2xx status ko accept karta hai) ----
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
            // ---- Strategy 2: profile page ke meta tags (og:description me followers) ----
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
    // TARGET ACCOUNT KE POSTS + REELS (likes page ke liye)
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
        // NAYA: notification par tap karne se app khule
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pf = Build.VERSION.SDK_INT >= 23
                ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.FLAG_UPDATE_CURRENT;
        builder.setContentIntent(PendingIntent.getActivity(this, 0, open, pf));
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

        // Naya: account ki cookies ke saath task chalao (har account apna session use karta hai)
        @JavascriptInterface
        public void runTask(final String target, final String taskType, final String mediaId, final String cookieStr) {
            if (!trusted()) return;
            // NAYA: multi-account parallel mode (WebView support na kare to neeche wala purana code chalega)
            if (parallelEnabled(cookieStr)) {
                parallelEnqueue(target, taskType, mediaId, cookieStr);
                return;
            }
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    // Pichhla task abhi chal raha ho to ruko (beech me dusra account na khule)
                    if (taskActive) {
                        uiHandler.postDelayed(this, 500);
                        return;
                    }
                    workerActed = false;
                    workerRetries = 0;
                    workerStartedAt = System.currentTimeMillis();
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

        // NAYA: accounts ke beech delay (seconds) set karne ke liye: Android.setAccountDelay(6)
        @JavascriptInterface
        public void setAccountDelay(final int seconds) {
            if (!trusted()) return;
            startGapMs = Math.max(0, Math.min(60, seconds)) * 1000L;
        }

        // NAYA: ek saath kitne account chalein (1 = ek ke baad ek, sabse safe). Android.setMaxParallel(1)
        @JavascriptInterface
        public void setMaxParallel(final int n) {
            if (!trusted()) return;
            maxParallel = Math.max(1, Math.min(3, n));
        }

        // Purani website ke liye bacha hua (kuch nahi karta): Fast Mode hata diya gaya hai
        @JavascriptInterface
        public void setFastMode(final boolean on) {
        }

        @JavascriptInterface
        public boolean isFastMode() {
            return false;
        }

        // NAYA: gap me random utar-chadhav (seconds, 0-10). Android.setGapJitter(2) => 9 sec me +-2
        @JavascriptInterface
        public void setGapJitter(final int seconds) {
            if (!trusted()) return;
            gapJitterMs = Math.max(0, Math.min(10, seconds)) * 1000L;
        }

        // NAYA: dead profiles ki list saaf (admin "Restore" ke liye)
        @JavascriptInterface
        public void clearDeadProfiles() {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    deadMap.clear();
                    unavailVotes.clear();
                    saveMap("dead_map", deadMap, DEAD_TTL_MS, 1500);
                }
            });
        }

        // NAYA: "pehle ho chuka" memory saaf
        @JavascriptInterface
        public void clearDoneCache() {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    doneMap.clear();
                    saveMap("done_map", doneMap, DONE_TTL_MS, 1500);
                }
            });
        }

        // Purana method (compatibility)
        @JavascriptInterface
        public void executeBrowserAction(String target, String taskType, String mediaId) {
            runTask(target, taskType, mediaId, "");
        }

        @JavascriptInterface
        public void executeBrowserFollow(String target) {
            runTask(target, "follow", "", "");
        }

        // Primary account ki cookie server par sync karne ke liye
        // Likes page: target account ke posts + reels (thumbnail, likes, code)
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

        // Jar me koi Instagram session na ho to dashboard primary ka session laga deta hai
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

        // Website ko app ka version batane ke liye (optional use)
        @JavascriptInterface
        public int getAppVersion() {
            return APP_VERSION_CODE;
        }

        // NAYA: page ko batata hai ki ye phone multi-account parallel support karta hai ya nahi
        @JavascriptInterface
        public boolean isParallelSupported() {
            try {
                return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
            } catch (Throwable t) {
                return false;
            }
        }

        // NAYA: Stop dabane par queue me ruke hue tasks cancel (jo chal rahe hain wo khatam honge)
        @JavascriptInterface
        public void clearPendingTasks() {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    PTask t;
                    while ((t = pendingTasks.poll()) != null) {
                        sendParallelResult(t.uid, t.target, false, "Cancelled");
                    }
                }
            });
        }

        // NAYA: Automation ON/OFF yaad rakhne ke liye. ON hone par app khulte hi website ko
        // window.onAppAutoStart() ka signal milta hai. Stop dabane par setAutoRun(false) bulao.
        @JavascriptInterface
        public void setAutoRun(final boolean on) {
            if (!trusted()) return;
            prefs.edit().putBoolean("auto_run", on).apply();
        }

        @JavascriptInterface
        public boolean isAutoRun() {
            return trusted() && prefs.getBoolean("auto_run", false);
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean keepOn) {
            if (!trusted()) return;
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (keepOn) {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        startAutomationBackground();
                        if (wakeLock != null && !wakeLock.isHeld()) {
                            wakeLock.acquire(12 * 60 * 60 * 1000L);
                        }
                    } else {
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        stopAutomationBackground();
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

    // ==================================================================
    // PARALLEL ENGINE: har account = alag WebView + alag cookie profile
    // - Accounts ek ke baad ek start hote hain (beech me startGapMs ka gap)
    // - Ek saath chalne wale accounts MAX_PARALLEL tak
    // - Round-robin: ek account ka task khatam, agla task dusre account ka
    // ==================================================================
    // Ek saath max itne account chalenge (phone overload na ho, isse jyada slow = jyada 'button not found')
    // DEFAULT 1 = turn by turn: ek account ka task poora hone ke baad hi agla khulega
    private volatile int maxParallel = 1;
    // Ek account ke itne tasks ek hi WebView me lagatar (tez, bar bar account switch nahi)
    private static final int STICKY_BATCH = 5;
    private int stickyCount = 0;
    // FIX: Parallel task ko poora samay (retry samet). Pehle 40 sec tha.
    private static final long SLOT_TASK_TIMEOUT_MS = 120000L;

    private class Slot {
        String uid = "";
        WebView wv;
        CookieManager cm;
        boolean active = false;
        int retries = 0;
        String target = "";
        String taskType = "follow";
        String mediaId = "";
        String key = "";
        Runnable timeout;
        boolean acted = false;
        long startedAt = 0;
    }

    private class PTask {
        String uid = "";
        String target = "";
        String taskType = "follow";
        String mediaId = "";
        String cookieStr = "";
        String key = "";
    }

    private final Map<String, Slot> slots = new HashMap<String, Slot>();
    private final ArrayDeque<PTask> pendingTasks = new ArrayDeque<PTask>();
    private int activeSlots = 0;

    // Duplicate / dead profile memory (phone me save rehti hai)
    private final Map<String, Long> doneMap = new HashMap<String, Long>();
    private final Map<String, Long> deadMap = new HashMap<String, Long>();
    private final Map<String, HashSet<String>> unavailVotes = new HashMap<String, HashSet<String>>();
    private static final long DONE_TTL_MS = 30L * 24 * 60 * 60 * 1000L;
    private static final long DEAD_TTL_MS = 7L * 24 * 60 * 60 * 1000L;

    // Anti-block gap: ek task KHATAM hone ke baad itna ruk ke agla account (default 9 sec +- jitter)
    private volatile long startGapMs = 9000L;
    private volatile long gapJitterMs = 2000L;
    private long curGapMs = 9000L;
    private long lastStartAt = 0;
    private long lastEndAt = 0;
    private final Random rnd = new Random();
    private String lastStartedUid = "";
    private boolean pumpScheduled = false;
    private final Runnable pumpRunnable = new Runnable() {
        @Override
        public void run() {
            pumpScheduled = false;
            pumpQueue();
        }
    };

    private long nextGap() {
        long j = gapJitterMs;
        long g = startGapMs;
        if (j > 0) g += (long) (rnd.nextDouble() * 2.0 * j) - j;
        return Math.max(0L, g);
    }

    private boolean parallelEnabled(String cookieStr) {
        if (cookieStr == null || cookieStr.isEmpty()) return false;
        if (cookieValue(cookieStr, "ds_user_id").isEmpty()) return false;
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
        } catch (Throwable t) {
            return false;
        }
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

    private boolean isDuplicate(PTask t) {
        for (PTask p : pendingTasks) {
            if (p.uid.equals(t.uid) && p.key.equals(t.key)) return true;
        }
        for (Slot s : slots.values()) {
            if (s.active && s.uid.equals(t.uid) && s.key.equals(t.key)) return true;
        }
        return false;
    }

    private void parallelEnqueue(String target, String taskType, String mediaId, String cookieStr) {
        final PTask t = new PTask();
        t.target = target == null ? "" : target;
        t.taskType = taskType == null ? "follow" : taskType;
        t.mediaId = mediaId == null ? "" : mediaId;
        t.cookieStr = cookieStr;
        t.uid = cookieValue(cookieStr, "ds_user_id").replaceAll("[^A-Za-z0-9]", "");
        t.key = taskKey(t.taskType, t.target, t.mediaId);
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                boolean like = "like".equalsIgnoreCase(t.taskType);
                // 1) Bekar (dead) profile: bina kuch kiye skip, website ko bhi bata do
                if (isDead(t.key)) {
                    reportDead(t.taskType, cleanName(t.target), "unavailable", t.mediaId);
                    sendParallelResult(t.uid, t.target, false, "Skipped: dead profile");
                    return;
                }
                // 2) Is account se ye kaam pehle ho chuka hai
                if (isDone(t.uid + "|" + t.key)) {
                    sendParallelResult(t.uid, t.target, true, like ? "Already Liked" : "Already Following");
                    return;
                }
                // 3) Wahi task queue me ya abhi chal raha hai (double order)
                if (isDuplicate(t)) {
                    sendParallelResult(t.uid, t.target, false, "Duplicate task skipped");
                    return;
                }
                pendingTasks.add(t);
                pumpQueue();
            }
        });
    }

    // Jab tak current task poora na ho (success ya sach ka error), activeSlots >= maxParallel
    // rehta hai, isliye dusra account kabhi beech me nahi khulta.
    private void pumpQueue() {
        if (pendingTasks.isEmpty() || activeSlots >= maxParallel) return;

        long base = (maxParallel <= 1) ? lastEndAt : lastStartAt;
        long wait = base + curGapMs - System.currentTimeMillis();
        if (wait > 0) {
            if (!pumpScheduled) {
                pumpScheduled = true;
                uiHandler.postDelayed(pumpRunnable, wait);
            }
            return;
        }

        PTask pick = null;
        // 1) Pehle wahi account (WebView garam hai -> tez), jab tak STICKY_BATCH poora na ho
        if (stickyCount < STICKY_BATCH) {
            for (PTask t : pendingTasks) {
                Slot s = slots.get(t.uid);
                if (s != null && s.active) continue;
                if (t.uid.equals(lastStartedUid)) { pick = t; break; }
            }
        }
        // 2) Warna agla account (round-robin). Dusra na ho to hi same account ka agla task
        if (pick == null) {
            for (PTask t : pendingTasks) {
                Slot s = slots.get(t.uid);
                if (s != null && s.active) continue; // is account ka task abhi chal raha hai
                if (pick == null) pick = t;          // fallback: pehla free task
                if (!t.uid.equals(lastStartedUid)) { pick = t; break; }
            }
        }
        if (pick != null) {
            pendingTasks.remove(pick);
            if (pick.uid.equals(lastStartedUid)) {
                stickyCount++;
            } else {
                releaseIdleSlot(lastStartedUid); // pichhle account ka WebView band
                stickyCount = 1;
            }
            lastStartAt = System.currentTimeMillis();
            lastStartedUid = pick.uid;
            curGapMs = nextGap();
            startOnSlot(pick, slots.get(pick.uid));
        }

        if (!pendingTasks.isEmpty() && !pumpScheduled) {
            pumpScheduled = true;
            uiHandler.postDelayed(pumpRunnable, Math.max(curGapMs, 1000L));
        }
    }

    private void startOnSlot(final PTask t, Slot existing) {
        Slot s = existing;
        try {
            if (s == null) s = createSlot(t.uid);
        } catch (Throwable e) {
            lastEndAt = System.currentTimeMillis();
            sendParallelResult(t.uid, t.target, false, "Setup error: " + e.getMessage());
            return;
        }

        final Slot fs = s;
        // Cookies sirf tab lagao jab zaroorat ho (naya WebView ya session badla). Garam WebView ki cookies mat chhedo.
        ensureCookies(fs, t.cookieStr);
        fs.acted = false;
        fs.startedAt = System.currentTimeMillis();
        fs.active = true;
        fs.retries = 0;
        fs.target = cleanName(t.target);
        fs.taskType = t.taskType;
        fs.mediaId = t.mediaId;
        fs.key = t.key;
        activeSlots++;
        fs.timeout = new Runnable() {
            @Override
            public void run() {
                finishSlot(fs, false, "Timeout");
            }
        };
        uiHandler.postDelayed(fs.timeout, SLOT_TASK_TIMEOUT_MS);
        fs.wv.loadUrl(buildTaskUrl(t.target, t.taskType, t.mediaId));
    }

    private void finishSlot(final Slot s, boolean ok, String msg) {
        if (s == null || !s.active) return;
        s.active = false;
        activeSlots--;
        if (activeSlots < 0) activeSlots = 0;
        lastEndAt = System.currentTimeMillis();
        if (s.timeout != null) uiHandler.removeCallbacks(s.timeout);
        if (ok) {
            doneMap.put(s.uid + "|" + s.key, lastEndAt);
            saveMap("done_map", doneMap, DONE_TTL_MS, 1500);
            unavailVotes.remove(s.key);
        } else if (msg != null && msg.toLowerCase().contains("unavailable")) {
            noteUnavailable(s);
        }
        sendParallelResult(s.uid, s.target, ok, msg);
        // WebView zinda rakho (agla task isi account ka ho to tez chalega), bas purana page/JS band karo
        try {
            s.wv.stopLoading();
            s.wv.loadUrl("about:blank");
        } catch (Exception ignored) {}
        scheduleIdleCleanup();
        pumpQueue();
    }

    // Ruka hua (idle) WebView band karke RAM free karo
    private void releaseIdleSlot(String uid) {
        if (uid == null || uid.isEmpty()) return;
        final Slot s = slots.get(uid);
        if (s == null || s.active) return;
        slots.remove(uid);
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup par = (ViewGroup) s.wv.getParent();
                    if (par != null) par.removeView(s.wv);
                    s.wv.stopLoading();
                    s.wv.destroy();
                } catch (Exception ignored) {}
            }
        });
    }

    // Queue khali ho jaye to 45 sec baad saare idle WebView band
    private final Runnable idleCleanup = new Runnable() {
        @Override
        public void run() {
            if (activeSlots == 0 && pendingTasks.isEmpty()) {
                for (String u : new ArrayList<String>(slots.keySet())) {
                    releaseIdleSlot(u);
                }
            }
        }
    };

    private void scheduleIdleCleanup() {
        uiHandler.removeCallbacks(idleCleanup);
        uiHandler.postDelayed(idleCleanup, 45000);
    }

    // Result page ko jaata hai: onWorkerResult(success, msg, accountId, target)
    // Pehle 2 arguments purane jaise hain, baaki 2 naye (account pehchanne ke liye)
    private void sendParallelResult(String uid, String target, boolean ok, String msg) {
        String safe = (msg == null ? "" : msg).replace("\\", "\\\\").replace("'", "\\'");
        String safeT = (target == null ? "" : target).replace("\\", "\\\\").replace("'", "\\'");
        mainWebView.evaluateJavascript(
                "if(window.onWorkerResult) window.onWorkerResult(" + ok + ", '" + safe + "', '" + uid + "', '" + safeT + "');", null);
    }

    private String buildTaskUrl(String target, String taskType, String mediaId) {
        String cleanTarget = target == null ? "" : target.replaceAll("[^A-Za-z0-9._]", "");
        if ("like".equalsIgnoreCase(taskType)) {
            if (mediaId != null && mediaId.startsWith("https://www.instagram.com/")) return mediaId;
            if (mediaId != null && !mediaId.isEmpty() && mediaId.matches("[A-Za-z0-9_-]+") && !mediaId.equals("25025320")) {
                return (mediaId.length() <= 12 ? "https://www.instagram.com/reel/" : "https://www.instagram.com/p/") + mediaId + "/";
            }
        }
        return "https://www.instagram.com/" + cleanTarget + "/";
    }

    private void applyCookiesTo(CookieManager cm, String cookieStr) {
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

    // 'not found' / 'unavailable' / page error par 2 baar page reload karke dobara try
    private boolean shouldRetry(Slot s, String msg) {
        if (s == null || !s.active || s.retries >= 2) return false;
        if (System.currentTimeMillis() - s.startedAt > 80000) return false; // retry ke liye samay bacha ho
        String m = msg == null ? "" : msg.toLowerCase();
        return m.contains("not found") || m.contains("did not register")
                || m.contains("page error") || m.contains("js error")
                || m.contains("unavailable");
    }

    // reload se pehle 3 sec ruko taaki Instagram ko saans mile
    private void retrySlot(final Slot s) {
        s.retries++;
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!s.active) return;
                try {
                    s.wv.reload();
                } catch (Exception e) {
                    finishSlot(s, false, "Page error");
                }
            }
        }, 3000);
    }

    private Slot createSlot(String uid) {
        Slot s = new Slot();
        s.uid = uid;
        WebView wv = new WebView(this);
        String pname = "acc_" + uid;
        Profile p = ProfileStore.getInstance().getOrCreateProfile(pname);
        WebViewCompat.setProfile(wv, pname);
        s.cm = p.getCookieManager();

        WebSettings st = wv.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setBlockNetworkImage(true); // photos load nahi honge -> tez
        st.setUserAgentString(USER_AGENT); // normal UA: Instagram ko secret key nahi dikhani
        s.cm.setAcceptCookie(true);
        s.cm.setAcceptThirdPartyCookies(wv, true);

        wv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wv.setAlpha(0.01f);
        ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
        if (root != null) root.addView(wv, 0);

        wv.addJavascriptInterface(new SlotBridge(s), "WorkerBridge");
        wv.setWebViewClient(new SlotClient(s));
        s.wv = wv;
        slots.put(uid, s);
        return s;
    }

    private void ensureCookies(Slot s, String cookieStr) {
        String curSid = cookieValue(s.cm.getCookie(IG_URL), "sessionid");
        if (curSid.isEmpty() || !curSid.equals(cookieValue(cookieStr, "sessionid"))) {
            applyCookiesTo(s.cm, cookieStr);
        }
    }

    // ==================================================================
    // Bekar (dead) profile: 2 alag accounts se "unavailable" aaye to dead mano
    // ==================================================================
    private void noteUnavailable(Slot s) {
        HashSet<String> v = unavailVotes.get(s.key);
        if (v == null) {
            v = new HashSet<String>();
            unavailVotes.put(s.key, v);
        }
        v.add(s.uid);
        if (v.size() >= 2) {
            deadMap.put(s.key, System.currentTimeMillis());
            saveMap("dead_map", deadMap, DEAD_TTL_MS, 1500);
            reportDead(s.taskType, s.target, "unavailable", s.mediaId);
            unavailVotes.remove(s.key);
        }
    }

    private static String jsq(String x) {
        return x == null ? "" : x.replace("\\", "\\\\").replace("'", "\\'");
    }

    // Website ko batao. Agar website me window.onProfileDead hai to wahi chalega,
    // warna app khud dead_profile.php ko POST karta hai (website me kuch jodna nahi padta).
    private void reportDead(String type, String target, String reason, String mediaId) {
        if (mainWebView == null || !trusted()) return;
        mainWebView.evaluateJavascript(
                "(function(){var t='" + jsq(target) + "',ty='" + jsq(type) + "',r='" + jsq(reason)
                        + "',m='" + jsq(mediaId) + "';"
                        + "if(window.onProfileDead){window.onProfileDead(t,ty,r,m);return;}"
                        + "try{var fd=new FormData();fd.append('target',t);fd.append('media',m);"
                        + "fd.append('type',ty);fd.append('reason',r);fd.append('dk','" + deviceKey + "');"
                        + "fetch('" + HOSTING_BASE + "dead_profile.php',{method:'POST',body:fd,credentials:'include'});}catch(e){}})();",
                null);
    }

    // ==================================================================
    // Phone me save hone wali chhoti memory (done / dead)
    // ==================================================================
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
            while (it.hasNext()) {
                if (now - it.next().getValue() > ttl) it.remove();
            }
            while (m.size() > cap) {
                String oldest = null;
                long ot = Long.MAX_VALUE;
                for (Map.Entry<String, Long> e : m.entrySet()) {
                    if (e.getValue() < ot) { ot = e.getValue(); oldest = e.getKey(); }
                }
                if (oldest == null) break;
                m.remove(oldest);
            }
            Set<String> out = new HashSet<String>();
            for (Map.Entry<String, Long> e : m.entrySet()) out.add(e.getKey() + "\t" + e.getValue());
            prefs.edit().putStringSet(pref, out).apply();
        } catch (Exception ignored) {}
    }

    public class SlotBridge {
        private final Slot s;
        SlotBridge(Slot s) { this.s = s; }

        // Script ne follow/like kar diya (API ya click)
        @JavascriptInterface
        public void onActed() {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (s.active) s.acted = true;
                }
            });
        }

        @JavascriptInterface
        public void onTaskResult(final boolean success, final String message) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!s.active) return; // purane/band task ka result ignore
                    if (!success && shouldRetry(s, message)) {
                        retrySlot(s);
                        return;
                    }
                    String m2 = message;
                    if (success && s.acted) {
                        // Hamne khud follow/like kiya, to "Already" nahi "Successfully" bhejo (coin ke liye)
                        if ("Already Following".equals(m2)) m2 = "Followed Successfully";
                        else if ("Already Liked".equals(m2)) m2 = "Post Liked Successfully";
                    }
                    finishSlot(s, success, m2);
                }
            });
        }
    }

    private class SlotClient extends WebViewClient {
        private final Slot s;
        SlotClient(Slot s) { this.s = s; }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            WebResourceResponse blocked = blockHeavy(request);
            return blocked != null ? blocked : super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageFinished(final WebView view, String url) {
            super.onPageFinished(view, url);
            if (!s.active || url == null || url.startsWith("about:")) return;

            String path = "";
            try { path = String.valueOf(Uri.parse(url).getPath()).toLowerCase(); } catch (Exception ignored) {}

            if (path.startsWith("/accounts/suspended")) { finishSlot(s, false, "Suspended: Instagram suspended this account"); return; }
            if (path.startsWith("/accounts/disabled")) { finishSlot(s, false, "Disabled: Instagram disabled this account"); return; }
            if (path.startsWith("/challenge") || path.startsWith("/checkpoint") || path.startsWith("/auth_platform")) { finishSlot(s, false, "Blocked: Challenge / Checkpoint"); return; }
            if (path.startsWith("/accounts/login") || path.startsWith("/accounts/emailsignup")) { finishSlot(s, false, "Session expired"); return; }

            uiHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!s.active) return;
                    if ("like".equalsIgnoreCase(s.taskType)) {
                        injectLikeScript(view);
                    } else {
                        injectFollowScript(view);
                    }
                }
            }, 400);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (s.active && request != null && request.isForMainFrame()) {
                if (shouldRetry(s, "page error")) {
                    retrySlot(s);
                } else {
                    finishSlot(s, false, "Page error");
                }
            }
        }
    }

    // ==================================================================
    // SPEED: hidden browsers me photos / videos / fonts load nahi hote
    // (follow/like ko sirf buttons chahiye) -> page 2-3 guna jaldi khulta hai
    // ==================================================================
    private WebResourceResponse blockHeavy(WebResourceRequest request) {
        try {
            if (request == null || request.getUrl() == null || request.isForMainFrame()) return null;
            String p = String.valueOf(request.getUrl().getPath()).toLowerCase();
            if (p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") || p.endsWith(".webp")
                    || p.endsWith(".gif") || p.endsWith(".mp4") || p.endsWith(".m4s") || p.endsWith(".webm")
                    || p.endsWith(".woff") || p.endsWith(".woff2") || p.endsWith(".ttf")) {
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==================================================================
    // BACKGROUND FIX: app background me jaaye tab bhi automation na ruke
    // 1) Foreground service (notification ke saath) -> Android process ko kill/sula nahi karta
    // 2) Heartbeat -> background me WebView ke JS timers slow ho jaate hain, isliye
    //    native side se har 1.5 sec page ko "tick" bheja jaata hai
    // 3) onPause me timers dobara resume
    // ==================================================================
    private boolean automationOn = false;
    private static final String POKE_JS =
            "(function(){try{if(window.__ufTick&&window.__ufRunning&&Date.now()-(window.__ufLast||0)>1200){window.__ufTick();}}catch(e){}})();";

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!automationOn) return;
            try {
                mainWebView.resumeTimers();
                // Website chahe to is function me apna loop dobara jaga sakti hai
                mainWebView.evaluateJavascript("if(window.onNativeHeartbeat) window.onNativeHeartbeat();", null);
                if (taskActive) workerWebView.evaluateJavascript(POKE_JS, null);
                for (Slot sl : new ArrayList<Slot>(slots.values())) {
                    if (sl.active && sl.wv != null) sl.wv.evaluateJavascript(POKE_JS, null);
                }
            } catch (Exception ignored) {}
            uiHandler.postDelayed(this, 1500);
        }
    };

    private void startAutomationBackground() {
        automationOn = true;
        uiHandler.removeCallbacks(heartbeat);
        uiHandler.postDelayed(heartbeat, 1500);
        try {
            Intent svc = new Intent(this, AutomationService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception ignored) {}
        requestBatteryExemptionOnce();
    }

    private void stopAutomationBackground() {
        automationOn = false;
        uiHandler.removeCallbacks(heartbeat);
        try {
            stopService(new Intent(this, AutomationService.class));
        } catch (Exception ignored) {}
    }

    // Battery optimization se app ko bahar karne ka system popup (sirf ek baar poochta hai)
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
        // Script ne follow/like kar diya (API ya click), ye yaad rakhne ke liye
        @JavascriptInterface
        public void onActed() {
            workerActed = true;
        }

        @JavascriptInterface
        public void onTaskResult(final boolean success, final String message) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    // FIX: fail aaye to pehle retry, tabhi result bhejo jab retry khatam ho
                    if (!success && workerShouldRetry(message)) {
                        retryWorker();
                        return;
                    }
                    String m2 = message;
                    if (success && workerActed) {
                        // Hamne khud follow/like kiya, to "Already" nahi "Successfully" bhejo (coin ke liye)
                        if ("Already Following".equals(m2)) m2 = "Followed Successfully";
                        else if ("Already Liked".equals(m2)) m2 = "Post Liked Successfully";
                    }
                    deliverResult(success, m2);
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Background me jaane par bhi WebView ke JS timers chalte rahein
        if (automationOn) {
            try { mainWebView.resumeTimers(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { mainWebView.resumeTimers(); } catch (Exception ignored) {}
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
        automationOn = false;
        try { stopService(new Intent(this, AutomationService.class)); } catch (Exception ignored) {}
        uiHandler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
