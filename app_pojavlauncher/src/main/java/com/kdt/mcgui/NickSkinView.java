package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Nick Launcher: shows the active account's skin as a rotating 3D player model with a name tag.
 * Pure Canvas rendering (orthographic projection, textured faces), no extra libraries.
 * The skin is downloaded by username/UUID and cached; it follows the selected account automatically.
 */
public class NickSkinView extends View {
    private static final String[] SKIN_URLS = {
            "https://mc-heads.net/skin/%s",
            "https://minotar.net/skin/%s"
    };
    private static final long CACHE_MS = 6L * 60L * 60L * 1000L;

    // ---- model: x,y,z min/max (pixels, y up, front = +z), texture origin u,v and box size w,h,d
    private static final class Part {
        float x0, y0, z0, x1, y1, z1;
        int u, v, w, h, d;
        Part(float x0, float y0, float z0, float x1, float y1, float z1, int u, int v, int w, int h, int d) {
            this.x0 = x0; this.y0 = y0; this.z0 = z0; this.x1 = x1; this.y1 = y1; this.z1 = z1;
            this.u = u; this.v = v; this.w = w; this.h = h; this.d = d;
        }
    }

    private static final class Face {
        final float[] p = new float[8]; // TL, TR, BR, BL (screen)
        float depth;
        int sx, sy, sw, sh;
    }

    private static final int MAX_FACES = 12 * 5;
    private final Face[] mFaces = new Face[MAX_FACES];
    private final int[] mOrder = new int[MAX_FACES];

