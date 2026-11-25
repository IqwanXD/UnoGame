package ru.unogame;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;

/**
 * Lapisan gambar OpenGL ES 2.0 (API Java bawaan Android).
 * Semua bentuk (kartu, tombol, lingkaran, glow, bayangan) digambar dengan satu shader SDF,
 * teks dan ikon XML drawable di-render ke tekstur lalu diwarnai di shader.
 * Koordinat virtual: lebar 1000, tinggi mengikuti rasio layar.
 */
public class Gfx {

    public static class Tex { public int id; public float w, h; }

    public float W = 1000f, H = 1800f, scale = 1f, ox, oy, time, padX;
    /** true = layar game (tinggi virtual 1100 saat landscape), false = menu (1800). */
    public boolean gameLayout;
    private final Context ctx;
    private int prog, aPos, uRect, uRes, uRot, uSize, uMode, uC1, uC2, uC3, uBC, uRad, uBorder, uSoft, uTime, uTex;
    private FloatBuffer vb;
    private int pxW = 1, pxH = 1;
    private final HashMap<String, Tex> texts = new HashMap<String, Tex>();
    /** Bitmap yang dipakai sebagai gambar halus (mis. color picker), disimpan per kunci. */
    private final HashMap<String, Tex> pics = new HashMap<String, Tex>();
    private final HashMap<Integer, Tex> icons = new HashMap<Integer, Tex>();
    /** Anggaran memori tekstur teks (byte). Lewati batas = cache dikosongkan dan dibuat ulang saat perlu. */
    private long texBytes;
    private static final long TEX_BUDGET = 10L * 1024 * 1024;

    private static final String VS =
            "attribute vec2 aPos;\n" +
            "uniform vec4 uRect;\n" +
            "uniform vec2 uRes;\n" +
            "uniform float uRot;\n" +
            "varying vec2 vLocal;\n" +
            "varying vec2 vUV;\n" +
            "void main(){\n" +
            "  vec2 l=(aPos-0.5)*uRect.zw;\n" +
            "  vLocal=l; vUV=aPos;\n" +
            "  float c=cos(uRot); float s=sin(uRot);\n" +
            "  vec2 p=vec2(l.x*c-l.y*s, l.x*s+l.y*c)+uRect.xy;\n" +
            "  gl_Position=vec4(p.x/uRes.x*2.0-1.0, 1.0-p.y/uRes.y*2.0, 0.0, 1.0);\n" +
            "}\n";

    private static final String FS =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec2 vLocal;\n" +
            "varying vec2 vUV;\n" +
            "uniform vec2 uSize;\n" +
            "uniform int uMode;\n" +
            "uniform vec4 uC1;\n" +
            "uniform vec4 uC2;\n" +
            "uniform vec4 uC3;\n" +
            "uniform vec4 uBC;\n" +
            "uniform float uRad;\n" +
            "uniform float uBorder;\n" +
            "uniform float uSoft;\n" +
            "uniform float uTime;\n" +
            "uniform sampler2D uTex;\n" +
            "void main(){\n" +
            "  if(uMode==1){\n" +
            "    float ta=texture2D(uTex,vUV).a;\n" +
            "    gl_FragColor=vec4(uC1.rgb,ta*uC1.a);\n" +
            "    return;\n" +
            "  }\n" +
            "  if(uMode==6){ gl_FragColor=texture2D(uTex,vUV)*vec4(uC1.rgb,uC1.a); return; }\n" +
            "  float d;\n" +
            "  if(uMode==0){\n" +
            "    vec2 q=abs(vLocal)-uSize*0.5+uRad;\n" +
            "    d=length(max(q,0.0))+min(max(q.x,q.y),0.0)-uRad;\n" +
            "  } else {\n" +
            "    vec2 h=uSize*0.5;\n" +
            "    d=(length(vLocal/h)-1.0)*min(h.x,h.y);\n" +
            "  }\n" +
            "  float a=1.0-smoothstep(-uSoft,uSoft,d);\n" +
            "  vec4 col;\n" +
            "  if(uMode==3){\n" +
            "    if(vLocal.y<0.0){ col=(vLocal.x<0.0)?uC1:uC2; } else { col=(vLocal.x<0.0)?uC3:uBC; }\n" +
            "  } else {\n" +
            "    float gy=clamp(vLocal.y/uSize.y+0.5,0.0,1.0);\n" +
            "    col=mix(uC1,uC2,gy);\n" +
            "  }\n" +
            "  if(uBorder>0.0){\n" +
            "    float bf=smoothstep(-uBorder-uSoft,-uBorder+uSoft,d);\n" +
            "    col=mix(col,uBC,bf);\n" +
            "  }\n" +
            "  gl_FragColor=vec4(col.rgb,col.a*a);\n" +
            "}\n";

    public Gfx(Context c) { ctx = c; }

