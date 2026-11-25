package ru.unogame;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/**
 * Audio dari assets. Efek kartu di assets/sfx; stasiun radio di assets/radio (lofi.ogg = default).
 * Musik hanya berbunyi saat game berjalan (setMap(-1) = senyap). Volume musik dan efek bisa diatur.
 */
public class Sfx {
    private static final String TAG = "UnoSfx";
    /** Tiga variasi tiap efek kartu, dipilih acak supaya tidak terdengar seperti rekaman yang diulang. */
    private static final String[] FX_PLACE = {"sfx/card_place1.ogg", "sfx/card_place2.ogg", "sfx/card_place3.ogg"};
    private static final String[] FX_DRAW = {"sfx/card_draw1.ogg", "sfx/card_draw2.ogg", "sfx/card_draw3.ogg"};
    private static final String RADIO_DIR = "radio";
    private static final String DEFAULT_STATION = "lofi.ogg";

    /** Musik latar map luar ruangan: 1 = padang rumput, 2 = pantai. Map 0 memakai radio. */
    private static final String[] BGM = {null, "sfx/bgm_grass.ogg", "sfx/bgm_beach.ogg"};
    private static final float[] BGM_VOL = {0.3f, 0.42f, 0.4f};

    private final Context app;
    private final SoundPool pool;
    private final HashSet<Integer> ready = new HashSet<Integer>();
    private final int[] sPlace = new int[FX_PLACE.length], sDraw = new int[FX_DRAW.length];
    private final java.util.Random fxRnd = new java.util.Random();
    private int lastPlace = -1, lastDraw = -1;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<String> stations = new ArrayList<String>();
    private int station;

    /** Volume 0..1 yang dipilih pemain. */
    private float musicVol = 0.8f, sfxVol = 1f;

    private MediaPlayer amb;
    private boolean ambReady;
    private float ambBase;
    private int curMap = -1;
    private boolean wantPlay;
    private int previewId;

