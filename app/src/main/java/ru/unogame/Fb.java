package ru.unogame;

import android.content.Context;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Klien Firebase Realtime Database lewat REST (tanpa SDK, tanpa androidx).
 * Konfigurasi dibaca dari assets/firebase.json:
 *   { "databaseURL": "https://xxx-default-rtdb.firebaseio.com", "auth": "" }
 * Hasil request dikirim kembali ke thread GL lewat antrian "out".
 */
public class Fb {

    public interface CB { void done(boolean ok, String body); }

    public boolean configured;
    public String base = "";
    private String auth = "";
    private final ConcurrentLinkedQueue<Runnable> out;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();

    public Fb(Context ctx, ConcurrentLinkedQueue<Runnable> out) {
        this.out = out;
        try {
            InputStream in = ctx.getAssets().open("firebase.json");
            String s = read(in);
            JSONObject o = new JSONObject(s);
            String u = o.optString("databaseURL", "");
            if (u.length() == 0) u = o.optString("firebase_url", "");
            if (u.length() == 0) {
                JSONObject pi = o.optJSONObject("project_info");
                if (pi != null) u = pi.optString("firebase_url", "");
            }
            auth = o.optString("auth", "");
            u = u.trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            base = u;
            configured = u.startsWith("http") && !u.contains("NAMA-PROJECT");
        } catch (Exception e) {
            configured = false;
        }
    }

    private static String read(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }

    public interface SCB { void event(String type, String path, String data); }

    /** Satu koneksi stream. Ditutup dengan closeStream(s); event dari stream yang sudah ditutup diabaikan. */
    public static class Stream {
        volatile boolean alive = true;
    }

    /**
     * Stream Server-Sent Events Firebase (realtime, tanpa polling). Setiap event dikirim ke thread GL
     * sebagai (tipe, path, data JSON). Tersambung ulang otomatis kalau putus.
     */
    public Stream openStream(final String path, final SCB cb) {
        final Stream s = new Stream();
        if (!configured) { s.alive = false; return s; }
        Thread th = new Thread(new Runnable() {
            public void run() {
                while (s.alive) {
                    HttpURLConnection c = null;
                    try {
                        String url = base + "/" + path + ".json";
                        if (auth.length() > 0) url += "?auth=" + URLEncoder.encode(auth, "UTF-8");
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setRequestProperty("Accept", "text/event-stream");
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(60000);
                        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                        String ev = null;
                        StringBuilder data = new StringBuilder();
                        String line;
                        while (s.alive && (line = br.readLine()) != null) {
                            if (line.startsWith("event:")) ev = line.substring(6).trim();
                            else if (line.startsWith("data:")) data.append(line.substring(5).trim());
                            else if (line.length() == 0 && ev != null) {
                                if (ev.equals("put") || ev.equals("patch")) dispatch(ev, data.toString(), s, cb);
                                ev = null;
                                data.setLength(0);
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (c != null) c.disconnect();
                    }
                    if (!s.alive) return;
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                }
            }
        }, "uno-stream");
        th.setDaemon(true);
        th.start();
        return s;
    }

    private void dispatch(final String type, String json, final Stream s, final SCB cb) {
        try {
            JSONObject o = new JSONObject(json);
            final String p = o.optString("path", "/");
            Object d = o.opt("data");
            final String dat = (d == null || d == JSONObject.NULL) ? null : d.toString();
            out.add(new Runnable() {
                public void run() { if (s.alive) cb.event(type, p, dat); }
            });
        } catch (Exception ignored) { }
    }

    public void closeStream(Stream s) {
        if (s != null) s.alive = false;
    }

    /** method: GET / PUT / DELETE. path tanpa ".json". */
    public void req(final String method, final String path, final String body, final CB cb) {
        if (!configured) {
            if (cb != null) out.add(new Runnable() { public void run() { cb.done(false, "not configured"); } });
            return;
        }
        ex.execute(new Runnable() {
            public void run() {
                boolean ok = false;
                String res = null;
                HttpURLConnection c = null;
                try {
                    String url = base + "/" + path + ".json";
                    if (auth.length() > 0) url += "?auth=" + URLEncoder.encode(auth, "UTF-8");
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    if (body != null) {
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/json");
                        OutputStream os = c.getOutputStream();
                        os.write(body.getBytes("UTF-8"));
                        os.close();
                    }
                    int code = c.getResponseCode();
                    InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    res = read(is);
                    ok = code < 400;
                } catch (Exception e) {
                    res = String.valueOf(e);
                } finally {
                    if (c != null) c.disconnect();
                }
                final boolean fo = ok;
                final String fr = res;
                if (cb != null) out.add(new Runnable() { public void run() { cb.done(fo, fr); } });
            }
        });
    }
}
