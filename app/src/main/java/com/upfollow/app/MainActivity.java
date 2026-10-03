package com.upfollow.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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

import java.net.URLEncoder;

public class MainActivity extends Activity {

    private WebView mainWebView;
    private WebView workerWebView;

    private static final String HOSTING_DASHBOARD = "https://follow2follow.shop/index.php";
    private static final String IG_LOGIN_URL = "https://www.instagram.com/accounts/login/";
    // FIX: purana Chrome/124 UA Instagram ko outdated lag sakta hai. Time-time pe update karte raho.
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";

    private static final String CHANNEL_ID = "upfollow_automation_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long TASK_TIMEOUT_MS = 40000L;

    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private String currentTaskType = "follow";
    private boolean isAddingSecondaryAccount = false;

    // FIX: sirf ek result per task jaye (duplicate onPageFinished se double result nahi aayega)
    private boolean taskActive = false;

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

        // FIX: Worker WebView ab full-size hai (1x1 par Instagram ka mobile layout/React render
        // sahi nahi hota, isliye Follow button DOM me aata hi nahi tha). Ye main WebView ke
        // NEECHE (index 0) add hota hai, to touch block nahi karta aur user ko dikhta nahi.
        workerWebView = new WebView(this);
        setupWebView(workerWebView);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        workerWebView.setLayoutParams(params);
        workerWebView.setAlpha(0.01f);

