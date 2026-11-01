package dev.horizon.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;

import java.util.Random;

/**
 * Launch splash for HorizonZ, shared by the launcher (`HorizonZBlackSplashOverlay`) and the game process
 * (`HorizonActivity.HorizonSplashOverlayView`) so the hand-off between the two is seamless.
 *
 * Layout: a sunrise-over-the-horizon emblem inside a progress ring on one side, the tracked "HORIZONZ"
 * wordmark with a stage label and a thin gradient progress line on the other (stacked in portrait).
 * Everything is driven by one clock `t` (seconds) so both processes can continue the same animation.
 */
public final class HorizonSplashRenderer {
    private static final int CYAN = 0xFF4FD8FF;
    private static final int VIOLET = 0xFF8B6CFF;
    private static final int WARM_LIGHT = 0xFFFFF1D0;
    private static final int WARM_DEEP = 0xFFFFA45C;
    private static final int PARTICLES = 42;

    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface mono = Typeface.MONOSPACE;
    private final float[] pX = new float[PARTICLES];
    private final float[] pY = new float[PARTICLES];
    private final float[] pSpeed = new float[PARTICLES];
    private final float[] pSize = new float[PARTICLES];
    private final float[] pPhase = new float[PARTICLES];
    private static final float[][] STARS = {
        {-0.55f, -0.62f}, {-0.2f, -0.82f}, {0.28f, -0.7f}, {0.6f, -0.45f}, {-0.7f, -0.25f}, {0.05f, -0.5f}
    };
    private static final char[] WORD = {'H', 'O', 'R', 'I', 'Z', 'O', 'N', 'Z'};

    private String curLabel = "";
    private String prevLabel = "";
    private float labelChangeT = -10f;

