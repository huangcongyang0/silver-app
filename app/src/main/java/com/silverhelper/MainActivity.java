package com.silverhelper;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 白银小帮手 —— 给爸爸用的极简 App
 * 核心：用系统自带的 WebView 打开 index.html，
 * 并由原生层直接抓取东方财富真实净值（无需任何后端服务器）。
 */
public class MainActivity extends Activity {

    private WebView wv;
    private SilverBridge bridge;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        wv.setWebViewClient(new WebViewClient());

        bridge = new SilverBridge();
        wv.addJavascriptInterface(bridge, "SilverBridge");

        setContentView(wv);
        wv.loadUrl("file:///android_asset/index.html");

        // 打开立即抓一次真实数据；之后每 30 分钟自动刷新
        scheduler.execute(this::fetchData);
        scheduler.scheduleAtFixedRate(this::fetchData, 30, 30, TimeUnit.MINUTES);
    }

    /** 直接抓取东方财富基金历史净值接口（免费、无需 Key） */
    private void fetchData() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(
                    "https://api.fund.eastmoney.com/f10/lsjz?fundCode=161226&pageIndex=1&pageSize=30");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Referer", "https://fundf10.eastmoney.com/");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.connect();

            InputStream in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            String json = sb.toString();
            JSONObject root = new JSONObject(json);
            JSONObject data = root.getJSONObject("Data");
            JSONArray list = data.getJSONArray("LSJZList");

            // 按日期排序，保证走势从左到右是时间顺序
            List<JSONObject> items = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) items.add(list.getJSONObject(i));
            items.sort(Comparator.comparing(o -> o.optString("FSRQ", "")));

            // 最新一条（价格 + 当日涨跌）
            JSONObject latest = items.get(items.size() - 1);
            double price = latest.getDouble("DWJZ");
            double changePct = latest.has("JZZZL") && !latest.isNull("JZZZL")
                    ? latest.getDouble("JZZZL") : 0.0;
            String date = latest.optString("FSRQ", "");

            // 组装走势序列
            JSONArray series = new JSONArray();
            for (JSONObject o : items) {
                JSONObject p = new JSONObject();
                p.put("value", o.getDouble("DWJZ"));
                p.put("date", o.optString("FSRQ", ""));
                series.put(p);
            }

            JSONObject out = new JSONObject();
            out.put("price", price);
            out.put("changePct", changePct);
            out.put("date", date);
            out.put("series", series);

            final String payload = out.toString();
            bridge.setData(payload);
            // 通知页面：有新数据了，刷新一下
            wv.post(() -> wv.evaluateJavascript("window.__silverRefresh && window.__silverRefresh()", null));

        } catch (Exception e) {
            // 抓不到就保持演示数据，App 永不崩
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    protected void onDestroy() {
        scheduler.shutdownNow();
        super.onDestroy();
    }

    /** 暴露给网页调用的数据桥：网页通过 SilverBridge.get() 拿到真实数据 */
    public static class SilverBridge {
        private volatile String data = "";

        @JavascriptInterface
        public String get() {
            return data;
        }

        public void setData(String d) {
            data = d == null ? "" : d;
        }
    }
}
