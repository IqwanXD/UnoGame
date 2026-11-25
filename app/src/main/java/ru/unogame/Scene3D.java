package ru.unogame;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Base64;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;

/**
 * Dunia 3D game Uno (OpenGL ES 2.0, Java murni): ruangan gelap, meja bundar, 10 kursi,
 * lampu bohlam jadul yang berayun, karakter kotak bermata kedip yang menutupi diri dengan kartu.
 * Kamera ada di kursi kita dan bisa menoleh 360 derajat (yaw) serta menunduk/mendongak (pitch).
 */
public class Scene3D {
    /** Mode ringan untuk RAM <= 3 GB: tanpa kaki meja, tangan kartu lebih sedikit, asap dimatikan. */
    public static boolean LITE;
    /** Palet warna aksesoris (dipilih di menu Karakter). */
    public static final int[] ACC_COL = {0xFFE53935, 0xFFFFB300, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFFF5F5F5};
    private static final int INK = 0xFF161616;

    public static final int CHAIRS = 10;
    public static final float CW = 0.34f, CH = 0.49f;
    static final float TABLE_R = 1.6f, CHAIR_R = 2.2f, TOP_Y = 0.81f;
    static final float DECK_X = -0.42f, PILE_X = 0.42f;
    static final float PI = 3.1415927f, SEAT_STEP = 2f * PI / CHAIRS;

    static class Mesh { FloatBuffer vb; ShortBuffer ib; int n; float[] va; short[] ia; }

    /** Benda statis yang digabung jadi satu mesh (satu draw call). Warna lewat palet, bukan uniform. */
    static class Batch {
        float[] v = new float[8 * 2048];
        short[] ix = new short[3 * 2048];
        int nv, ni;
        Mesh mesh;
        void grow(int addV, int addI) {
            if ((nv + addV) * 8 > v.length) v = Arrays.copyOf(v, Math.max(v.length * 2, (nv + addV) * 8));
            if (ni + addI > ix.length) ix = Arrays.copyOf(ix, Math.max(ix.length * 2, ni + addI));
        }
    }
    static final int PAL_W = 256;

    private static final String VS =
            "attribute vec3 aPos;\n" +
            "attribute vec3 aNrm;\n" +
            "attribute vec2 aUV;\n" +
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uModel;\n" +
            "uniform float uSw;\n" +
            "uniform float uSwT;\n" +
            "varying vec3 vW;\n" +
            "varying vec3 vN;\n" +
            "varying vec2 vUV;\n" +
            "void main(){\n" +
            "  vec3 p=aPos;\n" +
            "  float sw=uSw*aUV.y;\n" +
            "  p.x+=sw*sin(uSwT*1.7+aPos.x*2.3+aPos.z*1.7);\n" +
            "  p.z+=sw*0.5*sin(uSwT*1.3+aPos.z*2.1-aPos.x*1.1);\n" +
            "  vW=(uModel*vec4(p,1.0)).xyz;\n" +
            "  vN=(uModel*vec4(aNrm,0.0)).xyz;\n" +
            "  vUV=aUV;\n" +
            "  gl_Position=uMVP*vec4(p,1.0);\n" +
            "}\n";

    /** Fragment shader. Versi LITE (RAM kecil): mediump, tanpa kilap, pola tanah dan air disederhanakan. */
    private static final String FS =
            "#ifdef LITE\n" +
            "precision mediump float;\n" +
            "#elif defined(GL_FRAGMENT_PRECISION_HIGH)\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec3 vW;\n" +
            "varying vec3 vN;\n" +
            "varying vec2 vUV;\n" +
            "uniform vec4 uCol;\n" +
            "uniform vec3 uC2;\n" +
            "uniform vec3 uLP;\n" +
            "uniform vec3 uCam;\n" +
            "uniform vec3 uAmb;\n" +
            "uniform float uInt;\n" +
            "uniform float uEm;\n" +
            "uniform float uMode;\n" +
            "uniform float uT;\n" +
            "uniform sampler2D uTex;\n" +
            "#ifndef LITE\n" +
            "float hash(vec2 p){ return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453); }\n" +
            "#endif\n" +
            "void main(){\n" +
            "  vec4 base=uCol;\n" +
            "  if(uMode>0.5 && uMode<1.5){\n" +
            "    vec4 t=texture2D(uTex,vUV);\n" +
            "    if(t.a<0.4) discard;\n" +
            "    base=vec4(t.rgb*uCol.rgb,uCol.a);\n" +
            "  } else if(uMode>1.5 && uMode<2.5){\n" +
            "    float row=floor(vW.x*2.2);\n" +
            "#ifdef LITE\n" +
            "    float sh=0.88+0.1*fract(row*0.381);\n" +
            "    base.rgb*=sh;\n" +
            "#else\n" +
            "    float sh=0.85+0.12*fract(sin(row*12.9898)*43758.5453);\n" +
            "    float gr=0.92+0.08*sin(vW.z*35.0+row*5.0);\n" +
            "    base.rgb*=sh*gr;\n" +
            "#endif\n" +
            "  } else if(uMode>2.5 && uMode<3.5){\n" +
            "    float k=clamp(vW.y/25.0,0.0,1.0);\n" +
            "    base.rgb=mix(uC2,uCol.rgb,sqrt(k));\n" +
            "  } else if(uMode>3.5 && uMode<4.5){\n" +
            "#ifdef LITE\n" +
            "    base.rgb*=0.93+0.1*sin(vW.x*1.7)*sin(vW.z*1.3);\n" +
            "#else\n" +
            "    float n=hash(floor(vW.xz*45.0));\n" +
            "    float m=hash(floor(vW.xz*7.0)+3.0);\n" +
            "    base.rgb*=(0.86+0.14*n)*(0.94+0.1*m);\n" +
            "#endif\n" +
            "  } else if(uMode>4.5 && uMode<5.5){\n" +
            "#ifdef LITE\n" +
            "    float w=sin(vW.x*2.1+uT*0.9)*sin(vW.z*1.7-uT*0.7);\n" +
            "#else\n" +
            "    float w=sin(vW.x*2.1+uT*0.9)*sin(vW.z*1.7-uT*0.7)+0.5*sin((vW.x+vW.z)*3.3+uT*1.6);\n" +
            "#endif\n" +
            "    base.rgb=mix(uCol.rgb,uC2,clamp(0.5+0.25*w,0.0,1.0));\n" +
            "  } else if(uMode>5.5){\n" +
            "    base=vec4(texture2D(uTex,vec2(vUV.x,0.5)).rgb,1.0);\n" +
            "  }\n" +
            "  vec3 N=normalize(vN);\n" +
            "  vec3 V=normalize(uCam-vW);\n" +
            "  if(dot(N,V)<0.0) N=-N;\n" +
            "  vec3 Lv=uLP-vW;\n" +
            "  float d2=dot(Lv,Lv);\n" +
            "  vec3 L=Lv*inversesqrt(d2);\n" +
            "  float diff=max(dot(N,L),0.0);\n" +
            "  float cone=smoothstep(0.05,0.65,L.y);\n" +
            "  float att=uInt/(1.0+0.45*d2);\n" +
            "  vec3 lc=vec3(1.0,0.82,0.55);\n" +
            "#ifdef LITE\n" +
            "  vec3 lit=base.rgb*(uAmb+lc*(diff*att*cone*1.6));\n" +
            "  lit=mix(lit,base.rgb,uEm);\n" +
            "  gl_FragColor=vec4(lit*(1.0+0.45*(1.0-min(lit,vec3(1.0)))),base.a);\n" +
            "#else\n" +
            "  vec3 H=normalize(L+V);\n" +
            "  float sp=pow(max(dot(N,H),0.0),24.0)*0.25;\n" +
            "  vec3 lit=base.rgb*(uAmb+lc*(diff*att*cone*1.6))+lc*sp*att*cone;\n" +
            "  lit=mix(lit,base.rgb,uEm);\n" +
            "  gl_FragColor=vec4(pow(lit,vec3(0.8)),base.a);\n" +
            "#endif\n" +
            "}\n";

    private final Context ctx;
    private final Gfx g;
    private Mesh cube, cyl, cylHi, sph, plane, cur;
    /** Map yang dipilih host: 0 ruang gelap, 1 padang rumput, 2 pantai. */
    public int map;
    /** Warna kedua untuk langit (horizon) dan air (laut dangkal). */
    private int c2;
    /** Permukaan tempat kartu diletakkan (meja atau pasir/karpet) dan turunnya badan saat duduk di tanah. */
    private float topY = TOP_Y, sitDrop;
    private Mesh coneRed, coneWhite, faceQuad;
    private final HashMap<String, Integer> faceTex = new HashMap<String, Integer>();
    /** Rumput dan bunga di padang rumput: posisi tetap, ayunan dihitung per frame. */
    private float[] mdX, mdZ, mdH, mdPh, flX, flZ, flH, flPh;
    private int[] flCol;
    private int prog, aPos, aNrm, aUV, uMVP, uModel, uCol, uLP, uCam, uInt, uEm, uMode, uTex, uC2, uT, uAmb, uSw, uSwT;
    /** Benda statis yang sudah digabung: ruang gelap, kursi, padang rumput, pantai, bantal duduk. */
    private Batch roomB, chairB, grassEnvB, meadowB, beachEnvB, cushGrassB, cushBeachB;
    private Mesh roomM, chairM, grassEnvM, meadowM, beachEnvM, cushGrassM, cushBeachM;
    /** Palet warna global untuk mesh gabungan (tekstur 256x1). */
    private final ArrayList<Integer> palCols = new ArrayList<Integer>();
    private int palTex;
    private boolean palDirty = true;
    /** Bila tidak null, submit() tidak menggambar tetapi memasukkan mesh ke batch ini. */
    private Batch sink;
    private final Mesh[] fan = new Mesh[9];
    /** Bidikan crosshair di tengah layar: 0 tidak ada, 1 kursi kosong, 2 pemain, 3 dek. */
    public boolean aimOn;
    public float crossX = 500f, crossY = 900f;
    public int aimKind, aimSeat = -1, aimPlayer = -1;
    private final int[] seatOf = new int[CHAIRS];
    private final HashMap<Integer, Integer> faces = new HashMap<Integer, Integer>();
    private final float[] proj = new float[16], view = new float[16], vp = new float[16];
    private final float[] mdl = new float[16], mvp = new float[16], base = new float[16];
    private final float[] lp = new float[3];
    private final float[] blink = new float[CHAIRS], nextBlink = new float[CHAIRS];
    private final int[] chair = new int[CHAIRS];
    /** Kamera mengorbit ke kursi kita (animasi saat pindah kursi). */
    private float camAng, camTarget;
    private boolean camInit;
    private final Random rnd = new Random(7);
    private int pxW = 1, pxH = 1, n, me;
    private float t, lastT, lightInt = 1f;

    /** Arah pandang bebas: yaw 360 derajat (radian), pitch dibatasi. */
    public float yaw, pitch, shake;

    /** Kepala tiap kursi: sudut sekarang (hy, hp) mengejar target (ty, tp) dari arah pandang pemain itu. */
    private final float[] hy = new float[CHAIRS], hp = new float[CHAIRS], ty = new float[CHAIRS], tp = new float[CHAIRS];

    /** Tetapkan arah pandang pemain di kursi p (yaw = belok, pitch = dongak/tunduk, dalam radian). */
    public void setLook(int p, float yaw, float pitch) {
        if (p >= 0 && p < CHAIRS) { ty[p] = yaw; tp[p] = pitch; }
    }

    /** Normalisasi sudut ke rentang -PI..PI. */
    public static float wrapAng(float a) { return a - 6.2831853f * Math.round(a / 6.2831853f); }

    public Scene3D(Context c, Gfx gfx) {
        ctx = c;
        g = gfx;
    }