    private final Paint mSkinPaint = new Paint();
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTagBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTagTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final float[] mSrcPts = new float[6];
    private final float[] mDstPts = new float[6];
    private final Rect mSrcRect = new Rect();
    private final RectF mDstRect = new RectF();
    private final RectF mTmpRect = new RectF();
    private final float[] mIn = new float[12];

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            refreshAccount();
            mHandler.postDelayed(this, 1000);
        }
    };

    private Bitmap mSkin;
    private Bitmap mFallback;
    private boolean mSlim;
    private String mShownProfile = null;
    private String mName = "";
    private String mType = "";
    private volatile int mLoadToken = 0;

    private float mYaw = 28f;
    private float mPitch = 14f;
    private boolean mDragging = false;
    private float mLastX, mLastY;
    private long mLastFrameNs = 0;
    private float mDensity = 1f;

    public NickSkinView(Context context) {
        super(context);
        init();
    }

    public NickSkinView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NickSkinView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mDensity = getResources().getDisplayMetrics().density;
        for (int i = 0; i < MAX_FACES; i++) mFaces[i] = new Face();

        mSkinPaint.setFilterBitmap(false); // keep the pixel look
        mSkinPaint.setAntiAlias(false);

        mShadowPaint.setColor(Color.argb(90, 0, 0, 0));
        mTagBgPaint.setColor(Color.argb(150, 0, 0, 0));
        mTagTextPaint.setColor(Color.WHITE);
        mTagTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mTagTextPaint.setTextAlign(Paint.Align.CENTER);
        mTagTextPaint.setTextSize(16f * mDensity);
        mChipPaint.setTextAlign(Paint.Align.CENTER);
        mChipPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mChipPaint.setTextSize(9f * mDensity);
        mHintPaint.setColor(Color.argb(220, 255, 255, 255));
        mHintPaint.setTextAlign(Paint.Align.CENTER);
        mHintPaint.setTextSize(13f * mDensity);
    }

    // ------------------------------------------------------------------ account / skin loading

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mHandler.post(mPoll);
        mLastFrameNs = 0;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        mHandler.removeCallbacks(mPoll);
        mLoadToken++;
        super.onDetachedFromWindow();
    }

    /** Cheap check every second: only reload when the selected account changed. */
    private void refreshAccount() {
        String current;
        try {
            current = PojavProfile.getCurrentProfileName(getContext());
        } catch (Throwable t) {
            return;
        }
        if (current == null) current = "";
        if (current.equals(mShownProfile)) return;
        mShownProfile = current;

        MinecraftAccount account = null;
        if (!current.isEmpty()) {
            try {
                account = PojavProfile.getCurrentProfileContent(getContext(), null);
            } catch (Throwable ignored) {
            }
        }
        if (account == null) {
            mName = "";
            mType = "";
            mSkin = null;
            mLoadToken++;
            invalidate();
            return;
        }
        mName = account.username == null ? "" : account.username;
        mType = account.isMicrosoft ? "MICROSOFT" : "LOCAL";
        mSkin = null; // fallback model is shown while the real skin loads
        String query = account.isMicrosoft && account.profileId != null && !account.profileId.isEmpty()
                ? account.profileId.replace("-", "")
                : mName;
        loadSkinAsync(query, mName);
        invalidate();
    }

    private void loadSkinAsync(final String query, final String name) {
        final int token = ++mLoadToken;
        final File cacheDir = getContext().getApplicationContext().getCacheDir();
        new Thread(() -> {
            Bitmap bmp = null;
            File cache = new File(cacheDir, "nick_skin_" + name.replaceAll("[^A-Za-z0-9_]", "_") + ".png");
            try {
                if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_MS) {
                    bmp = BitmapFactory.decodeFile(cache.getAbsolutePath());
                }
                if (bmp == null) {
                    for (String fmt : SKIN_URLS) {
                        bmp = download(String.format(fmt, Uri.encode(query)), cache);
                        if (bmp != null) break;
                    }
                }
            } catch (Throwable ignored) {
            }
            final Bitmap normalized = normalize(bmp);
            post(() -> {
                if (token != mLoadToken) return;
                mSkin = normalized;
                mSlim = normalized != null && Color.alpha(normalized.getPixel(54, 20)) == 0;
                invalidate();
            });
        }, "NickSkinLoader").start();
    }

    private static Bitmap download(String address, File cacheFile) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "NickLauncher");
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            byte[] data = out.toByteArray();
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bmp == null || bmp.getWidth() != 64 || (bmp.getHeight() != 64 && bmp.getHeight() != 32)) {
                return null;
            }
            FileOutputStream fos = new FileOutputStream(cacheFile);
            fos.write(data);
            fos.close();
            return bmp;
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Converts old 64x32 skins to the 64x64 layout so the renderer has a single code path. */
    private static Bitmap normalize(Bitmap src) {
        if (src == null || src.getWidth() != 64) return null;
        if (src.getHeight() == 64) return src;
        if (src.getHeight() != 32) return null;
        Bitmap out = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint();
        p.setFilterBitmap(false);
        c.drawBitmap(src, 0, 0, p);
        mirrorCopy(c, src, p, 0, 16, 16, 48);   // right leg -> left leg
        mirrorCopy(c, src, p, 40, 16, 32, 48);  // right arm -> left arm
        return out;
    }

    private static void mirrorCopy(Canvas c, Bitmap src, Paint p, int sx, int sy, int dx, int dy) {
        c.save();
        c.translate(dx + 16, dy);
        c.scale(-1f, 1f);
        c.drawBitmap(src, new Rect(sx, sy, sx + 16, sy + 16), new Rect(0, 0, 16, 16), p);
        c.restore();
    }

    private Bitmap fallbackSkin() {
        if (mFallback != null) return mFallback;
        Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setAntiAlias(false);
        p.setColor(Color.rgb(201, 143, 107));
        c.drawRect(0, 0, 64, 64, p);                       // skin tone everywhere
        p.setColor(Color.rgb(74, 48, 32));                  // hair: top + back + sides of head
        c.drawRect(8, 0, 24, 8, p);
        c.drawRect(0, 8, 32, 10, p);
        c.drawRect(24, 8, 32, 16, p);
        c.drawRect(0, 8, 8, 16, p);
        p.setColor(Color.rgb(0, 160, 160));                 // shirt + arms
        c.drawRect(16, 16, 40, 32, p);
        c.drawRect(40, 16, 56, 32, p);
        c.drawRect(32, 48, 48, 64, p);
        p.setColor(Color.rgb(60, 60, 150));                 // pants / legs
        c.drawRect(0, 16, 16, 32, p);
        c.drawRect(16, 48, 32, 64, p);
        p.setColor(Color.WHITE);                            // eyes
        c.drawRect(10, 12, 12, 13, p);
        c.drawRect(14, 12, 16, 13, p);
        p.setColor(Color.rgb(60, 40, 120));
        c.drawRect(10, 12, 11, 13, p);
        c.drawRect(15, 12, 16, 13, p);
        mFallback = b;
        return b;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragging = true;
                mLastX = e.getX();
                mLastY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mDragging) {
                    mYaw += (e.getX() - mLastX) * 0.6f;
                    mPitch = Math.max(-5f, Math.min(35f, mPitch + (e.getY() - mLastY) * 0.25f));
                    mLastX = e.getX();
                    mLastY = e.getY();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0) return;

        if (mShownProfile != null && mName.isEmpty()) {
            canvas.drawText("Add an account to see your skin", w / 2f, h / 2f, mHintPaint);
            return;
        }
        if (mShownProfile == null) { // first frame, account not read yet
            refreshAccount();
            postInvalidateOnAnimation();
            return;
        }

        long now = System.nanoTime();
        if (mLastFrameNs != 0 && !mDragging) {
            float dt = Math.min(0.05f, (now - mLastFrameNs) / 1e9f);
            mYaw += dt * 22f;
        }
        mLastFrameNs = now;

        Bitmap skin = mSkin != null ? mSkin : fallbackSkin();
        boolean slim = mSkin != null && mSlim;

        final float scale = Math.min(h * 0.62f / 34f, w * 0.80f / 22f);
        final float cx = w / 2f;
        final float cy = h * 0.60f;

        final double yaw = Math.toRadians(mYaw);
        final double pitch = Math.toRadians(mPitch);
        final float cosY = (float) Math.cos(yaw), sinY = (float) Math.sin(yaw);
        final float cosP = (float) Math.cos(pitch), sinP = (float) Math.sin(pitch);

        // ground shadow
        float feetY = cy + 16f * scale * cosP;
        mTmpRect.set(cx - 11f * scale, feetY - 2.2f * scale * sinP - 1.2f * scale,
                cx + 11f * scale, feetY + 2.2f * scale * sinP + 1.2f * scale);
        canvas.drawOval(mTmpRect, mShadowPaint);

        // build faces
        int count = 0;
        final int armW = slim ? 3 : 4;
        Part[] parts = new Part[]{
                new Part(-4, 24, -4, 4, 32, 4, 0, 0, 8, 8, 8),
                new Part(-4, 12, -2, 4, 24, 2, 16, 16, 8, 12, 4),
                new Part(-4 - armW, 12, -2, -4, 24, 2, 40, 16, armW, 12, 4),
                new Part(4, 12, -2, 4 + armW, 24, 2, 32, 48, armW, 12, 4),
                new Part(-4, 0, -2, 0, 12, 2, 0, 16, 4, 12, 4),
                new Part(0, 0, -2, 4, 12, 2, 16, 48, 4, 12, 4),
        };
        // overlay layers (hat, jacket, sleeves, pants)
        int[][] overlayUv = {{32, 0}, {16, 32}, {40, 32}, {48, 48}, {0, 32}, {0, 48}};
        float[] grow = {0.5f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f};

        for (int layer = 0; layer < 2; layer++) {
            for (int i = 0; i < parts.length; i++) {
                Part b = parts[i];
                float g = layer == 0 ? 0f : grow[i];
                int u = layer == 0 ? b.u : overlayUv[i][0];
                int v = layer == 0 ? b.v : overlayUv[i][1];
                float x0 = b.x0 - g, x1 = b.x1 + g, y0 = b.y0 - g, y1 = b.y1 + g, z0 = b.z0 - g, z1 = b.z1 + g;
                // front, back, right, left, top
                count = addFace(count, 0, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1,
                        0f, 0f, 1f, u + b.d, v + b.d, b.w, b.h, cx, cy, scale, cosY, sinY, cosP, sinP);
                count = addFace(count, 1, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0,
                        0f, 0f, -1f, u + 2 * b.d + b.w, v + b.d, b.w, b.h, cx, cy, scale, cosY, sinY, cosP, sinP);
                count = addFace(count, 2, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0,
                        -1f, 0f, 0f, u, v + b.d, b.d, b.h, cx, cy, scale, cosY, sinY, cosP, sinP);
                count = addFace(count, 3, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1,
                        1f, 0f, 0f, u + b.d + b.w, v + b.d, b.d, b.h, cx, cy, scale, cosY, sinY, cosP, sinP);
                count = addFace(count, 4, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1,
                        0f, 1f, 0f, u + b.d, v, b.w, b.d, cx, cy, scale, cosY, sinY, cosP, sinP);
            }
        }

        // painter's algorithm: far to near (insertion sort, ~60 items)
        for (int i = 0; i < count; i++) mOrder[i] = i;
        for (int i = 1; i < count; i++) {
            int key = mOrder[i];
            float kd = mFaces[key].depth;
            int j = i - 1;
            while (j >= 0 && mFaces[mOrder[j]].depth > kd) {
                mOrder[j + 1] = mOrder[j];
                j--;
            }
            mOrder[j + 1] = key;
        }

        for (int i = 0; i < count; i++) {
            Face f = mFaces[mOrder[i]];
            mSrcPts[0] = 0; mSrcPts[1] = 0;
            mSrcPts[2] = f.sw; mSrcPts[3] = 0;
            mSrcPts[4] = 0; mSrcPts[5] = f.sh;
            mDstPts[0] = f.p[0]; mDstPts[1] = f.p[1];
            mDstPts[2] = f.p[2]; mDstPts[3] = f.p[3];
            mDstPts[4] = f.p[6]; mDstPts[5] = f.p[7];
            mMatrix.setPolyToPoly(mSrcPts, 0, mDstPts, 0, 3);
            mSrcRect.set(f.sx, f.sy, f.sx + f.sw, f.sy + f.sh);
            mDstRect.set(0, 0, f.sw, f.sh);
            canvas.save();
            canvas.concat(mMatrix);
            canvas.drawBitmap(skin, mSrcRect, mDstRect, mSkinPaint);
            canvas.restore();
        }

        drawNameTag(canvas, cx, cy - 17f * scale * cosP);

        postInvalidateOnAnimation();
    }

    private int addFace(int index, int faceId,
                        float ax, float ay, float az, float bx, float by, float bz,
                        float cx3, float cy3, float cz3, float dx, float dy, float dz,
                        float nx, float ny, float nz,
                        int sx, int sy, int sw, int sh,
                        float ox, float oy, float scale,
                        float cosY, float sinY, float cosP, float sinP) {
        // rotate the normal; only draw faces that look at the camera (+z)
        float nzY = -nx * sinY + nz * cosY;
        float nyP = ny * cosP - nzY * sinP;
        float nzP = ny * sinP + nzY * cosP;
        if (nzP <= 0.001f) return index;

        Face f = mFaces[index];
        float depth = 0f;
        final float[] in = mIn;
        in[0] = ax; in[1] = ay; in[2] = az; in[3] = bx; in[4] = by; in[5] = bz;
        in[6] = cx3; in[7] = cy3; in[8] = cz3; in[9] = dx; in[10] = dy; in[11] = dz;
        for (int k = 0; k < 4; k++) {
            float x = in[k * 3], y = in[k * 3 + 1], z = in[k * 3 + 2];
            float x1 = x * cosY + z * sinY;
            float z1 = -x * sinY + z * cosY;
            float y2 = y * cosP - z1 * sinP;
            float z2 = y * sinP + z1 * cosP;
            f.p[k * 2] = ox + x1 * scale;
            f.p[k * 2 + 1] = oy - (y2 - 16f * cosP) * scale;
            depth += z2;
        }
        f.depth = depth / 4f;
        f.sx = sx; f.sy = sy; f.sw = sw; f.sh = sh;
        return index + 1;
    }

    /** Name tag above the head: name on top, account type chip right under it. */
    private void drawNameTag(Canvas canvas, float centerX, float headTopY) {
        if (mName.isEmpty()) return;
        float padH = 10f * mDensity, padV = 5f * mDensity;
        float textW = mTagTextPaint.measureText(mName);
        float textH = mTagTextPaint.getTextSize();
        float tagW = textW + padH * 2, tagH = textH + padV * 2;
        float chipW = mChipPaint.measureText(mType) + 14f * mDensity;
        float chipH = mChipPaint.getTextSize() + 6f * mDensity;

        float chipTop = headTopY - chipH - 4f * mDensity;
        float tagTop = chipTop - tagH - 3f * mDensity;
        if (tagTop < 2f * mDensity) { // not enough room: shift both down
            float shift = 2f * mDensity - tagTop;
            tagTop += shift;
            chipTop += shift;
        }

        mTmpRect.set(centerX - tagW / 2f, tagTop, centerX + tagW / 2f, tagTop + tagH);
        canvas.drawRoundRect(mTmpRect, 6f * mDensity, 6f * mDensity, mTagBgPaint);
        canvas.drawText(mName, centerX, tagTop + padV + textH * 0.82f, mTagTextPaint);

        mTmpRect.set(centerX - chipW / 2f, chipTop, centerX + chipW / 2f, chipTop + chipH);
        mChipPaint.setColor("MICROSOFT".equals(mType) ? Color.rgb(94, 200, 79) : Color.rgb(120, 120, 135));
        canvas.drawRoundRect(mTmpRect, 4f * mDensity, 4f * mDensity, mChipPaint);
        mChipPaint.setColor(Color.rgb(10, 42, 10));
        canvas.drawText(mType, centerX, chipTop + chipH * 0.5f + mChipPaint.getTextSize() * 0.35f, mChipPaint);
    }
}