        ViewGroup rootView = (ViewGroup) findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.addView(workerWebView, 0);
        }

        // Native Bridges
        mainWebView.addJavascriptInterface(new MainAppInterface(), "Android");
        workerWebView.addJavascriptInterface(new WorkerAppInterface(), "WorkerBridge");

        // Main WebView Client
        mainWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                if (url != null && url.contains("force_authentication=1")) {
                    isAddingSecondaryAccount = true;
                }

                if (url != null && (url.contains("instagram.com/?") || url.equals("https://www.instagram.com/"))) {
                    CookieManager.getInstance().flush();
                    String cookies = CookieManager.getInstance().getCookie("https://www.instagram.com");
                    if (cookies != null && cookies.contains("sessionid")) {
                        try {
                            String encoded = URLEncoder.encode(cookies, "UTF-8");
                            if (isAddingSecondaryAccount) {
                                isAddingSecondaryAccount = false;
                                mainWebView.loadUrl(HOSTING_DASHBOARD + "?add_cookies=" + encoded);
                            } else {
                                mainWebView.loadUrl(HOSTING_DASHBOARD + "?cookies=" + encoded);
                            }
                        } catch (Exception e) {
                            mainWebView.loadUrl(HOSTING_DASHBOARD);
                        }
                    }
                }
            }
        });

        // Worker WebView Client
        workerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(final WebView view, String url) {
                super.onPageFinished(view, url);
                if (!taskActive) return;

                if (url != null && (url.contains("/challenge/") || url.contains("/suspended/") || url.contains("/checkpoint/"))) {
                    deliverResult(false, "Blocked: Challenge / Checkpoint");
                    return;
                }

                // FIX: login page par redirect = session valid nahi
                if (url != null && url.contains("/accounts/login")) {
                    deliverResult(false, "Not logged in");
                    return;
                }

                // Script ab khud polling karti hai (React render hone ka wait), isliye delay chhota
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
                }, 1500);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                // FIX: pehle har chhota resource error (image/script) bhi "Page error" bana deta tha.
                // Ab sirf main page ka error count hoga.
                if (request != null && request.isForMainFrame()) {
                    deliverResult(false, "Page error");
                }
            }
        });

        // Auto login check
        String cookies = CookieManager.getInstance().getCookie("https://www.instagram.com");
        if (cookies != null && cookies.contains("sessionid")) {
            mainWebView.loadUrl(HOSTING_DASHBOARD);
        } else {
            mainWebView.loadUrl(IG_LOGIN_URL);
        }
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

    private static String js(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    // Result ko ek hi baar main WebView tak bhejta hai
    private void deliverResult(boolean success, String message) {
        if (!taskActive) return;
        taskActive = false;
        uiHandler.removeCallbacks(taskTimeout);
        String safe = message == null ? "" : message.replace("\\", "\\\\").replace("'", "\\'");
        mainWebView.evaluateJavascript(
                "if(window.onWorkerResult) window.onWorkerResult(" + success + ", '" + safe + "');", null);
    }

    // ---------------- FOLLOW SCRIPT ----------------
    // Fixes:
    //  1. Polling: button aane tak 14 sec tak wait (pehle sirf 1 baar check hota tha)
    //  2. Double click hata diya (click() + dispatchEvent = 2 click => follow ke baad unfollow ho sakta tha)
    //  3. Click ke baad verify: "Following"/"Requested" dikhe tabhi success
    //  4. 'suspended' wala false-positive hata diya (bio me ye word ho to block lag jata tha)
    //  5. Pehle header ke andar button dhundta hai (suggested accounts ke Follow button se bachne ke liye)
    private void injectFollowScript(WebView view) {
        String script = js(
            "(function() {",
            "  if (window.__ufRunning) return;",
            "  window.__ufRunning = true;",
            "  var finished = false, tries = 0, clicked = false, clickedAt = 0, timer = null;",
            "  var FOLLOW = ['follow', 'follow back', '\u092b\u0949\u0932\u094b \u0915\u0930\u0947\u0902'];",
            "  var DONE = ['following', 'requested'];",
            "  function finish(ok, msg) {",
            "    if (finished) return;",
            "    finished = true; window.__ufRunning = false;",
            "    if (timer) clearInterval(timer);",
            "    window.WorkerBridge.onTaskResult(ok, msg);",
            "  }",
            "  function txt(e) { return ((e.innerText || e.textContent) || '').trim().toLowerCase(); }",
            "  function btns(root) { return Array.prototype.slice.call((root || document).querySelectorAll('button, div[role=button]')); }",
            "  function find(list, words) {",
            "    for (var i = 0; i < list.length; i++) { if (words.indexOf(txt(list[i])) > -1) return list[i]; }",
            "    return null;",
            "  }",
            "  function tick() {",
            "    try {",
            "      tries++;",
            "      var body = (document.body ? document.body.innerText : '').toLowerCase();",
            "      if (body.indexOf('action blocked') > -1 || body.indexOf('confirm you') > -1 || body.indexOf('try again later') > -1) {",
            "        finish(false, 'Blocked: Action Limit / Challenge'); return;",
            "      }",
            "      if (document.querySelector('input[name=username]')) { finish(false, 'Not logged in'); return; }",
            "      btns().forEach(function(p) {",
            "        var pt = txt(p);",
            "        if (pt === 'not now' || pt === 'allow all' || pt === 'allow all cookies' || pt === 'accept') p.click();",
            "      });",
            "      var scope = (tries > 8) ? document : (document.querySelector('header') || document.querySelector('main') || document);",
            "      var list = btns(scope);",
            "      if (clicked) {",
            "        if (find(list, DONE)) { finish(true, 'Followed Successfully'); return; }",
            "        if (Date.now() - clickedAt > 5000) { finish(false, 'Click did not register'); }",
            "        return;",
            "      }",
            "      if (find(list, DONE)) { finish(true, 'Already Following'); return; }",
            "      var fb = find(list, FOLLOW);",
            "      if (fb) { fb.click(); clicked = true; clickedAt = Date.now(); return; }",
            "      if (tries >= 20) { finish(false, 'Follow button not found'); }",
            "    } catch (err) {",
            "      finish(false, 'JS Error: ' + err.message);",
            "    }",
            "  }",
            "  timer = setInterval(tick, 700);",
            "  tick();",
            "})();"
        );
        view.evaluateJavascript(script, null);
    }

    // ---------------- LIKE SCRIPT ----------------
    private void injectLikeScript(WebView view) {
        String script = js(
            "(function() {",
            "  if (window.__ufRunning) return;",
            "  window.__ufRunning = true;",
            "  var finished = false, tries = 0, clicked = false, clickedAt = 0, timer = null;",
            "  function finish(ok, msg) {",
            "    if (finished) return;",
            "    finished = true; window.__ufRunning = false;",
            "    if (timer) clearInterval(timer);",
            "    window.WorkerBridge.onTaskResult(ok, msg);",
            "  }",
            "  function txt(e) { return ((e.innerText || e.textContent) || '').trim().toLowerCase(); }",
            "  function tick() {",
            "    try {",
            "      tries++;",
            "      var body = (document.body ? document.body.innerText : '').toLowerCase();",
            "      if (body.indexOf('action blocked') > -1 || body.indexOf('confirm you') > -1 || body.indexOf('try again later') > -1) {",
            "        finish(false, 'Blocked: Action Limit / Challenge'); return;",
            "      }",
            "      if (document.querySelector('input[name=username]')) { finish(false, 'Not logged in'); return; }",
            "      Array.prototype.slice.call(document.querySelectorAll('button, div[role=button]')).forEach(function(p) {",
            "        var pt = txt(p);",
            "        if (pt === 'not now') p.click();",
            "      });",
            "      var unlike = document.querySelector('svg[aria-label=Unlike], svg[aria-label=\"\u092a\u0938\u0902\u0926 \u0930\u0926\u094d\u0926 \u0915\u0930\u0947\u0902\"]');",
            "      if (unlike) { finish(true, clicked ? 'Post Liked Successfully' : 'Already Liked'); return; }",
            "      if (clicked) {",
            "        if (Date.now() - clickedAt > 5000) { finish(false, 'Like did not register'); }",
            "        return;",
            "      }",
            "      var likeSvg = document.querySelector('svg[aria-label=Like], svg[aria-label=\"\u092a\u0938\u0902\u0926 \u0915\u0930\u0947\u0902\"]');",
            "      if (likeSvg) {",
            "        var btn = likeSvg.closest('button') || likeSvg.closest('div[role=button]') || likeSvg.closest('span[role=button]') || likeSvg.parentElement;",
            "        btn.click(); clicked = true; clickedAt = Date.now(); return;",
            "      }",
            "      if (tries === 8) {",
            "        var videoEl = document.querySelector('video');",
            "        if (videoEl) {",
            "          videoEl.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window }));",
            "          clicked = true; clickedAt = Date.now(); return;",
            "        }",
            "        var postLink = document.querySelector('main a[href*=\"/p/\"], main a[href*=\"/reel/\"]');",
            "        if (postLink) { window.__ufRunning = false; if (timer) clearInterval(timer); window.location.href = postLink.href; return; }",
            "      }",
            "      if (tries >= 20) { finish(false, 'Like button not found'); }",
            "    } catch (err) {",
            "      finish(false, 'JS Error: ' + err.message);",
            "    }",
            "  }",
            "  timer = setInterval(tick, 700);",
            "  tick();",
            "})();"
        );
        view.evaluateJavascript(script, null);
    }

    private void createNotificationChannel() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Automation Service",
                    NotificationManager.IMPORTANCE_LOW
            );
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

    // App Bridges
    public class MainAppInterface {
        @JavascriptInterface
        public void executeBrowserAction(final String target, final String taskType, final String mediaId) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    currentTaskType = taskType;
                    taskActive = true;
                    uiHandler.removeCallbacks(taskTimeout);
                    uiHandler.postDelayed(taskTimeout, TASK_TIMEOUT_MS);

                    String cleanTarget = target.replace("@", "").trim();

                    if ("like".equalsIgnoreCase(taskType)) {
                        if (mediaId != null && !mediaId.isEmpty() && !mediaId.equals("25025320")) {
                            if (mediaId.startsWith("http")) {
                                workerWebView.loadUrl(mediaId);
                            } else if (mediaId.length() <= 12) {
                                workerWebView.loadUrl("https://www.instagram.com/reel/" + mediaId + "/");
                            } else {
                                workerWebView.loadUrl("https://www.instagram.com/p/" + mediaId + "/");
                            }
                        } else {
                            workerWebView.loadUrl("https://www.instagram.com/" + cleanTarget + "/");
                        }
                    } else {
                        workerWebView.loadUrl("https://www.instagram.com/" + cleanTarget + "/");
                    }
                }
            });
        }

        @JavascriptInterface
        public void executeBrowserFollow(String target) {
            executeBrowserAction(target, "follow", "");
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean keepOn) {
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
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    showOrUpdateNotification(accounts, tasks, coins);
                }
            });
        }

        @JavascriptInterface
        public void stopNotification() {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    clearAutomationNotification();
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
