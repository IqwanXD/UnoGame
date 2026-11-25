package ru.unogame;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Seluruh UI game digambar di sini dengan OpenGL ES 2.0 (tanpa XML layout). */
public class GameRenderer implements GLSurfaceView.Renderer {

    static final int S_MENU = 0, S_SETUP = 1, S_MPMENU = 2, S_LOBBY = 3, S_PROFILE = 4, S_GAME = 5, S_CUSTOM = 6, S_SETTINGS = 7;
    static final String[] HAT_NAMES = {"Tanpa", "Topi koboi", "Topi kasual", "Kopyah Ustad", "Topi tentara", "Rambut punk"};
    static final String[] FACE_NAMES = {"Tanpa", "Topeng anime", "Rokok di mulut dengan asap", "Masker mulut", "Kaca mata hitam", "Kaca mata bening"};
    static int pint(String v) { try { return Integer.parseInt(v); } catch (Exception e) { return 0; } }

    static final int[] CL = {0xFFFF7A6B, 0xFFFFE770, 0xFF7BE07E, 0xFF6CC2FF, 0xFFCE93D8, 0xFF80DEEA,
            0xFFFFB74D, 0xFFBCAAA4, 0xFFF48FB1, 0xFFB0BEC5};
    static final int[] CD = {0xFFD32F2F, 0xFFF2A100, 0xFF2E9E3A, 0xFF1565C0, 0xFF7B1FA2, 0xFF00838F,
            0xFFE65100, 0xFF5D4037, 0xFFC2185B, 0xFF455A64};
    static final int MAXP = 10;
    static final String[] BOTN = {"Budi", "Siti", "Andi", "Rina", "Joko", "Dewi", "Agus", "Maya", "Eko", "Tari"};
    static final float CW = 180f, PW = 190f;

    // ---------------------------------------------------------------- tipe bantu
    static class Btn { String id; float x, y, w, h; }
    static class HC { int code; float x, y, r, wait; }
    static class Fly {
        int code, kind, color; boolean up;
        float x0, y0, x1, y1, r0, r1, w0, w1, t, dur, delay;
    }
    static class Ring { float x, y, k; int color; }
    static class Pt { float x, y, vx, vy, life, max, size, rot, vr, grav; int color, type; }

    // ---------------------------------------------------------------- state
    final MainActivity act;
    final GLSurfaceView view;
    final Fb fb;
    final SharedPreferences sp;
    final ConcurrentLinkedQueue<Runnable> q = new ConcurrentLinkedQueue<Runnable>();
    final Random rnd = new Random();
    Gfx g;
    Scene3D w3;
    float lastY;

    int screen = S_MENU;
    float screenT = 0f, t = 0f;
    long lastNs = 0;
    String myName, myId;
    int wins, games, botCount = 2;
    String[] botNames = new String[5];

    boolean multi, isHost;
    String roomCode = "";
    ArrayList<String[]> lobby = new ArrayList<String[]>();
    float pollT;
    boolean polling;

    UnoEngine eng;
    int myIdx;
    ArrayList<HC> hc = new ArrayList<HC>();
    ArrayList<float[]> pile = new ArrayList<float[]>();
    ArrayList<Fly> flies = new ArrayList<Fly>();
    ArrayList<Pt> pts = new ArrayList<Pt>();
    ArrayList<Ring> rings = new ArrayList<Ring>();
    float handScroll;
    int selCode = -1, modal, pendingCard = -1, shownColor;
    float shakeT, shakeMag, bannerT = 9f, toastT, botT, botDelay = 1f, winT, overT;
    String bannerText = "", toastText = "";
    int bannerCol;
    boolean statsDone;
    /** System Juara (diatur di setup / lobby) dan status panel hasil juara. */
    boolean juaraOn, podiumClosed;
    /** Penampilan karakter pemain sendiri (disimpan di prefs, dikirim ke room). */
    int myHat, myFace, myCol, custTab;
    /** Warna custom (ARGB): badan pemain dan aksesoris. 0 = pakai bawaan. */
    int myBody, myAcc, mySkin;
    int svHueKey = -1;
    /** Target color picker (0 = badan, 1 = aksesoris) dan HSV yang sedang dipilih. */
    int custTarget;
    float cHue, cSat = 1f, cVal = 1f;
    boolean pickDrag;
    /** Map yang dipilih host (0 ruang gelap, 1 padang rumput, 2 pantai) dan status audionya. */
    int map, sfxMap = -1;
    long mapLocalAt;
    /** Foto wajah pemain sendiri (base64 PNG 128px) dan status pemuatan tekstur. */
    String faceB64;
    boolean faceShown;
    /** Volume musik dan efek (0..100), dan status slider yang sedang digeser (-1 = tidak ada). */
    int musicPct = 80, sfxPct = 100, sliderDrag = -1;
    boolean volPushed;
    /** Id pemain yang fotonya sudah diminta dari server (supaya tidak diminta berulang). */
    final HashMap<String, Boolean> faceAsked = new HashMap<String, Boolean>();
    long juaraLocalAt;

    int writesInFlight;
    long lastBack;

    /** Ukuran buffer gambar sebenarnya (piksel), dipakai MainActivity untuk mengubah koordinat sentuh. */
    volatile int surfW = 1, surfH = 1;

    // tukar posisi duduk: permintaan masuk (kita ditanya) dan permintaan keluar (kita menunggu jawaban)
    Fb.Stream swapSt;
    boolean swapFirst;
    String swapFromId, swapFromName, swapWaitId, swapWaitName;
    float swapAskT, swapWaitT;

    // kepala pemain: arah pandang tiap pemain disinkronkan lewat Firebase (uno_rooms/KODE/look/ID)
    boolean lookForce, lookSending, lookPolling;
    /** Stream realtime: arah kepala (look) dan state game. */
    Fb.Stream lookSt, stateSt;
    /** Hapus room di server setelah game selesai (host). */
    float overDelT;
    boolean roomGone;
    float lookSentY, lookSentP, lookT, lookPollT;
    final HashMap<String, float[]> remoteLook = new HashMap<String, float[]>();

    static final String[] ADJ = {"Gesit", "Lucu", "Kalem", "Riang", "Ceria", "Pintar", "Cepat", "Santai", "Berani", "Keren"};
    static final String[] NOUN = {"Kancil", "Garuda", "Komodo", "Bakso", "Kopi", "Mangga", "Tempe", "Elang", "Rusa", "Kuda", "Bebek", "Bambu"};

    // input
    ArrayList<Btn> bNow = new ArrayList<Btn>(), bPrev = new ArrayList<Btn>();
    String pressed;
    float downX, downY, lastX;
    boolean dragging;

    // dekorasi menu
    static final int DN = 11;
    float[] dx = new float[DN], dy = new float[DN], dv = new float[DN], dr = new float[DN], dvr = new float[DN];
    int[] dcode = new int[DN];
    boolean decoInit;

    public GameRenderer(MainActivity a, GLSurfaceView v) {
        act = a;
        view = v;
        sp = a.getSharedPreferences("unogame", 0);
        myId = sp.getString("id", null);
        if (myId == null) {
            myId = Long.toString(rnd.nextLong() >>> 1, 36);
            sp.edit().putString("id", myId).apply();
        }
        myName = sp.getString("name", null);
        if (myName == null) {
            myName = "Pemain" + (100 + rnd.nextInt(900));
            sp.edit().putString("name", myName).apply();
        }
        wins = sp.getInt("wins", 0);
        games = sp.getInt("games", 0);
        myHat = sp.getInt("hat", 0);
        myFace = sp.getInt("face", 0);
        myCol = sp.getInt("col", 0);
        myBody = sp.getInt("body", 0);
        myAcc = sp.getInt("acc", 0);
        mySkin = sp.getInt("skin", 0);
        faceB64 = sp.getString("face64", null);
        musicPct = sp.getInt("vol_music", 80);
        sfxPct = sp.getInt("vol_sfx", 100);
        fb = new Fb(a, q);
    }

    boolean inGame() { return screen == S_GAME; }

    /** true kalau ada lapisan yang menutupi dunia 3D (pilih warna, hasil akhir, podium juara). */
    boolean uiBlocked() {
        if (eng == null) return true;
        boolean podium = eng.juara && !eng.over && eng.ranks.size() >= 3 && !podiumClosed;
        return modal != 0 || eng.over || podium;
    }

    // ================================================================ lifecycle GL
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        g = new Gfx(act);
        g.init();
        w3 = new Scene3D(act, g);
        w3.init();
        lastNs = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        surfW = w;
        surfH = h;
        g.setSize(w, h);
        w3.setSize(w, h);
        decoInit = false;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        if (lastNs == 0) lastNs = now;
        float dt = Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        t += dt;
        g.time = t;
        Runnable r;
        while ((r = q.poll()) != null) r.run();
        if (!decoInit) initDeco();
        update(dt);

        ArrayList<Btn> tmp = bPrev;
        bPrev = bNow;
        bNow = tmp;
        bNow.clear();