    public HorizonSplashRenderer(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        Random rnd = new Random(7L);
        for (int i = 0; i < PARTICLES; i++) {
            pX[i] = rnd.nextFloat();
            pY[i] = rnd.nextFloat();
            pSpeed[i] = 0.02f + rnd.nextFloat() * 0.05f;
            pSize[i] = (1.0f + rnd.nextFloat() * 2.2f) * density;
            pPhase[i] = rnd.nextFloat() * 6.2831853f;
        }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setColor(Color.WHITE);
        dashed.setStrokeWidth(1.1f * density);
        dashed.setPathEffect(new DashPathEffect(new float[]{5f * density, 11f * density}, 0f));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float easeOutCubic(float x) {
        float k = 1f - clamp01(x);
        return 1f - k * k * k;
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float k = clamp01(x) - 1f;
        return 1f + c3 * k * k * k + c1 * k * k;
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    private static void alpha(Paint p, float k, float global) {
        p.setAlpha(Math.round(255f * clamp01(k * global)));
    }

    /**
     * @param t        seconds since the splash started (shared clock)
     * @param progress 0..1 loading progress
     * @param label    current stage label
     * @param global   overall opacity (used for the final fade-out)
     */
    public void draw(Canvas c, float w, float h, float t, float progress, String label, float global) {
        if (w <= 0f || h <= 0f) return;
        float g = clamp01(global);
        float prog = clamp01(progress);
        if (label != null && !label.equals(curLabel)) {
            prevLabel = curLabel;
            curLabel = label;
            labelChangeT = t;
        }

        // Background: near-black, slow aurora blobs, drifting particles, vignette.
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF04060B);
        fill.setAlpha(Math.round(255f * g));
        c.drawRect(0f, 0f, w, h, fill);

        float fadeIn = easeOutCubic(t / 1.2f);
        float big = Math.max(w, h);
        drawBlob(c, w * (0.22f + 0.07f * (float) Math.sin(t * 0.35f)), h * (0.38f + 0.10f * (float) Math.cos(t * 0.27f)),
            big * 0.55f, 0x6636B6E8, 0.55f * fadeIn * g);
        drawBlob(c, w * (0.80f + 0.06f * (float) Math.cos(t * 0.31f)), h * (0.70f + 0.09f * (float) Math.sin(t * 0.23f)),
            big * 0.50f, 0x607A55E8, 0.50f * fadeIn * g);

        for (int i = 0; i < PARTICLES; i++) {
            float y = fract(pY[i] - pSpeed[i] * t);
            float x = pX[i] + 0.012f * (float) Math.sin(t * 0.6f + pPhase[i]);
            float edge = Math.min(1f, Math.min(y, 1f - y) * 6f);
            float twinkle = 0.5f + 0.5f * (float) Math.sin(t * 1.7f + pPhase[i]);
            fill.setColor(i % 3 == 0 ? CYAN : Color.WHITE);
            alpha(fill, (0.10f + 0.32f * twinkle) * edge * fadeIn, g);
            float cx = x * w;
            float cy = y * h;
            c.save();
            c.rotate(45f, cx, cy);
            float s = pSize[i];
            c.drawRect(cx - s * 0.5f, cy - s * 0.5f, cx + s * 0.5f, cy + s * 0.5f, fill);
            c.restore();
        }

        fill.setShader(new RadialGradient(w / 2f, h / 2f, big * 0.75f, 0x00000000, 0xCC000000, Shader.TileMode.CLAMP));
        alpha(fill, 1f, g);
        c.drawRect(0f, 0f, w, h, fill);
        fill.setShader(null);

        // Layout (landscape: emblem left, text right; portrait: stacked).
        boolean landscape = w >= h * 1.15f;
        float r;
        float ecx;
        float ecy;
        float tx;
        float tw;
        if (landscape) {
            r = Math.min(h * 0.30f, w * 0.17f);
            ecx = w * 0.27f;
            ecy = h * 0.5f;
            tx = w * 0.45f;
            tw = Math.min(w * 0.44f, 460f * density);
        } else {
            r = Math.min(w * 0.27f, h * 0.15f);
            ecx = w / 2f;
            ecy = h * 0.30f;
            tx = w * 0.12f;
            tw = w * 0.76f;
        }

        drawEmblem(c, t, prog, g, r, ecx, ecy);

        // Wordmark sizing.
        float s = landscape ? h * 0.16f : w * 0.105f;
        text.setStyle(Paint.Style.FILL);
        text.setTypeface(light);
        text.setTextSize(s);
        float track = 0.14f * s;
        float total = 0f;
        float[] adv = new float[WORD.length];
        for (int i = 0; i < WORD.length; i++) {
            adv[i] = text.measureText(String.valueOf(WORD[i])) + track;
            total += adv[i];
        }
        total -= track;
        if (total > tw) {
            float k = tw / total;
            s *= k;
            track *= k;
            text.setTextSize(s);
            total = 0f;
            for (int i = 0; i < WORD.length; i++) {
                adv[i] = text.measureText(String.valueOf(WORD[i])) + track;
                total += adv[i];
            }
            total -= track;
        }
        float wordBase = landscape ? ecy - s * 0.55f : ecy + r * 1.25f + s * 1.0f;

        float x = tx;
        text.setShadowLayer(s * 0.18f, 0f, 0f, 0x334FD8FF);
        for (int i = 0; i < WORD.length; i++) {
            float p = easeOutCubic((t - (0.35f + i * 0.07f)) / 0.55f);
            float y = wordBase + (1f - p) * s * 0.35f;
            boolean accent = i == WORD.length - 1;
            if (accent) {
                text.setTypeface(medium);
                text.setShader(new LinearGradient(x, y - s * 0.75f, x, y, CYAN, VIOLET, Shader.TileMode.CLAMP));
                text.setColor(Color.WHITE);
            } else {
                text.setTypeface(light);
                text.setShader(null);
                text.setColor(0xFFF3F7FA);
            }
            alpha(text, p, g);
            c.drawText(String.valueOf(WORD[i]), x, y, text);
            x += adv[i];
        }
        text.setShader(null);
        text.clearShadowLayer();

        // Accent line that grows in, then the subtitle.
        float lineP = easeOutCubic((t - 0.9f) / 0.6f);
        float lineY = wordBase + s * 0.24f;
        line.setShader(new LinearGradient(tx, 0f, tx + tw, 0f, CYAN, VIOLET, Shader.TileMode.CLAMP));
        line.setStrokeWidth(Math.max(2f * density, s * 0.022f));
        alpha(line, 1f, g * lineP);
        c.drawLine(tx, lineY, tx + tw * 0.22f * lineP, lineY, line);
        line.setShader(null);

        float subSize = Math.max(10f * density, s * 0.15f);
        float subBase = lineY + s * 0.30f;
        text.setTypeface(medium);
        text.setTextSize(subSize);
        text.setLetterSpacing(0.28f);
        text.setColor(0xFF9AA7B5);
        alpha(text, easeOutCubic((t - 1.1f) / 0.5f) * 0.9f, g);
        c.drawText("MINECRAFT 26.3  \u00B7  RUST EDITION", tx, subBase, text);
        text.setLetterSpacing(0f);

        // Stage label (cross-fades on change) and percentage.
        float labelSize = Math.max(10f * density, s * 0.115f);
        float labelBase = subBase + s * 0.95f;
        float rowIn = easeOutCubic((t - 0.8f) / 0.6f);
        text.setTypeface(mono);
        text.setTextSize(labelSize);
        int pct = Math.max(1, Math.min(100, Math.round(prog * 100f)));
        String pctText = pct + "%";
        text.setColor(Color.WHITE);
        text.setFakeBoldText(true);
        float pctW = text.measureText(pctText);
        alpha(text, rowIn * 0.95f, g);
        c.drawText(pctText, tx + tw - pctW, labelBase, text);
        text.setFakeBoldText(false);

        float avail = tw - pctW - 14f * density;
        float cf = easeOutCubic((t - labelChangeT) / 0.35f);
        text.setColor(0xFFB6C2CE);
        if (cf < 1f && !prevLabel.isEmpty()) {
            alpha(text, (1f - cf) * 0.85f * rowIn, g);
            c.drawText(fit(prevLabel, avail), tx, labelBase - cf * 6f * density, text);
        }
        alpha(text, cf * 0.9f * rowIn, g);
        c.drawText(fit(curLabel, avail), tx, labelBase + (1f - cf) * 6f * density, text);

        // Thin gradient progress line with a moving shimmer.
        float barH = Math.max(3f * density, s * 0.028f);
        float barY = labelBase + labelSize * 0.9f;
        float barIn = easeOutCubic((t - 0.8f) / 0.6f);
        fill.setShader(null);
        fill.setColor(Color.WHITE);
        alpha(fill, 0.10f * barIn, g);
        rect.set(tx, barY, tx + tw, barY + barH);
        c.drawRoundRect(rect, barH / 2f, barH / 2f, fill);

        float fillW = Math.max(barH, tw * prog);
        rect.set(tx, barY, tx + fillW, barY + barH);
        fill.setShader(new LinearGradient(tx, 0f, tx + tw, 0f, CYAN, VIOLET, Shader.TileMode.CLAMP));
        fill.setColor(Color.WHITE);
        alpha(fill, barIn, g);
        c.drawRoundRect(rect, barH / 2f, barH / 2f, fill);
        fill.setShader(null);

        float shW = tw * 0.18f;
        float shX = tx + (fract(t * 0.55f) * 1.4f - 0.2f) * tw;
        c.save();
        c.clipRect(tx, barY - 1f, tx + fillW, barY + barH + 1f);
        fill.setShader(new LinearGradient(shX - shW, 0f, shX + shW, 0f,
            new int[]{0x00FFFFFF, 0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        fill.setColor(Color.WHITE);
        alpha(fill, barIn, g);
        c.drawRect(shX - shW, barY, shX + shW, barY + barH, fill);
        fill.setShader(null);
        c.restore();

        // Soft glow at the head of the line.
        float headX = tx + fillW;
        float glowR = barH * 4f;
        fill.setShader(new RadialGradient(headX, barY + barH / 2f, glowR, 0x884FD8FF, 0x004FD8FF, Shader.TileMode.CLAMP));
        fill.setColor(Color.WHITE);
        alpha(fill, barIn, g);
        c.drawCircle(headX, barY + barH / 2f, glowR, fill);
        fill.setShader(null);
    }

    private void drawBlob(Canvas c, float cx, float cy, float rad, int color, float k) {
        fill.setShader(new RadialGradient(cx, cy, rad, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        fill.setColor(Color.WHITE);
        fill.setAlpha(Math.round(255f * clamp01(k)));
        c.drawCircle(cx, cy, rad, fill);
        fill.setShader(null);
    }

    private void drawEmblem(Canvas c, float t, float prog, float g, float r, float ecx, float ecy) {
        float in = easeOutBack(t / 0.9f);
        float inA = clamp01(t / 0.6f);
        float sc = 0.78f + 0.22f * in;
        c.save();
        c.translate(ecx, ecy);
        c.scale(sc, sc);

        // Expanding halo ripples.
        line.setShader(null);
        line.setColor(CYAN);
        line.setStrokeWidth(1.2f * density);
        for (int i = 0; i < 2; i++) {
            float ph = fract(t * 0.45f + i * 0.5f);
            alpha(line, (1f - ph) * 0.28f * inA, g);
            c.drawCircle(0f, 0f, r * (0.62f + 0.55f * ph), line);
        }

        // Slowly rotating dashed orbit with a small satellite.
        c.save();
        c.rotate(t * 10f);
        alpha(dashed, 0.22f * inA, g);
        c.drawCircle(0f, 0f, r, dashed);
        c.restore();
        float sat = t * 1.3f;
        float sx = r * (float) Math.cos(sat);
        float sy = r * (float) Math.sin(sat);
        fill.setShader(new RadialGradient(sx, sy, 9f * density, 0xCC4FD8FF, 0x004FD8FF, Shader.TileMode.CLAMP));
        fill.setColor(Color.WHITE);
        alpha(fill, inA, g);
        c.drawCircle(sx, sy, 9f * density, fill);
        fill.setShader(null);
        fill.setColor(Color.WHITE);
        alpha(fill, inA, g);
        c.drawCircle(sx, sy, 2.2f * density, fill);

        // Progress ring: track, gradient arc and a glowing head.
        float ringR = r * 0.86f;
        float ringW = Math.max(3f * density, r * 0.055f);
        line.setColor(Color.WHITE);
        line.setStrokeWidth(ringW);
        alpha(line, 0.10f * inA, g);
        c.drawCircle(0f, 0f, ringR, line);

        float sweep = 360f * prog;
        if (sweep > 0.5f) {
            line.setShader(new SweepGradient(0f, 0f, new int[]{CYAN, VIOLET, CYAN}, new float[]{0f, 0.6f, 1f}));
            line.setColor(Color.WHITE);
            line.setStrokeWidth(ringW);
            alpha(line, inA, g);
            rect.set(-ringR, -ringR, ringR, ringR);
            c.save();
            c.rotate(-90f);
            c.drawArc(rect, 0f, sweep, false, line);
            c.restore();
            line.setShader(null);

            double ang = Math.toRadians(sweep - 90f);
            float hx = ringR * (float) Math.cos(ang);
            float hy = ringR * (float) Math.sin(ang);
            float hg = ringW * 3.2f;
            fill.setShader(new RadialGradient(hx, hy, hg, 0x994FD8FF, 0x004FD8FF, Shader.TileMode.CLAMP));
            fill.setColor(Color.WHITE);
            alpha(fill, inA, g);
            c.drawCircle(hx, hy, hg, fill);
            fill.setShader(null);
            fill.setColor(Color.WHITE);
            alpha(fill, inA, g);
            c.drawCircle(hx, hy, ringW * 0.55f, fill);
        }

        // Inner disc: sunrise over a horizon with a shimmering reflection.
        float discR = r * 0.70f;
        float horizon = r * 0.12f;
        clip.reset();
        clip.addCircle(0f, 0f, discR, Path.Direction.CW);
        c.save();
        c.clipPath(clip);

        fill.setShader(new LinearGradient(0f, -discR, 0f, horizon,
            new int[]{0xFF070B1C, 0xFF241A57, 0xFFC25A7E}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        fill.setColor(Color.WHITE);
        alpha(fill, 0.96f * inA, g);
        c.drawRect(-discR, -discR, discR, horizon, fill);

        fill.setShader(new LinearGradient(0f, horizon, 0f, discR, 0xFF0B1226, 0xFF04060B, Shader.TileMode.CLAMP));
        alpha(fill, 0.98f * inA, g);
        c.drawRect(-discR, horizon, discR, discR, fill);
        fill.setShader(null);

        fill.setColor(Color.WHITE);
        for (int i = 0; i < STARS.length; i++) {
            float twinkle = 0.5f + 0.5f * (float) Math.sin(t * 2.1f + i * 1.7f);
            alpha(fill, (0.25f + 0.6f * twinkle) * inA, g);
            c.drawCircle(STARS[i][0] * discR, STARS[i][1] * discR, (0.9f + 0.5f * twinkle) * density, fill);
        }

        float rise = easeOutCubic((t - 0.25f) / 1.4f);
        float sunR = r * 0.34f;
        float sunCy = horizon - sunR * 0.15f + (1f - rise) * sunR * 1.5f
            + (float) Math.sin(t * 1.2f) * r * 0.012f * rise;
        fill.setShader(new RadialGradient(0f, sunCy, sunR * 2.4f, 0x8CFFA45C, 0x00FFA45C, Shader.TileMode.CLAMP));
        fill.setColor(Color.WHITE);
        alpha(fill, rise, g);
        c.drawRect(-discR, -discR, discR, horizon, fill);

        c.save();
        c.clipRect(-discR, -discR, discR, horizon);
        fill.setShader(new RadialGradient(0f, sunCy, sunR, new int[]{WARM_LIGHT, WARM_DEEP},
            new float[]{0.35f, 1f}, Shader.TileMode.CLAMP));
        fill.setColor(Color.WHITE);
        alpha(fill, 1f, g);
        c.drawCircle(0f, sunCy, sunR, fill);
        fill.setShader(null);
        c.restore();

        fill.setColor(WARM_DEEP);
        for (int i = 0; i < 6; i++) {
            float gap = r * 0.06f * (1f + i * 0.35f);
            float yy = horizon + gap * (i + 1) * 0.8f;
            float ww = sunR * 2f * (1f - i * 0.13f) * (0.8f + 0.2f * (float) Math.sin(t * 2f + i));
            float hh = r * 0.022f * (1f + i * 0.2f);
            float off = (float) Math.sin(t * 1.5f + i * 0.9f) * r * 0.04f;
            alpha(fill, 0.55f * (1f - i / 6f) * rise, g);
            rect.set(off - ww / 2f, yy, off + ww / 2f, yy + hh);
            c.drawRoundRect(rect, hh / 2f, hh / 2f, fill);
        }

        line.setShader(null);
        line.setColor(Color.WHITE);
        line.setStrokeWidth(1f * density);
        alpha(line, 0.40f * inA, g);
        c.drawLine(-discR, horizon, discR, horizon, line);
        c.restore();

        line.setColor(Color.WHITE);
        line.setStrokeWidth(1.2f * density);
        alpha(line, 0.14f * inA, g);
        c.drawCircle(0f, 0f, discR, line);

        c.restore();
    }

    private String fit(String s, float maxWidth) {
        if (s == null || s.isEmpty()) return "";
        if (text.measureText(s) <= maxWidth) return s;
        int end = s.length();
        while (end > 4 && text.measureText(s, 0, end) + text.measureText("\u2026") > maxWidth) {
            end--;
        }
        return s.substring(0, end).trim() + "\u2026";
    }
}
