package com.hvxino.nixoplay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;

/**
 * Nixo Play ka apna visualizer. Teen layers, paanch styles, paanch color themes.
 * Sirf tab redraw karta hai jab music chal raha ho ya animation khatam ho rahi ho.
 */
public class VisualizerView extends View {
    public static final int OFF = 0, BARS = 1, WAVE = 2, CIRCLE = 3, PARTICLES = 4, SPECTRUM = 5;
    public static final String[] STYLE_NAMES = {"Off", "Bars", "Wave", "Circle", "Particles", "Spectrum"};
    public static final String[] THEME_NAMES = {"Nixo Neon", "Sunset", "Aurora", "Ice", "Fire"};
    private static final int[][] THEMES = {
            {0xFF22E5FF, 0xFF8B5CF6, 0xFFFF3DCB},
            {0xFFFFB300, 0xFFFF5722, 0xFFE91E63},
            {0xFF00E676, 0xFF00B0FF, 0xFF7C4DFF},
            {0xFFFFFFFF, 0xFF80D8FF, 0xFF2979FF},
            {0xFFFFEB3B, 0xFFFF9800, 0xFFF44336}};
    private static final int BANDS = 48, WAVE_N = 96, PN = 110;

    // settings (sab 0..100, offsets -50..50)
    public int sens = 60, size = 60, thick = 40, speed = 50, inten = 70, theme = 0;
    public int[] lStyle = {BARS, OFF, OFF};
    public int[] lx = {0, 0, 0};
    public int[] ly = {0, 0, 0};

    private final float[] target = new float[BANDS];
    private final float[] bands = new float[BANDS];
    private final float[] waveT = new float[WAVE_N];
    private final float[] wave = new float[WAVE_N];
    private float bass, mid, treb, rot, spawnAcc;
    private boolean active;
    private long last;
    private Bitmap bg;
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint glowPaint = new Paint();
    private final Path path = new Path();
    private final Matrix mx = new Matrix();
    private Shader shader;
    private final float[] px = new float[PN], py = new float[PN], pvx = new float[PN],
            pvy = new float[PN], plife = new float[PN], pmax = new float[PN];
    private final Random rnd = new Random();

    public VisualizerView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    // ----- input -----
    public void setActive(boolean a) {
        active = a;
        postInvalidateOnAnimation();
    }

    public void feedFft(byte[] f) {
        int n = f.length / 2;
        int maxBin = Math.max(8, (int) (n * 0.55f));
        for (int b = 0; b < BANDS; b++) {
            int lo = Math.max(1, (int) Math.pow(maxBin, (double) b / BANDS));
            int hi = Math.max(lo + 1, (int) Math.pow(maxBin, (double) (b + 1) / BANDS));
            float m = 0;
            for (int k = lo; k < hi && k < n; k++) {
                float re = f[2 * k], im = f[2 * k + 1];
                float mag = (float) Math.sqrt(re * re + im * im);
                if (mag > m) m = mag;
            }
            float v = (float) (Math.log10(1 + m) / 2.2);
            v *= 0.8f + 0.6f * b / BANDS;
            target[b] = v;
        }
        postInvalidateOnAnimation();
    }

    public void feedWave(byte[] w) {
        int step = Math.max(1, w.length / WAVE_N);
        for (int i = 0; i < WAVE_N && i * step < w.length; i++) {
            waveT[i] = ((w[i * step] & 0xFF) - 128) / 128f;
        }
        postInvalidateOnAnimation();
    }

    public void setBackgroundBitmap(Bitmap b) {
        bg = b;
        postInvalidateOnAnimation();
    }

    public void setTheme(int t) {
        theme = t;
        rebuildShader();
        postInvalidateOnAnimation();
    }

