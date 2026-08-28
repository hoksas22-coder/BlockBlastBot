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

    public interface TestCallback { void result(boolean ok, String message); }

    public static void testTouch(TestCallback cb) {
        BotAccessibilityService s = instance;
        if (s == null) { cb.result(false, "служба доступности не подключена"); return; }
        s.main.post(() -> {
            DisplayMetrics dm = s.getResources().getDisplayMetrics();
            float x = dm.widthPixels / 2f;
            float y = dm.heightPixels / 2f;
            Path p = new Path(); p.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 120))
                    .build();
            boolean dispatched;
            try {
                dispatched = s.dispatchGesture(g, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gestureDescription) {
                        Log.d(TAG, "TEST TOUCH: COMPLETED at " + x + "," + y);
                        cb.result(true, "completed");
                    }
                    @Override public void onCancelled(GestureDescription gestureDescription) {
                        Log.e(TAG, "TEST TOUCH: CANCELLED");
                        cb.result(false, "gesture cancelled");
                    }
                }, s.main);
            } catch (Throwable t) {
                Log.e(TAG, "TEST TOUCH: ERROR", t);
                cb.result(false, t.getClass().getSimpleName());
                return;
            }
            if (!dispatched) {
                Log.e(TAG, "TEST TOUCH: dispatchGesture=false");
                cb.result(false, "dispatchGesture=false");
            }
        });
    }

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

    private volatile long cooldownUntil = 0L;

    private void process(Bitmap bm) {
        if (!running) { bm.recycle(); return; }
        long now = System.currentTimeMillis();
        if (now < cooldownUntil) { bm.recycle(); return; }

        Board board = Vision.readBoard(bm);
        List<Piece> pieces = Vision.readPieces(bm);
        Log.d(TAG, "vision: pieces=" + pieces.size() + " board=" + boardCount(board));

        if (pieces.isEmpty()) { bm.recycle(); return; }
        Solver.Move m = Solver.best(board, pieces);
        if (m == null || m.order.length == 0) { bm.recycle(); return; }

        // IMPORTANT: do exactly one move per captured frame. Block Blast changes
        // both the board and the tray immediately after a successful drop, so
        // planning 2-3 moves from one old screenshot is unsafe.
        int idx = m.order[0];
        int row = m.pos[0][0], col = m.pos[0][1];
        Piece piece = pieces.get(idx);

        float sx = bm.getWidth() / 921f, sy = bm.getHeight() / 2048f;
        float x = piece.anchorX * sx;
        float y = piece.anchorY * sy;

        // We start from a real occupied cell of the piece and release on the
        // corresponding occupied cell of the placement. This preserves the
        // finger-to-piece offset instead of assuming that the geometric centre
        // is itself a filled cell.
        float tx = (board.x + (col + piece.anchorC + .5f) * board.cellW) * sx;
        float ty = (board.y + (row + piece.anchorR + .5f) * board.cellH) * sy;

        boolean moved = drag(x, y, tx, ty);
        Log.d(TAG, "move: " + moved + " piece=" + idx + " anchor=" +
                piece.anchorR + "," + piece.anchorC + " from=" + x + "," + y +
                " to=" + tx + "," + ty);

        // Give the game time to animate the drop and expose a fresh tray/board.
        cooldownUntil = System.currentTimeMillis() + (moved ? 850L : 350L);
        bm.recycle();
    }

    private static int boardCount(Board b) {
        int n = 0;
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) if (b.a[r][c]) n++;
        return n;
    }

    private boolean drag(float x1, float y1, float x2, float y2) {
        if (!running) return false;
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean ok =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        final float maxX = Math.max(2, dm.widthPixels - 2);
        final float maxY = Math.max(2, dm.heightPixels - 2);
        final float fx1 = Math.max(2, Math.min(maxX, x1));
        final float fy1 = Math.max(2, Math.min(maxY, y1));
        final float fx2 = Math.max(2, Math.min(maxX, x2));
        final float fy2 = Math.max(2, Math.min(maxY, y2));

        main.post(() -> {
            if (!running) { done.countDown(); return; }
            try {
                // One continuous stroke: DOWN -> tiny movement (keeps the
                // pointer captured) -> smooth drag -> UP. This avoids the
                // unreliable continuation-stroke behaviour seen on some phones.
                Path path = new Path();
                path.moveTo(fx1, fy1);
                path.lineTo(fx1 + Math.copySign(2f, fx2 - fx1 == 0 ? 1 : fx2 - fx1), fy1);
                path.lineTo(fx2, fy2);

                GestureDescription.StrokeDescription stroke =
                        new GestureDescription.StrokeDescription(path, 0, 1200, false);
                GestureDescription gesture = new GestureDescription.Builder()
                        .addStroke(stroke).build();

                boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gd) {
                        ok.set(true);
                        Log.d(TAG, "GESTURE OK " + fx1 + "," + fy1 + " -> " + fx2 + "," + fy2);
                        done.countDown();
                    }
                    @Override public void onCancelled(GestureDescription gd) {
                        Log.e(TAG, "GESTURE CANCELLED " + fx1 + "," + fy1 + " -> " + fx2 + "," + fy2);
                        done.countDown();
                    }
                }, main);
                if (!dispatched) {
                    Log.e(TAG, "GESTURE REJECTED");
                    done.countDown();
                }
            } catch (Throwable t) {
                Log.e(TAG, "GESTURE ERROR", t);
                done.countDown();
            }
        });

        try {
            done.await(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
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

    static class Board {
        boolean[][] a = new boolean[8][8];
        float x, y, cellW, cellH;
    }

    static class Piece {
        boolean[][] a;
        int h, w, minR, minC;
        int anchorR, anchorC;
        float anchorX, anchorY;
    }

    static class Vision {
        // Detect a tile by its local appearance rather than a specific hue.
        // This survives Block Blast changing the piece colour.
        static boolean sampleTile(Bitmap b, float x, float y, float sx, float sy, boolean tray) {
            int cx = Math.round(x * sx), cy = Math.round(y * sy);
            int inner = Math.max(3, Math.round((tray ? 13 : 25) * Math.min(sx, sy)));
            int outer = Math.max(inner + 4, Math.round((tray ? 22 : 38) * Math.min(sx, sy)));
            long inV = 0, inS = 0, inN = 0, ringV = 0, ringS = 0, ringN = 0;
            float[] hsv = new float[3];
            for (int dy = -outer; dy <= outer; dy += Math.max(1, Math.round(3 * Math.min(sx, sy)))) {
                for (int dx = -outer; dx <= outer; dx += Math.max(1, Math.round(3 * Math.min(sx, sy)))) {
                    if (dx*dx + dy*dy > outer*outer) continue;
                    int px = Math.max(0, Math.min(b.getWidth()-1, cx + dx));
                    int py = Math.max(0, Math.min(b.getHeight()-1, cy + dy));
                    Color.colorToHSV(b.getPixel(px, py), hsv);
                    boolean inside = dx*dx + dy*dy <= inner*inner;
                    if (inside) { inV += hsv[2]; inS += hsv[1]; inN++; }
                    else { ringV += hsv[2]; ringS += hsv[1]; ringN++; }
                }
            }
            if (inN == 0 || ringN == 0) return false;
            float iv = (inV / (float)inN), rv = (ringV / (float)ringN);
            float is = (inS / (float)inN), rs = (ringS / (float)ringN);
            // A tile is normally brighter than the empty board/tray background,
            // or strongly more saturated. Either signal is sufficient.
            return (iv - rv > (tray ? 0.08f : 0.10f)) ||
                   (is - rs > 0.08f && iv > 0.30f);
        }

        static Board readBoard(Bitmap b) {
            Board z = new Board();
            float sx = b.getWidth() / 921f, sy = b.getHeight() / 2048f;
            z.x = 47f; z.y = 447f; z.cellW = 92.35f; z.cellH = 92.35f;
            for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) {
                z.a[r][c] = sampleTile(b,
                        z.x + (c + .5f) * z.cellW,
                        z.y + (r + .5f) * z.cellH, sx, sy, false);
            }
            return z;
        }

        static List<Piece> readPieces(Bitmap b) {
            float sx = b.getWidth()/921f, sy = b.getHeight()/2048f;
            // The three tray slots are fixed horizontally, but the shape's
            // centre is NOT. We inspect a 5x5 logical grid in each slot.
            float[] origins = {167.6f, 413.6f, 705.5f};
            float cell = 45.9f;
            float y0 = 1504.5f;
            ArrayList<Piece> out = new ArrayList<>();

            for (float ox : origins) {
                boolean[][] grid = new boolean[5][5];
                int count = 0, minR=5, minC=5, maxR=-1, maxC=-1;
                for (int r=0;r<5;r++) for (int c=0;c<5;c++) {
                    float px = ox + (c-1)*cell;
                    float py = y0 + (r-2)*cell;
                    grid[r][c] = sampleTile(b, px, py, sx, sy, true);
                    if (grid[r][c]) {
                        count++; minR=Math.min(minR,r); maxR=Math.max(maxR,r);
                        minC=Math.min(minC,c); maxC=Math.max(maxC,c);
                    }
                }
                if (count < 1 || count > 5 || maxR < 0) continue;

                // Keep only the connected component containing the first tile.
                // This prevents stray UI pixels from becoming part of a piece.
                boolean[][] keep = new boolean[5][5];
                ArrayDeque<int[]> q = new ArrayDeque<>();
                int sr=-1, sc=-1;
                outer: for(int r=0;r<5;r++) for(int c=0;c<5;c++) if(grid[r][c]) { sr=r; sc=c; break outer; }
                q.add(new int[]{sr,sc}); keep[sr][sc]=true;
                int[] dr={1,-1,0,0}, dc={0,0,1,-1};
                while(!q.isEmpty()) {
                    int[] p=q.removeFirst();
                    for(int k=0;k<4;k++) {
                        int nr=p[0]+dr[k], nc=p[1]+dc[k];
                        if(nr>=0&&nr<5&&nc>=0&&nc<5&&grid[nr][nc]&&!keep[nr][nc]) {
                            keep[nr][nc]=true; q.addLast(new int[]{nr,nc});
                        }
                    }
                }
                minR=5; minC=5; maxR=-1; maxC=-1; count=0;
                for(int r=0;r<5;r++) for(int c=0;c<5;c++) if(keep[r][c]) {
                    count++; minR=Math.min(minR,r); maxR=Math.max(maxR,r);
                    minC=Math.min(minC,c); maxC=Math.max(maxC,c);
                }
                if(count < 1 || count > 5) continue;

                Piece p = new Piece();
                p.h=maxR-minR+1; p.w=maxC-minC+1;
                p.a=new boolean[p.h][p.w];
                float avgR=0, avgC=0;
                for(int r=minR;r<=maxR;r++) for(int c=minC;c<=maxC;c++) if(keep[r][c]) {
                    p.a[r-minR][c-minC]=true; avgR += r; avgC += c;
                }
                avgR/=count; avgC/=count;
                int ar=minR, ac=minC;
                double best=Double.MAX_VALUE;
                for(int r=minR;r<=maxR;r++) for(int c=minC;c<=maxC;c++) if(keep[r][c]) {
                    double d=(r-avgR)*(r-avgR)+(c-avgC)*(c-avgC);
                    if(d<best){best=d; ar=r; ac=c;}
                }
                p.anchorR=ar-minR; p.anchorC=ac-minC;
                p.anchorX=ox+(ac-1)*cell;
                p.anchorY=y0+(ar-2)*cell;
                out.add(p);
            }
            return out;
        }
    }

    static class Solver {
        static class Move {
            int[] order;
            int[][] pos;
            int score=-999999;
            Move(int n){ order=new int[n]; pos=new int[n][2]; }
        }

        static Move best(Board b, List<Piece> ps) {
            int n=ps.size();
            int[] ord=new int[n]; for(int i=0;i<n;i++) ord[i]=i;
            Move best=null;
            do {
                Board c=copy(b); int score=0; int[][] pp=new int[n][2]; boolean ok=true;
                for(int k=0;k<n;k++) {
                    Piece p=ps.get(ord[k]); int[] q=bestPos(c,p);
                    if(q==null){ok=false;break;}
                    place(c,p,q[0],q[1]); score+=evaluate(c);
                    pp[k]=q;
                }
                if(ok&&(best==null||score>best.score)) {
                    best=new Move(n); best.score=score;
                    for(int k=0;k<n;k++){best.order[k]=ord[k];best.pos[k]=pp[k];}
                }
            } while(next(ord));
            return best;
        }

        static int[] bestPos(Board b,Piece p) {
            int best=-999999,br=-1,bc=-1;
            for(int r=0;r<=8-p.h;r++) for(int c=0;c<=8-p.w;c++) {
                boolean ok=true;
                for(int y=0;y<p.h;y++) for(int x=0;x<p.w;x++) if(p.a[y][x]&&b.a[r+y][c+x]) ok=false;
                if(!ok) continue;
                Board t=copy(b); place(t,p,r,c); int s=evaluate(t);
                if(s>best){best=s;br=r;bc=c;}
            }
            return br<0?null:new int[]{br,bc};
        }

        static void place(Board b,Piece p,int r,int c) {
            for(int y=0;y<p.h;y++) for(int x=0;x<p.w;x++) if(p.a[y][x]) b.a[r+y][c+x]=true;
            boolean[] rows=new boolean[8], cols=new boolean[8];
            for(int r0=0;r0<8;r0++){rows[r0]=true;for(int c0=0;c0<8;c0++)if(!b.a[r0][c0])rows[r0]=false;}
            for(int c0=0;c0<8;c0++){cols[c0]=true;for(int r0=0;r0<8;r0++)if(!b.a[r0][c0])cols[c0]=false;}
            for(int r0=0;r0<8;r0++)if(rows[r0])for(int c0=0;c0<8;c0++)b.a[r0][c0]=false;
            for(int c0=0;c0<8;c0++)if(cols[c0])for(int r0=0;r0<8;r0++)b.a[r0][c0]=false;
        }

        static int evaluate(Board b) {
            int empty=0,adj=0,holes=0;
            for(int r=0;r<8;r++) for(int c=0;c<8;c++) {
                if(!b.a[r][c]) empty++;
                else {if(r<7&&b.a[r+1][c])adj++;if(c<7&&b.a[r][c+1])adj++;}
            }
            return empty*5+adj*2-holes;
        }

        static Board copy(Board b){
            Board n=new Board();
            for(int r=0;r<8;r++)System.arraycopy(b.a[r],0,n.a[r],0,8);
            n.x=b.x;n.y=b.y;n.cellW=b.cellW;n.cellH=b.cellH;return n;
        }

        static boolean next(int[] a){
            int i=a.length-2; while(i>=0&&a[i]>a[i+1])i--; if(i<0)return false;
            int j=a.length-1; while(a[j]<a[i])j--; int t=a[i];a[i]=a[j];a[j]=t;
            for(int l=i+1,r=a.length-1;l<r;l++,r--){t=a[l];a[l]=a[r];a[r]=t;} return true;
        }
    }

}
