package com.example.blockblastbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BotAccessibilityService extends AccessibilityService {
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_PROJECTION_DATA = "projection_data";
    private static final int NOTIFICATION_ID = 71;
    private static final String CHANNEL = "block_blast_bot";
    private static final String TAG = "BlockBlastBot";

    private static BotAccessibilityService instance;
    private static volatile boolean running = false;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private long lastFrame = 0;

    public static boolean isRunning() { return running; }

    @Override public void onServiceConnected() { super.onServiceConnected(); instance = this; }

    public static void startBot() { if (instance != null) running = true; }

    public static void stopBot() {
        running = false;
        if (instance != null) instance.stopCapture();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_RESULT_CODE)) {
            int result = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
            Intent data;
            if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent.class);
            else data = intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
            if (result == android.app.Activity.RESULT_OK && data != null) startProjection(result, data);
        }
        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent data) {
        try {
            createChannel();
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("Block Blast Bot")
                    .setContentText("Получаю поток экрана")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setOngoing(true).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTIFICATION_ID, n);

            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) return;
            projection.registerCallback(new MediaProjection.Callback() { @Override public void onStop() { stopCapture(); } }, main);

            DisplayMetrics dm = getResources().getDisplayMetrics();
            int width = dm.widthPixels, height = dm.heightPixels, dpi = dm.densityDpi;
            reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(r -> {
                Image image = r.acquireLatestImage();
                if (image == null) return;
                long now = System.currentTimeMillis();
                if (!running || now - lastFrame < 450 || !processing.compareAndSet(false, true)) { image.close(); return; }
                lastFrame = now;
                worker.execute(() -> {
                    Bitmap b = imageToBitmap(image, width, height);
                    image.close();
                    if (b != null) process(b);
                    processing.set(false);
                });
            }, main);
            virtualDisplay = projection.createVirtualDisplay("BlockBlastBot", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, main);
            running = true;
        } catch (Throwable t) {
            running = false;
            stopCapture();
        }
    }

    private Bitmap imageToBitmap(Image image, int width, int height) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            int paddedWidth = width + rowPadding / pixelStride;
            Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            buffer.rewind(); padded.copyPixelsFromBuffer(buffer);
            Bitmap out = Bitmap.createBitmap(padded, 0, 0, width, height);
            padded.recycle(); return out;
        } catch (Throwable ignored) { return null; }
    }

    private void process(Bitmap bm) {
        if (!running) { bm.recycle(); return; }
        Board board = Vision.readBoard(bm); List<Piece> pieces = Vision.readPieces(bm);
        Log.d(TAG, "vision: pieces=" + pieces.size() + " board=" + boardCount(board));
        if (pieces.size() != 3) { bm.recycle(); return; }
        Solver.Move m = Solver.best(board, pieces);
        if (m == null) { bm.recycle(); return; }
        float sx = bm.getWidth() / 921f, sy = bm.getHeight() / 2048f;
        for (int k = 0; k < 3 && running; k++) {
            int idx = m.order[k], row = m.pos[k][0], col = m.pos[k][1];
            float x = pieces.get(idx).cx * sx, y = pieces.get(idx).cy * sy;
            float tx = (board.x + (col + .5f) * board.cellW) * sx;
            float ty = (board.y + (row + .5f) * board.cellH) * sy;
            boolean moved = drag(x, y, tx, ty);
            Log.d(TAG, "move " + k + ": " + moved + " from=" + x + "," + y + " to=" + tx + "," + ty);
            if (!moved) break;
            try { Thread.sleep(180); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
        bm.recycle();
    }

    private static int boardCount(Board b) {
        int n=0; for(int r=0;r<10;r++) for(int c=0;c<10;c++) if(b.a[r][c]) n++; return n;
    }

    private boolean drag(float x1, float y1, float x2, float y2) {
        if (!running) return false;

        // dispatchGesture() is asynchronous. The previous version queued three
        // gestures from the worker thread and then slept for a fixed time. On
        // slower devices the next gesture could arrive while the previous one
        // was still active, causing Android to cancel/ignore it. Wait for the
        // actual completion callback before continuing.
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean ok =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        final float maxX = Math.max(1, dm.widthPixels - 1);
        final float maxY = Math.max(1, dm.heightPixels - 1);
        x1 = Math.max(1, Math.min(maxX - 1, x1));
        y1 = Math.max(1, Math.min(maxY - 1, y1));
        x2 = Math.max(1, Math.min(maxX - 1, x2));
        y2 = Math.max(1, Math.min(maxY - 1, y2));

        final float fx1=x1, fy1=y1, fx2=x2, fy2=y2;
        main.post(() -> {
            if (!running) { done.countDown(); return; }
            Path path = new Path();
            path.moveTo(fx1, fy1);
            // A tiny pause/movement at the beginning makes the touch behave more
            // like a real long-press + drag on games that require a firm pickup.
            float mx = fx1 + (fx2-fx1)*0.08f;
            float my = fy1 + (fy2-fy1)*0.08f;
            path.lineTo(mx, my);
            path.lineTo(fx2, fy2);

            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 850))
                    .build();

            boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gestureDescription) {
                    ok.set(true);
                    Log.d(TAG, "gesture completed: " + fx1 + "," + fy1 + " -> " + fx2 + "," + fy2);
                    done.countDown();
                }
                @Override public void onCancelled(GestureDescription gestureDescription) {
                    Log.w(TAG, "gesture CANCELLED: " + fx1 + "," + fy1 + " -> " + fx2 + "," + fy2);
                    done.countDown();
                }
            }, main);
            if (!dispatched) {
                Log.e(TAG, "dispatchGesture returned false. Is Accessibility gesture permission enabled?");
                done.countDown();
            }
        });

        try {
            done.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Block Blast Bot", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void stopCapture() {
        running = false;
        if (reader != null) { reader.close(); reader = null; }
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (projection != null) { MediaProjection p = projection; projection = null; try { p.stop(); } catch (Exception ignored) {} }
        main.post(() -> { try { stopForeground(true); } catch (Exception ignored) {} });
    }

    @Override public void onDestroy() { stopCapture(); worker.shutdownNow(); instance = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() { stopCapture(); }

    static class Board { boolean[][] a = new boolean[10][10]; float x,y,cellW,cellH; }
    static class Piece { boolean[][] a; int h,w,minR,minC; float cx,cy; }
    static class Vision {
        // Block Blast uses highly saturated colored tiles. Use a tolerant HSV-like test.
        static boolean isBlock(int c) {
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            int mx = Math.max(r, Math.max(g, b));
            int mn = Math.min(r, Math.min(g, b));
            if (mx < 70) return false;
            float sat = (mx - mn) / (float) Math.max(1, mx);
            return sat > 0.20f && !(r > 242 && g > 242 && b > 242);
        }

        static boolean sampleBlock(Bitmap b, float x, float y, float sx, float sy) {
            int cx = Math.round(x * sx), cy = Math.round(y * sy);
            int rad = Math.max(2, Math.round(7 * Math.min(sx, sy)));
            int hits = 0, total = 0;
            for (int yy = -rad; yy <= rad; yy += Math.max(1, rad / 2))
                for (int xx = -rad; xx <= rad; xx += Math.max(1, rad / 2)) {
                    int px = Math.max(0, Math.min(b.getWidth()-1, cx+xx));
                    int py = Math.max(0, Math.min(b.getHeight()-1, cy+yy));
                    total++;
                    if (isBlock(b.getPixel(px, py))) hits++;
                }
            return hits * 2 >= total;
        }

        static Board readBoard(Bitmap b) {
            Board z = new Board();
            float sx = b.getWidth() / 921f, sy = b.getHeight() / 2048f;
            z.x = 47; z.y = 447; z.cellW = 73.8f; z.cellH = 74f;
            for (int r=0;r<10;r++) for (int c=0;c<10;c++)
                z.a[r][c] = sampleBlock(b, z.x+(c+.5f)*z.cellW, z.y+(r+.5f)*z.cellH, sx, sy);
            return z;
        }

        // Read each tray slot on a fixed 3x5 logical grid. This is much more stable
        // than connected-component detection because the pieces are separated by
        // empty space and their exact colors vary between devices/frames.
        static List<Piece> readPieces(Bitmap b) {
            float sx = b.getWidth()/921f, sy = b.getHeight()/2048f;
            float[] centers = {210f, 460f, 711f};
            float cell = 41f;
            ArrayList<Piece> out = new ArrayList<>();

            for (float center : centers) {
                // Find the tray vertically instead of assuming one fixed Y coordinate.
                // Different phones/immersive-navigation modes move the tray.
                float bestCy = -1; int bestCount = 0;
                for (float candidate = 1260f; candidate <= 1840f; candidate += 20f) {
                    int count = 0;
                    for (int rr=0;rr<5;rr++) for (int cc=0;cc<5;cc++) {
                        if (sampleBlock(b, center + (cc-2)*cell,
                                           candidate + (rr-2)*cell, sx, sy)) count++;
                    }
                    if (count >= 1 && count <= 5 && count > bestCount) {
                        bestCount = count; bestCy = candidate;
                    }
                }
                if (bestCy < 0) continue;

                int minR=5, maxR=-1, minC=5, maxC=-1;
                boolean[][] grid = new boolean[5][5];
                for (int rr=0;rr<5;rr++) for (int cc=0;cc<5;cc++) {
                    float px = center + (cc-2)*cell;
                    float py = bestCy + (rr-2)*cell;
                    grid[rr][cc] = sampleBlock(b, px, py, sx, sy);
                    if (grid[rr][cc]) { minR=Math.min(minR,rr); maxR=Math.max(maxR,rr);
                                        minC=Math.min(minC,cc); maxC=Math.max(maxC,cc); }
                }
                if (maxR < 0) continue;

                Piece p = new Piece();
                p.h=maxR-minR+1; p.w=maxC-minC+1;
                p.a=new boolean[p.h][p.w];
                int count=0;
                for (int rr=minR;rr<=maxR;rr++) for (int cc=minC;cc<=maxC;cc++) {
                    p.a[rr-minR][cc-minC]=grid[rr][cc];
                    if (grid[rr][cc]) count++;
                }
                // A real Block Blast piece has 1..5 cells. Reject stray UI pixels.
                if (count < 1 || count > 5) continue;

                p.cx = center;
                p.cy = bestCy + (minR+maxR-4)*cell/2f;
                out.add(p);
            }
            return out;
        }
    }
    static class Solver {
        static class Move{int[]order=new int[3];int[][]pos=new int[3][2];int score=-999999;}
        static Move best(Board b,List<Piece>ps){Move best=null;int[]ord={0,1,2};do{Board c=copy(b);int score=0;int[][]pp=new int[3][2];boolean ok=true;for(int k=0;k<3;k++){Piece p=ps.get(ord[k]);int[]q=bestPos(c,p);if(q==null){ok=false;break;}place(c,p,q[0],q[1]);score+=evaluate(c);pp[k]=q;}if(ok&&(best==null||score>best.score)){best=new Move();best.score=score;for(int k=0;k<3;k++){best.order[k]=ord[k];best.pos[k]=pp[k];}}}while(next(ord));return best;}
        static int[]bestPos(Board b,Piece p){int best=-1,br=-1,bc=-1;for(int r=0;r<=10-p.h;r++)for(int c=0;c<=10-p.w;c++){boolean ok=true;for(int y=0;y<p.h;y++)for(int x=0;x<p.w;x++)if(p.a[y][x]&&b.a[r+y][c+x])ok=false;if(!ok)continue;Board t=copy(b);place(t,p,r,c);int s=evaluate(t);if(s>best){best=s;br=r;bc=c;}}return br<0?null:new int[]{br,bc};}
        static void place(Board b,Piece p,int r,int c){for(int y=0;y<p.h;y++)for(int x=0;x<p.w;x++)if(p.a[y][x])b.a[r+y][c+x]=true;for(int r0=0;r0<10;r0++){boolean full=true;for(int c0=0;c0<10;c0++)if(!b.a[r0][c0])full=false;if(full)for(int c0=0;c0<10;c0++)b.a[r0][c0]=false;}for(int c0=0;c0<10;c0++){boolean full=true;for(int r0=0;r0<10;r0++)if(!b.a[r0][c0])full=false;if(full)for(int r0=0;r0<10;r0++)b.a[r0][c0]=false;}}
        static int evaluate(Board b){int empty=0,adj=0;for(int r=0;r<10;r++)for(int c=0;c<10;c++){if(!b.a[r][c])empty++;else{if(r<9&&b.a[r+1][c])adj++;if(c<9&&b.a[r][c+1])adj++;}}return empty*3+adj;}
        static Board copy(Board b){Board n=new Board();for(int r=0;r<10;r++)System.arraycopy(b.a[r],0,n.a[r],0,10);n.x=b.x;n.y=b.y;n.cellW=b.cellW;n.cellH=b.cellH;return n;}
        static boolean next(int[]a){int i=a.length-2;while(i>=0&&a[i]>a[i+1])i--;if(i<0)return false;int j=a.length-1;while(a[j]<a[i])j--;int t=a[i];a[i]=a[j];a[j]=t;for(int l=i+1,r=a.length-1;l<r;l++,r--){t=a[l];a[l]=a[r];a[r]=t;}return true;}
    }
}