    private int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e("UnoGL", GLES20.glGetShaderInfoLog(s));
        return s;
    }

    public void init() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e("UnoGL", GLES20.glGetProgramInfoLog(prog));
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        uRect = GLES20.glGetUniformLocation(prog, "uRect");
        uRes = GLES20.glGetUniformLocation(prog, "uRes");
        uRot = GLES20.glGetUniformLocation(prog, "uRot");
        uSize = GLES20.glGetUniformLocation(prog, "uSize");
        uMode = GLES20.glGetUniformLocation(prog, "uMode");
        uC1 = GLES20.glGetUniformLocation(prog, "uC1");
        uC2 = GLES20.glGetUniformLocation(prog, "uC2");
        uC3 = GLES20.glGetUniformLocation(prog, "uC3");
        uBC = GLES20.glGetUniformLocation(prog, "uBC");
        uRad = GLES20.glGetUniformLocation(prog, "uRad");
        uBorder = GLES20.glGetUniformLocation(prog, "uBorder");
        uSoft = GLES20.glGetUniformLocation(prog, "uSoft");
        uTime = GLES20.glGetUniformLocation(prog, "uTime");
        uTex = GLES20.glGetUniformLocation(prog, "uTex");
        ByteBuffer bb = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder());
        vb = bb.asFloatBuffer();
        vb.put(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
        vb.position(0);
        texts.clear();
        pics.clear();
        icons.clear();
    }

    public void setSize(int w, int h) {
        pxW = w; pxH = h;
        layout();
    }

    /** Portrait: lebar virtual 1000. Landscape: kolom 1000 di tengah layar, tinggi virtual tetap. */
    public void layout() {
        float ns, nh, np = 0f;
        if (pxW <= pxH) {
            ns = pxW / 1000f;
            nh = pxH / ns;
        } else {
            nh = gameLayout ? 1100f : 1800f;
            ns = pxH / nh;
            np = (pxW / ns - 1000f) / 2f;
        }
        if (Math.abs(ns - scale) > 1e-4f) clearTexts();
        scale = ns; H = nh; padX = np;
    }

    /** Lebar penuh layar (virtual) untuk lapisan peredup. */
    public float fw() { return pxW / scale + 40f; }

    private void clearTexts() { flushTexts(); }

    private void flushTexts() {
        if (!texts.isEmpty()) {
            int[] ids = new int[texts.size()];
            int i = 0;
            for (Tex x : texts.values()) ids[i++] = x.id;
            GLES20.glDeleteTextures(ids.length, ids, 0);
            texts.clear();
        pics.clear();
        }
        texBytes = 0;
    }

    public void begin(boolean clear) {
        GLES20.glViewport(0, 0, pxW, pxH);
        if (clear) {
            GLES20.glClearColor(BG, BG, BG, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(prog);
        GLES20.glUniform2f(uRes, pxW / scale, H);
        GLES20.glUniform1f(uTime, time);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        vb.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vb);
    }

    public void additive(boolean on) {
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, on ? GLES20.GL_ONE : GLES20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void setCol(int loc, int argb, float alpha) {
        GLES20.glUniform4f(loc, ((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f,
                (argb & 255) / 255f, ((argb >>> 24) / 255f) * alpha);
    }

    private void quad(int mode, float cx, float cy, float w, float h, float pad, float rot,
                      int c1, int c2, int bc, int c3, float rad, float border, float soft,
                      float alpha, int tex) {
        GLES20.glUniform4f(uRect, cx + ox + padX, cy + oy, w + pad * 2f, h + pad * 2f);
        GLES20.glUniform2f(uSize, w, h);
        GLES20.glUniform1f(uRot, rot);
        GLES20.glUniform1i(uMode, mode);
        setCol(uC1, c1, alpha);
        setCol(uC2, c2, alpha);
        setCol(uC3, c3, alpha);
        setCol(uBC, bc, alpha);
        GLES20.glUniform1f(uRad, Math.min(rad, Math.min(w, h) * 0.5f));
        GLES20.glUniform1f(uBorder, border);
        GLES20.glUniform1f(uSoft, soft);
        if (tex != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glUniform1i(uTex, 0);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** Unggah bitmap sebagai tekstur dengan kunci ini (mengganti tekstur lama). */
    public void setPicture(String key, Bitmap b) {
        Tex old = pics.get(key);
        if (old != null) GLES20.glDeleteTextures(1, new int[]{old.id}, 0);
        pics.put(key, makeTex(b));
    }

    public boolean hasPicture(String key) { return pics.containsKey(key); }

    /** Gambar tekstur yang sudah diunggah: pusat (cx, cy), ukuran w x h, alpha a. */
    public void drawPicture(String key, float cx, float cy, float w, float h, float a) {
        Tex t = pics.get(key);
        if (t == null) return;
        quad(6, cx, cy, w, h, 0, 0, 0xFFFFFFFF, 0, 0, 0, 0, 0, 0, a, t.id);
    }

    // ------------------------------------------------------------ primitif
    /** Latar menu: warna solid abu-abu #212121 (layar sudah dibersihkan ke warna ini di begin(true)). */
    public static final float BG = 33f / 255f;

    public void bg() { }

    public void rr(float cx, float cy, float w, float h, float rad, int c1, int c2, float rot, float a) {
        quad(0, cx, cy, w, h, 3f, rot, c1, c2, 0, 0, rad, 0f, 1.2f, a, 0);
    }

    public void rrb(float cx, float cy, float w, float h, float rad, int c1, int c2,
                    int bc, float bw, float rot, float a) {
        quad(0, cx, cy, w, h, 3f, rot, c1, c2, bc, 0, rad, bw, 1.2f, a, 0);
    }

    public void ell(float cx, float cy, float w, float h, int c1, int c2, float rot, float a) {
        quad(5, cx, cy, w, h, 3f, rot, c1, c2, 0, 0, 0, 0f, 1.2f, a, 0);
    }

    /** Elips empat warna (kartu wild). Urutan: kiri-atas, kanan-atas, kanan-bawah, kiri-bawah. */
    public void ell4(float cx, float cy, float w, float h, float rot, float a,
                     int tl, int tr, int br, int bl) {
        quad(3, cx, cy, w, h, 3f, rot, tl, tr, br, bl, 0, 0f, 1.2f, a, 0);
    }

    public void glow(float cx, float cy, float r, int color, float a) {
        quad(5, cx, cy, r * 2f, r * 2f, r * 0.8f, 0, color, color, 0, 0, 0, 0f, r * 0.6f, a, 0);
    }

    public void shadow(float cx, float cy, float w, float h, float rad, float soft, float a) {
        quad(0, cx, cy, w, h, soft * 2f, 0, 0xFF000000, 0xFF000000, 0, 0, rad, 0f, soft, a, 0);
    }

    // ------------------------------------------------------------ tekstur
    private Tex makeTex(Bitmap b) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        b.recycle();
        Tex t = new Tex();
        t.id = id[0];
        return t;
    }

    private Tex textTex(String s, float size, boolean bold) {
        String key = s + "|" + Math.round(size) + "|" + (bold ? 1 : 0);
        Tex t = texts.get(key);
        if (t != null) return t;
        if (texts.size() > 200 || texBytes > TEX_BUDGET) flushTexts();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(Math.max(4f, size * scale));
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        p.setColor(0xFFFFFFFF);
        Paint.FontMetrics fm = p.getFontMetrics();
        int bw = (int) Math.ceil(p.measureText(s)) + 8;
        int bh = (int) Math.ceil(fm.descent - fm.ascent) + 8;
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        texBytes += (long) bw * bh * 4;
        Canvas c = new Canvas(b);
        c.drawText(s, 4, 4 - fm.ascent, p);
        t = makeTex(b);
        t.w = bw / scale;
        t.h = bh / scale;
        texts.put(key, t);
        return t;
    }

    public float textW(String s, float size, boolean bold) {
        return textTex(s, size, bold).w - 8f / scale;
    }

    /** Teks rata tengah pada (cx, cy). */
    public void text(String s, float cx, float cy, float size, int color, boolean bold, float a, float rot) {
        if (s == null || s.length() == 0) return;
        Tex t = textTex(s, size, bold);
        quad(1, cx, cy + size * 0.03f, t.w, t.h, 0, rot, color, 0, 0, 0, 0, 0, 0, a, t.id);
    }

    /** Teks dengan skala: bitmap dibuat sekali di ukuran dasar lalu diperbesar di quad, jadi tidak ada tekstur baru per frame. */
    public void textS(String s, float cx, float cy, float size, float sc, int color, boolean bold, float a, float rot) {
        if (s == null || s.length() == 0) return;
        Tex t = textTex(s, size, bold);
        quad(1, cx, cy + size * 0.03f * sc, t.w * sc, t.h * sc, 0, rot, color, 0, 0, 0, 0, 0, 0, a, t.id);
    }

    public void textL(String s, float x, float cy, float size, int color, boolean bold, float a) {
        if (s == null || s.length() == 0) return;
        Tex t = textTex(s, size, bold);
        quad(1, x + t.w / 2f - 4f / scale, cy + size * 0.03f, t.w, t.h, 0, 0, color, 0, 0, 0, 0, 0, 0, a, t.id);
    }

    /** Ikon dari XML vector drawable (res/drawable), diwarnai di shader. */
    public void icon(int res, float cx, float cy, float size, int color, float a, float rot) {
        Tex t = icons.get(res);
        if (t == null) {
            Drawable d = ctx.getResources().getDrawable(res, null);
            int px = 128;
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, px, px);
            d.draw(c);
            t = makeTex(b);
            t.w = 1f;
            t.h = 1f;
            icons.put(res, t);
        }
        quad(1, cx, cy, size, size, 0, rot, color, 0, 0, 0, 0, 0, 0, a, t.id);
    }
}