    // ================================================================ init
    private int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e("UnoGL3D", GLES20.glGetShaderInfoLog(s));
        return s;
    }

    public void init() {
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, VS));
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, (LITE ? "#define LITE\n" : "") + FS));
        GLES20.glLinkProgram(prog);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e("UnoGL3D", GLES20.glGetProgramInfoLog(prog));
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        aNrm = GLES20.glGetAttribLocation(prog, "aNrm");
        aUV = GLES20.glGetAttribLocation(prog, "aUV");
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP");
        uModel = GLES20.glGetUniformLocation(prog, "uModel");
        uCol = GLES20.glGetUniformLocation(prog, "uCol");
        uLP = GLES20.glGetUniformLocation(prog, "uLP");
        uCam = GLES20.glGetUniformLocation(prog, "uCam");
        uInt = GLES20.glGetUniformLocation(prog, "uInt");
        uEm = GLES20.glGetUniformLocation(prog, "uEm");
        uMode = GLES20.glGetUniformLocation(prog, "uMode");
        uTex = GLES20.glGetUniformLocation(prog, "uTex");
        uC2 = GLES20.glGetUniformLocation(prog, "uC2");
        uT = GLES20.glGetUniformLocation(prog, "uT");
        uAmb = GLES20.glGetUniformLocation(prog, "uAmb");
        uSw = GLES20.glGetUniformLocation(prog, "uSw");
        uSwT = GLES20.glGetUniformLocation(prog, "uSwT");
        palTex = 0;
        palDirty = true;
        cube = buildCube();
        cyl = buildCyl(LITE ? 10 : 16);
        cylHi = buildCyl(LITE ? 20 : 32);
        coneRed = buildWedges(16, 0);
        coneWhite = buildWedges(16, 1);
        faceQuad = buildFaceQuad();
        sph = buildSphere(LITE ? 6 : 8, LITE ? 8 : 10);
        if (roomB == null) bakeAll();
        plane = buildPlane();
        faces.clear();
        for (int i = 0; i < CHAIRS; i++) nextBlink[i] = rnd.nextFloat() * 3f;
    }

    public void setSize(int w, int h) { pxW = Math.max(1, w); pxH = Math.max(1, h); }

    // ================================================================ mesh
    private Mesh mk(float[] v, short[] ix) {
        Mesh m = new Mesh();
        m.vb = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        m.vb.put(v).position(0);
        m.ib = ByteBuffer.allocateDirect(ix.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        m.ib.put(ix).position(0);
        m.n = ix.length;
        m.va = v;
        m.ia = ix;
        return m;
    }

    private Mesh buildCube() {
        float[][] F = {{0, 0, 1, 1, 0, 0, 0, 1, 0}, {0, 0, -1, -1, 0, 0, 0, 1, 0}, {1, 0, 0, 0, 0, -1, 0, 1, 0},
                {-1, 0, 0, 0, 0, 1, 0, 1, 0}, {0, 1, 0, 1, 0, 0, 0, 0, -1}, {0, -1, 0, 1, 0, 0, 0, 0, 1}};
        int[][] C = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        float[] v = new float[24 * 8];
        short[] ix = new short[36];
        int p = 0;
        for (int f = 0; f < 6; f++) {
            float[] a = F[f];
            for (int k = 0; k < 4; k++) {
                float su = C[k][0], sv = C[k][1];
                v[p++] = 0.5f * (a[0] + su * a[3] + sv * a[6]);
                v[p++] = 0.5f * (a[1] + su * a[4] + sv * a[7]);
                v[p++] = 0.5f * (a[2] + su * a[5] + sv * a[8]);
                v[p++] = a[0]; v[p++] = a[1]; v[p++] = a[2];
                v[p++] = (su + 1f) / 2f; v[p++] = (1f - sv) / 2f;
            }
            int o = f * 4, q = f * 6;
            ix[q] = (short) o; ix[q + 1] = (short) (o + 1); ix[q + 2] = (short) (o + 2);
            ix[q + 3] = (short) o; ix[q + 4] = (short) (o + 2); ix[q + 5] = (short) (o + 3);
        }
        return mk(v, ix);
    }

    private Mesh buildCyl(int S) {
        int nv = (S + 1) * 2 + 2 * (S + 2);
        float[] v = new float[nv * 8];
        short[] ix = new short[S * 6 + S * 6];
        int p = 0, q = 0;
        for (int i = 0; i <= S; i++) {
            float a = (float) (i * 2.0 * Math.PI / S), c = (float) Math.cos(a), s = (float) Math.sin(a);
            for (int j = 0; j < 2; j++) {
                v[p++] = 0.5f * c; v[p++] = j == 0 ? 0.5f : -0.5f; v[p++] = 0.5f * s;
                v[p++] = c; v[p++] = 0f; v[p++] = s;
                v[p++] = (float) i / S; v[p++] = j;
            }
        }
        for (int i = 0; i < S; i++) {
            int a = i * 2;
            ix[q++] = (short) a; ix[q++] = (short) (a + 1); ix[q++] = (short) (a + 2);
            ix[q++] = (short) (a + 1); ix[q++] = (short) (a + 3); ix[q++] = (short) (a + 2);
        }
        int b0 = (S + 1) * 2;
        for (int cap = 0; cap < 2; cap++) {
            float y = cap == 0 ? 0.5f : -0.5f, ny = cap == 0 ? 1f : -1f;
            int c0 = b0 + cap * (S + 2);
            v[p++] = 0f; v[p++] = y; v[p++] = 0f; v[p++] = 0f; v[p++] = ny; v[p++] = 0f; v[p++] = 0.5f; v[p++] = 0.5f;
            for (int i = 0; i <= S; i++) {
                float a = (float) (i * 2.0 * Math.PI / S), c = (float) Math.cos(a), s = (float) Math.sin(a);
                v[p++] = 0.5f * c; v[p++] = y; v[p++] = 0.5f * s; v[p++] = 0f; v[p++] = ny; v[p++] = 0f;
                v[p++] = 0.5f + 0.5f * c; v[p++] = 0.5f + 0.5f * s;
            }
            for (int i = 0; i < S; i++) {
                ix[q++] = (short) c0; ix[q++] = (short) (c0 + 1 + i); ix[q++] = (short) (c0 + 2 + i);
            }
        }
        return mk(v, ix);
    }

    private Mesh buildSphere(int L, int M) {
        float[] v = new float[(L + 1) * (M + 1) * 8];
        short[] ix = new short[L * M * 6];
        int p = 0, q = 0;
        for (int i = 0; i <= L; i++) {
            float th = (float) (i * Math.PI / L), st = (float) Math.sin(th), ct = (float) Math.cos(th);
            for (int j = 0; j <= M; j++) {
                float ph = (float) (j * 2.0 * Math.PI / M);
                float x = st * (float) Math.cos(ph), z = st * (float) Math.sin(ph);
                v[p++] = 0.5f * x; v[p++] = 0.5f * ct; v[p++] = 0.5f * z;
                v[p++] = x; v[p++] = ct; v[p++] = z;
                v[p++] = (float) j / M; v[p++] = (float) i / L;
            }
        }
        for (int i = 0; i < L; i++) {
            for (int j = 0; j < M; j++) {
                int a = i * (M + 1) + j, b = a + M + 1;
                ix[q++] = (short) a; ix[q++] = (short) b; ix[q++] = (short) (a + 1);
                ix[q++] = (short) (a + 1); ix[q++] = (short) b; ix[q++] = (short) (b + 1);
            }
        }
        return mk(v, ix);
    }

    /** Kerucut payung: irisan selang-seling (parity 0 atau 1) supaya bisa dua warna. Ujung ke atas. */
    private Mesh buildWedges(int S, int parity) {
        int tris = S / 2;
        float[] v = new float[tris * 3 * 8];
        short[] ix = new short[tris * 3];
        int p = 0, q = 0;
        for (int i = parity; i < S; i += 2) {
            double a0 = i * 2.0 * Math.PI / S, a1 = (i + 1) * 2.0 * Math.PI / S;
            float[][] pts = {{0f, 0.5f, 0f},
                    {(float) (0.5 * Math.cos(a0)), -0.5f, (float) (0.5 * Math.sin(a0))},
                    {(float) (0.5 * Math.cos(a1)), -0.5f, (float) (0.5 * Math.sin(a1))}};
            for (int k = 0; k < 3; k++) {
                float[] pt = pts[k];
                float nx, ny, nz;
                if (k == 0) { nx = 0f; ny = 1f; nz = 0f; }
                else {
                    double a = k == 1 ? a0 : a1;
                    float l = (float) Math.sqrt(1.25);
                    nx = (float) Math.cos(a) / l; ny = 0.5f / l; nz = (float) Math.sin(a) / l;
                }
                v[p++] = pt[0]; v[p++] = pt[1]; v[p++] = pt[2];
                v[p++] = nx; v[p++] = ny; v[p++] = nz;
                v[p++] = 0f; v[p++] = 0f;
                ix[q] = (short) q; q++;
            }
        }
        return mk(v, ix);
    }

    /** Kuad 1x1 dengan UV penuh (0..1) untuk foto wajah. */
    private Mesh buildFaceQuad() {
        float[] v = {
                -0.5f, 0.5f, 0f, 0f, 0f, 1f, 0f, 0f,
                0.5f, 0.5f, 0f, 0f, 0f, 1f, 1f, 0f,
                0.5f, -0.5f, 0f, 0f, 0f, 1f, 1f, 1f,
                -0.5f, -0.5f, 0f, 0f, 0f, 1f, 0f, 1f};
        return mk(v, new short[]{0, 1, 2, 0, 2, 3});
    }

    /** Bidang 1x1 menghadap +Z. Tekstur kartu: 256x371 dalam bitmap 256x512. */
    private Mesh buildPlane() {
        float vm = 371f / 512f;
        float[] v = {
                -0.5f, 0.5f, 0f, 0f, 0f, 1f, 0f, 0f,
                0.5f, 0.5f, 0f, 0f, 0f, 1f, 1f, 0f,
                0.5f, -0.5f, 0f, 0f, 0f, 1f, 1f, vm,
                -0.5f, -0.5f, 0f, 0f, 0f, 1f, 0f, vm};
        return mk(v, new short[]{0, 1, 2, 0, 2, 3});
    }

    // ================================================================ tekstur kartu
    private int faceTex(int code) {
        Integer id = faces.get(code);
        if (id != null) return id;
        int bw = LITE ? 96 : 128;
        Bitmap b = Bitmap.createBitmap(bw, bw * 2, Bitmap.Config.ARGB_8888);
        drawFace(new Canvas(b), code, bw / 256f);
        int[] tx = new int[1];
        GLES20.glGenTextures(1, tx, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tx[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, LITE ? GLES20.GL_LINEAR : GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        if (!LITE) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        b.recycle();
        faces.put(code, tx[0]);
        return tx[0];
    }

    private void icon(Canvas c, int res, RectF r, int color) {
        Drawable d = ctx.getResources().getDrawable(res, null).mutate();
        d.setTint(color);
        d.setBounds((int) r.left, (int) r.top, (int) r.right, (int) r.bottom);
        d.draw(c);
    }

    private void label(Canvas c, Paint p, String s, float cx, float cy, float size, int color) {
        p.setTextSize(size);
        p.setColor(color);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, p);
    }

    private void drawFace(Canvas c, int code, float sc) {
        c.scale(sc, sc); // gambar di ruang 256x371; bitmap lebar 256*sc dan tinggi dua kalinya (v maksimum tetap 371/512)
        final float W = 256f, H = 371f, cx = W / 2f, cy = H / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setColor(0xFFFFFFFF);
        c.drawRoundRect(new RectF(0, 0, W, H), 26, 26, p);
        RectF in = new RectF(11, 11, W - 11, H - 11);
        RectF ov = new RectF(cx - 118, cy - 94, cx + 118, cy + 94);
        if (code < 0) {
            p.setColor(0xFF15162E);
            c.drawRoundRect(in, 18, 18, p);
            c.save();
            c.rotate(-49f, cx, cy);
            p.setColor(0xFFD32F2F);
            c.drawOval(ov, p);
            label(c, p, "UNO", cx, cy, 84f, 0xFFFFD83D);
            c.restore();
            return;
        }
        int col = UnoEngine.col(code), v = UnoEngine.val(code);
        boolean wild = col == UnoEngine.WILD;
        if (wild) {
            p.setColor(0xFF111116);
        } else {
            p.setShader(new LinearGradient(0, 0, 0, H, GameRenderer.CL[col], GameRenderer.CD[col], Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(in, 18, 18, p);
        p.setShader(null);
        c.save();
        c.rotate(-49f, cx, cy);
        if (wild) {
            Path path = new Path();
            path.addOval(ov, Path.Direction.CW);
            c.save();
            c.clipPath(path);
            int[] q = {0, 1, 2, 3};
            for (int i = 0; i < 4; i++) {
                p.setColor(GameRenderer.CL[q[i]]);
                c.drawRect(i % 2 == 0 ? ov.left : cx, i < 2 ? ov.top : cy, i % 2 == 0 ? cx : ov.right, i < 2 ? cy : ov.bottom, p);
            }
            c.restore();
        } else {
            p.setColor(0xFFFFFFFF);
            c.drawOval(ov, p);
        }
        c.restore();
        int ink = wild ? 0xFFFFFFFF : GameRenderer.CD[col];
        if (v == UnoEngine.SKIP || v == UnoEngine.REV) {
            int ic = v == UnoEngine.SKIP ? R.drawable.ic_skip : R.drawable.ic_reverse;
            icon(c, ic, new RectF(cx - 66, cy - 66, cx + 66, cy + 66), GameRenderer.CD[wild ? 0 : col]);
            icon(c, ic, new RectF(22, 26, 66, 70), 0xFFFFFFFF);
            c.save();
            c.rotate(180f, cx, cy);
            icon(c, ic, new RectF(22, 26, 66, 70), 0xFFFFFFFF);
            c.restore();
        } else if (!(wild && v == UnoEngine.W)) {
            String fs = GameRenderer.faceStr(v);
            if (wild) label(c, p, fs, cx + 3f, cy + 4f, 120f, 0x99000000);
            label(c, p, fs, cx, cy, wild ? 120f : 140f, ink);
            label(c, p, fs, 50f, 52f, 46f, 0xFFFFFFFF);
            c.save();
            c.rotate(180f, cx, cy);
            label(c, p, fs, 50f, 52f, 46f, 0xFFFFFFFF);
            c.restore();
        }
    }

    // ================================================================ kamera
    public void updateCamera() {
        boolean land = pxW > pxH;
        Matrix.perspectiveM(proj, 0, land ? 46f : 60f, (float) pxW / pxH, 0.2f, 40f);
        proj[9] = land ? -0.10f : -0.20f; // geser dunia ke atas agar kartu di bawah layar tidak menutup meja
        float cr = CHAIR_R + 0.12f;
        float ex = cr * (float) Math.sin(camAng) + (rnd.nextFloat() - 0.5f) * shake, ey = 1.55f + (rnd.nextFloat() - 0.5f) * shake, ez = cr * (float) Math.cos(camAng);
        pitch = Math.max(-0.9f, Math.min(1.0f, pitch));
        float p = -0.30f + pitch;
        float cp = (float) Math.cos(p), sp = (float) Math.sin(p), sy = (float) Math.sin(yaw + camAng), cyw = (float) Math.cos(yaw + camAng);
        Matrix.setLookAtM(view, 0, ex, ey, ez, ex - sy * cp, ey + sp, ez - cyw * cp, 0f, 1f, 0f);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
    }

    /** Titik dunia -> koordinat virtual layar. Hasil: {x, y, satuan-layar-per-meter}; null jika di belakang kamera. */
    public float[] project(float x, float y, float z) {
        float cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12];
        float cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13];
        float cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15];
        if (cw < 0.15f) return null;
        float sc = g.scale;
        return new float[]{(cx / cw * 0.5f + 0.5f) * pxW / sc - g.padX, (0.5f - cy / cw * 0.5f) * pxH / sc,
                proj[5] * 0.5f * pxH / (sc * cw)};
    }

    /** Kursi tiap pemain dari nomor kursi di meja; kursi kita selalu kursi 0 (kamera). */
    public void setPlayers(UnoEngine e, int mi) {
        int np = e.players.size();
        n = np;
        me = mi;
        for (int p = 0; p < np && p < CHAIRS; p++) chair[p] = Math.max(0, Math.min(CHAIRS - 1, e.players.get(p).seat));
        for (int k = 0; k < CHAIRS; k++) seatOf[k] = -1;
        for (int p = 0; p < np && p < CHAIRS; p++) seatOf[chair[p]] = p;
        if (mi >= 0 && mi < np) camTarget = chair[mi] * SEAT_STEP;
        if (!camInit) { camAng = camTarget; camInit = true; }
    }

    /** Layar kursi ke-k dari kursi kita (k = 0 kursi kita). Dipakai sebagai area klik kursi kosong. */
    public float[] chairScreen(int k) {
        float th = k * SEAT_STEP;
        float x = CHAIR_R * (float) Math.sin(th), z = CHAIR_R * (float) Math.cos(th);
        return project(x, 0.9f - sitDrop + groundAt(x, z), z);
    }

    private float[] loc(int p, float lx, float ly, float lz) {
        float th = chair[p] * SEAT_STEP, yw = th + PI, c = (float) Math.cos(yw), s = (float) Math.sin(yw);
        float x = CHAIR_R * (float) Math.sin(th) + lx * c + lz * s;
        float z = CHAIR_R * (float) Math.cos(th) - lx * s + lz * c;
        return new float[]{x, ly - sitDrop + groundAt(x, z), z};
    }

    /** what: 0 = tengah kipas kartu, 1 = atas kepala, 2 = badan, 3 = pusat kepala. */
    public float[] seatScreen(int p, int what) {
        if (p < 0 || p >= n || p >= CHAIRS) return null;
        float[] w = what == 0 ? loc(p, 0f, 1.05f, 0.4f) : what == 1 ? loc(p, 0f, 1.78f, 0f)
                : what == 2 ? loc(p, 0f, 1.1f, 0.2f) : loc(p, 0f, 1.34f, 0f);
        return project(w[0], w[1], w[2]);
    }

    public float[] deckScreen() { return project(DECK_X, topY + 0.05f, 0f); }

    public float[] pileScreen() { return project(PILE_X, topY + 0.02f, 0f); }

    public float[] lampScreen() { return project(lp[0], lp[1], lp[2]); }

    // ================================================================ gambar
    private void setBase(float tx, float ty, float tz, float ryDeg) {
        Matrix.setIdentityM(base, 0);
        Matrix.translateM(base, 0, tx, ty, tz);
        Matrix.rotateM(base, 0, ryDeg, 0f, 1f, 0f);
    }

    private void submit(Mesh m, int col, float a, float em, float mode, int tex) {
        if (sink != null) {
            if (mode == 0f && tex == 0) bAdd(sink, m, mdl, col, 0f);
            return;
        }
        Matrix.multiplyMM(mvp, 0, vp, 0, mdl, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, mdl, 0);
        GLES20.glUniform4f(uCol, ((col >> 16) & 255) / 255f, ((col >> 8) & 255) / 255f, (col & 255) / 255f, a);
        GLES20.glUniform1f(uEm, em);
        GLES20.glUniform1f(uMode, mode);
        GLES20.glUniform3f(uC2, ((c2 >> 16) & 255) / 255f, ((c2 >> 8) & 255) / 255f, (c2 & 255) / 255f);
        if (tex != 0) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        if (m != cur) {
            cur = m;
            m.vb.position(0);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, m.vb);
            m.vb.position(3);
            GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, 32, m.vb);
            m.vb.position(6);
            if (aUV >= 0) GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 32, m.vb);
            m.vb.position(0);
        }
        m.ib.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.n, GLES20.GL_UNSIGNED_SHORT, m.ib);
    }

    private void box(Mesh m, float tx, float ty, float tz, float rx, float ry, float rz,
                     float sx, float sy, float sz, int col, float a, float em) {
        place(tx, ty, tz, rx, ry, rz, sx, sy, sz);
        submit(m, col, a, em, 0f, 0);
    }

    private void place(float tx, float ty, float tz, float rx, float ry, float rz, float sx, float sy, float sz) {
        System.arraycopy(base, 0, mdl, 0, 16);
        Matrix.translateM(mdl, 0, tx, ty, tz);
        if (ry != 0f) Matrix.rotateM(mdl, 0, ry, 0f, 1f, 0f);
        if (rx != 0f) Matrix.rotateM(mdl, 0, rx, 1f, 0f, 0f);
        if (rz != 0f) Matrix.rotateM(mdl, 0, rz, 0f, 0f, 1f);
        Matrix.scaleM(mdl, 0, sx, sy, sz);
    }

    private void card(int code, float tx, float ty, float tz, float rx, float ry, float rz, float w, float h) {
        place(tx, ty, tz, rx, ry, rz, w, h, 1f);
        submit(plane, 0xFFFFFFFF, 1f, 0f, 1f, faceTex(code));
    }

    /** Hitung mdl untuk batang dari titik A ke B (mesh unit kubus atau silinder). false kalau terlalu pendek. */
    private boolean barM(float ax, float ay, float az, float bx, float by, float bz, float th) {
        float dx = bx - ax, dy = by - ay, dz = bz - az, len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4f) return false;
        float ang = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, dy / len))));
        System.arraycopy(base, 0, mdl, 0, 16);
        Matrix.translateM(mdl, 0, (ax + bx) / 2f, (ay + by) / 2f, (az + bz) / 2f);
        if (Math.abs(dz) + Math.abs(dx) > 1e-5f) Matrix.rotateM(mdl, 0, ang, dz, 0f, -dx);
        else if (dy < 0f) Matrix.rotateM(mdl, 0, 180f, 1f, 0f, 0f);
        Matrix.scaleM(mdl, 0, th, len, th);
        return true;
    }

    /** Batang berpenampang kotak. */
    private void bar(float ax, float ay, float az, float bx, float by, float bz, float th, int col) {
        if (barM(ax, ay, az, bx, by, bz, th)) submit(cube, col, 1f, 0f, 0f, 0);
    }

    /** Batang berpenampang bulat (batang pohon, tiang, antena). */
    private void cbar(float ax, float ay, float az, float bx, float by, float bz, float dia, int col) {
        if (barM(ax, ay, az, bx, by, bz, dia)) submit(cyl, col, 1f, 0f, 0f, 0);
    }

    /** Garis outline yang menyala (emisif). */
    private void edge(float ax, float ay, float az, float bx, float by, float bz, float th, int col) {
        if (barM(ax, ay, az, bx, by, bz, th)) submit(cube, col, 1f, 1f, 0f, 0);
    }

    /** Kotak kawat 12 rusuk di sekitar pusat (cx, cy, cz) berukuran (sx, sy, sz), pada sistem base sekarang. */
    private void obox(float cx, float cy, float cz, float sx, float sy, float sz, float th, int col) {
        float x0 = cx - sx / 2f, x1 = cx + sx / 2f, y0 = cy - sy / 2f, y1 = cy + sy / 2f, z0 = cz - sz / 2f, z1 = cz + sz / 2f;
        edge(x0, y0, z0, x1, y0, z0, th, col); edge(x0, y0, z1, x1, y0, z1, th, col);
        edge(x0, y1, z0, x1, y1, z0, th, col); edge(x0, y1, z1, x1, y1, z1, th, col);
        edge(x0, y0, z0, x0, y1, z0, th, col); edge(x1, y0, z0, x1, y1, z0, th, col);
        edge(x0, y0, z1, x0, y1, z1, th, col); edge(x1, y0, z1, x1, y1, z1, th, col);
        edge(x0, y0, z0, x0, y0, z1, th, col); edge(x1, y0, z0, x1, y0, z1, th, col);
        edge(x0, y1, z0, x0, y1, z1, th, col); edge(x1, y1, z0, x1, y1, z1, th, col);
    }

    // ================================================================ batch statis
    private float palU(int col) {
        Integer c = Integer.valueOf(col | 0xFF000000);
        int i = palCols.indexOf(c);
        if (i < 0) {
            if (palCols.size() >= PAL_W) i = PAL_W - 1;
            else { palCols.add(c); i = palCols.size() - 1; palDirty = true; }
        }
        return (i + 0.5f) / PAL_W;
    }

    private void palFlush() {
        if (palTex == 0) {
            int[] tx = new int[1];
            GLES20.glGenTextures(1, tx, 0);
            palTex = tx[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, palTex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            palDirty = true;
        }
        if (!palDirty) return;
        ByteBuffer bb = ByteBuffer.allocateDirect(PAL_W * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < PAL_W; i++) {
            int c = i < palCols.size() ? palCols.get(i).intValue() : 0xFFFF00FF;
            bb.put((byte) ((c >> 16) & 255)).put((byte) ((c >> 8) & 255)).put((byte) (c & 255)).put((byte) 255);
        }
        bb.position(0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, palTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, PAL_W, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
        palDirty = false;
    }

    /** Masukkan mesh dengan transformasi md ke batch. wy = bobot ayunan angin (0 = diam). */
    private void bAdd(Batch b, Mesh m, float[] md, int col, float wy) {
        if (m.va == null) return;
        int nvm = m.va.length / 8;
        if (b.nv + nvm > 65000) return;
        float u = palU(col);
        b.grow(nvm, m.ia.length);
        for (int i = 0; i < nvm; i++) {
            int sI = i * 8, d = (b.nv + i) * 8;
            float x = m.va[sI], y = m.va[sI + 1], z = m.va[sI + 2];
            b.v[d] = md[0] * x + md[4] * y + md[8] * z + md[12];
            b.v[d + 1] = md[1] * x + md[5] * y + md[9] * z + md[13];
            b.v[d + 2] = md[2] * x + md[6] * y + md[10] * z + md[14];
            float nx = m.va[sI + 3], ny = m.va[sI + 4], nz = m.va[sI + 5];
            float ax = md[0] * nx + md[4] * ny + md[8] * nz, ay = md[1] * nx + md[5] * ny + md[9] * nz, az = md[2] * nx + md[6] * ny + md[10] * nz;
            float l = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            if (l > 1e-6f) { ax /= l; ay /= l; az /= l; }
            b.v[d + 3] = ax; b.v[d + 4] = ay; b.v[d + 5] = az;
            b.v[d + 6] = u; b.v[d + 7] = wy;
        }
        for (int i = 0; i < m.ia.length; i++) b.ix[b.ni + i] = (short) (m.ia[i] + b.nv);
        b.nv += nvm;
        b.ni += m.ia.length;
    }

    /** Segitiga tunggal (helai rumput, daun). w0..w2 = bobot ayunan tiap titik. */
    private void tri(Batch b, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                     int col, float w0, float w1, float w2) {
        if (b.nv + 3 > 65000) return;
        float ux = x1 - x0, uy = y1 - y0, uz = z1 - z0, vx = x2 - x0, vy = y2 - y0, vz = z2 - z0;
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-9f) return;
        nx /= l; ny /= l; nz /= l;
        float u = palU(col);
        b.grow(3, 3);
        float[] px = {x0, x1, x2}, py = {y0, y1, y2}, pz = {z0, z1, z2}, pw = {w0, w1, w2};
        for (int i = 0; i < 3; i++) {
            int d = (b.nv + i) * 8;
            b.v[d] = px[i]; b.v[d + 1] = py[i]; b.v[d + 2] = pz[i];
            b.v[d + 3] = nx; b.v[d + 4] = ny; b.v[d + 5] = nz;
            b.v[d + 6] = u; b.v[d + 7] = pw[i];
            b.ix[b.ni + i] = (short) (b.nv + i);
        }
        b.nv += 3;
        b.ni += 3;
    }

    private Mesh finish(Batch b) {
        Mesh m = mk(Arrays.copyOf(b.v, b.nv * 8), Arrays.copyOf(b.ix, b.ni));
        m.va = null;
        m.ia = null;
        b.v = null;
        b.ix = null;
        return m;
    }

    /** Gambar seluruh mesh gabungan dengan matriks model mdl (atau base). sway = amplitudo ayunan angin. */
    private void drawBatch(Mesh m, float sway) {
        palFlush();
        Matrix.multiplyMM(mvp, 0, vp, 0, mdl, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, mdl, 0);
        GLES20.glUniform4f(uCol, 1f, 1f, 1f, 1f);
        GLES20.glUniform1f(uEm, 0f);
        GLES20.glUniform1f(uMode, 6f);
        GLES20.glUniform1f(uSw, sway);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, palTex);
        cur = null;
        m.vb.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, m.vb);
        m.vb.position(3);
        GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, 32, m.vb);
        m.vb.position(6);
        if (aUV >= 0) GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 32, m.vb);
        m.vb.position(0);
        m.ib.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.n, GLES20.GL_UNSIGNED_SHORT, m.ib);
        if (sway != 0f) GLES20.glUniform1f(uSw, 0f);
    }

    // ================================================================ render
    /** Ambient per map: ruang gelap sangat redup, luar ruangan lebih terang. */
    private void ambient(float r, float g2, float b) { GLES20.glUniform3f(uAmb, r, g2, b); }

    /** Tinggi bukit di jarak r dari pusat: puncaknya tepat di lantai meja, lerengnya turun ke dataran. */
    static float hillY(float r) {
        float q = Math.min(1f, r / 15f);
        return -1.6f + 1.6f * (float) Math.sqrt(Math.max(0f, 1f - q * q));
    }

    /** Tinggi permukaan di titik (x, z) sesuai map. */
    private float groundAt(float x, float z) {
        return map == 1 ? hillY((float) Math.sqrt(x * x + z * z)) : 0f;
    }

    /** Gambar mesh dengan transformasi sendiri dan warna kedua (langit dan air). Pakai base identitas. */
    private void pm(Mesh m, float tx, float ty, float tz, float sx, float sy, float sz,
                    int col, int col2, float a, float em, float mode) {
        place(tx, ty, tz, 0f, 0f, 0f, sx, sy, sz);
        c2 = col2;
        submit(m, col, a, em, mode, 0);
        c2 = 0;
    }

    // ================================================================ model statis (di-bake sekali)
    private void bakeAll() {
        Batch b;
        sink = roomB = b = new Batch();
        Matrix.setIdentityM(base, 0);
        bakeRoom();
        roomM = finish(b);
        sink = chairB = b = new Batch();
        Matrix.setIdentityM(base, 0);
        bakeChair();
        chairM = finish(b);
        sink = grassEnvB = b = new Batch();
        Matrix.setIdentityM(base, 0);
        bakeGrass();
        grassEnvM = finish(b);
        sink = meadowB = b = new Batch();
        Matrix.setIdentityM(base, 0);
        bakeMeadow();
        meadowM = finish(b);
        sink = beachEnvB = b = new Batch();
        Matrix.setIdentityM(base, 0);
        bakeBeach();
        beachEnvM = finish(b);
        sink = cushGrassB = b = new Batch();
        Matrix.setIdentityM(base, 0);
        box(cyl, 0f, 0.03f, 0f, 0, 0, 0, 0.5f, 0.06f, 0.5f, 0xFFD9B26F, 1f, 0f);
        box(cyl, 0f, 0.062f, 0f, 0, 0, 0, 0.36f, 0.012f, 0.36f, 0xFFB98A45, 1f, 0f);
        box(sph, 0f, 0.07f, 0f, 0, 0, 0, 0.06f, 0.04f, 0.06f, 0xFF8A5A2B, 1f, 0f);
        cushGrassM = finish(b);
        sink = cushBeachB = b = new Batch();
        Matrix.setIdentityM(base, 0);
        box(cube, 0f, 0.015f, 0f, 0, 0, 0, 0.56f, 0.03f, 0.46f, 0xFFF5F5F5, 1f, 0f);
        box(cube, 0f, 0.016f, -0.12f, 0, 0, 0, 0.56f, 0.032f, 0.07f, 0xFFE53935, 1f, 0f);
        box(cube, 0f, 0.016f, 0.12f, 0, 0, 0, 0.56f, 0.032f, 0.07f, 0xFF1E88E5, 1f, 0f);
        cushBeachM = finish(b);
        sink = null;
        Matrix.setIdentityM(base, 0);
    }

    private void blob(float x, float y, float z, float sx, float sy, float sz, int col) {
        place(x, y, z, 0f, 0f, 0f, sx, sy, sz);
        submit(sph, col, 1f, 0f, 0f, 0);
    }

    /** Ruang gelap: dinding, karpet, meja pedestal, radio. */
    private void bakeRoom() {
        box(cube, 0f, 2.28f, 0f, 0, 0, 0, 13f, 4.6f, 13f, 0xFF3A3D40, 1f, 0f);
        int bb = 0xFF2A2420;
        box(cube, 0f, 0.12f, -6.46f, 0, 0, 0, 12.9f, 0.24f, 0.08f, bb, 1f, 0f);
        box(cube, 0f, 0.12f, 6.46f, 0, 0, 0, 12.9f, 0.24f, 0.08f, bb, 1f, 0f);
        box(cube, -6.46f, 0.12f, 0f, 0, 0, 0, 0.08f, 0.24f, 12.9f, bb, 1f, 0f);
        box(cube, 6.46f, 0.12f, 0f, 0, 0, 0, 0.08f, 0.24f, 12.9f, bb, 1f, 0f);
        box(cyl, 0f, 0.004f, 0f, 0, 0, 0, 7.0f, 0.008f, 7.0f, 0xFF2E1212, 1f, 0f);
        box(cyl, 0f, 0.007f, 0f, 0, 0, 0, 6.6f, 0.012f, 6.6f, 0xFF4A1A1A, 1f, 0f);
        box(cyl, 0f, 0.009f, 0f, 0, 0, 0, 6.15f, 0.014f, 6.15f, 0xFF6B2A2A, 1f, 0f);
        box(cyl, 0f, 0.011f, 0f, 0, 0, 0, 5.95f, 0.016f, 5.95f, 0xFF4A1A1A, 1f, 0f);
        bakeTable();
        bakeRadio();
    }

    /** Meja bundar: papan kayu bertepi, kain hijau, cincin kuningan, kolom pedestal, dan lima kaki melengkung. */
    private void bakeTable() {
        int wood = 0xFF5B3A22, rim = 0xFF3E2616, dark = 0xFF2B1B10;
        box(cyl, 0f, 0.785f, 0f, 0, 0, 0, TABLE_R * 2f, 0.05f, TABLE_R * 2f, wood, 1f, 0f);
        box(cyl, 0f, 0.758f, 0f, 0, 0, 0, TABLE_R * 2f + 0.07f, 0.034f, TABLE_R * 2f + 0.07f, rim, 1f, 0f);
        box(cyl, 0f, 0.8125f, 0f, 0, 0, 0, 2.86f, 0.003f, 2.86f, 0xFF8A6A2A, 1f, 0f);
        box(cyl, 0f, 0.812f, 0f, 0, 0, 0, 2.74f, 0.004f, 2.74f, 0xFF1E4D3A, 1f, 0f);
        box(cyl, 0f, 0.705f, 0f, 0, 0, 0, 2.5f, 0.1f, 2.5f, dark, 1f, 0f);
        box(cyl, 0f, 0.38f, 0f, 0, 0, 0, 0.34f, 0.64f, 0.34f, dark, 1f, 0f);
        box(sph, 0f, 0.38f, 0f, 0, 0, 0, 0.52f, 0.3f, 0.52f, rim, 1f, 0f);
        box(cyl, 0f, 0.64f, 0f, 0, 0, 0, 0.5f, 0.05f, 0.5f, rim, 1f, 0f);
        box(cyl, 0f, 0.05f, 0f, 0, 0, 0, 0.8f, 0.1f, 0.8f, dark, 1f, 0f);
        for (int i = 0; i < 5; i++) {
            float a = i * 1.2566f + 0.3f, c = (float) Math.cos(a), s = (float) Math.sin(a);
            bar(c * 0.25f, 0.12f, s * 0.25f, c * 1.25f, 0.045f, s * 1.25f, 0.1f, dark);
            blob(c * 1.27f, 0.045f, s * 1.27f, 0.14f, 0.09f, 0.14f, rim);
        }
    }

    /** Radio jadul: kabinet kayu, kisi speaker, dial bundar berskala, dua knob, kaki, dan antena teleskop. */
    private void bakeRadio() {
        float x = 0f, y = TOP_Y + 0.004f, z = -0.62f;
        int wood = 0xFF6A4630, dk = 0xFF3B2A1E, cream = 0xFFE8DBB5, tan = 0xFFB59A6B, brown = 0xFF4A2E1C;
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2)
                box(cube, x + sx * 0.17f, y + 0.008f, z + sz * 0.055f, 0, 0, 0, 0.035f, 0.016f, 0.035f, dk, 1f, 0f);
        box(cube, x, y + 0.101f, z, 0, 0, 0, 0.40f, 0.17f, 0.15f, wood, 1f, 0f);
        box(cube, x, y + 0.19f, z, 0, 0, 0, 0.375f, 0.014f, 0.13f, dk, 1f, 0f);
        float fz = z + 0.076f;
        box(cube, x, y + 0.10f, fz, 0, 0, 0, 0.37f, 0.15f, 0.004f, dk, 1f, 0f);
        box(cube, x - 0.085f, y + 0.10f, fz + 0.003f, 0, 0, 0, 0.2f, 0.12f, 0.004f, tan, 1f, 0f);
        for (int i = 0; i < 6; i++)
            box(cube, x - 0.085f, y + 0.055f + i * 0.018f, fz + 0.006f, 0, 0, 0, 0.2f, 0.005f, 0.003f, brown, 1f, 0f);
        box(cyl, x + 0.115f, y + 0.125f, fz + 0.004f, 90f, 0, 0, 0.105f, 0.006f, 0.105f, cream, 1f, 0f);
        for (int i = 0; i < 5; i++)
            box(cube, x + 0.115f + (i - 2) * 0.018f, y + 0.14f, fz + 0.008f, 0, 0, 0, 0.003f, 0.022f, 0.002f, 0xFF222222, 1f, 0f);
        box(cube, x + 0.128f, y + 0.123f, fz + 0.009f, 0, 0, 0, 0.003f, 0.05f, 0.002f, 0xFFD32F2F, 1f, 0f);
        for (int k = 0; k < 2; k++) {
            float kx = x + 0.085f + k * 0.06f;
            box(cyl, kx, y + 0.04f, fz + 0.012f, 90f, 0, 0, 0.036f, 0.024f, 0.036f, brown, 1f, 0f);
            box(cube, kx, y + 0.052f, fz + 0.025f, 0, 0, 0, 0.004f, 0.012f, 0.002f, cream, 1f, 0f);
        }
        cbar(x + 0.17f, y + 0.195f, z - 0.04f, x + 0.25f, y + 0.58f, z - 0.12f, 0.008f, 0xFFCCCCCC);
        blob(x + 0.25f, y + 0.585f, z - 0.12f, 0.022f, 0.022f, 0.022f, 0xFFDDDDDD);
    }

    /** Lampu pada dial radio yang menyala hangat (dinamis, kecil). */
    private void radioGlow() {
        float k = 0.85f + 0.15f * (float) Math.sin(t * 3f);
        box(sph, 0.115f, TOP_Y + 0.004f + 0.125f, -0.62f + 0.076f + 0.014f, 0, 0, 0, 0.04f * k, 0.04f * k, 0.012f, 0xFFFFC870, 0.9f, 1f);
    }

    /** Kursi makan dari kayu (koordinat lokal, bagian belakang di -z): dudukan berbantal, kaki, sandaran berbilah, palang kaki. */
    private void bakeChair() {
        int wood = 0xFF3A2A1E, wood2 = 0xFF4A3524, cush = 0xFF6B2D2D;
        box(cube, 0f, 0.455f, 0f, 0, 0, 0, 0.46f, 0.045f, 0.46f, wood, 1f, 0f);
        box(cube, 0f, 0.5f, 0.01f, 0, 0, 0, 0.42f, 0.045f, 0.4f, cush, 1f, 0f);
        for (int sx = -1; sx <= 1; sx += 2) {
            box(cube, sx * 0.2f, 0.21f, 0.2f, 0, 0, 0, 0.045f, 0.42f, 0.045f, wood, 1f, 0f);
            box(cube, sx * 0.2f, 0.5f, -0.215f, -5f, 0, 0, 0.045f, 1.0f, 0.045f, wood, 1f, 0f);
        }
        box(cube, 0f, 0.95f, -0.255f, -5f, 0, 0, 0.45f, 0.08f, 0.03f, wood2, 1f, 0f);
        box(cube, 0f, 0.72f, -0.245f, -5f, 0, 0, 0.4f, 0.04f, 0.025f, wood, 1f, 0f);
        for (int i = -1; i <= 1; i++) box(cube, i * 0.12f, 0.835f, -0.25f, -5f, 0, 0, 0.06f, 0.2f, 0.02f, wood2, 1f, 0f);
        bar(-0.2f, 0.14f, 0.2f, 0.2f, 0.14f, 0.2f, 0.022f, wood);
        bar(-0.2f, 0.14f, -0.17f, 0.2f, 0.14f, -0.17f, 0.022f, wood);
        bar(-0.2f, 0.14f, 0.2f, -0.2f, 0.14f, -0.17f, 0.022f, wood);
        bar(0.2f, 0.14f, 0.2f, 0.2f, 0.14f, -0.17f, 0.022f, wood);
    }

    // ---------------------------------------------------------------- padang rumput
    private void bakeGrass() {
        // karpet piknik berlapis, tebal sampai menutup celah dengan bukit
        box(cylHi, 0f, 0f, 0f, 0, 0, 0, 5.6f, 0.07f, 5.6f, 0xFFB71C1C, 1f, 0f);
        box(cylHi, 0f, 0.001f, 0f, 0, 0, 0, 5.3f, 0.07f, 5.3f, 0xFFEFE6D2, 1f, 0f);
        box(cylHi, 0f, 0.002f, 0f, 0, 0, 0, 5.0f, 0.07f, 5.0f, 0xFFE53935, 1f, 0f);
        box(cylHi, 0f, 0.003f, 0f, 0, 0, 0, 4.7f, 0.07f, 4.7f, 0xFFEFE6D2, 1f, 0f);
        box(cylHi, 0f, 0.004f, 0f, 0, 0, 0, 4.4f, 0.07f, 4.4f, 0xFFD32F2F, 1f, 0f);
        banyan(-8.5f, -6.5f);
        roundTree(7.5f, -8.0f, 3.6f);
        roundTree(-10.5f, 3.5f, 3.0f);
        roundTree(9.5f, 6.0f, 2.6f);
        for (int i = 0; i < 4; i++) {
            float a = 0.8f + i * 1.7f, r = 6.2f + (i % 2) * 2.5f;
            float x = r * (float) Math.cos(a), z = r * (float) Math.sin(a), y = hillY(r);
            blob(x, y + 0.16f, z, 0.9f + 0.2f * i, 0.55f + 0.1f * i, 0.8f + 0.15f * i, i % 2 == 0 ? 0xFF8A8D90 : 0xFF74777A);
            blob(x + 0.5f, y + 0.1f, z + 0.3f, 0.4f, 0.28f, 0.4f, 0xFF9A9DA0);
        }
        for (int i = 0; i < 5; i++) {
            float a = 3.2f + i * 1.15f, r = 7.0f + (i % 3) * 1.6f;
            float x = r * (float) Math.cos(a), z = r * (float) Math.sin(a), y = hillY(r);
            blob(x, y + 0.3f, z, 1.0f, 0.7f, 1.0f, 0xFF2F6B2A);
            blob(x + 0.4f, y + 0.25f, z - 0.2f, 0.7f, 0.5f, 0.7f, 0xFF3F8A35);
            blob(x - 0.15f, y + 0.5f, z + 0.1f, 0.12f, 0.12f, 0.12f, 0xFFE53935);
            blob(x + 0.3f, y + 0.45f, z + 0.35f, 0.12f, 0.12f, 0.12f, 0xFFE53935);
        }
    }

    private float hillAt(float x, float z) { return hillY((float) Math.sqrt(x * x + z * z)); }

    /** Pohon bulat kecil: batang bergaris dan tajuk dari gumpalan daun. */
    private void roundTree(float tx, float tz, float h) {
        float ty = hillAt(tx, tz);
        cbar(tx, ty - 0.1f, tz, tx + 0.05f, ty + h * 0.6f, tz, 0.3f, 0xFF5E4026);
        cbar(tx, ty + h * 0.45f, tz, tx + 0.7f, ty + h * 0.85f, tz + 0.2f, 0.12f, 0xFF5E4026);
        cbar(tx, ty + h * 0.5f, tz, tx - 0.6f, ty + h * 0.9f, tz - 0.3f, 0.12f, 0xFF5E4026);
        blob(tx, ty + h * 0.95f, tz, h * 0.7f, h * 0.5f, h * 0.7f, 0xFF2F6B2A);
        blob(tx + 0.7f, ty + h * 0.85f, tz + 0.2f, h * 0.5f, h * 0.38f, h * 0.5f, 0xFF3F8A35);
        blob(tx - 0.65f, ty + h * 0.9f, tz - 0.3f, h * 0.5f, h * 0.38f, h * 0.5f, 0xFF357A2E);
        blob(tx, ty + h * 1.2f, tz, h * 0.45f, h * 0.3f, h * 0.45f, 0xFF4B9A3C);
    }

    /** Pohon beringin: pangkal berakar papan, batang kembar, cabang besar, tajuk berlapis, tiang dan akar gantung. */
    private void banyan(float tx, float tz) {
        float ty = hillAt(tx, tz);
        int bark = 0xFF5E4026, bark2 = 0xFF6E4C2E, leaf = 0xFF2F6B2A, leaf2 = 0xFF3F8A35, leaf3 = 0xFF285C24, aerial = 0xFF7A5A38;
        for (int i = 0; i < 7; i++) {
            float a = i * 0.8976f + 0.3f, rr = 1.3f + 0.25f * (i % 2);
            cbar(tx + rr * (float) Math.cos(a), ty - 0.05f, tz + rr * (float) Math.sin(a),
                    tx + 0.35f * (float) Math.cos(a), ty + 1.4f, tz + 0.35f * (float) Math.sin(a), 0.34f, bark2);
        }
        cbar(tx, ty - 0.1f, tz, tx, ty + 1.5f, tz, 1.3f, bark);
        cbar(tx, ty + 1.4f, tz, tx + 0.05f, ty + 2.9f, tz - 0.04f, 1.05f, bark);
        cbar(tx + 0.05f, ty + 2.8f, tz - 0.04f, tx + 0.1f, ty + 3.9f, tz, 0.8f, bark);
        for (int i = 0; i < 3; i++) {
            float a = i * 2.0944f + 0.5f;
            cbar(tx + 0.7f * (float) Math.cos(a), ty - 0.05f, tz + 0.7f * (float) Math.sin(a),
                    tx + 0.55f * (float) Math.cos(a), ty + 3.3f, tz + 0.55f * (float) Math.sin(a), 0.5f, bark);
        }
        for (int i = 0; i < 6; i++) {
            float a = i * 1.0472f + 0.4f, c = (float) Math.cos(a), s = (float) Math.sin(a);
            float ex = tx + 2.8f * c, ez = tz + 2.8f * s, ey = ty + 4.6f + 0.2f * (float) Math.sin(i * 2f);
            cbar(tx, ty + 3.5f, tz, tx + 1.5f * c, ty + 4.2f, tz + 1.5f * s, 0.4f, bark);
            cbar(tx + 1.5f * c, ty + 4.2f, tz + 1.5f * s, ex, ey, ez, 0.3f, bark);
            if (i % 2 == 0) {
                float px = tx + 2.0f * c, pz = tz + 2.0f * s;
                cbar(px, ty + 4.3f, pz, px + 0.05f, hillAt(px, pz) - 0.05f, pz, 0.3f, aerial);
            }
        }
        for (int i = 0; i < 8; i++) {
            float a = i * 0.7854f, rr = 3.0f + (i % 2) * 0.5f, w = 3.4f + (i % 3) * 0.3f;
            blob(tx + rr * (float) Math.cos(a), ty + 4.5f + 0.2f * (float) Math.sin(i * 1.7f), tz + rr * (float) Math.sin(a),
                    w, 1.9f, w, i % 3 == 0 ? leaf : (i % 3 == 1 ? leaf2 : leaf3));
        }
        for (int i = 0; i < 6; i++) {
            float a = i * 1.0472f + 0.5f;
            blob(tx + 1.8f * (float) Math.cos(a), ty + 5.4f, tz + 1.8f * (float) Math.sin(a), 3.0f, 1.8f, 3.0f, i % 2 == 0 ? leaf2 : leaf);
        }
        blob(tx, ty + 6.2f, tz, 3.6f, 1.7f, 3.6f, 0xFF4B9A3C);
        for (int i = 0; i < 10; i++) {
            float a = i * 0.6283f + 0.2f;
            blob(tx + 4.5f * (float) Math.cos(a), ty + 4.0f, tz + 4.5f * (float) Math.sin(a), 1.7f, 1.1f, 1.7f, i % 2 == 0 ? leaf3 : leaf);
        }
        for (int i = 0; i < 28; i++) {
            float a = i * 0.2244f, rr = 2.3f + (i % 5) * 0.5f;
            float x = tx + rr * (float) Math.cos(a), z = tz + rr * (float) Math.sin(a);
            float top = ty + 3.9f + 0.3f * (float) Math.sin(i), gy = hillAt(x, z);
            float mx = x + 0.08f * (float) Math.sin(i * 1.3f), mz = z + 0.08f * (float) Math.cos(i * 1.3f);
            float th = 0.04f + 0.03f * (i % 3);
            cbar(x, top, z, mx, (top + gy) / 2f, mz, th, aerial);
            cbar(mx, (top + gy) / 2f, mz, x, gy - 0.03f, z, th * 0.8f, aerial);
        }
    }

    /** Rumput berumpun dan bunga sebagai segitiga: satu draw call, bergoyang di vertex shader. */
    private void bakeMeadow() {
        Batch b = meadowB;
        java.util.Random r = new java.util.Random(21);
        int clumps = LITE ? 150 : 300, fl = LITE ? 18 : 40;
        int[] greens = {0xFF4E9A3A, 0xFF5FAE46, 0xFF3C7F2E, 0xFF74B650};
        for (int i = 0; i < clumps; i++) {
            double a = r.nextDouble() * 2.0 * Math.PI, rad = 2.9 + r.nextDouble() * 10.5;
            float x = (float) (rad * Math.cos(a)), z = (float) (rad * Math.sin(a)), y0 = hillAt(x, z) - 0.01f;
            for (int k = 0; k < 3; k++) {
                float ang = r.nextFloat() * 3.1416f, cx = (float) Math.cos(ang), sz = (float) Math.sin(ang);
                float h = 0.15f + 0.17f * r.nextFloat(), w = 0.035f + 0.02f * r.nextFloat();
                float ox = (r.nextFloat() - 0.5f) * 0.1f, oz = (r.nextFloat() - 0.5f) * 0.1f, lean = (r.nextFloat() - 0.5f) * 0.1f;
                tri(b, x + ox - cx * w, y0, z + oz - sz * w, x + ox + cx * w, y0, z + oz + sz * w,
                        x + ox + lean * sz, y0 + h, z + oz - lean * cx, greens[(i + k) % 4], 0f, 0f, 1f);
            }
        }
        int[] palette = {0xFFFF5C8D, 0xFFFFD54F, 0xFFFFFFFF, 0xFFBA68C8, 0xFFFF8A65, 0xFF64B5F6};
        for (int i = 0; i < fl; i++) {
            double a = r.nextDouble() * 2.0 * Math.PI, rad = 3.2 + r.nextDouble() * 9.5;
            float x = (float) (rad * Math.cos(a)), z = (float) (rad * Math.sin(a)), y0 = hillAt(x, z) - 0.01f;
            float h = 0.26f + 0.14f * r.nextFloat(), w = 0.02f;
            tri(b, x - w, y0, z, x + w, y0, z, x, y0 + h, z, 0xFF3E8E3A, 0f, 0f, 1f);
            tri(b, x, y0, z - w, x, y0, z + w, x, y0 + h, z, 0xFF3E8E3A, 0f, 0f, 1f);
            int col = palette[r.nextInt(palette.length)];
            place(x, y0 + h + 0.01f, z, 0f, 0f, 0f, 0.11f, 0.08f, 0.11f);
            bAdd(b, sph, mdl, col, 1f);
            place(x, y0 + h + 0.045f, z, 0f, 0f, 0f, 0.04f, 0.03f, 0.04f);
            bAdd(b, sph, mdl, 0xFFFFD54F, 1f);
        }
    }

    // ---------------------------------------------------------------- pantai
    private void bakeBeach() {
        umbrella(-4f, -3f, 0xFFE53935);
        umbrella(5.2f, -6.2f, 0xFFFFB300);
        box(cube, -4.5f, 0.012f, -1.4f, 0, 25f, 0, 0.9f, 0.022f, 1.9f, 0xFF2E86C1, 1f, 0f);
        box(cube, -4.5f, 0.014f, -1.4f, 0, 25f, 0, 0.15f, 0.022f, 1.9f, 0xFFFFFFFF, 1f, 0f);
        box(cube, -3.6f, 0.012f, -0.5f, 0, -20f, 0, 0.8f, 0.022f, 1.7f, 0xFFF4B400, 1f, 0f);
        palm(6f, 5f, 0.6f, 1.3f);
        palm(-7f, 7.5f, 2.4f, 1.0f);
        palm(8.5f, -2.5f, -0.4f, 1.5f);
        cbar(3.5f, 0.1f, -8.2f, 5.3f, 0.14f, -7.6f, 0.22f, 0xFF8C7A63);
        cbar(4.4f, 0.14f, -7.9f, 4.9f, 0.45f, -8.3f, 0.07f, 0xFF8C7A63);
        for (int i = 0; i < 5; i++) {
            float a = 1.1f + i * 1.3f, r = 9.5f + (i % 2) * 2f;
            float x = r * (float) Math.cos(a), z = r * (float) Math.sin(a);
            blob(x, 0.12f, z, 0.9f + 0.15f * i, 0.5f, 0.8f, i % 2 == 0 ? 0xFF7C7F82 : 0xFF6B6E71);
            blob(x + 0.5f, 0.07f, z + 0.3f, 0.4f, 0.22f, 0.4f, 0xFF8E9194);
        }
        // istana pasir kecil dengan bendera
        float cx = 1.5f, cz = -6.8f;
        int sand = 0xFFD9BE86;
        box(cyl, cx, 0.12f, cz, 0, 0, 0, 1.1f, 0.24f, 1.1f, sand, 1f, 0f);
        box(cyl, cx, 0.36f, cz, 0, 0, 0, 0.5f, 0.24f, 0.5f, sand, 1f, 0f);
        for (int i = 0; i < 4; i++) {
            float a = i * 1.5708f + 0.78f;
            box(cyl, cx + 0.5f * (float) Math.cos(a), 0.3f, cz + 0.5f * (float) Math.sin(a), 0, 0, 0, 0.22f, 0.6f, 0.22f, sand, 1f, 0f);
        }
        cbar(cx, 0.48f, cz, cx, 0.9f, cz, 0.012f, 0xFFEEEEEE);
        tri(beachEnvB, cx, 0.9f, cz, cx, 0.78f, cz, cx + 0.2f, 0.84f, cz, 0xFFE53935, 0f, 0f, 0f);
        for (int i = 0; i < 6; i++) {
            float a = 0.4f + i * 1.0f, r = 5.5f + i * 0.7f;
            blob(r * (float) Math.cos(a), 0.015f, r * (float) Math.sin(a), 0.1f, 0.04f, 0.08f, i % 2 == 0 ? 0xFFF8E1E1 : 0xFFFFF3E0);
        }
    }

    private void umbrella(float x, float z, int c1) {
        cbar(x, 0f, z, x, 2.7f, z, 0.06f, 0xFFF5F5F5);
        place(x, 2.6f, z, 0, 0, 0, 3.4f, 0.6f, 3.4f);
        submit(coneRed, c1, 1f, 0f, 0f, 0);
        place(x, 2.6f, z, 0, 0, 0, 3.4f, 0.6f, 3.4f);
        submit(coneWhite, 0xFFFFFFFF, 1f, 0f, 0f, 0);
        blob(x, 2.93f, z, 0.14f, 0.14f, 0.14f, 0xFFF5F5F5);
    }

    /** Pohon kelapa: batang melengkung beruas, pelepah berdaun menyirip yang menjuntai, dan buah kelapa. */
    private void palm(float tx, float tz, float dirA, float bend) {
        float dx = (float) Math.cos(dirA), dz = (float) Math.sin(dirA);
        int N = 14;
        float cx = tx, cy = 0f, cz = tz;
        for (int i = 0; i < N; i++) {
            float t0 = i / (float) N, t1 = (i + 1) / (float) N;
            float x0 = tx + dx * bend * t0 * t0, y0 = 5.0f * t0, z0 = tz + dz * bend * t0 * t0;
            float x1 = tx + dx * bend * t1 * t1, y1 = 5.0f * t1, z1 = tz + dz * bend * t1 * t1;
            cbar(x0, y0 - 0.03f, z0, x1, y1 + 0.03f, z1, 0.34f - 0.16f * t0, i % 2 == 0 ? 0xFF8B6B47 : 0xFF7A5C3C);
            cx = x1; cy = y1; cz = z1;
        }
        blob(cx, cy + 0.05f, cz, 0.34f, 0.26f, 0.34f, 0xFF5E4A30);
        for (int j = 0; j < 9; j++) {
            float psi = j * 0.698f + 0.2f * dirA, fx = (float) Math.cos(psi), fz = (float) Math.sin(psi);
            float px = -fz, pz = fx;
            int lc = j % 2 == 0 ? 0xFF3E9A3A : 0xFF2F7F2C;
            float lx = cx, ly = cy + 0.1f, lz = cz;
            for (int k = 1; k <= 6; k++) {
                float s = k / 6f, hd = 1.9f * s, ny = cy + 0.1f + 0.9f * s - 1.6f * s * s;
                float nx2 = cx + fx * hd, nz2 = cz + fz * hd;
                cbar(lx, ly, lz, nx2, ny, nz2, 0.03f, 0xFF5E7A3A);
                float len = 0.55f * (1f - 0.5f * s);
                for (int side = -1; side <= 1; side += 2) {
                    float tx2 = nx2 + px * side * len * 0.9f + fx * len * 0.3f;
                    float tz2 = nz2 + pz * side * len * 0.9f + fz * len * 0.3f;
                    tri(beachEnvB, lx, ly, lz, nx2, ny, nz2, tx2, ny - len * 0.5f, tz2, lc, 0f, 0f, 0f);
                }
                lx = nx2; ly = ny; lz = nz2;
            }
        }
        blob(cx + 0.14f, cy - 0.18f, cz, 0.22f, 0.22f, 0.22f, 0xFF5B3A1E);
        blob(cx - 0.12f, cy - 0.2f, cz + 0.1f, 0.22f, 0.22f, 0.22f, 0xFF6B4A2A);
        blob(cx + 0.02f, cy - 0.22f, cz - 0.14f, 0.22f, 0.22f, 0.22f, 0xFF5B3A1E);
    }

    // ================================================================ lingkungan per map (dinamis + mesh statis)
    private void roomEnv() {
        ambient(0.03f, 0.032f, 0.04f);
        place(0f, -0.05f, 0f, 0, 0, 0, 13f, 0.1f, 13f);
        submit(cube, 0xFF4A3322, 1f, 0f, 2f, 0);
        Matrix.setIdentityM(mdl, 0);
        drawBatch(roomM, 0f);
    }

    private void grassEnv() {
        ambient(0.36f, 0.38f, 0.32f);
        pm(sph, 0f, 0f, 0f, 70f, 70f, 70f, 0xFF3F86CC, 0xFFEAF4E4, 1f, 1f, 3f);
        pm(cylHi, 0f, -1.62f, 0f, 160f, 0.02f, 160f, 0xFF4E8A34, 0, 1f, 0f, 4f);
        pm(sph, 0f, -1.6f, 0f, 30f, 3.2f, 30f, 0xFF6BAE42, 0, 1f, 0f, 4f);
        Matrix.setIdentityM(mdl, 0);
        drawBatch(grassEnvM, 0f);
        drawBatch(meadowM, 0.09f);
    }

    private void beachEnv() {
        ambient(0.4f, 0.37f, 0.32f);
        pm(sph, 0f, 0f, 0f, 70f, 70f, 70f, 0xFF2F8FD8, 0xFFBFE8F5, 1f, 1f, 3f);
        pm(cylHi, 0f, -0.06f, 0f, 180f, 0.02f, 180f, 0xFF1B6FA8, 0xFF56C2E6, 1f, 0f, 5f);
        pm(cylHi, 0f, -0.02f, 0f, 28.8f, 0.02f, 28.8f, 0xFFFFFFFF, 0, 0.7f, 0f, 0f);
        pm(cylHi, 0f, -0.05f, 0f, 28f, 0.1f, 28f, 0xFFE9CF9A, 0, 1f, 0f, 4f);
        Matrix.setIdentityM(mdl, 0);
        drawBatch(beachEnvM, 0f);
    }

    /** Nyala api: kerucut kecil berkelip, emisif. */
    private void flame(float x, float y, float z, int i) {
        float fh = 0.11f * (0.85f + 0.15f * (float) Math.sin(t * 11f + i * 2.1f) + 0.05f * (float) Math.sin(t * 29f + i));
        box(sph, x, y + fh * 0.5f + 0.01f, z, 0, 0, 0, 0.05f, fh, 0.05f, 0xFFFFD27A, 1f, 1f);
    }

    /** Cahaya lilin: kabut hangat transparan di sekitar nyala. */
    private void halo(float x, float y, float z, float r) {
        float k = 0.85f + 0.15f * (float) Math.sin(t * 13f + x * 7f);
        box(sph, x, y, z, 0, 0, 0, r * 2f * k, r * 2f * k, r * 2f * k, 0xFFFFA940, 0.16f, 1f);
    }

    /**
     * Lilin: tiga lilin di lampu gantung yang berayun, dan tiga lilin di atas meja (ruang gelap saja).
     * glow = false: badan lilin dan piringan (opak). glow = true: kabut cahaya (transparan).
     */
    private void candles(boolean glow) {
        Matrix.setIdentityM(base, 0);
        if (!glow) {
            bar(0f, 4.6f, 0f, lp[0], lp[1] + 0.34f, lp[2], 0.012f, 0xFF111111);
            box(cyl, lp[0], lp[1] + 0.3f, lp[2], 0, 0, 0, 0.42f, 0.03f, 0.42f, 0xFF8A6A2A, 1f, 0f);
        }
        for (int i = 0; i < 3; i++) {
            float a = i * 2.0944f + 0.5f, rr = 0.15f, hgt = 0.26f + 0.06f * (i % 2);
            float cx = lp[0] + rr * (float) Math.cos(a), cz = lp[2] + rr * (float) Math.sin(a);
            float b0 = lp[1] + 0.315f;
            if (!glow) box(cyl, cx, b0 + hgt / 2f, cz, 0, 0, 0, 0.1f, hgt, 0.1f, 0xFFF3E7C9, 1f, 0f);
            else halo(cx, b0 + hgt + 0.05f, cz, 0.22f);
            if (!glow) flame(cx, b0 + hgt, cz, i);
        }
    }

    public void render(UnoEngine e, int mi, ArrayList<float[]> pile, int shownColor, float time) {
        float dt = Math.min(0.1f, Math.max(0f, time - lastT));
        lastT = time;
        t = time;
        setPlayers(e, mi);
        camAng += wrapAng(camTarget - camAng) * 0.14f;
        updateCamera();
        GLES20.glViewport(0, 0, pxW, pxH);
        GLES20.glDepthMask(true);
        if (map == 0) GLES20.glClearColor(0.008f, 0.008f, 0.01f, 1f);
        else GLES20.glClearColor(0.45f, 0.65f, 0.85f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(prog);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniform1f(uT, t);
        GLES20.glUniform1f(uSw, 0f);
        GLES20.glUniform1f(uSwT, t % 62.8318f);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aNrm);
        if (aUV >= 0) GLES20.glEnableVertexAttribArray(aUV);
        cur = null;
        c2 = 0;

        if (map == 0) {
            // lilin gantung berayun pelan + sesekali berkedip
            float a1 = 0.05f * (float) Math.sin(t * 0.8f), a2 = 0.04f * (float) Math.sin(t * 0.63f + 1f);
            lp[0] = 2f * (float) Math.sin(a1);
            lp[1] = 4.5f - 2f * (float) (Math.cos(a1) * Math.cos(a2));
            lp[2] = 2f * (float) Math.sin(a2);
            float fl = (t % 7f) > 6.6f ? 0.55f + 0.45f * (float) Math.abs(Math.sin(t * 60f)) : 1f;
            lightInt = 2.2f * fl;
        } else {
            // matahari: cahaya dari atas-samping
            lp[0] = map == 1 ? -5f : 3f;
            lp[1] = 11f;
            lp[2] = map == 1 ? -6f : -4f;
            lightInt = 40f;
        }
        GLES20.glUniform3f(uLP, lp[0], lp[1], lp[2]);
        GLES20.glUniform3f(uCam, 0f, 1.55f, CHAIR_R + 0.12f);
        GLES20.glUniform1f(uInt, lightInt);

        for (int i = 0; i < CHAIRS; i++) {
            if (blink[i] > 0f) blink[i] -= dt;
            nextBlink[i] -= dt;
            if (nextBlink[i] <= 0f) { blink[i] = 0.14f; nextBlink[i] = 1.5f + rnd.nextFloat() * 4f; }
        }

        // kepala mengejar arah pandang pemain, lewat jalur terpendek di lingkaran
        float ks = Math.min(1f, dt * 16f);
        for (int p = 0; p < CHAIRS; p++) {
            hy[p] = wrapAng(hy[p] + wrapAng(ty[p] - hy[p]) * ks);
            hp[p] += (tp[p] - hp[p]) * ks;
        }

        Matrix.setIdentityM(base, 0);
        topY = map == 0 ? TOP_Y : (map == 1 ? 0.035f : 0.012f);
        sitDrop = map == 0 ? 0f : 0.5f;
        if (map == 0) roomEnv(); else if (map == 1) grassEnv(); else beachEnv();
        computeAim();

        // kursi (ruang gelap) atau bantal duduk (luar ruangan); tempat duduk kita sendiri disembunyikan supaya tidak menutupi pandangan
        int own = (mi >= 0 && mi < n && mi < CHAIRS) ? chair[mi] : -1;
        for (int k = 0; k < CHAIRS; k++) {
            if (map == 0 && k == own) continue;
            float th = k * SEAT_STEP;
            setBase(CHAIR_R * (float) Math.sin(th), map == 0 ? 0f : (map == 1 ? 0.035f : 0f), CHAIR_R * (float) Math.cos(th), k * 36f + 180f);
            System.arraycopy(base, 0, mdl, 0, 16);
            drawBatch(map == 0 ? chairM : (map == 1 ? cushGrassM : cushBeachM), 0f);
        }
        Matrix.setIdentityM(base, 0);
        if (map == 0) radioGlow();
        Matrix.setIdentityM(base, 0);
        if (map == 0) candles(false);

        // dek dan tumpukan buang
        float hd = 0.004f + 0.1f * e.draw.size() / 100f;
        box(cube, DECK_X, topY + 0.003f + hd / 2f, 0f, 0, 0, 0, CW, hd, CH, 0xFF15162E, 1f, 0f);
        card(-1, DECK_X, topY + 0.0045f + hd, 0f, -90f, 0f, 0f, CW, CH);
        for (int i = 0; i < pile.size(); i++) {
            float[] pe = pile.get(i);
            card((int) pe[0], PILE_X + pe[2] * 0.004f, topY + 0.006f + i * 0.003f, pe[3] * 0.004f,
                    -90f, -(float) Math.toDegrees(pe[1]), 0f, CW, CH);
        }
        // karakter
        for (int p = 0; p < e.players.size() && p < CHAIRS; p++) if (p != mi) character(e, p);

        // translusen: cahaya warna aktif di meja + titik orbit arah putaran + kabut lilin
        Matrix.setIdentityM(base, 0);
        GLES20.glDepthMask(false);
        int sc = Math.max(0, Math.min(3, shownColor));
        box(cyl, PILE_X, topY + 0.0035f, 0f, 0, 0, 0, 0.95f, 0.002f, 0.95f, GameRenderer.CL[sc], 0.5f, 0.85f);
        int dots = LITE ? 6 : 12;
        for (int i = 0; i < dots; i++) {
            float a = t * 0.9f * e.dir + i * 6.2832f / dots;
            box(cube, PILE_X + (float) Math.cos(a) * 0.4f, topY + 0.012f, (float) Math.sin(a) * 0.4f, 0, 0, 0,
                    0.03f, 0.012f, 0.03f, GameRenderer.CL[sc], 0.95f, 1f);
        }
        if (map == 0) candles(true);
        GLES20.glDepthMask(true);
        drawAim(e);

        GLES20.glDisableVertexAttribArray(aNrm);
        if (aUV >= 0) GLES20.glDisableVertexAttribArray(aUV);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
    }

    // ================================================================ bidikan crosshair
    /** Cari benda yang paling dekat dengan titik tengah layar: dek, kursi kosong, atau pemain. */
    private void computeAim() {
        aimKind = 0;
        aimSeat = -1;
        aimPlayer = -1;
        if (!aimOn) return;
        float best = 1f;
        int own = (me >= 0 && me < n && me < CHAIRS) ? chair[me] : -1;
        float[] d = project(DECK_X, topY + 0.05f, 0f);
        if (d != null) {
            float r = Math.max(46f, 0.30f * d[2]);
            float dx = d[0] - crossX, dy = d[1] - crossY;
            float q = (float) Math.sqrt(dx * dx + dy * dy) / r;
            if (q < best) { best = q; aimKind = 3; }
        }
        for (int k = 0; k < CHAIRS; k++) {
            if (k == own) continue;
            float th = k * SEAT_STEP, x = CHAIR_R * (float) Math.sin(th), z = CHAIR_R * (float) Math.cos(th);
            boolean occ = seatOf[k] >= 0;
            float y = occ ? groundAt(x, z) - sitDrop + 1.0f : (map == 0 ? 0.55f : topY + 0.1f);
            float[] sc = project(x, y, z);
            if (sc == null) continue;
            float r = Math.max(46f, (occ ? 0.34f : 0.30f) * sc[2]);
            float dx = sc[0] - crossX, dy = sc[1] - crossY;
            float q = (float) Math.sqrt(dx * dx + dy * dy) / r;
            if (q < best) {
                best = q;
                aimKind = occ ? 2 : 1;
                aimSeat = k;
                aimPlayer = occ ? seatOf[k] : -1;
            }
        }
    }

    /** Outline kotak kawat menyala pada benda yang sedang dibidik. */
    private void drawAim(UnoEngine e) {
        if (aimKind == 0) return;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        int col = 0xFFFFE066;
        float th = 0.012f;
        if (aimKind == 3) {
            Matrix.setIdentityM(base, 0);
            float hd = 0.004f + 0.1f * e.draw.size() / 100f;
            obox(DECK_X, topY + 0.003f + hd / 2f, 0f, CW + 0.05f, hd + 0.025f, CH + 0.05f, th, col);
        } else if (aimSeat >= 0) {
            float a = aimSeat * SEAT_STEP, x = CHAIR_R * (float) Math.sin(a), z = CHAIR_R * (float) Math.cos(a);
            float yaw = aimSeat * 36f + 180f;
            if (aimKind == 2) {
                setBase(x, groundAt(x, z) - sitDrop, z, yaw);
                obox(0f, 1.0f, 0.03f, 0.6f, 1.12f, 0.52f, th, col);
            } else if (map == 0) {
                setBase(x, 0f, z, yaw);
                obox(0f, 0.5f, 0f, 0.56f, 1.02f, 0.56f, th, col);
            } else {
                setBase(x, map == 1 ? 0.035f : 0f, z, yaw);
                obox(0f, 0.045f, 0f, 0.62f, 0.1f, 0.62f, th, col);
            }
        }
        Matrix.setIdentityM(base, 0);
    }

    /** Kipas kartu (m kartu) sebagai satu mesh. Posisi sama dengan kartu satu per satu di tangan karakter. */
    private Mesh fanMesh(int m) {
        if (fan[m] != null) return fan[m];
        float vm = 371f / 512f, w = 0.135f, h = 0.195f;
        float[] v = new float[m * 4 * 8];
        short[] ix = new short[m * 6];
        float step = m > 1 ? Math.min(8f, 60f / (m - 1)) : 0f;
        int p = 0;
        float[][] cr = {{-w, h, 0f, 0f}, {w, h, 1f, 0f}, {w, -h, 1f, vm}, {-w, -h, 0f, vm}};
        for (int i = 0; i < m; i++) {
            float ph = (i - (m - 1) / 2f) * step, rr = (float) Math.toRadians(ph);
            float c = (float) Math.cos(rr), s = (float) Math.sin(rr);
            float cx = -0.19f * s, cy = 0.86f + 0.19f * c, cz = 0.414f + i * 0.003f;
            for (int k = 0; k < 4; k++) {
                float x = cr[k][0], y = cr[k][1];
                v[p++] = cx + x * c - y * s; v[p++] = cy + x * s + y * c; v[p++] = cz;
                v[p++] = 0f; v[p++] = 0f; v[p++] = 1f;
                v[p++] = cr[k][2]; v[p++] = cr[k][3];
            }
            int o = i * 4, q = i * 6;
            ix[q] = (short) o; ix[q + 1] = (short) (o + 1); ix[q + 2] = (short) (o + 2);
            ix[q + 3] = (short) o; ix[q + 4] = (short) (o + 2); ix[q + 5] = (short) (o + 3);
        }
        fan[m] = mk(v, ix);
        return fan[m];
    }

    /** Layar kotak radio untuk area sentuh. null bila di belakang kamera. */
    public float[] radioScreen() { return project(0f, TOP_Y + 0.12f, -0.62f); }

    /** Tekstur wajah pemain dengan id tertentu, atau 0 bila belum ada. */
    public int faceTexFor(String id) {
        if (id == null) return 0;
        Integer t = faceTex.get(id);
        return t == null ? 0 : t;
    }

    /** Foto wajah (base64 PNG) menjadi tekstur. Hanya dipanggil dari thread GL. */
    public void setFace(String id, String b64) {
        if (id == null || b64 == null) return;
        try {
            byte[] d = Base64.decode(b64, Base64.DEFAULT);
            Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length);
            if (b == null) return;
            Integer old = faceTex.get(id);
            if (old != null) GLES20.glDeleteTextures(1, new int[]{old}, 0);
            int[] tx = new int[1];
            GLES20.glGenTextures(1, tx, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tx[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            b.recycle();
            faceTex.put(id, tx[0]);
        } catch (IllegalArgumentException e) {
            Log.w("UnoGL3D", "foto wajah tidak valid", e);
        }
    }

    /** Pratinjau: satu karakter 3D di kotak (xv, yv, wv, hv) layar virtual. Hanya menghapus kedalaman di kotak itu. */
    public void renderPreview(UnoEngine.P pl, float xv, float yv, float wv, float hv, float time) {
        float sc = g.scale;
        int px = Math.round((xv + g.padX) * sc), pw = Math.max(1, Math.round(wv * sc));
        int ph = Math.max(1, Math.round(hv * sc)), py = pxH - Math.round((yv + hv) * sc);
        t = time;
        GLES20.glViewport(px, py, pw, ph);
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(px, py, pw, ph);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(prog);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniform1f(uT, t);
        GLES20.glUniform1f(uSw, 0f);
        GLES20.glUniform1f(uSwT, 0f);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aNrm);
        if (aUV >= 0) GLES20.glEnableVertexAttribArray(aUV);
        cur = null;
        c2 = 0;

        Matrix.perspectiveM(proj, 0, 30f, (float) pw / ph, 0.1f, 20f);
        Matrix.setLookAtM(view, 0, 0f, 1.15f, -0.55f, 0f, 0.95f, 2.2f, 0f, 1f, 0f);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        ambient(0.32f, 0.32f, 0.36f);
        GLES20.glUniform3f(uLP, 0.8f, 2.6f, -1.2f);
        GLES20.glUniform3f(uCam, 0f, 1.15f, -0.55f);
        GLES20.glUniform1f(uInt, 4f);

        // karakter tunggal di posisi kursi 0; kepala dan badan lurus ke kamera
        int savedChair = chair[0];
        float savedDrop = sitDrop;
        chair[0] = 0;
        sitDrop = 0.5f;
        hy[0] = 0f; hp[0] = 0f; ty[0] = 0f; tp[0] = 0f;
        UnoEngine e = new UnoEngine();
        e.players.add(pl);
        e.turn = -1;
        Matrix.setIdentityM(base, 0);
        character(e, 0);
        chair[0] = savedChair;
        sitDrop = savedDrop;

        GLES20.glDisableVertexAttribArray(aNrm);
        if (aUV >= 0) GLES20.glDisableVertexAttribArray(aUV);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    /** Kepala: yaw (belok) lalu pitch (dongak/tunduk) di pusat kepala, lalu offset lokal (mata). */
    private void headPlace(float cy, float yawDeg, float pitchDeg, float ox, float oy, float oz,
                           float sx, float sy, float sz) {
        System.arraycopy(base, 0, mdl, 0, 16);
        Matrix.translateM(mdl, 0, 0f, cy, 0f);
        Matrix.rotateM(mdl, 0, yawDeg, 0f, 1f, 0f);
        Matrix.rotateM(mdl, 0, pitchDeg, 1f, 0f, 0f);
        Matrix.translateM(mdl, 0, ox, oy, oz);
        Matrix.scaleM(mdl, 0, sx, sy, sz);
    }

    private void acc(float hcy, float hy, float hp, float ox, float oy, float oz, float sx, float sy, float sz, int col, float a) {
        headPlace(hcy, hy, hp, ox, oy, oz, sx, sy, sz);
        submit(cube, col, a, 0f, 0f, 0);
    }

    private void glasses(float hcy, float hy, float hp, int frame, int lens, float la) {
        for (int s = -1; s <= 1; s += 2) {
            acc(hcy, hy, hp, s * 0.085f, 0.03f, 0.19f, 0.14f, 0.075f, 0.012f, frame, 1f);
            acc(hcy, hy, hp, s * 0.085f, 0.03f, 0.2f, 0.12f, 0.06f, 0.005f, lens, la);
        }
        acc(hcy, hy, hp, 0f, 0.035f, 0.19f, 0.04f, 0.012f, 0.012f, frame, 1f);
    }

    /** Topi dan aksesoris wajah. Puncak kepala = hcy + 0.17; topi dan rambut menempel tepat di sana. */
    private void accessories(UnoEngine.P pl, float hcy, float hyDeg, float hpDeg) {
        int c = pl.acc != 0 ? pl.acc : ACC_COL[((pl.col % ACC_COL.length) + ACC_COL.length) % ACC_COL.length];
        final float TOP = 0.17f;
        switch (pl.hat) {
            case 1: // topi koboi
                acc(hcy, hyDeg, hpDeg, 0f, TOP + 0.12f, 0f, 0.37f, 0.24f, 0.37f, c, 1f);
                acc(hcy, hyDeg, hpDeg, 0f, TOP, 0f, 0.66f, 0.025f, 0.66f, c, 1f);
                acc(hcy, hyDeg, hpDeg, 0f, TOP + 0.02f, 0f, 0.375f, 0.05f, 0.375f, INK, 1f);
                break;
            case 2: // topi kasual dengan visor ke depan
                acc(hcy, hyDeg, hpDeg, 0f, TOP + 0.09f, 0f, 0.37f, 0.18f, 0.37f, c, 1f);
                acc(hcy, hyDeg, hpDeg, 0f, TOP + 0.03f, 0.2f, 0.36f, 0.03f, 0.22f, c, 1f);
                break;
            case 3: // kopyah ustad
                acc(hcy, hyDeg, hpDeg, 0f, TOP + 0.02f, 0f, 0.33f, 0.04f, 0.33f, INK, 1f);
                acc(hcy, hyDeg, hpDeg, 0f, TOP + 0.1f, 0f, 0.3f, 0.15f, 0.3f, c, 1f);
                break;
            case 4: // topi tentara
                acc(hcy, hyDeg, hpDeg, 0f, TOP + 0.09f, 0f, 0.4f, 0.18f, 0.4f, c, 1f);
                acc(hcy, hyDeg, hpDeg, 0f, TOP + 0.03f, 0.2f, 0.42f, 0.04f, 0.12f, c, 1f);
                break;
            case 5: // rambut punk: duri-duri condong ke depan, arah wajah
                for (int i = 0; i < 5; i++) spike(hcy, hyDeg, hpDeg, 0f, TOP + 0.1f, -0.12f + i * 0.06f, 35f, c);
                break;
            default: break;
        }
        switch (pl.face) {
            case 1: // topeng anime
                acc(hcy, hyDeg, hpDeg, 0f, 0.03f, 0.178f, 0.34f, 0.27f, 0.012f, 0xFFF6F1EA, 1f);
                for (int s = -1; s <= 1; s += 2) acc(hcy, hyDeg, hpDeg, s * 0.085f, 0.05f, 0.19f, 0.09f, 0.12f, 0.01f, INK, 1f);
                break;
            case 2: // rokok di mulut: batang dan bara menghadap ke depan, asap naik dari ujungnya
                acc(hcy, hyDeg, hpDeg, 0f, -0.09f, 0.25f, 0.018f, 0.018f, 0.16f, 0xFFF2F2F2, 1f);
                acc(hcy, hyDeg, hpDeg, 0f, -0.09f, 0.335f, 0.022f, 0.022f, 0.022f, 0xFFFF6A1A, 1f);
                if (!LITE) for (int i = 0; i < 4; i++) {
                    float fr = (t * 0.3f + i / 4f) % 1f;
                    float sz = 0.035f + fr * 0.1f;
                    acc(hcy, hyDeg, hpDeg, 0.03f * (float) Math.sin(t * 1.3f + i), -0.09f + fr * 0.45f, 0.36f + fr * 0.1f,
                            sz, sz, sz, 0xFFD0D0D0, (1f - fr) * 0.35f);
                }
                break;
            case 3: // masker mulut
                acc(hcy, hyDeg, hpDeg, 0f, -0.1f, 0.178f, 0.24f, 0.13f, 0.02f, 0xFFFFFFFF, 1f);
                break;
            case 4: glasses(hcy, hyDeg, hpDeg, INK, 0xFF0B0B0B, 0.9f); break;   // kaca mata hitam
            case 5: glasses(hcy, hyDeg, hpDeg, 0xFFB8C7D9, 0xFFEAF6FF, 0.25f); break; // kaca mata bening
            default: break;
        }
    }

    /** Satu duri rambut: silinder kecil di kepala yang dimiringkan ke depan. */
    private void spike(float hcy, float hyDeg, float hpDeg, float ox, float oy, float oz, float tilt, int col) {
        headPlace(hcy, hyDeg, hpDeg, 0f, 0f, 0f, 1f, 1f, 1f);
        Matrix.translateM(mdl, 0, ox, oy, oz);
        Matrix.rotateM(mdl, 0, tilt, 1f, 0f, 0f);
        Matrix.scaleM(mdl, 0, 0.06f, 0.24f, 0.06f);
        submit(cube, col, 1f, 0f, 0f, 0);
    }

    private void character(UnoEngine e, int p) {
        int k = chair[p], ci = p % GameRenderer.CL.length;
        float th = k * SEAT_STEP, yawDeg = k * 36f + 180f;
        float bx = CHAIR_R * (float) Math.sin(th), bz = CHAIR_R * (float) Math.cos(th);
        setBase(bx, groundAt(bx, bz) - sitDrop, bz, yawDeg);
        UnoEngine.P pl = e.players.get(p);
        int ft = faceTexFor(pl.id);
        int shirt = pl.body != 0 ? pl.body : GameRenderer.CD[ci];
        int skin = pl.skin != 0 ? pl.skin : GameRenderer.CL[ci];
        boolean turn = e.turn == p && !e.over;
        float bob = (e.over && e.winner == p) ? 0.05f * (float) Math.abs(Math.sin(t * 6f)) : 0f;
        box(cube, 0f, 0.8f + bob, 0f, 0, 0, 0, 0.46f, 0.55f, 0.26f, shirt, 1f, 0f);

        // kepala ikut arah pandang pemain: yaw = belok kiri/kanan, pitch = dongak/tunduk
        float hcy = 1.34f + bob;
        float hyDeg = (float) Math.toDegrees(hy[p]);
        float hpDeg = (float) Math.toDegrees(-hp[p] * 0.8f);
        float glowH = turn ? 0.18f + 0.15f * (float) Math.sin(t * 6f) : 0f;
        headPlace(hcy, hyDeg, hpDeg, 0f, 0f, 0f, 0.36f, 0.34f, 0.34f);
        submit(cube, skin, 1f, glowH, 0f, 0);

        // mata: kedip + pupil mengikuti pemain yang sedang giliran (ikut kepala)
        float es = blink[p] > 0f ? 0.1f : 1f, po = 0f;
        if (!e.over && e.turn != p && e.turn >= 0 && e.turn < e.players.size() && e.turn < CHAIRS) {
            float tk = chair[e.turn] * SEAT_STEP;
            float tx = CHAIR_R * ((float) Math.sin(tk) - (float) Math.sin(th));
            float tz = CHAIR_R * ((float) Math.cos(tk) - (float) Math.cos(th));
            float len = (float) Math.sqrt(tx * tx + tz * tz);
            float yw = (float) Math.toRadians(yawDeg);
            if (len > 0.01f) po = Math.max(-1f, Math.min(1f, (tx * (float) Math.cos(yw) - tz * (float) Math.sin(yw)) / len)) * 0.022f;
        }
        if (ft == 0) for (int s = -1; s <= 1; s += 2) {
            headPlace(hcy, hyDeg, hpDeg, s * 0.085f, 0.03f, 0.171f, 0.075f, 0.075f * es, 0.012f);
            submit(cube, 0xFFFFFFFF, 1f, 0.9f, 0f, 0);
            headPlace(hcy, hyDeg, hpDeg, s * 0.085f + po, 0.03f, 0.178f, 0.036f, 0.05f * es, 0.012f);
            submit(cube, 0xFF101010, 1f, 0f, 0f, 0);
        }
        if (ft != 0) {
            headPlace(hcy, hyDeg, hpDeg, 0f, 0.0f, 0.178f, 0.34f, 0.34f, 0.01f);
            submit(faceQuad, 0xFFFFFFFF, 1f, 0f, 1f, ft);
        }
        accessories(pl, hcy, hyDeg, hpDeg);
        // penanda giliran / siap ditembak / pemenang
        boolean win = e.over && e.winner == p;
        if (turn || win) {
            int mc = win ? 0xFFFFD83D : 0xFFFFE066;
            float ms = win ? 0.17f : 0.11f;
            box(cube, 0f, 1.74f + 0.04f * (float) Math.sin(t * 4f), 0f, 45f, t * 120f, 45f, ms, ms, ms, mc, 1f, 1f);
        }
        // lengan, tangan kotak, dan kipas kartu yang menutupi badan (hanya mata yang mengintip)
        float py = 0.84f, pz = 0.41f;
        for (int s = -1; s <= 1; s += 2) {
            bar(s * 0.25f, 1.0f, 0f, s * 0.075f, py + 0.02f, pz, 0.085f, shirt);
            box(cube, s * 0.075f, py + 0.02f, pz, 0, 0, 0, 0.09f, 0.09f, 0.09f, skin, 1f, 0f);
        }
        int m = Math.min(LITE ? 5 : 8, pl.hand.size());
        if (m > 0) {
            System.arraycopy(base, 0, mdl, 0, 16);
            submit(fanMesh(m), 0xFFFFFFFF, 1f, 0f, 1f, faceTex(-1));
        }
    }
}