    public Sfx(Context c) {
        app = c.getApplicationContext();
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(aa).build();
        pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            public void onLoadComplete(SoundPool sp, int id, int status) {
                if (status == 0) ready.add(id);
                else fail("Efek suara gagal dimuat", null);
            }
        });
        for (int i = 0; i < FX_PLACE.length; i++) sPlace[i] = loadFx(FX_PLACE[i]);
        for (int i = 0; i < FX_DRAW.length; i++) sDraw[i] = loadFx(FX_DRAW[i]);
        listStations();
    }

    /** Daftar stasiun dari assets/radio. lofi.ogg selalu di urutan pertama. */
    private void listStations() {
        try {
            String[] names = app.getAssets().list(RADIO_DIR);
            if (names != null) for (String n : names) {
                String l = n.toLowerCase();
                if (l.endsWith(".ogg") || l.endsWith(".mp3") || l.endsWith(".wav") || l.endsWith(".m4a")) stations.add(n);
            }
        } catch (IOException e) {
            fail("Daftar radio tidak bisa dibaca", e);
        }
        Collections.sort(stations);
        int li = stations.indexOf(DEFAULT_STATION);
        if (li > 0) { stations.remove(li); stations.add(0, DEFAULT_STATION); }
        if (stations.isEmpty()) stations.add(DEFAULT_STATION);
    }

    public String stationName() {
        String n = stations.get(station);
        return n.substring(0, n.lastIndexOf('.')).replace('_', ' ');
    }

    /** Pindah stasiun. Musik berganti hanya kalau sedang di ruang gelap saat bermain. */
    public String nextStation() {
        station = (station + 1) % stations.size();
        if (curMap == 0) playAsset("radio/" + stations.get(station), BGM_VOL[0], "Radio " + stationName());
        return stationName();
    }

    /** Volume musik dan efek, 0..1. Berlaku langsung untuk musik yang sedang berbunyi. */
    public void setVolumes(float music, float sfx) {
        musicVol = music;
        sfxVol = sfx;
        if (amb != null && ambReady) {
            try { amb.setVolume(ambBase * musicVol, ambBase * musicVol); } catch (IllegalStateException ignored) { }
        }
    }

    /** Contoh musik: stasiun aktif, 6 detik, boleh dipakai di layar pengaturan. */
    public void previewMusic() {
        curMap = -1;
        playAsset("radio/" + stations.get(station), 0.5f, null);
        final int id = previewId;
        ui.postDelayed(new Runnable() {
            public void run() { if (id == previewId && curMap < 0) releaseAmb(); }
        }, 6000);
    }

    /** Contoh efek: suara taruh kartu lalu ambil kartu. */
    public void previewSfx() {
        if (!anyReady(sPlace)) { info("Efek suara belum siap"); return; }
        place();
        ui.postDelayed(new Runnable() {
            public void run() { draw(); }
        }, 450);
    }

    private boolean anyReady(int[] ids) {
        for (int id : ids) if (id != 0 && ready.contains(id)) return true;
        return false;
    }

    /** Pilih satu variasi yang sudah siap, tidak sama dengan yang terakhir dipakai. Mengembalikan indeks atau -1. */
    private int pick(int[] ids, int last) {
        int n = ids.length, start = fxRnd.nextInt(n);
        for (int k = 0; k < n; k++) {
            int i = (start + k) % n;
            if (i != last && ids[i] != 0 && ready.contains(ids[i])) return i;
        }
        return (last >= 0 && ids[last] != 0 && ready.contains(ids[last])) ? last : -1;
    }

    /** Salin aset ke cache. Dipanggil setiap mulai supaya selalu sesuai isi APK. */
    private File stage(String asset) throws IOException {
        File out = new File(app.getCacheDir(), asset.replace('/', '_'));
        InputStream in = app.getAssets().open(asset);
        try {
            FileOutputStream fo = new FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            } finally {
                fo.close();
            }
        } finally {
            in.close();
        }
        return out;
    }

    private void fail(String msg, Throwable e) {
        Log.e(TAG, msg, e);
        final String text = msg + (e != null && e.getMessage() != null ? ": " + e.getMessage() : "");
        ui.post(new Runnable() {
            public void run() { Toast.makeText(app, "Audio: " + text, Toast.LENGTH_LONG).show(); }
        });
    }

    private void info(final String msg) {
        ui.post(new Runnable() {
            public void run() { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show(); }
        });
    }

    private int loadFx(String asset) {
        try {
            return pool.load(stage(asset).getAbsolutePath(), 1);
        } catch (IOException e) {
            fail("Tidak bisa membaca " + asset, e);
            return 0;
        }
    }

    /** Taruh kartu: variasi acak, volume dan pitch sedikit berbeda tiap kali. */
    public void place() {
        int i = pick(sPlace, lastPlace);
        if (i < 0) return;
        lastPlace = i;
        float v = sfxVol * (0.8f + 0.15f * fxRnd.nextFloat());
        pool.play(sPlace[i], v, v, 1, 0, 0.94f + 0.12f * fxRnd.nextFloat());
    }

    /** Ambil kartu dari dek. */
    public void draw() {
        int i = pick(sDraw, lastDraw);
        if (i < 0) return;
        lastDraw = i;
        float v = sfxVol * (0.8f + 0.15f * fxRnd.nextFloat());
        pool.play(sDraw[i], v, v, 1, 0, 0.95f + 0.1f * fxRnd.nextFloat());
    }

    /**
     * Musik sesuai map yang sedang dimainkan. map = -1 berarti senyap (lobi, menu, pemilihan map).
     * Map 0 memakai stasiun radio, map lain memakai musik latar map itu.
     */
    public void setMap(int map) {
        if (map == curMap) return;
        curMap = map;
        releaseAmb();
        if (map < 0 || map >= BGM.length) return;
        if (map == 0) playAsset("radio/" + stations.get(station), BGM_VOL[0], "Radio " + stationName());
        else playAsset(BGM[map], BGM_VOL[map], "Musik " + (map == 1 ? "padang rumput" : "pantai"));
    }

    /** Putar satu aset sebagai musik loop. label null = tanpa toast. */
    private void playAsset(String asset, float vol, final String label) {
        releaseAmb();
        previewId++;
        ambBase = vol;
        final MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(stage(asset).getAbsolutePath());
            mp.setLooping(true);
            mp.setVolume(vol * musicVol, vol * musicVol);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer p) {
                    if (p != amb) return;
                    ambReady = true;
                    if (wantPlay) {
                        p.start();
                        if (label != null) info(label + " diputar");
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer p, int what, int extra) {
                    fail("Musik gagal diputar", new IOException("kode " + what + "/" + extra));
                    return true;
                }
            });
            amb = mp;
            mp.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            fail("Musik tidak bisa dimuat", e);
            mp.release();
            amb = null;
        }
    }

    private void releaseAmb() {
        if (amb != null) {
            try { amb.stop(); } catch (Exception ignored) { }
            amb.release();
        }
        amb = null;
        ambReady = false;
    }

    /** Aktivitas kembali: putar ulang musik map yang aktif, kalau ada. Senyap tetap senyap. */
    public void resume() {
        wantPlay = true;
        if (amb == null && curMap >= 0) {
            int m = curMap;
            curMap = -1;
            setMap(m);
        } else if (amb != null && ambReady && !amb.isPlaying() && curMap >= 0) {
            amb.start();
        }
    }

    public void pause() {
        wantPlay = false;
        if (amb != null && ambReady && amb.isPlaying()) amb.pause();
    }
}
