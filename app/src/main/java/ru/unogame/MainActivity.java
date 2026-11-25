package ru.unogame;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

public class MainActivity extends Activity {

    public interface TextCb { void ok(String s); }

    /** Perangkat RAM kecil (<= 1.5 GB): framebuffer 16-bit dan batas 30 FPS supaya ringan. */
    private static final int LOW_RAM_FPS = 30;
    /** Perangkat dengan RAM <= 3 GB memakai mode ringan. */
    private static final long LOW_RAM_BYTES = 3L * 1024 * 1024 * 1024;

    private GLSurfaceView gl;
    private GameRenderer rr;
    private boolean lowRam;
    Sfx sfx;
    private final Random rnd = new Random();
    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable pump = new Runnable() {
        public void run() {
            if (gl != null) gl.requestRender();
            // layar game 30 FPS, menu 60 FPS: lebih hemat GPU, baterai, dan panas
            // semua layar 30 FPS di perangkat RAM kecil; di perangkat biasa menu 60 FPS
            tick.postDelayed(this, (rr != null && rr.inGame()) || lowRam ? 1000 / LOW_RAM_FPS : 16);
        }
    };

    /** Hanya huruf dan angka, huruf kecil diubah jadi kapital (untuk kode room). */
    private static final InputFilter CODE_FILTER = new InputFilter() {
        public CharSequence filter(CharSequence src, int start, int end, Spanned dst, int dstart, int dend) {
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                char c = Character.toUpperCase(src.charAt(i));
                if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) sb.append(c);
            }
            return sb.toString();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        lowRam = isLowRam();
        Scene3D.LITE = lowRam;
        sfx = new Sfx(this);
        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        if (lowRam) gl.setEGLConfigChooser(5, 6, 5, 0, 16, 0); // RGB565: buffer warna separuh ukuran
        else gl.setEGLConfigChooser(8, 8, 8, 0, 16, 0);
        gl.setPreserveEGLContextOnPause(true);
        rr = new GameRenderer(this, gl);
        gl.setRenderer(rr);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY); // frame diatur oleh pump di bawah
        // grafis tidak terlalu HD: buffer gambar dikecilkan, sisanya diregangkan oleh sistem
        gl.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or2, int ob) {
                applyRenderScale(r - l, b - t);
            }
        });
        gl.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                final int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE
                        || a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    // koordinat layar -> koordinat buffer gambar (berbeda kalau resolusi dikecilkan)
                    final float x = e.getX() * rr.surfW / (float) Math.max(1, gl.getWidth());
                    final float y = e.getY() * rr.surfH / (float) Math.max(1, gl.getHeight());
                    gl.queueEvent(new Runnable() {
                        public void run() { rr.touch(a, x, y); }
                    });
                }
                return true;
            }
        });
        setContentView(gl, new FrameLayout.LayoutParams(-1, -1));
    }

    private int fixW, fixH;

    /** Sisi pendek buffer gambar: 480 px di RAM kecil, 720 px di perangkat biasa. */
    private void applyRenderScale(int w, int h) {
        if (w <= 0 || h <= 0) return;
        int cap = lowRam ? 480 : 720;
        int shortSide = Math.min(w, h);
        if (shortSide <= cap) {
            if (fixW != 0) { gl.getHolder().setSizeFromLayout(); fixW = 0; fixH = 0; }
            return;
        }
        float k = cap / (float) shortSide;
        int tw = Math.max(2, Math.round(w * k)), th = Math.max(2, Math.round(h * k));
        if (tw == fixW && th == fixH) return;
        fixW = tw;
        fixH = th;
        gl.getHolder().setFixedSize(tw, th);
    }

    private static final int REQ_FACE = 41, REQ_CROP = 42;

    /** Buka galeri untuk memilih foto wajah. */
    void pickFace() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(i, REQ_FACE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_FACE && data.getData() != null) {
            // foto dari galeri -> layar potong (gambar utuh, bisa digeser dan di-zoom)
            startActivityForResult(new Intent(this, CropActivity.class)
                    .putExtra(CropActivity.EXTRA_URI, data.getData()), REQ_CROP);
        } else if (req == REQ_CROP) {
            final String path = data.getStringExtra(CropActivity.EXTRA_PATH);
            if (path == null) return;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        java.io.File f = new java.io.File(path);
                        byte[] bytes = new byte[(int) f.length()];
                        java.io.FileInputStream fi = new java.io.FileInputStream(f);
                        int off = 0;
                        while (off < bytes.length) {
                            int n = fi.read(bytes, off, bytes.length - off);
                            if (n < 0) break;
                            off += n;
                        }
                        fi.close();
                        final String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                        gl.queueEvent(new Runnable() {
                            public void run() { rr.setFace(b64); }
                        });
                    } catch (IOException e) {
                        runOnUiThread(new Runnable() {
                            public void run() { Toast.makeText(MainActivity.this, "Foto gagal dibaca", Toast.LENGTH_SHORT).show(); }
                        });
                    }
                }
            }).start();
        }
    }

    private boolean isLowRam() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            if (am.isLowRamDevice()) return true;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem <= LOW_RAM_BYTES;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        gl.onResume();
        hideBars();
        tick.removeCallbacks(pump);
        tick.post(pump);
        if (sfx != null) sfx.resume();
    }

    @Override
    protected void onPause() {
        tick.removeCallbacks(pump);
        if (sfx != null) sfx.pause();
        gl.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ================================================================ dialog input (keyboard sistem)

    /**
     * Dialog input memakai keyboard sistem. Field di kiri, tombol Acak di kanan field (kalau randomBtn),
     * lalu tombol Batal dan Simpan di bawah. Hasil Simpan dikirim ke thread GL.
     */
    void showTextDialog(final String title, final String hint, final String init, final int max,
                        final boolean code, final boolean randomBtn, final TextCb cb) {
        runOnUiThread(new Runnable() {
            public void run() { buildTextDialog(title, hint, init, max, code, randomBtn, cb); }
        });
    }

    /** Kotak bergradasi dengan garis tipis, sama seperti panel di game. */
    private static GradientDrawable shape(int c1, int c2, float radius, int stroke, int strokeW) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{c1, c2});
        d.setCornerRadius(radius);
        if (strokeW > 0) d.setStroke(strokeW, stroke);
        return d;
    }

    /** Tombol bergaya tombol game: sudut bulat, gradasi, teks tebal tanpa huruf kapital semua. */
    private void styleBtn(Button b, int c1, int c2, float dp) {
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setBackground(shape(c1, c2, 18 * dp, 0x60FFFFFF, (int) (2 * dp)));
        b.setPadding((int) (16 * dp), (int) (12 * dp), (int) (16 * dp), (int) (12 * dp));
        b.setStateListAnimator(null);
    }

    private void buildTextDialog(String title, String hint, String init, int max,
                                 boolean code, boolean randomBtn, final TextCb cb) {
        final float dp = getResources().getDisplayMetrics().density;
        final int pad = (int) (22 * dp), gap = (int) (10 * dp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(shape(0xF22A2F5C, 0xF2171A3A, 30 * dp, 0x40FFFFFF, (int) (2 * dp)));

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tv);

        View accent = new View(this);
        accent.setBackground(shape(0xFF4BA3FF, 0xFF1565C0, 2 * dp, 0, 0));
        LinearLayout.LayoutParams accLp = new LinearLayout.LayoutParams((int) (56 * dp), (int) (4 * dp));
        accLp.topMargin = gap / 2;
        root.addView(accent, accLp);

        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint(hint);
        et.setHintTextColor(0x80FFFFFF);
        et.setTextColor(0xFFFFFFFF);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        et.setBackgroundColor(Color.TRANSPARENT);
        et.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
        et.setText(init);
        et.setSelection(et.getText().length());
        et.setInputType(InputType.TYPE_CLASS_TEXT
                | (code ? InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS : InputType.TYPE_TEXT_FLAG_CAP_WORDS));
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setFilters(code
                ? new InputFilter[]{CODE_FILTER, new InputFilter.LengthFilter(max)}
                : new InputFilter[]{new InputFilter.LengthFilter(max)});
        row.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
        if (randomBtn) {
            Button acak = new Button(this);
            acak.setText("Acak");
            styleBtn(acak, 0xFFFFC93C, 0xFFE08A00, dp);
            acak.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    et.setText(GameRenderer.randomName(rnd));
                    et.setSelection(et.getText().length());
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = gap;
            row.addView(acak, lp);
        }
        field.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View underline = new View(this);
        underline.setBackgroundColor(0xFF4BA3FF);
        field.addView(underline, new LinearLayout.LayoutParams(-1, (int) (3 * dp)));
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, -2);
        fieldLp.topMargin = gap * 2;
        root.addView(field, fieldLp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        final Button batal = new Button(this);
        batal.setText("Batal");
        styleBtn(batal, 0xFF6A6F8F, 0xFF4A4F6F, dp);
        final Button simpan = new Button(this);
        simpan.setText("Simpan");
        styleBtn(simpan, 0xFF66D96B, 0xFF2E9E3A, dp);
        LinearLayout.LayoutParams lpBatal = new LinearLayout.LayoutParams(0, -2, 1f);
        lpBatal.rightMargin = gap / 2;
        LinearLayout.LayoutParams lpSimpan = new LinearLayout.LayoutParams(0, -2, 1f);
        lpSimpan.leftMargin = gap / 2;
        btns.addView(batal, lpBatal);
        btns.addView(simpan, lpSimpan);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(-1, -2);
        btnsLp.topMargin = gap * 2;
        root.addView(btns, btnsLp);

        final AlertDialog dlg = new AlertDialog.Builder(this).setView(root).create();
        dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dlg.getWindow().setDimAmount(0.7f);
        batal.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        simpan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String val = et.getText().toString().trim();
                if (val.length() == 0) { et.setError("Isi dulu"); return; }
                dlg.dismiss();
                gl.queueEvent(new Runnable() {
                    public void run() { cb.ok(val); }
                });
            }
        });
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) { simpan.performClick(); return true; }
                return false;
            }
        });
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
        et.requestFocus();
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        et.postDelayed(new Runnable() {
            public void run() { if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT); }
        }, 150);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        gl.queueEvent(new Runnable() {
            public void run() {
                if (!rr.back()) {
                    runOnUiThread(new Runnable() { public void run() { finish(); } });
                }
            }
        });
    }
}