    // ----- config -----
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("sens", sens).put("size", size).put("thick", thick)
                    .put("speed", speed).put("inten", inten).put("theme", theme);
            JSONArray la = new JSONArray();
            for (int i = 0; i < 3; i++) {
                la.put(new JSONObject().put("s", lStyle[i]).put("x", lx[i]).put("y", ly[i]));
            }
            o.put("layers", la);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** @return true agar JSON sahi tha */
    public boolean fromJson(String js) {
        try {
            JSONObject o = new JSONObject(js);
            sens = clampI(o.optInt("sens", sens), 0, 100);
            size = clampI(o.optInt("size", size), 0, 100);
            thick = clampI(o.optInt("thick", thick), 0, 100);
            speed = clampI(o.optInt("speed", speed), 0, 100);
            inten = clampI(o.optInt("inten", inten), 0, 100);
            theme = clampI(o.optInt("theme", theme), 0, THEMES.length - 1);
            JSONArray la = o.optJSONArray("layers");
            if (la != null) {
                for (int i = 0; i < 3 && i < la.length(); i++) {
                    JSONObject l = la.getJSONObject(i);
                    lStyle[i] = clampI(l.optInt("s", OFF), 0, STYLE_NAMES.length - 1);
                    lx[i] = clampI(l.optInt("x", 0), -50, 50);
                    ly[i] = clampI(l.optInt("y", 0), -50, 50);
                }
            }
            rebuildShader();
            postInvalidateOnAnimation();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private float sensF() { return 0.3f + sens / 100f * 2.2f; }
    private float sizeF() { return 0.4f + size / 100f * 1.1f; }
    private float thickPx() { return d * (1.5f + thick / 100f * 14f); }
    private float speedF() { return 0.2f + speed / 100f * 2.8f; }
    private float intenF() { return inten / 100f; }

    // ----- drawing -----
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuildShader();
        glowPaint.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.6f,
                0xFF2B2468, 0x000B0A1A, Shader.TileMode.CLAMP));
    }

    private void rebuildShader() {
        int w = getWidth();
        if (w <= 0) return;
        shader = new LinearGradient(0, 0, w, 0, THEMES[theme], null, Shader.TileMode.CLAMP);
    }

    private boolean uses(int style) {
        for (int i = 0; i < 3; i++) if (lStyle[i] == style) return true;
        return false;
    }

    private void update(float dt) {
        float up = Math.min(1f, dt * 20f), down = Math.min(1f, dt * 6f), sf = sensF();
        for (int i = 0; i < BANDS; i++) {
            float t = Math.min(1f, target[i] * sf);
            bands[i] += (t - bands[i]) * (t > bands[i] ? up : down);
            if (!active) target[i] *= Math.max(0f, 1f - dt * 3f);
        }
        float a = 0, b = 0, c = 0;
        for (int i = 0; i < 6; i++) a += bands[i];
        for (int i = 6; i < 25; i++) b += bands[i];
        for (int i = 25; i < BANDS; i++) c += bands[i];
        bass = a / 6f;
        mid = b / 19f;
        treb = c / 23f;
        rot += dt * speedF() * 0.5f;
        float wk = Math.min(1f, dt * 14f);
        for (int i = 0; i < WAVE_N; i++) {
            wave[i] += (waveT[i] - wave[i]) * wk;
            if (!active) waveT[i] *= 0.9f;
        }
        if (uses(PARTICLES)) updateParticles(dt);
    }

    private void updateParticles(float dt) {
        if (active) {
            spawnAcc += dt * (14f + bass * 80f * sensF());
            while (spawnAcc >= 1f) {
                spawnAcc -= 1f;
                for (int i = 0; i < PN; i++) {
                    if (plife[i] <= 0f) {
                        float a = rnd.nextFloat() * 6.2832f;
                        float sp = d * (40f + mid * 200f * speedF() + rnd.nextFloat() * 60f);
                        px[i] = (float) Math.cos(a) * d * 10f;
                        py[i] = (float) Math.sin(a) * d * 10f;
                        pvx[i] = (float) Math.cos(a) * sp;
                        pvy[i] = (float) Math.sin(a) * sp;
                        pmax[i] = plife[i] = 0.9f + rnd.nextFloat() * 1.4f;
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < PN; i++) {
            if (plife[i] > 0f) {
                px[i] += pvx[i] * dt;
                py[i] += pvy[i] * dt;
                plife[i] -= dt;
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        update(dt);
        drawBg(c);
        for (int i = 0; i < 3; i++) {
            if (lStyle[i] == OFF) continue;
            c.save();
            c.translate(lx[i] / 100f * getWidth() * 0.5f, ly[i] / 100f * getHeight() * 0.5f);
            drawLayer(c, lStyle[i]);
            c.restore();
        }
        boolean moving = active;
        if (!moving) {
            for (int i = 0; i < BANDS; i++) {
                if (bands[i] > 0.01f) {
                    moving = true;
                    break;
                }
            }
        }
        if (moving) postInvalidateOnAnimation();
        else last = 0;
    }

    private void drawBg(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFF0B0A1A);
        if (bg != null) {
            float s = Math.max((float) w / bg.getWidth(), (float) h / bg.getHeight())
                    * (1f + bass * 0.04f * intenF());
            mx.reset();
            mx.postScale(s, s);
            mx.postTranslate((w - bg.getWidth() * s) / 2f, (h - bg.getHeight() * s) / 2f);
            c.drawBitmap(bg, mx, bgPaint);
            c.drawColor(0x66000000);
        } else if (glowPaint.getShader() != null) {
            glowPaint.setAlpha((int) (70 + 185 * Math.min(1f, bass)));
            c.drawRect(0, 0, w, h, glowPaint);
        }
    }

    private static float clamp1(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private void drawLayer(Canvas c, int style) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        float sz = sizeF(), th = thickPx(), in = intenF(), sf = sensF();
        p.setShader(shader);
        p.setStyle(Paint.Style.STROKE);

        switch (style) {
            case BARS: {
                float span = w * 0.94f * Math.min(1f, sz);
                float gap = span / BANDS;
                float maxH = h * 0.55f * Math.min(sz, 1.4f);
                float bw = Math.max(d * 1.5f, Math.min(th, gap * 0.85f));
                float baseY = cy + maxH * 0.5f, x0 = cx - span / 2f + gap / 2f;
                for (int pass = in > 0.45f ? 0 : 1; pass < 2; pass++) {
                    p.setStrokeWidth(pass == 0 ? bw * 2.6f : bw);
                    p.setAlpha(pass == 0 ? (int) (50 * in) : (int) (120 + 135 * in));
                    for (int i = 0; i < BANDS; i++) {
                        float x = x0 + gap * i;
                        float hh = Math.max(bw * 0.5f, bands[i] * maxH);
                        c.drawLine(x, baseY, x, baseY - hh, p);
                    }
                }
                break;
            }
            case WAVE: {
                float span = w * 0.94f * Math.min(1f, sz);
                float amp = h * 0.4f * Math.min(sz, 1.4f);
                float x0 = cx - span / 2f, step = span / (WAVE_N - 1);
                float prevX = x0, prevY = cy + clamp1(wave[0] * sf * 1.8f) * amp;
                path.reset();
                path.moveTo(prevX, prevY);
                for (int i = 1; i < WAVE_N; i++) {
                    float x = x0 + step * i, y = cy + clamp1(wave[i] * sf * 1.8f) * amp;
                    path.quadTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f);
                    prevX = x;
                    prevY = y;
                }
                path.lineTo(prevX, prevY);
                for (int pass = in > 0.45f ? 0 : 1; pass < 2; pass++) {
                    p.setStrokeWidth(pass == 0 ? th * 3f : Math.max(d * 2f, th * 0.7f));
                    p.setAlpha(pass == 0 ? (int) (45 * in) : (int) (130 + 125 * in));
                    c.drawPath(path, p);
                }
                break;
            }
            case CIRCLE: {
                float rad = Math.min(w, h) * 0.16f * sz * (1f + bass * 0.18f);
                int n = 96;
                float bw = Math.max(d * 2f, th * 0.55f);
                for (int pass = in > 0.45f ? 0 : 1; pass < 2; pass++) {
                    p.setStrokeWidth(pass == 0 ? bw * 2.4f : bw);
                    p.setAlpha(pass == 0 ? (int) (50 * in) : (int) (120 + 135 * in));
                    for (int i = 0; i < n; i++) {
                        int j = i < n / 2 ? i : n - 1 - i;
                        int b = Math.min(BANDS - 1, j * BANDS / (n / 2));
                        float len = Math.max(d * 2f, bands[b] * rad);
                        double a = rot + 2 * Math.PI * i / n;
                        float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                        c.drawLine(cx + cs * rad, cy + sn * rad,
                                cx + cs * (rad + len), cy + sn * (rad + len), p);
                    }
                }
                p.setStrokeWidth(bw * 0.7f);
                p.setAlpha(150);
                c.drawCircle(cx, cy, rad * 0.9f, p);
                break;
            }
            case PARTICLES: {
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                int[] cols = THEMES[theme];
                for (int i = 0; i < PN; i++) {
                    if (plife[i] <= 0f) continue;
                    float k = plife[i] / pmax[i];
                    int al = (int) (255 * k * (0.45f + 0.55f * in));
                    p.setColor((cols[i % 3] & 0x00FFFFFF) | (al << 24));
                    float r = d * 1.2f + th * 0.25f + treb * d * 6f;
                    c.drawCircle(cx + px[i], cy + py[i], r * (0.5f + 0.5f * k), p);
                }
                break;
            }
            case SPECTRUM: {
                float span = w * 0.94f * Math.min(1f, sz);
                float maxH = h * 0.46f * Math.min(sz, 1.4f);
                int m = 64;
                path.reset();
                path.moveTo(cx - span / 2f, cy);
                for (int k = 0; k <= m; k++) {
                    float t = (float) k / m;
                    int bi = (int) (Math.abs(t - 0.5f) * 2f * (BANDS - 1));
                    path.lineTo(cx - span / 2f + span * t, cy - Math.max(d, bands[bi] * maxH));
                }
                path.lineTo(cx + span / 2f, cy);
                path.close();
                p.setStyle(Paint.Style.FILL);
                p.setAlpha((int) (70 + 120 * in));
                c.drawPath(path, p);
                c.save();
                c.scale(1f, -1f, cx, cy);
                c.drawPath(path, p);
                c.restore();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(d * 1.5f, th * 0.4f));
                p.setAlpha(220);
                c.drawPath(path, p);
                c.save();
                c.scale(1f, -1f, cx, cy);
                c.drawPath(path, p);
                c.restore();
                break;
            }
            default:
                break;
        }
    }
}
