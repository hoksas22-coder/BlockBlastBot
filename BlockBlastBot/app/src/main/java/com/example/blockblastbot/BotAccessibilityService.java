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

    private void process(Bitmap bm) {
        if (!running) { bm.recycle(); return; }
        Board board = Vision.readBoard(bm); List<Piece> pieces = Vision.readPieces(bm);
        Log.d(TAG, "vision: pieces=" + pieces.size() + " board=" + boardCount(board));
        for (int i=0;i<pieces.size();i++) Log.d(TAG, "piece["+i+"] source="+pieces.get(i).cx+","+pieces.get(i).cy+" anchor="+pieces.get(i).anchorR+","+pieces.get(i).anchorC+" size="+pieces.get(i).h+"x"+pieces.get(i).w);
        if (pieces.size() != 3) { bm.recycle(); return; }
        Solver.Move m = Solver.best(board, pieces);
        if (m == null) { bm.recycle(); return; }
        float sx = bm.getWidth() / 921f, sy = bm.getHeight() / 2048f;
        for (int k = 0; k < 3 && running; k++) {
            int idx = m.order[k], row = m.pos[k][0], col = m.pos[k][1];
            Piece piece = pieces.get(idx);
            float x = piece.cx * sx, y = piece.cy * sy;
            float tx = (board.x + (col + piece.anchorC + .5f) * board.cellW) * sx;
            float ty = (board.y + (row + piece.anchorR + .5f) * board.cellH) * sy;
            boolean moved = drag(x, y, tx, ty);
            Log.d(TAG, "move " + k + ": " + moved + " from=" + x + "," + y + " to=" + tx + "," + ty);
            if (!moved) break;
            try { Thread.sleep(180); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
        bm.recycle();
    }

    private static int boardCount(Board b) {
        int n=0; for(int r=0;r<8;r++) for(int c=0;c<8;c++) if(b.a[r][c]) n++; return n;
    }

    private boolean drag(float x1, float y1, float x2, float y2) {
        if (!running) return false;
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean ok = new java.util.concurrent.atomic.AtomicBoolean(false);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final float maxX = Math.max(2, dm.widthPixels - 2), maxY = Math.max(2, dm.heightPixels - 2);
        final float fx1 = Math.max(2, Math.min(maxX, x1)), fy1 = Math.max(2, Math.min(maxY, y1));
        final float fx2 = Math.max(2, Math.min(maxX, x2)), fy2 = Math.max(2, Math.min(maxY, y2));
        main.post(() -> {
            if (!running) { done.countDown(); return; }
            try {
                // First stroke creates a real DOWN and a tiny MOVE while held.
                // The continuation keeps the same finger DOWN and then drags.
                Path holdPath = new Path();
                holdPath.moveTo(fx1, fy1);
                holdPath.lineTo(fx1 + Math.min(2f, maxX-fx1), fy1 + Math.min(2f, maxY-fy1));
                GestureDescription.StrokeDescription hold =
                        new GestureDescription.StrokeDescription(holdPath, 0, 300, true);

                float sx0 = fx1 + Math.min(2f, maxX-fx1);
                float sy0 = fy1 + Math.min(2f, maxY-fy1);
                Path movePath = new Path();
                movePath.moveTo(sx0, sy0);
                movePath.lineTo(fx2, fy2);
                GestureDescription.StrokeDescription move = hold.continueStroke(movePath, 300, 900, false);

                GestureDescription gesture = new GestureDescription.Builder().addStroke(hold).addStroke(move).build();
                boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gd) {
                        ok.set(true); Log.d(TAG, "DRAG OK " + fx1 + "," + fy1 + " -> " + fx2 + "," + fy2); done.countDown();
                    }
                    @Override public void onCancelled(GestureDescription gd) {
                        Log.e(TAG, "DRAG CANCELLED " + fx1 + "," + fy1 + " -> " + fx2 + "," + fy2); done.countDown();
                    }
                }, main);
                if (!dispatched) { Log.e(TAG, "DRAG REJECTED"); done.countDown(); }
            } catch (Throwable t) { Log.e(TAG, "DRAG ERROR", t); done.countDown(); }
        });
        try { if (!done.await(3500, java.util.concurrent.TimeUnit.MILLISECONDS)) Log.e(TAG, "DRAG TIMEOUT"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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

    static class Board { boolean[][] a = new boolean[8][8]; float x,y,cellW,cellH; }
    static class Piece { boolean[][] a; int h,w,minR,minC,anchorR,anchorC; float cx,cy; }
    static class Vision {
        // The screenshot/game uses bright cyan-blue tiles on a green background.
        // The old detector treated the green background as a block because it only
        // checked saturation. Require the tile's blue channel to dominate green.
        static boolean isBlock(int c) {
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            return b > 135 && b > g + 18 && g > r + 45 && r < 100;
        }

        static boolean sampleBlock(Bitmap b, float x, float y, float sx, float sy) {
            int cx = Math.round(x * sx), cy = Math.round(y * sy);
            int rad = Math.max(3, Math.round(9 * Math.min(sx, sy)));
            int hits = 0, total = 0;
            for (int yy = -rad; yy <= rad; yy += Math.max(1, rad / 3)) {
                for (int xx = -rad; xx <= rad; xx += Math.max(1, rad / 3)) {
                    int px = Math.max(0, Math.min(b.getWidth()-1, cx+xx));
                    int py = Math.max(0, Math.min(b.getHeight()-1, cy+yy));
                    total++;
                    if (isBlock(b.getPixel(px, py))) hits++;
                }
            }
            return hits * 2 >= total;
        }

        static Board readBoard(Bitmap b) {
            Board z = new Board();
            // Block Blast board is 8x8. Calibrated from the supplied 921x2048 screenshot.
            // Outer board is approximately x=47..790, y=447..1190; cell size ~92.9 px.
            float sx = b.getWidth() / 921f, sy = b.getHeight() / 2048f;
            z.x = 47f; z.y = 447f; z.cellW = 92.9f; z.cellH = 92.9f;
            for (int r=0;r<8;r++) for (int c=0;c<8;c++)
                z.a[r][c] = sampleBlock(b, z.x+(c+.5f)*z.cellW,
                        z.y+(r+.5f)*z.cellH, sx, sy);
            return z;
        }

        static class TrayCell { float x,y; TrayCell(float x,float y){this.x=x;this.y=y;} }

        static List<Piece> readPieces(Bitmap b) {
            ArrayList<Piece> out = new ArrayList<>();
            float sx = b.getWidth()/921f, sy = b.getHeight()/2048f;

            // Detect individual blue tray cells in the actual tray area instead of
            // assuming three fixed 5x5 grids. This handles the different vertical
            // offsets of the pieces and avoids confusing the green background with
            // blocks.
            int left = Math.max(0, Math.round(70*sx));
            int right = Math.min(b.getWidth(), Math.round(850*sx));
            int top = Math.max(0, Math.round(1370*sy));
            int bottom = Math.min(b.getHeight(), Math.round(1650*sy));
            int w = right-left, h = bottom-top;
            if (w <= 0 || h <= 0) return out;

            Bitmap crop = Bitmap.createBitmap(b, left, top, w, h);
            try {
                // Connected components over a small binary mask. Android has no
                // OpenCV dependency, so use a compact flood fill over sampled pixels.
                int step = Math.max(2, Math.round(2 * Math.min(sx, sy)));
                int gw = (w + step - 1) / step, gh = (h + step - 1) / step;
                boolean[][] on = new boolean[gh][gw], seen = new boolean[gh][gw];
                for (int gy=0; gy<gh; gy++) for (int gx=0; gx<gw; gx++) {
                    int px = Math.min(w-1, gx*step + step/2);
                    int py = Math.min(h-1, gy*step + step/2);
                    on[gy][gx] = isBlock(crop.getPixel(px,py));
                }

                ArrayList<TrayCell> cells = new ArrayList<>();
                int[] dx={1,-1,0,0}, dy={0,0,1,-1};
                ArrayDeque<int[]> q = new ArrayDeque<>();
                for (int gy=0; gy<gh; gy++) for (int gx=0; gx<gw; gx++) {
                    if (!on[gy][gx] || seen[gy][gx]) continue;
                    seen[gy][gx]=true; q.add(new int[]{gx,gy});
                    int count=0, minx=gx,maxx=gx,miny=gy,maxy=gy;
                    while(!q.isEmpty()) {
                        int[] v=q.removeFirst(); int x0=v[0], y0=v[1]; count++;
                        minx=Math.min(minx,x0); maxx=Math.max(maxx,x0);
                        miny=Math.min(miny,y0); maxy=Math.max(maxy,y0);
                        for(int d=0;d<4;d++){int nx=x0+dx[d],ny=y0+dy[d];
                            if(nx>=0&&nx<gw&&ny>=0&&ny<gh&&on[ny][nx]&&!seen[ny][nx]){
                                seen[ny][nx]=true;q.add(new int[]{nx,ny});
                            }
                        }
                    }
                    // Each tile is roughly 43x42 px. Convert a sufficiently large
                    // component into its center; tiny anti-aliasing fragments are ignored.
                    if (count >= 120) {
                        float px = left + ((minx+maxx+1)/2f)*step;
                        float py = top  + ((miny+maxy+1)/2f)*step;
                        cells.add(new TrayCell(px/sx, py/sy));
                    }
                }

                // Group tile centers into the three tray pieces. The gaps between
                // pieces are ~200 logical px, while cells within a piece are ~46 px.
                cells.sort((a,c)->Float.compare(a.x,c.x));
                ArrayList<ArrayList<TrayCell>> groups = new ArrayList<>();
                for (TrayCell c : cells) {
                    ArrayList<TrayCell> g = groups.isEmpty()?null:groups.get(groups.size()-1);
                    float maxX = -Float.MAX_VALUE;
                    if (g!=null) for(TrayCell z:g) maxX=Math.max(maxX,z.x);
                    if (g==null || c.x-maxX > 120f) { g=new ArrayList<>(); groups.add(g); }
                    g.add(c);
                }
                if (groups.size() > 3) {
                    // Keep the three groups with the most cells.
                    groups.sort((a,c)->Integer.compare(c.size(),a.size()));
                    while(groups.size()>3) groups.remove(groups.size()-1);
                    groups.sort((a,c)->Float.compare(avgX(a),avgX(c)));
                }

                for (ArrayList<TrayCell> g : groups) {
                    if (g.size()<1 || g.size()>5) continue;
                    float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE;
                    for(TrayCell c:g){minX=Math.min(minX,c.x);minY=Math.min(minY,c.y);maxX=Math.max(maxX,c.x);maxY=Math.max(maxY,c.y);}
                    float grid=46f;
                    int cols=Math.max(1,Math.round((maxX-minX)/grid)+1);
                    int rows=Math.max(1,Math.round((maxY-minY)/grid)+1);
                    cols=Math.min(5,cols); rows=Math.min(5,rows);
                    boolean[][] a=new boolean[rows][cols];
                    for(TrayCell c:g){int cc=Math.max(0,Math.min(cols-1,Math.round((c.x-minX)/grid))); int rr=Math.max(0,Math.min(rows-1,Math.round((c.y-minY)/grid))); a[rr][cc]=true;}
                    Piece p=new Piece(); p.h=rows;p.w=cols;p.a=a;
                    p.minR=0;p.minC=0;
                    // Use an actual occupied cell closest to the group's centroid.
                    // This guarantees DOWN starts on a tile, not in empty space.
                    float ax=avgX(g), ay=avgY(g), best=Float.MAX_VALUE;
                    for(TrayCell c:g){
                        float d=(c.x-ax)*(c.x-ax)+(c.y-ay)*(c.y-ay);
                        if(d<best){
                            best=d; p.cx=c.x; p.cy=c.y;
                            p.anchorC=Math.max(0,Math.min(cols-1,Math.round((c.x-minX)/grid)));
                            p.anchorR=Math.max(0,Math.min(rows-1,Math.round((c.y-minY)/grid)));
                        }
                    }
                    out.add(p);
                }
            } finally { crop.recycle(); }
            out.sort((a,c)->Float.compare(a.cx,c.cx));
            return out;
        }

        static float avgX(ArrayList<TrayCell> list) { float s=0; for(TrayCell o:list) s += o.x; return s/list.size(); }
        static float avgY(ArrayList<TrayCell> list) { float s=0; for(TrayCell o:list) s += o.y; return s/list.size(); }
    }

    static class Solver {
        static class Move{int[]order=new int[3];int[][]pos=new int[3][2];int score=-999999;}
        static Move best(Board b,List<Piece>ps){Move best=null;int[]ord={0,1,2};do{Board c=copy(b);int score=0;int[][]pp=new int[3][2];boolean ok=true;for(int k=0;k<3;k++){Piece p=ps.get(ord[k]);int[]q=bestPos(c,p);if(q==null){ok=false;break;}place(c,p,q[0],q[1]);score+=evaluate(c);pp[k]=q;}if(ok&&(best==null||score>best.score)){best=new Move();best.score=score;for(int k=0;k<3;k++){best.order[k]=ord[k];best.pos[k]=pp[k];}}}while(next(ord));return best;}
        static int[]bestPos(Board b,Piece p){int best=-1,br=-1,bc=-1;for(int r=0;r<=8-p.h;r++)for(int c=0;c<=8-p.w;c++){boolean ok=true;for(int y=0;y<p.h;y++)for(int x=0;x<p.w;x++)if(p.a[y][x]&&b.a[r+y][c+x])ok=false;if(!ok)continue;Board t=copy(b);place(t,p,r,c);int s=evaluate(t);if(s>best){best=s;br=r;bc=c;}}return br<0?null:new int[]{br,bc};}
        static void place(Board b,Piece p,int r,int c){for(int y=0;y<p.h;y++)for(int x=0;x<p.w;x++)if(p.a[y][x])b.a[r+y][c+x]=true;for(int r0=0;r0<8;r0++){boolean full=true;for(int c0=0;c0<8;c0++)if(!b.a[r0][c0])full=false;if(full)for(int c0=0;c0<8;c0++)b.a[r0][c0]=false;}for(int c0=0;c0<8;c0++){boolean full=true;for(int r0=0;r0<8;r0++)if(!b.a[r0][c0])full=false;if(full)for(int r0=0;r0<8;r0++)b.a[r0][c0]=false;}}
        static int evaluate(Board b){int empty=0,adj=0;for(int r=0;r<8;r++)for(int c=0;c<8;c++){if(!b.a[r][c])empty++;else{if(r<7&&b.a[r+1][c])adj++;if(c<7&&b.a[r][c+1])adj++;}}return empty*3+adj;}
        static Board copy(Board b){Board n=new Board();for(int r=0;r<8;r++)System.arraycopy(b.a[r],0,n.a[r],0,8);n.x=b.x;n.y=b.y;n.cellW=b.cellW;n.cellH=b.cellH;return n;}
        static boolean next(int[]a){int i=a.length-2;while(i>=0&&a[i]>a[i+1])i--;if(i<0)return false;int j=a.length-1;while(a[j]<a[i])j--;int t=a[i];a[i]=a[j];a[j]=t;for(int l=i+1,r=a.length-1;l<r;l++,r--){t=a[l];a[l]=a[r];a[r]=t;}return true;}
    }
}