        if (!faceShown && w3 != null && faceB64 != null) { w3.setFace(myId, faceB64); faceShown = true; }
        if (act.sfx != null && !volPushed) { act.sfx.setVolumes(musicPct / 100f, sfxPct / 100f); volPushed = true; }
        int target = screen == S_GAME ? map : -1;
        if (act.sfx != null && target != sfxMap) { act.sfx.setMap(target); sfxMap = target; }
        boolean in3d = screen == S_GAME && eng != null && !eng.players.isEmpty() && w3 != null;
        if (in3d) {
            w3.shake = shakeT > 0f ? Math.max(0f, shakeT) * shakeMag / 0.4f * 0.0012f : 0f;
            applyLook();
            w3.map = map;
            w3.aimOn = !uiBlocked();
            w3.crossX = g.W / 2f;
            w3.crossY = g.H / 2f;
            w3.render(eng, myIdx, pile, shownColor, t);
            g.begin(false);
        } else g.begin(true);
        if (screen == S_MENU) drawMenu();
        else if (screen == S_SETUP) drawSetup();
        else if (screen == S_MPMENU) drawMpMenu();
        else if (screen == S_LOBBY) drawLobby();
        else if (screen == S_PROFILE) drawProfile();
        else if (screen == S_CUSTOM) drawCustom();
        else if (screen == S_SETTINGS) drawSettings();
        else drawGame(dt);
        drawToast();
        if (screenT < 1f) {
            g.ox = 0; g.oy = 0;
            g.rr(g.W / 2f, g.H / 2f, g.fw(), g.H + 20f, 0, 0xFF000000, 0xFF000000, 0, 1f - screenT);
        }
    }

    // ================================================================ input
    public void touch(int action, float px, float py) {
        float x = px / g.scale - g.padX, y = py / g.scale;
        if (action == 0) {
            pressed = hit(x, y);
            downX = x; downY = y; lastX = x; lastY = y;
            dragging = false;
            pickDrag = inPicker(x, y);
            if (pickDrag) pickAt(x, y);
            sliderDrag = sliderAt(x, y);
            if (sliderDrag >= 0) setSlider(sliderDrag, x);
        } else if (action == 2) {
            if (pickDrag) { pickAt(x, y); lastX = x; lastY = y; return; }
            if (sliderDrag >= 0) { setSlider(sliderDrag, x); return; }
            if (Math.abs(x - downX) > 16f || Math.abs(y - downY) > 16f) dragging = true;
            if (dragging && screen == S_GAME) {
                if (downY > g.H - 480f) handScroll += x - lastX;
                else if (w3 != null) {
                    w3.yaw -= (x - lastX) * 0.0035f;
                    w3.pitch -= (y - lastY) * 0.0025f;
                }
            }
            lastX = x; lastY = y;
        } else if (action == 1) {
            pickDrag = false;
            sliderDrag = -1;
            if (pressed != null && !dragging) {
                String h = hit(x, y);
                if (pressed.equals(h)) click(h);
            }
            pressed = null;
            dragging = false;
        } else {
            pressed = null;
            dragging = false;
        }
    }

    /** Tombol UI (kartu, deck, menu) didahulukan. Tombol kursi kalah, dan tidak aktif di sisi kanan layar
     *  (sisi kanan dipakai untuk memutar kamera). */
    String hit(float x, float y) {
        String seat = null;
        for (int i = bPrev.size() - 1; i >= 0; i--) {
            Btn b = bPrev.get(i);
            if (x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h) {
                if (!b.id.startsWith("seat:")) return b.id;
                if (seat == null && x < g.W * 0.72f) seat = b.id;
            }
        }
        return seat;
    }

    void addBtn(String id, float x, float y, float w, float h) {
        Btn b = new Btn();
        b.id = id; b.x = x; b.y = y; b.w = w; b.h = h;
        bNow.add(b);
    }

    /** true kalau back sudah ditangani; false = keluar aplikasi. */
    public boolean back() {
        if (screen == S_MENU) return false;
        if (screen == S_CUSTOM) { go(S_PROFILE); return true; }
        if (screen == S_SETTINGS) { go(S_MENU); return true; }
        if (screen == S_SETUP || screen == S_MPMENU || screen == S_PROFILE) { go(S_MENU); return true; }
        if (screen == S_LOBBY) { leaveRoomRemote(); multi = false; go(S_MPMENU); return true; }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000 || (eng != null && eng.over)) {
            leaveRoomRemote();
            boolean wasMulti = multi;
            multi = false;
            go(wasMulti ? S_MPMENU : S_MENU);
        } else {
            lastBack = now;
            toast("Tekan back lagi untuk keluar dari game");
        }
        return true;
    }

    void go(int s) {
        screen = s;
        g.gameLayout = s == S_GAME;
        g.layout();
        screenT = 0f;
        pressed = null;
        handScroll = 0f;
    }

    void click(String id) {
        if (id.equals("sp")) go(S_SETUP);
        else if (id.equals("mp")) go(S_MPMENU);
        else if (id.equals("profile")) go(S_PROFILE);
        else if (id.equals("settings")) go(S_SETTINGS);
        else if (id.equals("prev:music")) { if (act.sfx != null) act.sfx.previewMusic(); }
        else if (id.equals("prev:sfx")) { if (act.sfx != null) act.sfx.previewSfx(); }
        else if (id.equals("back")) back();
        else if (id.startsWith("bots:")) botCount = Integer.parseInt(id.substring(5));
        else if (id.equals("startsp")) startSingle();
        else if (id.equals("create")) createRoom();
        else if (id.equals("join")) {
            if (!fb.configured) { toast("Isi assets/firebase.json dulu"); return; }
            openText("Gabung Room", "Masukkan kode room", "", 4, true, false, new MainActivity.TextCb() {
                public void ok(String s) { joinRoom(s); }
            });
        } else if (id.equals("edit")) {
            openText("Nama Pemain", "Nama kamu", myName, 12, false, true, new MainActivity.TextCb() {
                public void ok(String s) {
                    myName = s;
                    sp.edit().putString("name", myName).apply();
                }
            });
        } else if (id.equals("startmp")) {
            if (lobby.size() < 2) toast("Minimal 2 pemain");
            else hostStart();
        } else if (id.startsWith("card:")) tapCard(Integer.parseInt(id.substring(5)));
        else if (id.equals("deck")) tapDeck();
        else if (id.startsWith("pick:")) pickColor(Integer.parseInt(id.substring(5)));
        else if (id.equals("again")) {
            if (multi) rematch(); else startSingle();
        } else if (id.equals("menu")) back();
        else if (id.equals("center") && w3 != null) { w3.yaw = 0f; w3.pitch = 0f; }
        else if (id.equals("juara")) toggleJuara();
        else if (id.equals("custom")) { go(S_CUSTOM); loadHsv(); }
        else if (id.startsWith("tab:")) custTab = pint(id.substring(4));
        else if (id.equals("face:upload")) act.pickFace();
        else if (id.equals("radio")) { if (act.sfx != null) act.sfx.nextStation(); }
        else if (id.startsWith("it:")) {
            String[] q = id.split(":");
            int v = Integer.parseInt(q[2]);
            if (q[1].equals("0")) myHat = v; else myFace = v;
            saveLook();
        } else if (id.startsWith("ctg:")) { custTarget = pint(id.substring(4)); loadHsv(); }
        else if (id.startsWith("pre:")) {
            int c = pint(id.substring(4));
            setTargetColor(targetPalette()[c]);
            loadHsv();
        }
        else if (id.startsWith("map:")) pickMap(pint(id.substring(4)));
        else if (id.startsWith("seat:")) moveMySeat(Integer.parseInt(id.substring(5)));
        else if (id.equals("act:aim")) aimAction();
        else if (id.equals("swap:yes")) answerSwap(true);
        else if (id.equals("swap:no")) answerSwap(false);
        else if (id.equals("podiumclose")) podiumClosed = true;
    }

    /** Aksi tombol kanan layar sesuai benda yang dibidik crosshair. */
    void aimAction() {
        if (w3 == null || eng == null || eng.over) return;
        int k = w3.aimKind;
        if (k == 3) {
            if (eng.turn != myIdx) { toast("Belum giliranmu"); return; }
            tapDeck();
        } else if (k == 1 && w3.aimSeat >= 0) {
            moveMySeat(w3.aimSeat);
        } else if (k == 2 && w3.aimPlayer >= 0 && w3.aimPlayer < eng.players.size()) {
            requestSwap(w3.aimPlayer);
        }
    }

    /** Minta tukar kursi dengan pemain p. Bot langsung setuju; pemain lain di room diminta konfirmasi. */
    void requestSwap(int p) {
        if (p == myIdx) return;
        UnoEngine.P t2 = eng.players.get(p);
        if (t2.bot || !multi) {
            doSwapWith(t2.id);
            toast("Tukar posisi dengan " + t2.name);
            return;
        }
        if (swapWaitId != null) { toast("Masih menunggu jawaban " + swapWaitName); return; }
        try {
            JSONObject o = new JSONObject();
            o.put("from", myId);
            o.put("name", myName);
            o.put("st", 0);
            o.put("ts", System.currentTimeMillis());
            fb.req("PUT", "uno_rooms/" + roomCode + "/swap/" + t2.id, o.toString(), null);
        } catch (Exception ex) { return; }
        swapWaitId = t2.id;
        swapWaitName = t2.name;
        swapWaitT = 15f;
        toast("Permintaan tukar dikirim ke " + t2.name);
    }

    /** Tukar kursi kita dengan pemain beridentitas id. */
    void doSwapWith(String id) {
        int me2 = eng.indexOf(myId), other = eng.indexOf(id);
        if (me2 < 0 || other < 0) return;
        int ni = eng.swapSeats(me2, other);
        if (ni < 0) return;
        myIdx = ni;
        afterAction();
    }

    /** Jawab permintaan tukar yang masuk: Tukar (yes) atau Biarkan (no). */
    void answerSwap(boolean yes) {
        if (swapFromId == null) return;
        String from = swapFromId, name = swapFromName;
        swapFromId = null;
        if (yes && eng != null && !eng.over && eng.indexOf(from) >= 0) {
            doSwapWith(from);
            toast("Tukar posisi dengan " + name);
        } else yes = false;
        try {
            JSONObject o = new JSONObject();
            o.put("from", from);
            o.put("name", myName);
            o.put("st", yes ? 1 : 2);
            o.put("ts", System.currentTimeMillis());
            fb.req("PUT", "uno_rooms/" + roomCode + "/swap/" + myId, o.toString(), null);
        } catch (Exception ignored) { }
    }

    /** Event stream swap: kunci = id pemain yang diminta, nilai = {from, name, st: 0 minta, 1 setuju, 2 tolak}. */
    void onSwap(String type, String path, String data) {
        if (swapFirst) { swapFirst = false; if (path.equals("/")) return; } // isi lama sebelum kita masuk
        if (data == null) return;
        try {
            if (path.equals("/")) {
                JSONObject all = new JSONObject(data);
                Iterator<String> it = all.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject v = all.optJSONObject(k);
                    if (v != null) swapEntry(k, v);
                }
            } else {
                String id = path.startsWith("/") ? path.substring(1) : path;
                if (id.indexOf('/') >= 0) return;
                swapEntry(id, new JSONObject(data));
            }
        } catch (Exception ignored) { }
    }

    void swapEntry(String id, JSONObject v) {
        int st = v.optInt("st", 0);
        String from = v.optString("from", "");
        if (id.equals(myId) && st == 0 && from.length() > 0 && !from.equals(myId)) {
            swapFromId = from;
            swapFromName = v.optString("name", "Pemain");
            swapAskT = 15f;
        } else if (swapWaitId != null && id.equals(swapWaitId) && from.equals(myId) && st != 0) {
            toast(st == 1 ? "Tukar posisi dengan " + swapWaitName + " berhasil" : swapWaitName + " memilih tetap di tempat");
            fb.req("DELETE", "uno_rooms/" + roomCode + "/swap/" + id, null, null);
            swapWaitId = null;
        }
    }

    /** Pindah ke kursi kosong. Urutan giliran mengikuti kursi, jadi ikut berubah. */
    void moveMySeat(int seat) {
        if (eng == null || eng.over || myIdx < 0) return;
        int ni = eng.moveSeat(myIdx, seat);
        if (ni < 0) return;
        myIdx = ni;
        afterAction();
    }

    void saveLook() {
        sp.edit().putInt("hat", myHat).putInt("face", myFace).putInt("col", myCol)
                .putInt("body", myBody).putInt("acc", myAcc).putInt("skin", mySkin).apply();
    }

    /** Layar Karakter: tab Hat dan Face, lalu tab Warna (color picker untuk badan dan aksesoris). Tersimpan otomatis. */
    void drawCustom() {
        g.bg();
        drawDeco();
        backBtn();
        title("Karakter");
        // pratinjau karakter 3D yang sedang diubah (topi, wajah, warna badan dan aksesoris)
        float top = 150f, bot = g.H * 0.33f - 70f;
        g.rrb(500f, (top + bot) / 2f, 300f, bot - top, 40f, 0xE02A2F5C, 0xE0171A3A, 0x40FFFFFF, 3f, 0f, 1f);
        if (w3 != null) {
            UnoEngine.P pv = new UnoEngine.P();
            pv.id = myId; pv.name = myName;
            pv.hat = myHat; pv.face = myFace; pv.col = myCol; pv.acc = myAcc; pv.body = myBody; pv.skin = mySkin;
            w3.renderPreview(pv, 350f, top + 10f, 300f, bot - top - 20f, t);
            g.begin(false);
        }
        float ty = g.H * 0.33f;
        String[] tabs = {"Hat", "Face", "Warna"};
        for (int i = 0; i < tabs.length; i++) {
            boolean on = custTab == i;
            button("tab:" + i, tabs[i], R.drawable.ic_person, 500f + (i - 1) * 300f, ty, 280f, 110f,
                    on ? 0xFFFFC93C : 0xFF4A4F6F, on ? 0xFFE08A00 : 0xFF2E3250);
        }
        if (custTab == 2) { drawColorTab(pkY0()); return; }
        String[] names = custTab == 0 ? HAT_NAMES : FACE_NAMES;
        int sel = custTab == 0 ? myHat : myFace;
        float y0 = g.H * 0.43f, rowY = y0;
        if (custTab == 1) {
            button("face:upload", faceB64 == null ? "Upload Foto Wajah" : "Ganti Foto Wajah", R.drawable.ic_add,
                    500f, y0, 760f, 96f, 0xFF4BA3FF, 0xFF1565C0);
            rowY = y0 + 130f;
        }
        for (int i = 0; i < names.length; i++) {
            boolean on = i == sel;
            button("it:" + custTab + ":" + i, names[i], R.drawable.ic_person, 500f, rowY + i * 124f, 760f, 104f,
                    on ? 0xFF66D96B : 0xFF4A4F6F, on ? 0xFF2E9E3A : 0xFF2E3250);
        }
    }

    // ---------------------------------------------------------------- color picker
    static final float PK_X0 = 150f, PK_W = 700f;

    float pkY0() { return g.H * 0.43f; }

    static final int[] SKIN_PAL = {0xFFF1C27D, 0xFFE0AC69, 0xFFC68642, 0xFF8D5524, 0xFFFFDBAC, 0xFF5D4037};

    /** Palet preset untuk target yang sedang dipilih. */
    int[] targetPalette() { return custTarget == 0 ? SKIN_PAL : custTarget == 1 ? CD : Scene3D.ACC_COL; }

    /** Warna kulit (Badan), baju (Baju), dan aksesoris: masing-masing punya fallback bawaan. */
    int skinColor() { return mySkin != 0 ? mySkin : CL[0]; }
    int bodyColor() { return myBody != 0 ? myBody : CD[0]; }
    int accColor() { return myAcc != 0 ? myAcc : Scene3D.ACC_COL[myCol % Scene3D.ACC_COL.length]; }
    int pickedColor() { return custTarget == 0 ? skinColor() : custTarget == 1 ? bodyColor() : accColor(); }

    /** Simpan warna untuk target yang dipilih. */
    void setTargetColor(int c) {
        if (custTarget == 0) mySkin = c;
        else if (custTarget == 1) myBody = c;
        else myAcc = c;
        saveLook();
    }

    /** Bitmap bar hue: 256 langkah halus dari merah sampai merah lagi. */
    Bitmap makeHueBitmap() {
        Bitmap b = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888);
        int[] px = new int[256];
        float[] hsv = {0f, 1f, 1f};
        for (int x = 0; x < 256; x++) { hsv[0] = x * 360f / 256f; px[x] = Color.HSVToColor(hsv); }
        b.setPixels(px, 0, 256, 0, 0, 256, 1);
        return b;
    }

    /** Bitmap kotak saturasi (kiri ke kanan) dan kecerahan (atas ke bawah) untuk satu hue. */
    Bitmap makeSvBitmap(float hue) {
        final int N = 128;
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        int[] px = new int[N * N];
        float[] hsv = {hue, 0f, 0f};
        for (int y = 0; y < N; y++) {
            hsv[2] = 1f - y / (float) (N - 1);
            for (int x = 0; x < N; x++) {
                hsv[1] = x / (float) (N - 1);
                px[y * N + x] = Color.HSVToColor(hsv);
            }
        }
        b.setPixels(px, 0, N, 0, 0, N, N);
        return b;
    }

    /** Tab Warna: tiga target (Badan, Baju, Aksesoris), preset cepat, bar hue dan kotak SV halus, pratinjau. */
    void drawColorTab(float y0) {
        String[] names = {"Badan", "Baju", "Aksesoris"};
        for (int i = 0; i < 3; i++) {
            boolean on = custTarget == i;
            button("ctg:" + i, names[i], R.drawable.ic_person, 500f + (i - 1) * 270f, y0, 250f, 96f,
                    on ? 0xFF66D96B : 0xFF4A4F6F, on ? 0xFF2E9E3A : 0xFF2E3250);
        }

        int cur = pickedColor();
        int[] pal = targetPalette();
        for (int c = 0; c < 6; c++) {
            float cx = 500f + (c - 2.5f) * 120f, cy = y0 + 130f;
            if (pal[c] == cur) g.rr(cx, cy, 88f, 88f, 44f, 0xFFFFFFFF, 0xFFFFFFFF, 0f, 1f);
            g.rr(cx, cy, 72f, 72f, 36f, pal[c], pal[c], 0f, 1f);
            addBtn("pre:" + c, cx - 44f, cy - 44f, 88f, 88f);
        }

        float hy = y0 + 230f, sy = y0 + 300f, sh = 360f;
        if (!g.hasPicture("hue")) g.setPicture("hue", makeHueBitmap());
        g.drawPicture("hue", PK_X0 + PK_W / 2f, hy, PK_W, 56f, 1f);
        g.rr(PK_X0 + cHue / 360f * PK_W, hy, 10f, 70f, 5f, 0xFFFFFFFF, 0xFFFFFFFF, 0f, 1f);

        int hk = Math.round(cHue);
        if (!g.hasPicture("sv") || hk != svHueKey) {
            g.setPicture("sv", makeSvBitmap(hk));
            svHueKey = hk;
        }
        g.drawPicture("sv", PK_X0 + PK_W / 2f, sy + sh / 2f, PK_W, sh, 1f);
        float ix = PK_X0 + cSat * PK_W, iy = sy + (1f - cVal) * sh;
        g.rr(ix, iy, 36f, 36f, 18f, 0xFFFFFFFF, 0xFFFFFFFF, 0f, 1f);
        g.rr(ix, iy, 28f, 28f, 14f, cur, cur, 0f, 1f);

        float py = y0 + 730f;
        g.rr(PK_X0 + 90f, py, 130f, 130f, 40f, 0xFFFFFFFF, 0xFFFFFFFF, 0f, 1f);
        g.rr(PK_X0 + 90f, py, 116f, 116f, 34f, cur, cur, 0f, 1f);
        g.textL(String.format(Locale.ROOT, "#%06X", cur & 0xFFFFFF), PK_X0 + 190f, py, 46f, 0xFFFFFFFF, true, 1f);
        g.text("Geser di kotak atau bar warna", 500f, py + 110f, 26f, 0xCCFFFFFF, false, 1f, 0f);
    }

    /** Muat HSV dari warna target supaya picker mulai dari warna yang benar. */
    void loadHsv() {
        float[] hsv = new float[3];
        Color.colorToHSV(pickedColor(), hsv);
        cHue = hsv[0];
        cSat = hsv[1];
        cVal = hsv[2];
    }

    boolean inPicker(float x, float y) {
        float y0 = pkY0();
        return screen == S_CUSTOM && custTab == 2 && x >= PK_X0 - 12f && x <= PK_X0 + PK_W + 12f
                && y >= y0 + 196f && y <= y0 + 668f;
    }

    static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** Sentuh atau geser di picker: bar hue mengatur warna, kotak mengatur saturasi dan kecerahan. */
    void pickAt(float x, float y) {
        float y0 = pkY0();
        if (y <= y0 + 280f) cHue = clamp01((x - PK_X0) / PK_W) * 359.9f;
        else {
            cSat = clamp01((x - PK_X0) / PK_W);
            cVal = 1f - clamp01((y - (y0 + 300f)) / 360f);
        }
        int c = Color.HSVToColor(new float[]{cHue, cSat, cVal});
        setTargetColor(c);
    }

    // ---------------------------------------------------------------- pilihan map
    static final String[] MAP_NAMES = {"Ruang Gelap", "Padang Rumput", "Pantai"};
    static final int[] MAP_C1 = {0xFF3A2F55, 0xFF8BD06A, 0xFF56C2E6};
    static final int[] MAP_C2 = {0xFF120E1E, 0xFF2F6B2A, 0xFF1B6FA8};

    /** Tiga kartu map. Hanya host (atau mode single player) yang bisa menekan kartu. */
    void mapRow(float cy, boolean editable) {
        for (int i = 0; i < MAP_NAMES.length; i++) {
            float cx = 500f + (i - 1) * 290f, w = 270f, h = 110f;
            boolean sel = map == i;
            float s = ("map:" + i).equals(pressed) && editable ? 0.96f : 1f;
            g.shadow(cx, cy + 6f, w * s, h * s, 28f, 14f, 0.4f);
            g.rrb(cx, cy, w * s, h * s, 28f * s, MAP_C1[i], MAP_C2[i],
                    sel ? 0xFFFFD83D : 0x50FFFFFF, sel ? 6f : 2f, 0f, 1f);
            g.text(MAP_NAMES[i], cx, cy, 30f, 0xFFFFFFFF, true, sel || editable ? 1f : 0.6f, 0f);
            if (editable) addBtn("map:" + i, cx - w / 2f, cy - h / 2f, w, h);
        }
    }

    /** Ganti map. Di room, hanya host yang bisa mengubah; nilainya disimpan di meta room. */
    void pickMap(int m) {
        if (m < 0 || m >= MAP_NAMES.length) return;
        if (multi && !isHost) { toast("Hanya host yang bisa memilih map"); return; }
        map = m;
        if (multi) {
            mapLocalAt = System.currentTimeMillis();
            fb.req("PUT", "uno_rooms/" + roomCode + "/meta/map", String.valueOf(m), null);
        }
    }

    /** Foto wajah dari galeri (sudah dipotong persegi dan base64). Disimpan lokal, dikirim ke server, jadi tekstur. */
    void setFace(String b64) {
        faceB64 = b64;
        faceShown = true;
        sp.edit().putString("face64", b64).apply();
        if (w3 != null) w3.setFace(myId, b64);
        if (fb.configured) fb.req("PUT", "uno_faces/" + myId, JSONObject.quote(b64), null);
        toast("Foto wajah tersimpan");
    }

    /** Ambil foto wajah pemain lain dari server, sekali per pemain. */
    void loadFace(final String id) {
        if (id == null || id.equals(myId) || !fb.configured || faceAsked.containsKey(id)) return;
        faceAsked.put(id, true);
        fb.req("GET", "uno_faces/" + id, null, new Fb.CB() {
            public void done(boolean ok, String b) {
                if (!ok || b == null) return;
                try {
                    Object v = new org.json.JSONTokener(b).nextValue();
                    if (v instanceof String) {
                        final String s = (String) v;
                        q.add(new Runnable() {
                            public void run() { if (w3 != null) w3.setFace(id, s); }
                        });
                    }
                } catch (Exception ignored) { }
            }
        });
    }

    // ================================================================ layar: pengaturan
    /** Pusat vertikal slider 0 = musik, 1 = efek suara. */
    float sliderY(int i) { return g.H * (i == 0 ? 0.36f : 0.5f); }

    /** Slider yang disentuh di (x, y), atau -1. */
    int sliderAt(float x, float y) {
        if (screen != S_SETTINGS) return -1;
        for (int i = 0; i < 2; i++) {
            float cy = sliderY(i);
            if (x >= 150f && x <= 850f && y >= cy - 60f && y <= cy + 40f) return i;
        }
        return -1;
    }

    void setSlider(int i, float x) {
        int pct = Math.round(clamp01((x - 170f) / 660f) * 100f);
        if (i == 0) musicPct = pct; else sfxPct = pct;
        sp.edit().putInt(i == 0 ? "vol_music" : "vol_sfx", pct).apply();
        if (act.sfx != null) act.sfx.setVolumes(musicPct / 100f, sfxPct / 100f);
    }

    void drawSettings() {
        g.bg();
        drawDeco();
        backBtn();
        title("Pengaturan");
        sliderRow(0, "Volume musik", musicPct);
        sliderRow(1, "Volume efek suara", sfxPct);
        button("prev:music", "Putar contoh musik", R.drawable.ic_play, 500f, g.H * 0.66f, 660f, 124f, 0xFF4BA3FF, 0xFF1565C0);
        button("prev:sfx", "Putar contoh efek", R.drawable.ic_play, 500f, g.H * 0.8f, 660f, 124f, 0xFF66D96B, 0xFF2E9E3A);
        g.text("Musik map hanya terdengar saat bermain", 500f, g.H * 0.92f, 28f, 0xCCFFFFFF, false, 1f, 0f);
    }

    void sliderRow(int i, String label, int pct) {
        float cy = sliderY(i), v = pct / 100f;
        g.text(label + "  " + pct + "%", 500f, cy - 70f, 36f, 0xFFFFFFFF, true, 1f, 0f);
        g.rr(500f, cy, 660f, 22f, 11f, 0x60FFFFFF, 0x60FFFFFF, 0f, 1f);
        if (v > 0f) g.rr(170f + v * 330f, cy, v * 660f, 22f, 11f, 0xFF66D96B, 0xFF2E9E3A, 0f, 1f);
        g.rr(170f + v * 660f, cy, 54f, 54f, 27f, 0xFFFFFFFF, 0xFFFFFFFF, 0f, 1f);
    }

    void toggleJuara() {
        if (multi) {
            if (!isHost) { toast("Hanya host yang bisa mengubah"); return; }
            juaraOn = !juaraOn;
            juaraLocalAt = System.currentTimeMillis();
            fb.req("PUT", "uno_rooms/" + roomCode + "/meta/juara", juaraOn ? "true" : "false", null);
        } else {
            juaraOn = !juaraOn;
        }
    }

    /** Dialog input memakai keyboard sistem (lihat MainActivity.showTextDialog). */
    void openText(String title, String hint, String init, int max, boolean code, boolean randomBtn,
                  MainActivity.TextCb cb) {
        act.showTextDialog(title, hint, init, max, code, randomBtn, cb);
    }

    /** Nama acak: kata sifat + nama hewan/makanan, selalu maksimal 12 karakter. */
    static String randomName(Random r) {
        return ADJ[r.nextInt(ADJ.length)] + NOUN[r.nextInt(NOUN.length)];
    }

    /** Ringkas pesan error jaringan / Firebase untuk toast. */
    static String why(String b) {
        if (b == null) return "tanpa respons";
        b = b.replace("java.net.", "").replace("javax.net.ssl.", "").trim();
        if (b.contains("Permission denied")) return "rules database menolak (401)";
        if (b.contains("UnknownHost")) return "tidak ada internet/DNS diblokir";
        return b.length() > 60 ? b.substring(0, 60) : b;
    }

    void toast(String s) { toastText = s; toastT = 2.4f; }

    void banner(String s, int c) { bannerText = s; bannerCol = c; bannerT = 0f; }

    // ================================================================ update
    void initDeco() {
        for (int i = 0; i < DN; i++) resetDeco(i, true);
        decoInit = true;
    }

    void resetDeco(int i, boolean anywhere) {
        // lebar layar virtual (di landscape lebih lebar dari 1000); padX = margin kiri yang tersembunyi
        float left = -g.padX, width = g.W + 2f * g.padX;
        float x;
        if (g.padX > 1f && rnd.nextFloat() < 0.85f) {
            // landscape: kartu lebih sering di area kiri/kanan, kolom menu di tengah tetap rapi
            boolean sideLeft = rnd.nextBoolean();
            if (sideLeft) x = left + rnd.nextFloat() * (500f - 380f - left);
            else x = 500f + 380f + rnd.nextFloat() * (left + width - 500f - 380f);
        } else {
            x = left + rnd.nextFloat() * width;
        }
        dx[i] = x;
        dy[i] = anywhere ? rnd.nextFloat() * g.H : g.H + 200f;
        dv[i] = 30f + rnd.nextFloat() * 60f;
        dr[i] = (rnd.nextFloat() - 0.5f) * 1.2f;
        dvr[i] = (rnd.nextFloat() - 0.5f) * 0.5f;
        dcode[i] = rnd.nextFloat() < 0.15f ? UnoEngine.mk(UnoEngine.WILD, UnoEngine.W) : UnoEngine.mk(rnd.nextInt(4), rnd.nextInt(13));
    }

    void update(float dt) {
        screenT = Math.min(1f, screenT + dt * 3.2f);
        if (shakeT > 0f) {
            shakeT -= dt;
            float k = Math.max(0f, shakeT) * shakeMag / 0.4f;
            g.ox = (float) Math.sin(t * 70f) * k;
            g.oy = (float) Math.cos(t * 63f) * k;
        } else { g.ox = 0f; g.oy = 0f; }
        if (swapFromId != null) {
            swapAskT -= dt;
            if (swapAskT <= 0f) answerSwap(false);
        }
        if (swapWaitId != null) {
            swapWaitT -= dt;
            if (swapWaitT <= 0f) {
                fb.req("DELETE", "uno_rooms/" + roomCode + "/swap/" + swapWaitId, null, null);
                toast(swapWaitName + " tidak menjawab");
                swapWaitId = null;
            }
        }
        for (int i = 0; i < decoN(); i++) {
            dy[i] -= dv[i] * dt;
            dr[i] += dvr[i] * dt;
            if (dy[i] < -220f) resetDeco(i, false);
        }
        if (toastT > 0f) toastT -= dt;
        if (bannerT < 9f) bannerT += dt;

        for (int i = pts.size() - 1; i >= 0; i--) {
            Pt p = pts.get(i);
            p.life -= dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vy += p.grav * dt;
            p.rot += p.vr * dt;
            if (p.life <= 0f) pts.remove(i);
        }
        for (int i = rings.size() - 1; i >= 0; i--) {
            Ring r = rings.get(i);
            r.k += dt * 1.7f;
            if (r.k >= 1f) rings.remove(i);
        }
        for (int i = flies.size() - 1; i >= 0; i--) {
            Fly f = flies.get(i);
            f.t += dt;
            if (f.t >= f.delay + f.dur) {
                if (f.kind == 0) land(f);
                flies.remove(i);
            }
        }

        if (multi && (screen == S_LOBBY || screen == S_GAME)) {
            pollT -= dt;
            if (pollT <= 0f && !polling) {
                pollT = screen == S_GAME ? 2f : 0.8f; // di game state datang lewat stream; polling jadi cadangan
                polling = true;
                final boolean lobbyMode = screen == S_LOBBY;
                String path = lobbyMode ? "uno_rooms/" + roomCode : "uno_rooms/" + roomCode + "/state";
                fb.req("GET", path, null, new Fb.CB() {
                    public void done(boolean ok, String body) {
                        polling = false;
                        if (!ok) return;
                        if (lobbyMode) handleRoom(body); else applyRemote(body);
                    }
                });
            }
        }

        updateLook(dt);

        if (screen == S_GAME && eng != null) {
            boolean landing = false;
            for (Fly f : flies) if (f.kind == 0) { landing = true; break; }
            if (!landing && !eng.disc.isEmpty()) {
                int top = eng.top();
                if (pile.isEmpty() || (int) pile.get(pile.size() - 1)[0] != top) pushPile(top, (rnd.nextFloat() - 0.5f) * 0.7f);
                shownColor = eng.color;
            }
            if (eng.over) {
                if (!statsDone) {
                    statsDone = true;
                    games++;
                    if (eng.winner == myIdx) wins++;
                    sp.edit().putInt("games", games).putInt("wins", wins).apply();
                }
                if (multi && isHost && !roomGone && roomCode.length() > 0) {
                    overDelT += dt;
                    if (overDelT >= 8f) {
                        roomGone = true;
                        fb.req("DELETE", "uno_rooms/" + roomCode, null, null);
                    }
                }
                overT = Math.min(1f, overT + dt * 2.5f);
                winT -= dt;
                if (winT <= 0f) {
                    winT = 0.7f;
                    burst(80f + rnd.nextFloat() * (g.W - 160f), 200f + rnd.nextFloat() * g.H * 0.4f, CL[rnd.nextInt(4)], 16, 520f);
                }
            } else {
                overT = 0f;
                if (!eng.players.isEmpty() && eng.turn < eng.players.size()) {
                    UnoEngine.P cur = eng.players.get(eng.turn);
                    if (cur.bot && canAct() && flies.isEmpty()) {
                        botT += dt;
                        if (botT > botDelay) {
                            botT = 0f;
                            botDelay = 0.9f + rnd.nextFloat() * 0.8f;
                            doBot();
                        }
                    } else botT = 0f;
                }
            }
        }
    }

    boolean canAct() {
        if (!multi) return true;
        for (int i = 0; i < eng.players.size(); i++) if (!eng.players.get(i).bot) return i == myIdx;
        return false;
    }

    // ================================================================ efek
    void burst(float x, float y, int color, int n, float speed) {
        Pt gl = new Pt();
        gl.x = x; gl.y = y; gl.life = gl.max = 0.4f; gl.size = 130f; gl.color = color; gl.type = 1;
        pts.add(gl);
        for (int i = 0; i < n; i++) {
            Pt p = new Pt();
            float a = rnd.nextFloat() * 6.2832f;
            float s = speed * (0.3f + rnd.nextFloat() * 0.8f);
            p.x = x; p.y = y;
            p.vx = (float) Math.cos(a) * s;
            p.vy = (float) Math.sin(a) * s - 120f;
            p.life = p.max = 0.7f + rnd.nextFloat() * 0.8f;
            p.size = 10f + rnd.nextFloat() * 14f;
            p.rot = rnd.nextFloat() * 6f;
            p.vr = (rnd.nextFloat() - 0.5f) * 14f;
            p.grav = 900f;
            p.color = rnd.nextFloat() < 0.25f ? 0xFFFFFFFF : color;
            p.type = rnd.nextFloat() < 0.3f ? 2 : 0;
            pts.add(p);
        }
    }

    void confetti() {
        for (int i = 0; i < (Scene3D.LITE ? 24 : 60); i++) {
            Pt p = new Pt();
            p.x = rnd.nextFloat() * g.W;
            p.y = -40f - rnd.nextFloat() * 700f;
            p.vx = (rnd.nextFloat() - 0.5f) * 220f;
            p.vy = 220f + rnd.nextFloat() * 300f;
            p.life = p.max = 5f + rnd.nextFloat() * 2f;
            p.size = 14f + rnd.nextFloat() * 14f;
            p.rot = rnd.nextFloat() * 6f;
            p.vr = (rnd.nextFloat() - 0.5f) * 10f;
            p.grav = 30f;
            p.color = CL[rnd.nextInt(4)];
            p.type = 0;
            pts.add(p);
        }
    }

    void ring(float x, float y, int color) {
        Ring r = new Ring();
        r.x = x; r.y = y; r.color = color;
        rings.add(r);
    }

    void shake(float mag) { shakeT = 0.4f; shakeMag = mag; }

    void pushPile(int code, float rot) {
        pile.add(new float[]{code, rot, (rnd.nextFloat() - 0.5f) * 24f, (rnd.nextFloat() - 0.5f) * 24f});
        while (pile.size() > 6) pile.remove(0);
    }

    void land(Fly f) {
        pushPile(f.code, f.r1);
        if (f.color >= 0 && f.color < 4) shownColor = f.color;
        int c = (f.color >= 0 && f.color < 4) ? CL[f.color] : 0xFFFFFFFF;
        burst(f.x1, f.y1, c, 14, 380f);
        ring(f.x1, f.y1, c);
    }

    // ================================================================ gambar: dasar
    static String init1(String s) {
        return s.length() > 0 ? s.substring(0, 1).toUpperCase(Locale.ROOT) : "?";
    }

    void button(String id, String label, int icon, float cx, float cy, float w, float h, int c1, int c2) {
        boolean pr = id.equals(pressed);
        cy += (float) Math.sin(t * 2f + id.length() * 1.3f) * 2f;
        float s = pr ? 0.95f : 1f;
        float ww = w * s, hh = h * s;
        g.shadow(cx, cy + 8f, ww, hh, hh * 0.3f, 16f, 0.45f);
        g.rrb(cx, cy, ww, hh, hh * 0.3f, c1, c2, 0x80FFFFFF, 3f, 0f, 1f);
        g.rr(cx, cy - hh * 0.22f, ww - 18f, hh * 0.42f, hh * 0.2f, 0x38FFFFFF, 0x06FFFFFF, 0f, 1f);
        float tx = cx;
        if (icon != 0) {
            g.icon(icon, cx - ww / 2f + hh * 0.62f, cy, hh * 0.46f, 0xFFFFFFFF, 1f, 0f);
            tx = cx + hh * 0.28f;
        }
        g.text(label, tx + 2f, cy + 2f, hh * 0.36f, 0xFF000000, true, 0.3f, 0f);
        g.text(label, tx, cy, hh * 0.36f, 0xFFFFFFFF, true, 1f, 0f);
        addBtn(id, cx - w / 2f, cy - h / 2f, w, h);
    }

    void iconBtn(String id, int icon, float cx, float cy, float r) {
        float s = id.equals(pressed) ? 0.92f : 1f;
        g.shadow(cx, cy + 5f, 2f * r * s, 2f * r * s, r, 10f, 0.4f);
        g.rrb(cx, cy, 2f * r * s, 2f * r * s, r * s, 0xFF3A3F70, 0xFF20244A, 0x80FFFFFF, 3f, 0f, 1f);
        g.icon(icon, cx, cy, r * 1.05f * s, 0xFFFFFFFF, 1f, 0f);
        addBtn(id, cx - r - 12f, cy - r - 12f, 2f * r + 24f, 2f * r + 24f);
    }

    void backBtn() { iconBtn("back", R.drawable.ic_back, 80f, 130f, 36f); }

    void title(String s) {
        g.text(s, 502f, 134f, 66f, 0xFF000000, true, 0.3f, 0f);
        g.text(s, 500f, 130f, 66f, 0xFFFFFFFF, true, 1f, 0f);
    }

    void avatar(float x, float y, float r, int idx, String name, boolean bot) {
        int k = (idx & 0x7fffffff) % CL.length;
        g.shadow(x, y + 6f, 2f * r, 2f * r, r, 12f, 0.4f);
        g.rrb(x, y, 2f * r, 2f * r, r, CL[k], CD[k], 0xFFFFFFFF, 4f, 0f, 1f);
        g.text(init1(name), x, y, r * 1.0f, 0xFFFFFFFF, true, 1f, 0f);
        if (bot) {
            g.rrb(x + r * 0.7f, y + r * 0.7f, r * 0.8f, r * 0.8f, r * 0.4f, 0xFF455A64, 0xFF263238, 0xFFFFFFFF, 2f, 0f, 1f);
            g.icon(R.drawable.ic_robot, x + r * 0.7f, y + r * 0.7f, r * 0.5f, 0xFFFFFFFF, 1f, 0f);
        }
    }

    void panel(float cx, float cy, float w, float h) {
        g.shadow(cx, cy + 8f, w, h, 36f, 22f, 0.4f);
        g.rrb(cx, cy, w, h, 36f, 0xE02A2F5C, 0xE0171A3A, 0x40FFFFFF, 3f, 0f, 1f);
    }

    static String faceStr(int v) {
        if (v <= 9) return String.valueOf(v);
        if (v == UnoEngine.D2) return "+2";
        if (v == UnoEngine.W4) return "+4";
        return "";
    }

    /** Gambar satu kartu. code < 0 = punggung kartu. */
    void card(int code, float cx, float cy, float w, float rot, float a, float dim) {
        float h = w * 1.45f, rad = w * 0.1f;
        g.shadow(cx, cy + w * 0.04f, w, h, rad, w * 0.09f, 0.38f * a);
        g.rr(cx, cy, w, h, rad, 0xFFFFFFFF, 0xFFEAEAEA, rot, a);
        float iw = w * 0.88f, ih = h * 0.915f, ir = rad * 0.75f;
        if (code < 0) {
            g.rr(cx, cy, iw, ih, ir, 0xFF2A2B4A, 0xFF0B0B18, rot, a);
            g.ell(cx, cy, w * 0.96f, h * 0.52f, 0xFFE53935, 0xFFB71C1C, rot - 0.85f, a);
            g.text("UNO", cx, cy, w * 0.3f, 0xFFFFD83D, true, a, rot - 0.85f);
            if (dim > 0f) g.rr(cx, cy, w, h, rad, 0xFF000000, 0xFF000000, rot, dim * a);
            return;
        }
        int col = UnoEngine.col(code), v = UnoEngine.val(code);
        boolean wild = col == UnoEngine.WILD;
        if (wild) g.rr(cx, cy, iw, ih, ir, 0xFF2E2E38, 0xFF050507, rot, a);
        else g.rr(cx, cy, iw, ih, ir, CL[col], CD[col], rot, a);
        if (wild) g.ell4(cx, cy, w * 0.96f, h * 0.52f, rot - 0.85f, a, CL[0], CL[1], CL[2], CL[3]);
        else g.ell(cx, cy, w * 0.96f, h * 0.52f, 0xFFFFFFFF, 0xFFF1F1F1, rot - 0.85f, a);

        float c = (float) Math.cos(rot), s = (float) Math.sin(rot);
        float lx = -w * 0.3f, ly = -h * 0.4f;
        float tlx = cx + lx * c - ly * s, tly = cy + lx * s + ly * c;
        float brx = cx - lx * c + ly * s, bry = cy - lx * s - ly * c;
        if (v == UnoEngine.SKIP || v == UnoEngine.REV) {
            int ic = v == UnoEngine.SKIP ? R.drawable.ic_skip : R.drawable.ic_reverse;
            g.icon(ic, cx, cy, w * 0.55f, CD[col], a, rot);
            g.icon(ic, tlx, tly, w * 0.2f, 0xFFFFFFFF, a, rot);
            g.icon(ic, brx, bry, w * 0.2f, 0xFFFFFFFF, a, rot + 3.1416f);
        } else if (!(wild && v == UnoEngine.W)) {
            String fs = faceStr(v);
            if (wild) {
                g.text(fs, cx + 2f, cy + 3f, w * 0.4f, 0xFF000000, true, 0.5f * a, rot);
                g.text(fs, cx, cy, w * 0.4f, 0xFFFFFFFF, true, a, rot);
            } else {
                g.text(fs, cx + 2f, cy + 3f, w * 0.5f, 0xFF000000, true, 0.25f * a, rot);
                g.text(fs, cx, cy, w * 0.5f, CD[col], true, a, rot);
            }
            g.text(fs, tlx, tly, w * 0.2f, 0xFFFFFFFF, true, a, rot);
            g.text(fs, brx, bry, w * 0.2f, 0xFFFFFFFF, true, a, rot + 3.1416f);
        }
        if (dim > 0f) g.rr(cx, cy, w, h, rad, 0xFF000000, 0xFF000000, rot, dim * a);
    }

    void drawToast() {
        if (toastT <= 0f) return;
        float a = Math.min(1f, toastT * 2f);
        float w = Math.min(g.W - 60f, g.textW(toastText, 32f, true) + 70f);
        float y = g.H - 70f;
        g.shadow(500f, y + 4f, w, 66f, 33f, 12f, 0.4f * a);
        g.rrb(500f, y, w, 66f, 33f, 0xF0262A55, 0xF0161938, 0x60FFFFFF, 2f, 0f, a);
        g.text(toastText, 500f, y, 32f, 0xFFFFFFFF, true, a, 0f);
    }

    // ================================================================ layar: menu
    /** Jumlah kartu dekorasi menu: lebih sedikit di perangkat RAM kecil. */
    int decoN() { return Scene3D.LITE ? 5 : DN; }

    void drawDeco() {
        for (int i = 0; i < decoN(); i++) card(dcode[i], dx[i], dy[i], 130f, dr[i], 0.3f, 0f);
    }

    void chip(String text, int icon, float cx, float cy, float w) {
        g.rrb(cx, cy, w, 70f, 35f, 0xB0262A55, 0xB0161938, 0x50FFFFFF, 2f, 0f, 1f);
        g.icon(icon, cx - w / 2f + 42f, cy, 36f, 0xFFFFD83D, 1f, 0f);
        g.text(text, cx + 18f, cy, 32f, 0xFFFFFFFF, true, 1f, 0f);
    }

    void drawMenu() {
        g.bg();
        drawDeco();
        chip(myName, R.drawable.ic_person, 500f, 120f, Math.min(520f, g.textW(myName, 32f, true) + 120f));
        float ly = g.H * 0.22f;
        String[] L = {"U", "N", "O"};
        int[] LC = {0, 1, 3};
        for (int i = 0; i < 3; i++) {
            float x = 500f + (i - 1) * 215f;
            float y = ly + (float) Math.sin(t * 1.6f + i * 1.1f) * 14f;
            float rot = (i - 1) * 0.16f + (float) Math.sin(t * 1.2f + i) * 0.04f;
            g.shadow(x, y + 12f, 190f, 250f, 34f, 26f, 0.5f);
            g.rr(x, y, 190f, 250f, 36f, 0xFFFFFFFF, 0xFFEDEDED, rot, 1f);
            g.rr(x, y, 166f, 226f, 28f, CL[LC[i]], CD[LC[i]], rot, 1f);
            g.text(L[i], x + 4f, y + 6f, 170f, 0xFF000000, true, 0.28f, rot);
            g.text(L[i], x, y, 170f, 0xFFFFFFFF, true, 1f, rot);
        }
        g.text("UnoGame", 500f, ly + 205f, 58f, 0xFFFFFFFF, true, 1f, 0f);
        float y0 = g.H * 0.5f;
        button("sp", "Singleplayer", R.drawable.ic_robot, 500f, y0, 660f, 136f, 0xFF4BA3FF, 0xFF1565C0);
        button("mp", "Multiplayer", R.drawable.ic_group, 500f, y0 + 172f, 660f, 136f, 0xFF66D96B, 0xFF2E9E3A);
        button("profile", "Profile", R.drawable.ic_person, 500f, y0 + 344f, 660f, 136f, 0xFFFFC93C, 0xFFE08A00);
        button("settings", "Pengaturan", R.drawable.ic_group, 500f, y0 + 516f, 660f, 136f, 0xFF8E9BB8, 0xFF4A5570);
        g.text("2 - 10 pemain", 500f, g.H - 80f, 30f, 0xFFFFFFFF, false, 0.55f, 0f);
    }

    // ================================================================ layar: singleplayer
    void drawSetup() {
        g.bg();
        drawDeco();
        backBtn();
        title("Singleplayer");
        g.text("Pilih jumlah komputer", 500f, g.H * 0.25f, 44f, 0xFFFFFFFF, true, 0.95f, 0f);
        for (int i = 1; i <= MAXP - 1; i++) {
            float x = 500f + ((i - 1) % 5 - 2) * 160f, y = g.H * 0.31f + ((i - 1) / 5) * 150f;
            boolean sel = botCount == i;
            float s = sel ? 1.2f : 1f;
            if (sel) g.glow(x, y, 100f, CL[i % 4], 0.6f);
            g.shadow(x, y + 6f, 110f * s, 110f * s, 32f * s, 12f, 0.4f);
            if (sel) g.rrb(x, y, 110f * s, 110f * s, 32f * s, CL[i % 4], CD[i % 4], 0xFFFFFFFF, 4f, 0f, 1f);
            else g.rrb(x, y, 110f, 110f, 32f, 0xFF2E3466, 0xFF1A1E3F, 0x50FFFFFF, 2f, 0f, 1f);
            g.text(String.valueOf(i), x, y, 58f * s, 0xFFFFFFFF, true, 1f, 0f);
            addBtn("bots:" + i, x - 62f, y - 62f, 124f, 124f);
        }
        panel(500f, g.H * 0.58f, 900f, 500f);
        g.text("Pemain (" + (botCount + 1) + ")", 500f, g.H * 0.58f - 215f, 38f, 0xFFFFFFFF, true, 1f, 0f);
        int total = botCount + 1;
        for (int k = 0; k < total; k++) {
            float x = 500f + ((k % 5) - 2) * 170f;
            float y = g.H * 0.58f - 85f + (k / 5) * 210f;
            String nm = k == 0 ? myName : BOTN[(k - 1) % BOTN.length];
            avatar(x, y, 48f, k, nm, k > 0);
            g.text(nm, x, y + 78f, 26f, 0xFFFFFFFF, true, 1f, 0f);
        }
        mapRow(g.H * 0.755f, true);
        switchRow("juara", 500f, g.H * 0.84f, 660f, 120f, juaraOn, "Top 3 jadi juara 1-3");
        button("startsp", "Mulai Main", R.drawable.ic_play, 500f, g.H * 0.93f, 660f, 140f, 0xFFFF7A6B, 0xFFD32F2F);
    }

    // ================================================================ layar: profil
    void drawProfile() {
        g.bg();
        drawDeco();
        backBtn();
        title("Profile");
        g.glow(500f, g.H * 0.28f, 190f, CL[(myName.hashCode() & 0x7fffffff) % 4], 0.45f);
        avatar(500f, g.H * 0.28f, 120f, myName.hashCode(), myName, false);
        g.text(myName, 500f, g.H * 0.28f + 190f, 70f, 0xFFFFFFFF, true, 1f, 0f);
        button("edit", "Ubah Nama", R.drawable.ic_edit, 500f - 200f, g.H * 0.28f + 330f, 380f, 130f, 0xFF4BA3FF, 0xFF1565C0);
        button("custom", "Karakter", R.drawable.ic_person, 500f + 200f, g.H * 0.28f + 330f, 380f, 130f, 0xFFFFC93C, 0xFFE08A00);
        panel(500f, g.H * 0.28f + 560f, 900f, 230f);
        float py = g.H * 0.28f;
        int wr = games > 0 ? wins * 100 / games : 0;
        g.text("Main", 500f - 270f, py + 520f, 34f, 0xCCFFFFFF, true, 1f, 0f);
        g.text(String.valueOf(games), 500f - 270f, py + 590f, 72f, 0xFFFFE770, true, 1f, 0f);
        g.text("Menang", 500f, py + 520f, 34f, 0xCCFFFFFF, true, 1f, 0f);
        g.text(String.valueOf(wins), 500f, py + 590f, 72f, 0xFF7BE07E, true, 1f, 0f);
        g.text("Winrate", 500f + 270f, py + 520f, 34f, 0xCCFFFFFF, true, 1f, 0f);
        g.text(wr + "%", 500f + 270f, py + 590f, 72f, 0xFFFFB74D, true, 1f, 0f);
    }

    // ================================================================ layar: multiplayer
    void drawMpMenu() {
        g.bg();
        drawDeco();
        backBtn();
        title("Multiplayer");
        float y0 = g.H * 0.36f;
        button("create", "Buat Room", R.drawable.ic_add, 500f, y0, 660f, 140f, 0xFF66D96B, 0xFF2E9E3A);
        button("join", "Gabung Room", R.drawable.ic_enter, 500f, y0 + 180f, 660f, 140f, 0xFF4BA3FF, 0xFF1565C0);
        panel(500f, y0 + 430f, 800f, 190f);
        if (fb.configured) {
            g.text("Server Firebase siap", 500f, y0 + 400f, 36f, 0xFF7BE07E, true, 1f, 0f);
            g.text("Maks 10 pemain per room", 500f, y0 + 456f, 30f, 0xCCFFFFFF, false, 1f, 0f);
        } else {
            g.text("firebase.json belum diisi", 500f, y0 + 395f, 36f, 0xFFFF7A6B, true, 1f, 0f);
            g.text("Edit assets/firebase.json", 500f, y0 + 445f, 30f, 0xCCFFFFFF, false, 1f, 0f);
            g.text("isi databaseURL project kamu", 500f, y0 + 485f, 30f, 0xCCFFFFFF, false, 1f, 0f);
        }
    }

    void drawLobby() {
        g.bg();
        backBtn();
        title("Lobby");
        g.text("KODE ROOM", 500f, g.H * 0.2f, 34f, 0xCCFFFFFF, true, 1f, 0f);
        for (int i = 0; i < roomCode.length(); i++) {
            float x = 500f + (i - (roomCode.length() - 1) / 2f) * 150f;
            float y = g.H * 0.27f + (float) Math.sin(t * 2f + i) * 6f;
            g.shadow(x, y + 8f, 130f, 160f, 26f, 18f, 0.45f);
            g.rrb(x, y, 130f, 160f, 26f, CL[i % 4], CD[i % 4], 0xFFFFFFFF, 4f, 0f, 1f);
            g.text(roomCode.substring(i, i + 1), x, y, 100f, 0xFFFFFFFF, true, 1f, 0f);
        }
        g.text("Bagikan kode ini ke temanmu", 500f, g.H * 0.35f, 30f, 0xCCFFFFFF, false, 1f, 0f);
        for (int i = 0; i < MAXP; i++) {
            float y = g.H * 0.38f + i * 66f;
            g.rrb(500f, y, 820f, 60f, 22f, 0xB0262A55, 0xB0161938, 0x40FFFFFF, 2f, 0f, 1f);
            if (i < lobby.size()) {
                String[] p = lobby.get(i);
                avatar(150f, y, 28f, i, p[1], false);
                g.textL(p[1], 210f, y, 34f, 0xFFFFFFFF, true, 1f);
                if (i == 0) g.icon(R.drawable.ic_crown, 830f, y, 40f, 0xFFFFD83D, 1f, 0f);
                if (p[0].equals(myId)) g.textL("Kamu", 640f, y, 28f, 0xFF7BE07E, true, 1f);
            } else {
                String dots = "...".substring(0, 1 + (int) (t * 2f) % 3);
                g.textL("Menunggu pemain" + dots, 210f, y, 32f, 0xFFFFFFFF, false, 0.4f);
            }
        }
        mapRow(g.H - 390f, isHost);
        switchRow("juara", 500f, g.H - 240f, 660f, 120f, juaraOn, isHost ? "Top 3 jadi juara 1-3" : "Diatur oleh host");
        if (isHost) {
            button("startmp", "Mulai Game", R.drawable.ic_play, 500f, g.H - 100f, 660f, 130f,
                    lobby.size() >= 2 ? 0xFFFF7A6B : 0xFF6A6F8F, lobby.size() >= 2 ? 0xFFD32F2F : 0xFF4A4F6F);
        } else {
            g.text("Menunggu host memulai game...", 500f, g.H - 100f, 36f, 0xFFFFFFFF, true, 0.6f + 0.4f * (float) Math.sin(t * 3f), 0f);
        }
    }

    // ================================================================ layar: game
    /** Posisi layar (virtual) objek 3D: {x, y, lebar kartu di layar}. */
    float[] deckP() {
        float[] d = w3.deckScreen();
        if (d == null) return new float[]{g.W / 2f - 140f, g.H * 0.4f, 170f};
        return new float[]{d[0], d[1], Scene3D.CW * d[2]};
    }

    float[] pileP() {
        float[] d = w3.pileScreen();
        if (d == null) return new float[]{g.W / 2f + 140f, g.H * 0.4f, 170f};
        return new float[]{d[0], d[1], Scene3D.CW * d[2]};
    }

    float[] playerPos(int p) {
        int n = eng.players.size();
        if (p == myIdx) return new float[]{g.W / 2f, g.H - 250f};
        w3.setPlayers(eng, myIdx);
        float[] s = w3.seatScreen(p, 0);
        if (s == null) return new float[]{g.W / 2f, g.H * 0.3f};
        return new float[]{s[0], s[1]};
    }

    /** Label 2D di atas karakter 3D: nama, jumlah kartu, cincin giliran. */
    void drawSeat(int p) {
        UnoEngine.P pl = eng.players.get(p);
        float[] top = w3.seatScreen(p, 1), body = w3.seatScreen(p, 2), cards = w3.seatScreen(p, 0), head = w3.seatScreen(p, 3);
        if (top == null || body == null || cards == null || head == null) return;
        float u = body[2], x = head[0];
        if (x < -150f || x > g.W + 150f) return;
        boolean turn = eng.turn == p && !eng.over;
        if (turn) {
            float k = 0.5f + 0.5f * (float) Math.sin(t * 6f);
            float r = Math.max(30f, 0.34f * u);
            g.glow(x, head[1], r * 1.5f, 0xFFFFE066, 0.35f + 0.25f * k);
            g.rrb(x, head[1], 2f * r, 2f * r, r, 0x00FFE066, 0x00FFE066, 0xFFFFE066, 5f, 0f, 0.5f + 0.5f * k);
        }
        float ny = top[1] - 0.12f * u - 22f;
        float nw = g.textW(pl.name, 28f, true);
        g.text(pl.name, x + (pl.bot ? 18f : 0f), ny, 28f, 0xFFFFFFFF, true, 1f, 0f);
        if (pl.bot) g.icon(R.drawable.ic_robot, x - nw / 2f - 6f, ny, 34f, 0xFFFFFFFF, 0.9f, 0f);
        int hn = pl.hand.size();
        float bx = cards[0] + 0.3f * u, by = cards[1];
        g.rrb(bx, by, 44f, 44f, 22f, 0xFFE53935, 0xFFB71C1C, 0xFFFFFFFF, 3f, 0f, 1f);
        g.text(String.valueOf(hn), bx, by, 26f, 0xFFFFFFFF, true, 1f, 0f);
    }

    /** Titik crosshair di tengah layar dan tombol aksi di kanan layar sesuai benda yang dibidik. */
    void drawAimHud(boolean mine, float pulse) {
        int k = w3.aimKind;
        float cx = g.W / 2f, cy = g.H / 2f;
        float r = k != 0 ? 9f + 2f * pulse : 6f;
        int dot = k != 0 ? 0xFFFFE066 : 0xFFFFFFFF;
        g.rr(cx, cy, r * 2f + 6f, r * 2f + 6f, r + 3f, 0xB0000000, 0xB0000000, 0f, 1f);
        g.rr(cx, cy, r * 2f, r * 2f, r, dot, dot, 0f, 1f);
        if (k == 0) return;
        int icon, c1, c2;
        String label;
        float alpha = 1f;
        if (k == 1) { icon = R.drawable.ic_chair; label = "Duduk"; c1 = 0xFF66D96B; c2 = 0xFF2E9E3A; }
        else if (k == 2) { icon = R.drawable.ic_refresh; label = "Tukar"; c1 = 0xFF4BA3FF; c2 = 0xFF1565C0; }
        else {
            icon = R.drawable.ic_hand; label = "Ambil"; c1 = 0xFFFFC93C; c2 = 0xFFE08A00;
            if (!mine) alpha = 0.5f;
        }
        float bx = g.W + g.padX - 100f, by = g.H * 0.5f, br = 62f;
        boolean pr = "act:aim".equals(pressed);
        float sc = pr ? 0.92f : 1f;
        g.shadow(bx, by + 6f, 2f * br * sc, 2f * br * sc, br, 12f, 0.45f * alpha);
        g.rrb(bx, by, 2f * br * sc, 2f * br * sc, br * sc, c1, c2, 0xFFFFFFFF, 4f, 0f, alpha);
        g.icon(icon, bx, by, br * 1.05f * sc, 0xFFFFFFFF, alpha, 0f);
        g.text(label, bx + 2f, by + br + 36f, 28f, 0xFF000000, true, 0.5f * alpha, 0f);
        g.text(label, bx, by + br + 34f, 28f, 0xFFFFFFFF, true, alpha, 0f);
        addBtn("act:aim", bx - br - 14f, by - br - 14f, 2f * br + 28f, 2f * br + 28f + 60f);
    }

    /** Notifikasi permintaan tukar posisi dari pemain lain: Tukar atau Biarkan. */
    void drawSwapPanel() {
        if (swapFromId == null || eng == null || eng.over) return;
        float cx = 500f, cy = 330f, w = 640f, h = 200f;
        panel(cx, cy, w, h);
        g.text(swapFromName + " ingin tukar posisi", cx, cy - 52f, 32f, 0xFFFFFFFF, true, 1f, 0f);
        float k = Math.max(0f, Math.min(1f, swapAskT / 15f));
        g.rr(cx, cy - 18f, w - 80f, 8f, 4f, 0x40FFFFFF, 0x40FFFFFF, 0f, 1f);
        g.rr(cx - (w - 80f) * (1f - k) / 2f, cy - 18f, (w - 80f) * k, 8f, 4f, 0xFFFFE066, 0xFFFFE066, 0f, 1f);
        button("swap:yes", "Tukar", R.drawable.ic_refresh, cx - 150f, cy + 52f, 270f, 76f, 0xFF66D96B, 0xFF2E9E3A);
        button("swap:no", "Biarkan", R.drawable.ic_back, cx + 150f, cy + 52f, 270f, 76f, 0xFF6A6F8F, 0xFF4A4F6F);
    }

    void drawGame(float dt) {
        if (eng == null || eng.players.isEmpty()) return;
        if (myIdx < 0) myIdx = 0;
        int n = eng.players.size();
        w3.setPlayers(eng, myIdx);
        float[] dp = deckP();
        float cy = g.H * 0.47f;
        boolean podium = eng.juara && !eng.over && eng.ranks.size() >= 3 && !podiumClosed;
        boolean block = modal != 0 || eng.over || podium;
        boolean mine = eng.turn == myIdx && !eng.over && modal == 0;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 5f);

        // halo bohlam jadul
        float[] lamp = w3.lampScreen();
        if (lamp != null) {
            g.additive(true);
            g.glow(lamp[0], lamp[1], Math.min(460f, 0.55f * lamp[2]), 0xFFFFC866, 0.8f);
            g.additive(false);
        }

        iconBtn("back", R.drawable.ic_back, 80f, 130f, 36f);
        iconBtn("center", R.drawable.ic_aim, g.W - 80f, 250f, 36f);
        if (multi) chip("Room " + roomCode, R.drawable.ic_group, g.W - 200f, 130f, 330f);

        // HUD: kartu teratas, warna aktif, arah putaran (kartu di meja terlihat miring dari kursi)
        int sc0 = Math.max(0, Math.min(3, shownColor));
        float hx = 110f, hy = 330f;
        g.glow(hx, hy, 120f, CD[sc0], 0.35f + 0.15f * pulse);
        if (!eng.disc.isEmpty()) card(eng.top(), hx, hy, 120f, 0f, 1f, 0f);
        g.rrb(hx + 70f, hy + 112f, 40f, 40f, 20f, CL[sc0], CD[sc0], 0xFFFFFFFF, 3f, 0f, 1f);
        g.icon(R.drawable.ic_reverse, hx - 66f, hy + 112f, 46f, 0xFFFFFFFF, 0.9f, eng.dir > 0 ? 0f : 3.1416f);

        // dek di meja 3D
        boolean needDraw = mine && (eng.drawnCard >= 0 || !eng.hasPlayable(myIdx));
        if (needDraw) g.glow(dp[0], dp[1], dp[2] * 0.9f, 0xFFFFE066, 0.25f + 0.45f * pulse);
        g.text(String.valueOf(eng.draw.size()), dp[0], dp[1] + dp[2] * 0.5f, 30f, 0xFFFFFFFF, true, 0.85f, 0f);
        if (!block) addBtn("deck", dp[0] - dp[2] * 0.6f, dp[1] - dp[2] * 0.45f, dp[2] * 1.2f, dp[2] * 0.9f);
        if (!block && map == 0 && w3 != null) {
            float[] rp = w3.radioScreen();
            if (rp != null) {
                float rs = Math.max(80f, rp[2] * 0.5f);
                addBtn("radio", rp[0] - rs / 2f, rp[1] - rs / 2f, rs, rs);
            }
        }

        if (!block) drawAimHud(mine, pulse);
        drawSwapPanel();

        // label karakter lawan
        for (int p = 0; p < n; p++) if (p != myIdx) drawSeat(p);

        // tangan sendiri
        UnoEngine.P me = eng.players.get(myIdx);
        syncHand(me.hand, dp[0], dp[1]);
        int hn = hc.size();
        float baseY = g.H - 250f;
        float avail = g.W - CW - 60f;
        float spc = hn > 1 ? Math.max(64f, Math.min(118f, avail / (hn - 1))) : 0f;
        float total = spc * (hn - 1);
        float maxS = Math.max(0f, (total - avail) / 2f);
        handScroll = Math.max(-maxS, Math.min(maxS, handScroll));
        int selIdx = -1;
        for (int i = 0; i < hn; i++) if (hc.get(i).code == selCode && eng.canPlay(myIdx, selCode)) { selIdx = i; break; }
        float lerp = Math.min(1f, dt * 12f);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < hn; i++) {
                if ((i == selIdx) != (pass == 1)) continue;
                HC h = hc.get(i);
                if (h.wait > 0f) { h.wait -= dt; continue; }
                float m = i - (hn - 1) / 2f;
                float tx = g.W / 2f + m * spc + handScroll;
                float ty = baseY + Math.min(46f, m * m * (hn > 9 ? 0.35f : 1.4f));
                float tr = Math.max(-0.3f, Math.min(0.3f, m * (hn > 9 ? 0.012f : 0.04f)));
                boolean pl = mine && eng.canPlay(myIdx, h.code);
                if (pl) ty -= 28f;
                if (i == selIdx) ty -= 62f;
                h.x += (tx - h.x) * lerp;
                h.y += (ty - h.y) * lerp;
                h.r += (tr - h.r) * lerp;
                if (pl) g.glow(h.x, h.y, CW * 0.8f, 0xFFFFFFFF, 0.2f + 0.12f * pulse);
                card(h.code, h.x, h.y, CW, h.r, 1f, (mine && !pl) ? 0.45f : 0f);
                if (!block) {
                    boolean wide = i == hn - 1 || i == selIdx;
                    addBtn("card:" + i, h.x - CW / 2f, h.y - CW * 0.725f, wide ? CW : spc, CW * 1.45f);
                }
            }
        }

        // teks status
        if (!eng.over) {
            String st;
            if (eng.turn == myIdx) {
                st = eng.drawnCard >= 0 ? "Mainkan kartu yang diambil, atau ketuk dek untuk lewat" : "Giliranmu! Pilih kartu atau ambil dari dek";
            } else if (eng.ranks.contains(myIdx)) {
                st = "Kamu sudah selesai, menonton sampai habis...";
            } else {
                st = "Giliran " + eng.players.get(eng.turn).name + "...";
            }
            float a = eng.turn == myIdx ? 0.7f + 0.3f * pulse : 0.75f;
            g.text(st, 500f, g.H - 548f, 30f, eng.turn == myIdx ? 0xFFFFE770 : 0xFFFFFFFF, true, a, 0f);
        }

        // kartu terbang
        for (Fly f : flies) {
            float p = (f.t - f.delay) / f.dur;
            if (p < 0f) continue;
            p = Math.min(1f, p);
            float e = 1f - (1f - p) * (1f - p) * (1f - p);
            float x = f.x0 + (f.x1 - f.x0) * e;
            float y = f.y0 + (f.y1 - f.y0) * e - (float) Math.sin(p * 3.1416f) * 70f;
            float w = f.w0 + (f.w1 - f.w0) * e;
            float r = f.r0 + (f.r1 - f.r0) * e;
            card(f.up ? f.code : -1, x, y, w, r, 1f, 0f);
        }

        drawFx();

        if (modal == 1 && !eng.over) {
            g.rr(500f, g.H / 2f, g.fw(), g.H + 20f, 0, 0xC0000000, 0xC0000000, 0, 1f);
            g.text("Pilih warna", 500f, g.H * 0.34f, 64f, 0xFFFFFFFF, true, 1f, 0f);
            for (int i = 0; i < 4; i++) {
                float x = 500f + ((i % 2) - 0.5f) * 250f, y = g.H * 0.46f + ((i / 2) - 0.5f) * 250f;
                float s = 1f + 0.04f * (float) Math.sin(t * 5f + i);
                g.shadow(x, y + 8f, 220f * s, 220f * s, 50f, 18f, 0.5f);
                g.rrb(x, y, 220f * s, 220f * s, 50f, CL[i], CD[i], 0xFFFFFFFF, 5f, 0f, 1f);
                addBtn("pick:" + i, x - 110f, y - 110f, 220f, 220f);
            }
        }

        if (eng.over) { if (eng.juara) drawPodium(true); else drawOver(); }
        else if (podium) drawPodium(false);

        // banner besar
        if (bannerT < 1.3f) {
            float k = Math.min(1f, bannerT / 0.18f);
            float sc2 = 0.4f + 0.6f * (1f + 2.7f * (k - 1f) * (k - 1f) * (k - 1f) + 1.7f * (k - 1f) * (k - 1f));
            float a = bannerT > 1f ? 1f - (bannerT - 1f) / 0.3f : 1f;
            g.textS(bannerText, 506f, cy - 14f, 130f, sc2, 0xFF000000, true, 0.4f * a, -0.08f);
            g.textS(bannerText, 500f, cy - 20f, 130f, sc2, bannerCol, true, a, -0.08f);
        }
    }

    /** Saklar System Juara: label, keterangan, dan track on/off yang hijau saat aktif. */
    void switchRow(String id, float cx, float cy, float w, float h, boolean on, String sub) {
        boolean pr = id.equals(pressed);
        float s = pr ? 0.97f : 1f;
        float ww = w * s, hh = h * s;
        g.shadow(cx, cy + 8f, ww, hh, hh * 0.3f, 16f, 0.45f);
        g.rrb(cx, cy, ww, hh, hh * 0.3f, 0xE02A2F5C, 0xE0171A3A, 0x40FFFFFF, 3f, 0f, 1f);
        float left = cx - ww / 2f + 36f;
        g.textL("System Juara", left, cy - hh * 0.17f, 38f, 0xFFFFFFFF, true, 1f);
        g.textL(sub, left, cy + hh * 0.2f, 24f, 0xCCFFFFFF, false, 1f);
        float tw = 130f, th = 62f, tx = cx + ww / 2f - 36f - tw / 2f;
        int c1 = on ? 0xFF66D96B : 0xFF3A3F70, c2 = on ? 0xFF2E9E3A : 0xFF20244A;
        g.rrb(tx, cy, tw, th, th / 2f, c1, c2, 0x60FFFFFF, 2f, 0f, 1f);
        float kr = th - 14f;
        float kx = on ? tx + tw / 2f - th / 2f : tx - tw / 2f + th / 2f;
        g.rr(kx, cy, kr, kr, kr / 2f, 0xFFFFFFFF, 0xFFE8E8E8, 0f, 1f);
        addBtn(id, cx - ww / 2f, cy - hh / 2f, ww, hh);
    }

    /**
     * Panel hasil juara. Tiga teratas tampil sebagai podium, sisanya di list bawah
     * sesuai urutan selesai (pemain yang belum selesai ditampilkan dengan sisa kartunya).
     */
    void drawPodium(boolean fin) {
        int n = eng.players.size();
        float a = fin ? overT : 1f;
        ArrayList<Integer> order = new ArrayList<Integer>(eng.ranks);
        ArrayList<Integer> rest = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) if (!order.contains(i)) rest.add(i);
        Collections.sort(rest, new java.util.Comparator<Integer>() {
            public int compare(Integer x, Integer y) {
                return eng.players.get(x).hand.size() - eng.players.get(y).hand.size();
            }
        });
        order.addAll(rest);

        int rows = Math.max(0, n - 3);
        float ph = Math.min(g.H - 60f, 600f + rows * 60f + 170f);
        float rowH = rows > 0 ? Math.min(60f, (ph - 770f) / rows) : 60f;
        float top = g.H / 2f - ph / 2f;

        g.rr(500f, g.H / 2f, g.fw(), g.H + 20f, 0, 0xB0000000, 0xB0000000, 0, fin ? overT : 1f);
        panel(500f, g.H / 2f, 860f, ph);
        g.text(fin ? "Hasil Juara" : "Ada Juara!", 500f, top + 70f, 54f, 0xFFFFFFFF, true, a, 0f);

        float[] px = {500f, 240f, 760f}, py = {top + 240f, top + 300f, top + 300f}, pr = {66f, 54f, 54f};
        int[] medal = {0xFFFFD83D, 0xFFCFD8DC, 0xFFE0A070};
        for (int i = 0; i < 3 && i < order.size(); i++) {
            int p = order.get(i);
            UnoEngine.P pl = eng.players.get(p);
            avatar(px[i], py[i], pr[i], p, pl.name, pl.bot);
            if (i == 0) g.icon(R.drawable.ic_crown, px[i], py[i] - pr[i] - 40f, 60f, 0xFFFFD83D, a, 0f);
            g.text(pl.name, px[i], py[i] + pr[i] + 42f, 32f, 0xFFFFFFFF, true, a, 0f);
            g.text("JUARA " + (i + 1), px[i], py[i] + pr[i] + 80f, 26f, medal[i], true, a, 0f);
        }

        float ly = top + 470f;
        for (int k = 3; k < n; k++) {
            int p = order.get(k);
            UnoEngine.P pl = eng.players.get(p);
            float y = ly + (k - 3) * rowH + rowH / 2f;
            boolean done = k < eng.ranks.size();
            boolean me = p == myIdx;
            g.rrb(500f, y, 760f, rowH - 8f, 18f, 0x90262A55, 0x90161938, 0x30FFFFFF, 2f, 0f, a);
            g.textL((k + 1) + ".", 150f, y, 28f, 0xCCFFFFFF, true, a);
            g.textL(pl.name, 200f, y, 28f, me ? 0xFF7BE07E : 0xFFFFFFFF, true, a);
            String st = done ? "Selesai" : pl.hand.size() + " kartu";
            float sw = g.textW(st, 26f, true);
            g.textL(st, 830f - sw, y, 26f, done ? 0xFF7BE07E : 0xFFFFB74D, true, a);
        }

        float by = top + ph - 100f;
        if (fin) {
            if (!multi) {
                button("again", "Main Lagi", R.drawable.ic_refresh, 500f - 200f, by, 380f, 110f, 0xFF66D96B, 0xFF2E9E3A);
                button("menu", "Keluar", R.drawable.ic_back, 500f + 200f, by, 380f, 110f, 0xFF4BA3FF, 0xFF1565C0);
            } else {
                g.text("Room selesai, dihapus dari server", 500f, by - 80f, 30f, 0xCCFFFFFF, false, 0.8f, 0f);
                button("menu", "Keluar", R.drawable.ic_back, 500f, by, 480f, 110f, 0xFF4BA3FF, 0xFF1565C0);
            }
        } else {
            boolean watching = eng.ranks.contains(myIdx);
            button("podiumclose", watching ? "Tonton Lanjut" : "Lanjut Main", R.drawable.ic_play,
                    500f, by, 480f, 110f, 0xFF4BA3FF, 0xFF1565C0);
        }
    }

    void drawOver() {
        g.rr(500f, g.H / 2f, g.fw(), g.H + 20f, 0, 0xB0000000, 0xB0000000, 0, overT);
        float y = g.H * 0.42f;
        boolean won = eng.winner == myIdx;
        panel(500f, y, 800f, 720f);
        g.glow(500f, y - 215f, 150f, 0xFFFFD83D, 0.55f * overT);
        g.icon(R.drawable.ic_crown, 500f, y - 215f + (float) Math.sin(t * 3f) * 8f, 190f, 0xFFFFD83D, overT, 0f);
        String nm = eng.winner >= 0 && eng.winner < eng.players.size() ? eng.players.get(eng.winner).name : "";
        g.text(won ? "Kamu Menang!" : nm + " Menang!", 500f, y - 50f, 70f, 0xFFFFFFFF, true, overT, 0f);
        if (!multi) {
            button("again", "Main Lagi", R.drawable.ic_refresh, 500f, y + 110f, 600f, 120f, 0xFF66D96B, 0xFF2E9E3A);
        } else {
            g.text("Room selesai, dihapus dari server", 500f, y + 110f, 30f, 0xCCFFFFFF, false, 0.8f, 0f);
        }
        button("menu", "Keluar", R.drawable.ic_back, 500f, y + 255f, 600f, 110f, 0xFF4BA3FF, 0xFF1565C0);
    }

    void drawFx() {
        for (Pt p : pts) {
            if (p.type == 1) continue;
            float k = p.life / p.max;
            if (p.type == 0) g.rr(p.x, p.y, p.size, p.size * 0.55f, 2f, p.color, p.color, p.rot, Math.min(1f, k * 2f));
            else g.glow(p.x, p.y, p.size * 0.5f, p.color, Math.min(1f, k * 2f));
        }
        g.additive(true);
        for (Pt p : pts) {
            if (p.type != 1) continue;
            g.glow(p.x, p.y, p.size * (1.4f - p.life / p.max), p.color, p.life / p.max);
        }
        g.additive(false);
        for (Ring r : rings) {
            float k = r.k;
            float rad = 40f + k * 320f;
            int c0 = r.color & 0x00FFFFFF;
            g.rrb(r.x, r.y, 2f * rad, 2f * rad, rad, c0, c0, r.color, 16f * (1f - k) + 2f, 0f, 1f - k);
        }
    }

    // ================================================================ tangan & aksi
    void syncHand(ArrayList<Integer> hand, float deckX, float cy) {
        ArrayList<HC> nl = new ArrayList<HC>(hand.size());
        boolean[] used = new boolean[hc.size()];
        int spawn = 0;
        for (int code : hand) {
            HC f = null;
            for (int j = 0; j < hc.size(); j++) {
                if (!used[j] && hc.get(j).code == code) { used[j] = true; f = hc.get(j); break; }
            }
            if (f == null) {
                f = new HC();
                f.code = code;
                f.x = deckX; f.y = cy; f.r = 0f;
                f.wait = spawn * 0.08f;
                spawn++;
            }
            nl.add(f);
        }
        hc = nl;
    }

    HC removeHC(int code) {
        for (int i = 0; i < hc.size(); i++) if (hc.get(i).code == code) return hc.remove(i);
        return null;
    }

    void tapCard(int idx) {
        if (eng == null || eng.over || eng.turn != myIdx || modal != 0) return;
        ArrayList<Integer> hand = eng.players.get(myIdx).hand;
        if (idx < 0 || idx >= hand.size()) return;
        int code = hand.get(idx);
        if (!eng.canPlay(myIdx, code)) {
            toast("Kartu itu belum bisa dimainkan");
            return;
        }
        if (selCode != code) { selCode = code; return; }
        selCode = -1;
        if (UnoEngine.col(code) == UnoEngine.WILD) {
            pendingCard = code;
            modal = 1;
        } else {
            eng.play(myIdx, code, -1);
            afterAction();
        }
    }

    void pickColor(int c) {
        if (modal != 1 || pendingCard < 0) return;
        int code = pendingCard;
        pendingCard = -1;
        modal = 0;
        eng.play(myIdx, code, c);
        afterAction();
    }

    void tapDeck() {
        if (eng == null || eng.over || eng.turn != myIdx || modal != 0) return;
        selCode = -1;
        if (eng.drawnCard >= 0) eng.pass(myIdx); else eng.drawTurn(myIdx);
        afterAction();
    }

    void doBot() {
        int pi = eng.turn;
        int c = eng.botChoose(pi);
        if (c >= 0) {
            int ch = UnoEngine.col(c) == UnoEngine.WILD ? eng.botColor(pi) : -1;
            eng.play(pi, c, ch);
        } else if (eng.drawnCard >= 0) {
            eng.pass(pi);
        } else {
            eng.drawTurn(pi);
        }
        afterAction();
    }

    void afterAction() {
        for (int[] e : new ArrayList<int[]>(eng.evs)) handleEvent(e);
        if (multi) pushState();
    }

    void handleEvent(int[] e) {
        int type = e[0], p = e[1], c = e[2], n = e[3];
        float[] dk = deckP(), pl3 = pileP();
        float cy = dk[1];
        if (type == UnoEngine.EV_PLAY) act.sfx.place();
        else if (type == UnoEngine.EV_DRAW) act.sfx.draw();
        float[] pp = (p >= 0 && p < eng.players.size()) ? playerPos(p) : new float[]{dk[0], dk[1]};
        if (type == UnoEngine.EV_START) {
            pile.clear(); flies.clear(); hc.clear(); pts.clear(); rings.clear();
            statsDone = false; modal = 0; selCode = -1; pendingCard = -1; overT = 0f; botT = 0f; podiumClosed = false;
            pushPile(eng.top(), (rnd.nextFloat() - 0.5f) * 0.7f);
            shownColor = eng.color;
            banner("Mulai!", 0xFFFFE770);
        } else if (type == UnoEngine.EV_PLAY) {
            Fly f = new Fly();
            f.code = c; f.up = true; f.kind = 0; f.color = n;
            if (p == myIdx) {
                HC h = removeHC(c);
                if (h != null) { f.x0 = h.x; f.y0 = h.y; f.r0 = h.r; }
                else { f.x0 = g.W / 2f; f.y0 = g.H - 250f; }
                f.w0 = CW;
            } else {
                f.x0 = pp[0]; f.y0 = pp[1]; f.w0 = 60f;
            }
            f.x1 = pl3[0]; f.y1 = pl3[1]; f.w1 = pl3[2];
            f.r1 = (rnd.nextFloat() - 0.5f) * 0.7f;
            f.dur = 0.42f;
            flies.add(f);
        } else if (type == UnoEngine.EV_DRAW) {
            if (p != myIdx) {
                for (int i = 0; i < n && i < 5; i++) {
                    Fly f = new Fly();
                    f.code = -1; f.up = false; f.kind = 1;
                    f.x0 = dk[0]; f.y0 = dk[1]; f.w0 = dk[2] * 0.6f;
                    f.x1 = pp[0]; f.y1 = pp[1]; f.w1 = 50f;
                    f.dur = 0.45f; f.delay = i * 0.13f;
                    flies.add(f);
                }
            }
            if (n >= 2) {
                if (p == myIdx) shake(18f);
                ring(pp[0], pp[1], 0xFFFF5252);
                banner("+" + n, 0xFFFF7A6B);
            }
        } else if (type == UnoEngine.EV_SKIP) {
            ring(pp[0], pp[1], 0xFFFF5252);
            banner("LEWAT!", 0xFFFF7A6B);
        } else if (type == UnoEngine.EV_REV) {
            banner("BALIK ARAH", 0xFF6CC2FF);
        } else if (type == UnoEngine.EV_UNO) {
            banner("UNO!", 0xFFFFE066);
            burst(pp[0], pp[1], 0xFFFFE066, 22, 460f);
            ring(pp[0], pp[1], 0xFFFFE066);
        } else if (type == UnoEngine.EV_WIN) {
            overT = 0f; winT = 0f;
            confetti();
        } else if (type == UnoEngine.EV_FINISH) {
            int place = c;
            int pc = place == 1 ? 0xFFFFD83D : place == 2 ? 0xFFCFD8DC : place == 3 ? 0xFFE0A070 : 0xFF7BE07E;
            burst(pp[0], pp[1], pc, 14, 360f);
            ring(pp[0], pp[1], pc);
            banner(place <= 3 ? "JUARA " + place : "SELESAI", pc);
            if (p == myIdx) toast("Kamu selesai di posisi " + place);
            else if (p >= 0 && p < eng.players.size()) toast(eng.players.get(p).name + " selesai di posisi " + place);
        } else if (type == UnoEngine.EV_PASS) {
            if (p != myIdx && p >= 0 && p < eng.players.size()) toast(eng.players.get(p).name + " lewat");
        }
    }

    // ================================================================ kepala pemain (arah kamera)
    /**
     * Kirim arah kamera sendiri (tiap 50 ms saat berubah) dan terima arah pemain lain lewat stream realtime.
     * Stream dibuka saat masuk game multiplayer dan ditutup saat keluar.
     */
    void updateLook(float dt) {
        boolean live = multi && screen == S_GAME && eng != null && w3 != null && roomCode.length() > 0;
        if (!live) {
            if (lookSt != null) { fb.closeStream(lookSt); lookSt = null; }
            if (stateSt != null) { fb.closeStream(stateSt); stateSt = null; }
            if (swapSt != null) { fb.closeStream(swapSt); swapSt = null; }
            swapFromId = null;
            swapWaitId = null;
            remoteLook.clear();
            return;
        }
        if (lookSt == null) {
            lookSt = fb.openStream("uno_rooms/" + roomCode + "/look", new Fb.SCB() {
                public void event(String type, String path, String data) { onLook(type, path, data); }
            });
        }
        if (stateSt == null) {
            // state game lewat stream: perubahan (termasuk pindah kursi) langsung sampai ke semua pemain
            stateSt = fb.openStream("uno_rooms/" + roomCode + "/state", new Fb.SCB() {
                public void event(String type, String path, String data) {
                    if (path.equals("/")) applyRemote(data);
                }
            });
        }
        if (swapSt == null) {
            swapFirst = true;
            swapSt = fb.openStream("uno_rooms/" + roomCode + "/swap", new Fb.SCB() {
                public void event(String type, String path, String data) { onSwap(type, path, data); }
            });
        }
        lookT += dt;
        if (lookT >= 0.05f && !lookSending) {
            float yw = Scene3D.wrapAng(w3.yaw), pt = w3.pitch;
            if (lookForce || Math.abs(Scene3D.wrapAng(yw - lookSentY)) > 0.005f || Math.abs(pt - lookSentP) > 0.005f) {
                lookForce = false;
                lookT = 0f;
                lookSending = true;
                lookSentY = yw;
                lookSentP = pt;
                JSONObject o = new JSONObject();
                try {
                    o.put("y", (double) yw);
                    o.put("p", (double) pt);
                } catch (Exception ignored) { }
                fb.req("PUT", "uno_rooms/" + roomCode + "/look/" + myId, o.toString(), new Fb.CB() {
                    public void done(boolean ok, String b) { lookSending = false; }
                });
            }
        }
    }

    /** Event stream look: "put" di root = seluruh daftar, "put"/"patch" di /ID = satu pemain. */
    void onLook(String type, String path, String data) {
        if (path.equals("/")) {
            if (type.equals("put")) remoteLook.clear();
            if (data != null) parseLook(data);
            return;
        }
        String id = path.startsWith("/") ? path.substring(1) : path;
        if (data == null) { remoteLook.remove(id); return; }
        try {
            JSONObject v = new JSONObject(data);
            remoteLook.put(id, new float[]{(float) v.optDouble("y", 0.0), (float) v.optDouble("p", 0.0)});
        } catch (Exception ignored) { }
    }

    /** Tambah/ubah arah pemain dari objek {id: {y, p}} tanpa menghapus yang lain. */
    void parseLook(String body) {
        try {
            JSONObject o = new JSONObject(body);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject v = o.optJSONObject(k);
                if (v != null) remoteLook.put(k, new float[]{(float) v.optDouble("y", 0.0), (float) v.optDouble("p", 0.0)});
            }
        } catch (Exception ignored) { }
    }

    /** Terapkan arah kepala ke tiap karakter lawan. Bot dan diri sendiri tetap lurus ke meja. */
    void applyLook() {
        if (eng == null || w3 == null) return;
        int n = Math.min(eng.players.size(), Scene3D.CHAIRS);
        for (int i = 0; i < n; i++) {
            UnoEngine.P p = eng.players.get(i);
            float[] l = (multi && !p.bot && i != myIdx) ? remoteLook.get(p.id) : null;
            if (l != null) w3.setLook(i, l[0], l[1]);
            else w3.setLook(i, 0f, 0f);
        }
    }

    // ================================================================ mulai game
    void resetVisuals() {
        pile.clear(); flies.clear(); hc.clear(); pts.clear(); rings.clear();
        modal = 0; selCode = -1; pendingCard = -1; overT = 0f; botT = 0f; handScroll = 0f;
        if (w3 != null) { w3.yaw = 0f; w3.pitch = 0f; }
        statsDone = false; bannerT = 9f; podiumClosed = false; overDelT = 0f; roomGone = false;
        lookForce = true;
    }

    void startSingle() {
        multi = false;
        ArrayList<String> names = new ArrayList<String>();
        for (String s : BOTN) names.add(s);
        Collections.shuffle(names, rnd);
        ArrayList<UnoEngine.P> ps = new ArrayList<UnoEngine.P>();
        UnoEngine.P me = new UnoEngine.P();
        me.id = myId; me.name = myName; me.hat = myHat; me.face = myFace; me.col = myCol;
        me.acc = myAcc; me.body = myBody; me.skin = mySkin;
        ps.add(me);
        for (int i = 0; i < botCount; i++) {
            UnoEngine.P b = new UnoEngine.P();
            b.id = "bot" + i; b.name = names.get(i); b.bot = true;
            b.hat = rnd.nextInt(6); b.col = rnd.nextInt(6); b.face = rnd.nextInt(3) == 0 ? rnd.nextInt(6) : 0;
            ps.add(b);
        }
        UnoEngine e = new UnoEngine();
        e.juara = juaraOn;
        e.newGame(ps);
        eng = e;
        myIdx = 0;
        resetVisuals();
        go(S_GAME);
        for (int[] ev : new ArrayList<int[]>(eng.evs)) handleEvent(ev);
    }

    // ================================================================ multiplayer
    static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    String genCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(CODE_CHARS.charAt(rnd.nextInt(CODE_CHARS.length())));
        return sb.toString();
    }

    void createRoom() {
        if (!fb.configured) { toast("Isi assets/firebase.json dulu"); return; }
        final String code = genCode();
        toast("Membuat room...");
        fb.req("GET", "uno_rooms/" + code + "/meta", null, new Fb.CB() {
            public void done(boolean ok, String b) {
                if (!ok) { toast("Gagal: " + why(b)); return; }
                if (b != null && !b.trim().equals("null")) { createRoom(); return; }
                try {
                    JSONObject room = new JSONObject();
                    JSONObject meta = new JSONObject();
                    meta.put("host", myId);
                    meta.put("status", "lobby");
                    meta.put("t", System.currentTimeMillis());
                    meta.put("juara", juaraOn);
                    meta.put("map", map);
                    JSONObject me = new JSONObject();
                    me.put("name", myName);
                    me.put("hat", myHat);
                    me.put("face", myFace);
                    me.put("col", myCol);
                    me.put("acc", myAcc);
                    me.put("body", myBody);
                    me.put("skin", mySkin);
                    me.put("t", System.currentTimeMillis());
                    JSONObject lb = new JSONObject();
                    lb.put(myId, me);
                    room.put("meta", meta);
                    room.put("lobby", lb);
                    fb.req("PUT", "uno_rooms/" + code, room.toString(), new Fb.CB() {
                        public void done(boolean ok2, String b2) {
                            if (!ok2) { toast("Gagal membuat room: " + why(b2)); return; }
                            roomCode = code;
                            multi = true;
                            isHost = true;
                            lobby = new ArrayList<String[]>();
                            lobby.add(new String[]{myId, myName, "0", String.valueOf(myHat), String.valueOf(myFace), String.valueOf(myCol),
                                    String.valueOf(myAcc), String.valueOf(myBody), String.valueOf(mySkin)});
                            pollT = 0f;
                            go(S_LOBBY);
                        }
                    });
                } catch (Exception ex) {
                    toast("Gagal membuat room");
                }
            }
        });
    }

    void joinRoom(String raw) {
        final String c = raw.trim().toUpperCase(Locale.ROOT);
        if (c.length() == 0) return;
        toast("Menghubungkan...");
        fb.req("GET", "uno_rooms/" + c, null, new Fb.CB() {
            public void done(boolean ok, String b) {
                if (!ok) { toast("Gagal: " + why(b)); return; }
                if (b == null || b.trim().equals("null")) { toast("Room tidak ditemukan"); return; }
                try {
                    JSONObject room = new JSONObject(b);
                    JSONObject meta = room.optJSONObject("meta");
                    if (meta == null || !"lobby".equals(meta.optString("status"))) { toast("Game sudah dimulai"); return; }
                    JSONObject lb = room.optJSONObject("lobby");
                    if (lb != null && lb.length() >= MAXP && !lb.has(myId)) { toast("Room penuh (maks 10)"); return; }
                    JSONObject me = new JSONObject();
                    me.put("name", myName);
                    me.put("hat", myHat);
                    me.put("face", myFace);
                    me.put("col", myCol);
                    me.put("acc", myAcc);
                    me.put("body", myBody);
                    me.put("skin", mySkin);
                    me.put("t", System.currentTimeMillis());
                    fb.req("PUT", "uno_rooms/" + c + "/lobby/" + myId, me.toString(), new Fb.CB() {
                        public void done(boolean ok2, String b2) {
                            if (!ok2) { toast("Gagal bergabung: " + why(b2)); return; }
                            roomCode = c;
                            multi = true;
                            isHost = false;
                            lobby = new ArrayList<String[]>();
                            pollT = 0f;
                            go(S_LOBBY);
                        }
                    });
                } catch (Exception ex) {
                    toast("Data room rusak");
                }
            }
        });
    }

    void handleRoom(String body) {
        if (screen != S_LOBBY) return;
        if (body == null || body.trim().equals("null")) {
            if (!isHost) { toast("Room ditutup oleh host"); multi = false; go(S_MPMENU); }
            return;
        }
        try {
            JSONObject room = new JSONObject(body);
            JSONObject lb = room.optJSONObject("lobby");
            ArrayList<String[]> l = new ArrayList<String[]>();
            if (lb != null) {
                Iterator<String> it = lb.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject o = lb.optJSONObject(k);
                    if (o != null) l.add(new String[]{k, o.optString("name", "?"), String.valueOf(o.optLong("t", 0L)),
                        String.valueOf(o.optInt("hat", 0)), String.valueOf(o.optInt("face", 0)), String.valueOf(o.optInt("col", 0)),
                        String.valueOf(o.optInt("acc", 0)), String.valueOf(o.optInt("body", 0)),
                        String.valueOf(o.optInt("skin", 0))});
                }
            }
            Collections.sort(l, new java.util.Comparator<String[]>() {
                public int compare(String[] a, String[] b) {
                    return Long.compare(Long.parseLong(a[2]), Long.parseLong(b[2]));
                }
            });
            lobby = l;
            for (String[] fs : l) loadFace(fs[0]);
            JSONObject meta = room.optJSONObject("meta");
            if (meta != null && System.currentTimeMillis() - juaraLocalAt > 2500) juaraOn = meta.optBoolean("juara", false);
            if (meta != null && System.currentTimeMillis() - mapLocalAt > 2500) map = Math.max(0, Math.min(MAP_NAMES.length - 1, meta.optInt("map", 0)));
            JSONObject st = room.optJSONObject("state");
            if (st != null && "playing".equals(st.optString("status"))) startMpGame(st);
        } catch (Exception ignored) { }
    }

    void startMpGame(JSONObject st) {
        UnoEngine e = new UnoEngine();
        e.fromJson(st);
        int idx = e.indexOf(myId);
        if (idx < 0) {
            toast("Game sudah dimulai tanpa kamu");
            multi = false;
            go(S_MPMENU);
            return;
        }
        eng = e;
        myIdx = idx;
        for (UnoEngine.P fp : e.players) loadFace(fp.id);
        resetVisuals();
        go(S_GAME);
        for (int[] ev : new ArrayList<int[]>(eng.evs)) handleEvent(ev);
    }

    void hostStart() {
        if (lobby.size() < 2) { toast("Minimal 2 pemain"); return; }
        ArrayList<UnoEngine.P> ps = new ArrayList<UnoEngine.P>();
        for (String[] s : lobby) {
            UnoEngine.P p = new UnoEngine.P();
            p.id = s[0]; p.name = s[1];
            if (s.length >= 6) { p.hat = pint(s[3]); p.face = pint(s[4]); p.col = pint(s[5]); }
            if (s.length >= 8) { p.acc = pint(s[6]); p.body = pint(s[7]); }
            if (s.length >= 9) p.skin = pint(s[8]);
            ps.add(p);
        }
        UnoEngine e = new UnoEngine();
        e.juara = juaraOn;
        e.newGame(ps);
        eng = e;
        myIdx = e.indexOf(myId);
        resetVisuals();
        go(S_GAME);
        for (int[] ev : new ArrayList<int[]>(eng.evs)) handleEvent(ev);
        pushState();
        fb.req("PUT", "uno_rooms/" + roomCode + "/meta/status", "\"playing\"", null);
    }

    void rematch() {
        ArrayList<UnoEngine.P> ps = new ArrayList<UnoEngine.P>();
        for (UnoEngine.P p : eng.players) {
            UnoEngine.P n = new UnoEngine.P();
            n.id = p.id; n.name = p.name; n.bot = p.bot; n.seat = p.seat; n.hat = p.hat; n.face = p.face; n.col = p.col;
            n.acc = p.acc; n.body = p.body; n.skin = p.skin;
            ps.add(n);
        }
        eng.newGame(ps);
        myIdx = eng.indexOf(myId);
        afterAction();
    }

    void pushState() {
        if (!multi || eng == null) return;
        eng.wid = myId + "_" + System.nanoTime();
        writesInFlight++;
        sendState(eng.toJson().toString(), 3);
    }

    void sendState(final String json, final int tries) {
        fb.req("PUT", "uno_rooms/" + roomCode + "/state", json, new Fb.CB() {
            public void done(boolean ok, String b) {
                if (ok) { writesInFlight--; return; }
                if (tries > 1) sendState(json, tries - 1);
                else { writesInFlight--; toast("Gagal kirim: " + why(b)); }
            }
        });
    }

    void applyRemote(String body) {
        if (screen != S_GAME || eng == null) return;
        if (body == null || body.trim().equals("null")) {
            if (multi && !eng.over) { toast("Room ditutup oleh host"); multi = false; go(S_MPMENU); }
            return;
        }
        try {
            JSONObject st = new JSONObject(body);
            int rs = st.optInt("seq", 0);
            String rw = st.optString("wid", "");
            boolean newer = rs > eng.seq || (rs == eng.seq && !rw.equals(eng.wid) && writesInFlight == 0);
            if (!newer) return;
            eng.fromJson(st);
            myIdx = eng.indexOf(myId);
            if (myIdx < 0) {
                toast("Kamu sudah tidak ada di game ini");
                multi = false;
                go(S_MPMENU);
                return;
            }
            for (int[] ev : new ArrayList<int[]>(eng.evs)) handleEvent(ev);
        } catch (Exception ignored) { }
    }

    /** Dipanggil sebelum meninggalkan lobby / game multiplayer. */
    void leaveRoomRemote() {
        if (multi && roomCode.length() > 0) fb.req("DELETE", "uno_rooms/" + roomCode + "/look/" + myId, null, null);
        if (!multi || roomCode.length() == 0) return;
        if (screen == S_LOBBY) {
            if (isHost) fb.req("DELETE", "uno_rooms/" + roomCode, null, null);
            else fb.req("DELETE", "uno_rooms/" + roomCode + "/lobby/" + myId, null, null);
        } else if (eng != null) {
            if (eng.over) {
                if (isHost) fb.req("DELETE", "uno_rooms/" + roomCode, null, null);
            } else if (myIdx >= 0 && myIdx < eng.players.size()) {
                eng.players.get(myIdx).bot = true;
                eng.evs.clear();
                eng.seq++;
                pushState();
            }
        }
    }
}
