package ru.unogame;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Pemotong foto wajah. Gambar utuh tampil di kotak persegi; bisa digeser dan di-zoom.
 * Di luar kotak digelapkan. Hasilnya PNG 256px dengan transparansi asli.
 */
public class CropActivity extends Activity {
    static final String EXTRA_URI = "uri";
    static final String EXTRA_PATH = "path";
    private static final int OUT = 256;
    private static final int MAX_SIDE = 1024;

    private Bitmap src;
    private CropView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = getIntent().getParcelableExtra(EXTRA_URI);
        try {
            src = decodeLimited(uri);
        } catch (IOException | RuntimeException e) {
            src = null;
        }
        if (src == null) {
            Toast.makeText(this, "Foto tidak bisa dibuka", Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0D24);
        view = new CropView(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        Button ok = new Button(this);
        ok.setText("Pakai Foto Ini");
        ok.setTextColor(0xFFFFFFFF);
        ok.setBackgroundColor(0xFF1565C0);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(36);
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finishCrop(); }
        });
        root.addView(ok, lp);
        setContentView(root);
    }

    /** Decode gambar, diperkecil bila terlalu besar supaya tidak kehabisan memori. */
    private Bitmap decodeLimited(Uri uri) throws IOException {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("tidak bisa membuka");
        BitmapFactory.decodeStream(in, null, probe);
        in.close();
        int max = Math.max(probe.outWidth, probe.outHeight), sample = 1;
        while (max / sample > MAX_SIDE) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("tidak bisa membuka");
        Bitmap b = BitmapFactory.decodeStream(in, null, o);
        in.close();
        return b;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void finishCrop() {
        try {
            Bitmap out = view.render(OUT);
            File f = new File(getCacheDir(), "face_crop.png");
            FileOutputStream fo = new FileOutputStream(f);
            out.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            out.recycle();
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_PATH, f.getAbsolutePath()));
            finish();
        } catch (IOException e) {
            Toast.makeText(this, "Foto gagal disimpan", Toast.LENGTH_SHORT).show();
        }
    }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Kanvas pemotong: gambar dengan transformasi (scale, tx, ty) dan kotak persegi di tengah. */
    class CropView extends View {
        private float scale, tx, ty;
        private float frame, frameX, frameY;
        private float lastX, lastY;
        private boolean dragging;
        private final ScaleGestureDetector sgd;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint dim = new Paint();
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        CropView(Context c) {
            super(c);
            dim.setColor(0xB0000000);
            line.setColor(0xFFFFFFFF);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                public boolean onScale(ScaleGestureDetector d) {
                    float ns = clamp(scale * d.getScaleFactor(), minScale(), 8f);
                    float k = ns / scale;
                    float fx = d.getFocusX(), fy = d.getFocusY();
                    tx = fx - (fx - tx) * k;
                    ty = fy - (fy - ty) * k;
                    scale = ns;
                    invalidate();
                    return true;
                }
            });
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            frame = Math.min(w, h) * 0.86f;
            frameX = (w - frame) / 2f;
            frameY = (h - frame) / 2f;
            // awal: seluruh gambar muat di dalam kotak
            scale = frame / Math.max(src.getWidth(), src.getHeight());
            tx = frameX + (frame - src.getWidth() * scale) / 2f;
            ty = frameY + (frame - src.getHeight() * scale) / 2f;
        }

        private float minScale() { return frame / Math.max(src.getWidth(), src.getHeight()) * 0.5f; }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            sgd.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX(); lastY = e.getY(); dragging = true; break;
                case MotionEvent.ACTION_MOVE:
                    if (dragging && !sgd.isInProgress()) {
                        tx += e.getX() - lastX;
                        ty += e.getY() - lastY;
                        lastX = e.getX(); lastY = e.getY();
                        invalidate();
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false; break;
                default: break;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(0xFF0A0D24);
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postTranslate(tx, ty);
            c.drawBitmap(src, m, paint);
            // gelapkan area di luar kotak
            c.drawRect(0, 0, getWidth(), frameY, dim);
            c.drawRect(0, frameY + frame, getWidth(), getHeight(), dim);
            c.drawRect(0, frameY, frameX, frameY + frame, dim);
            c.drawRect(frameX + frame, frameY, getWidth(), frameY + frame, dim);
            c.drawRect(frameX, frameY, frameX + frame, frameY + frame, line);
        }

        /** Potong kotak dan ubah ukurannya ke px x px. Bagian di luar gambar tetap transparan. */
        Bitmap render(int px) {
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            float k = px / frame;
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postTranslate(tx - frameX, ty - frameY);
            m.postScale(k, k);
            c.drawBitmap(src, m, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            return b;
        }
    }
}
