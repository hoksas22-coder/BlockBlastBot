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

        // IMPORTANT: make exactly ONE placement from each captured frame.
        // Block Blast can animate the tray colour, board clears and the new piece
        // set immediately after a drop. Planning all 3 moves from one old frame
        // makes the second/third move stale. Re-capturing after every placement
        // keeps the vision state synchronized with the actual game.
        Board board = Vision.readBoard(bm);
        List<Piece> pieces = Vision.readPieces(bm);
        Log.d(TAG, "vision: pieces=" + pieces.size() + " board=" + boardCount(board));
        for (int i=0;i<pieces.size();i++) {
            Piece q=pieces.get(i);
            Log.d(TAG, "piece["+i+"] source="+q.cx+","+q.cy+" anchor="+q.anchorR+","+q.anchorC+" size="+q.h+"x"+q.w);
        }
        if (pieces.size() != 3) {
            bm.recycle();
            return;
        }

        Solver.Move m = Solver.best(board, pieces);
        if (m == null) { bm.recycle(); return; }

        int idx = m.order[0];
        int row = m.pos[0][0], col = m.pos[0][1];
        Piece piece = pieces.get(idx);
        float sx = bm.getWidth() / 921f, sy = bm.getHeight() / 2048f;

        // Start on a real occupied tray cell, not the group's bounding-box center.
        float x = piece.cx * sx, y = piece.cy * sy;

        // Block Blast lifts the held piece above the finger. Therefore the finger
        // must finish about one cell BELOW the anchor cell we want to occupy.
        // Without this offset the visual piece is released too high and is often
        // outside/above the intended landing zone.
        final float LIFT_OFFSET = 93f;
        float tx = (board.x + (col + piece.anchorC + .5f) * board.cellW) * sx;
        float ty = (board.y + (row + piece.anchorR + .5f) * board.cellH + LIFT_OFFSET) * sy;

        boolean moved = drag(x, y, tx, ty);
        Log.d(TAG, "move: piece=" + idx + " moved=" + moved +
                " from=" + x + "," + y + " fingerDrop=" + tx + "," + ty +
                " anchorTarget=" + (board.x + (col + piece.anchorC + .5f) * board.cellW) + "," +
                (board.y + (row + piece.anchorR + .5f) * board.cellH));

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
        final float fx1 = Math.max(2, Math.min(maxX, x1));
        final float fy1 = Math.max(2, Math.min(maxY, y1));
        final float fx2 = Math.max(2, Math.min(maxX, x2));
        final float fy2 = Math.max(2, Math.min(maxY, y2));

        main.post(() -> {
            if (!running) { done.countDown(); return; }
            try {
                // IMPORTANT: use ONE continuous stroke. Some Unity games interpret
                // Accessibility continuation strokes as a tap/click even though the
                // callback reports COMPLETED. A single path gives the game one real
                // DOWN -> MOVE... -> UP sequence.
                Path path = new Path();
                path.moveTo(fx1, fy1);

                // Tiny initial move makes the DOWN unambiguous, then hold the finger
                // almost still before the actual drag. The whole operation remains
                // one stroke, so there is no synthetic UP between hold and move.
                float ex = fx1;
                float ey = fy1;
                float dx = fx2 - fx1;
                float dy = fy2 - fy1;
                float len = (float)Math.hypot(dx, dy);
                float ux = len > 1 ? dx / len : 1f;
                float uy = len > 1 ? dy / len : 0f;
                float nudge = Math.min(3f, Math.max(1f, len));
                ex += ux * nudge;
                ey += uy * nudge;
                path.lineTo(ex, ey);

                // Add several intermediate points. This is deliberately not a straight
                // instantaneous jump: Unity receives a stream of MOVE events.
                int steps = 24;
                for (int i = 1; i <= steps; i++) {
                    float t = i / (float)steps;
                    // Smoothstep gives a human-like acceleration/deceleration.
                    float q = t * t * (3f - 2f * t);
                    path.lineTo(ex + (fx2 - ex) * q, ey + (fy2 - ey) * q);
                }

                long duration = 1400;
                GestureDescription.StrokeDescription stroke =
                        new GestureDescription.StrokeDescription(path, 0, duration, false);
                GestureDescription gesture = new GestureDescription.Builder()
                        .addStroke(stroke).build();

                Log.d(TAG, "DRAG dispatch DOWN=" + fx1 + "," + fy1 +
                        " END=" + fx2 + "," + fy2 + " duration=" + duration);

                boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gd) {
                        ok.set(true);
                        Log.d(TAG, "DRAG COMPLETED DOWN=" + fx1 + "," + fy1 +
                                " END=" + fx2 + "," + fy2);
                        done.countDown();
                    }
                    @Override public void onCancelled(GestureDescription gd) {
                        Log.e(TAG, "DRAG CANCELLED DOWN=" + fx1 + "," + fy1 +
                                " END=" + fx2 + "," + fy2);
                        done.countDown();
                    }
                }, main);
                if (!dispatched) {
                    Log.e(TAG, "DRAG REJECTED DOWN=" + fx1 + "," + fy1 +
                            " END=" + fx2 + "," + fy2);
                    done.countDown();
                }
            } catch (Throwable t) {
                Log.e(TAG, "DRAG ERROR", t);
                done.countDown();
            }
        });
        try {
            if (!done.await(4500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "DRAG TIMEOUT");
            }
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

    static class Board { boolean[][] a = new boolean[8][8]; float x,y,cellW,cellH; }
    static class Piece { boolean[][] a; int h,w,minR,minC,anchorR,anchorC; float cx,cy; }
    static class Vision {
        // Color-independent tile detection. Block Blast can change the tile color,
        // so do NOT assume blue/cyan. We compare each tile against the local green
        // background and use brightness/color distance instead.
        static float luma(int c) {
            return 0.2126f*Color.red(c) + 0.7152f*Color.green(c) + 0.0722f*Color.blue(c);
        }
        static float dist(int c1, int c2) {
            float dr=Color.red(c1)-Color.red(c2), dg=Color.green(c1)-Color.green(c2), db=Color.blue(c1)-Color.blue(c2);
            return (float)Math.sqrt(dr*dr+dg*dg+db*db);
        }
        static int meanColor(Bitmap b, int x0,int y0,int x1,int y1) {
            long sr=0,sg=0,sb=0,n=0;
            x0=Math.max(0,Math.min(b.getWidth()-1,x0)); x1=Math.max(x0+1,Math.min(b.getWidth(),x1));
            y0=Math.max(0,Math.min(b.getHeight()-1,y0)); y1=Math.max(y0+1,Math.min(b.getHeight(),y1));
            int step=Math.max(1,Math.min(x1-x0,y1-y0)/12);
            for(int y=y0;y<y1;y+=step) for(int x=x0;x<x1;x+=step){int c=b.getPixel(x,y);sr+=Color.red(c);sg+=Color.green(c);sb+=Color.blue(c);n++;}
            return Color.rgb((int)(sr/n),(int)(sg/n),(int)(sb/n));
        }
        static boolean sampleBlock(Bitmap b, float x, float y, float sx, float sy, int ignoredBg) {
            int cx=Math.round(x*sx), cy=Math.round(y*sy);
            int coreR=Math.max(10,Math.round(18*Math.min(sx,sy)));
            int outerR=Math.max(coreR+6,Math.round(43*Math.min(sx,sy)));
            long cr=0,cg=0,cb=0,cn=0, rr=0,rg=0,rb=0,rn=0;
            for(int yy=-outerR;yy<=outerR;yy+=3) for(int xx=-outerR;xx<=outerR;xx+=3){
                if(xx*xx+yy*yy > outerR*outerR) continue;
                int px=Math.max(0,Math.min(b.getWidth()-1,cx+xx)), py=Math.max(0,Math.min(b.getHeight()-1,cy+yy));
                int c=b.getPixel(px,py);
                if(Math.abs(xx)<=coreR && Math.abs(yy)<=coreR){cr+=Color.red(c);cg+=Color.green(c);cb+=Color.blue(c);cn++;}
                else {rr+=Color.red(c);rg+=Color.green(c);rb+=Color.blue(c);rn++;}
            }
            if(cn==0||rn==0)return false;
            int core=Color.rgb((int)(cr/cn),(int)(cg/cn),(int)(cb/cn));
            int ring=Color.rgb((int)(rr/rn),(int)(rg/rn),(int)(rb/rn));
            // Occupied cells differ from the immediate board background regardless
            // of whether the current skin is cyan, red, yellow, purple, etc.
            return dist(core,ring)>28f;
        }

        static Board readBoard(Bitmap b) {
            Board z=new Board();
            float sx=b.getWidth()/921f, sy=b.getHeight()/2048f;
            z.x=47f; z.y=447f; z.cellW=92.9f; z.cellH=92.9f;

            // Estimate the empty-board color from the four corners just outside the grid.
            int bg1=meanColor(b,20,420,70,445), bg2=meanColor(b,770,420,820,445);
            int bg=Color.rgb((Color.red(bg1)+Color.red(bg2))/2,(Color.green(bg1)+Color.green(bg2))/2,(Color.blue(bg1)+Color.blue(bg2))/2);
            for(int r=0;r<8;r++) for(int c=0;c<8;c++)
                z.a[r][c]=sampleBlock(b,z.x+(c+.5f)*z.cellW,z.y+(r+.5f)*z.cellH,sx,sy,bg);
            return z;
        }

        static class TrayCell { float x,y; TrayCell(float x,float y){this.x=x;this.y=y;} }

        static List<Piece> readPieces(Bitmap b) {
            ArrayList<Piece> out=new ArrayList<>();
            float sx=b.getWidth()/921f, sy=b.getHeight()/2048f;

            // The three tray slots are fixed relative to the calibrated 921x2048
            // screen. Detect cells independently in each slot instead of merging
            // the whole tray. This prevents a colour change, shadow, or a new piece
            // from making the detector lose all three pieces at once.
            final float[] slotX = {190f, 460f, 730f};
            final float centerY = 1540f;
            final float pitch = 46f;
            final int maxCells = 5;

            for (int slot=0; slot<3; slot++) {
                ArrayList<TrayCell> cells=new ArrayList<>();
                float cx0=slotX[slot];

                for (int r=-2; r<=2; r++) for (int c=-2; c<=2; c++) {
                    float cx=cx0+c*pitch, cy=centerY+r*pitch;
                    if (cx<80||cx>840||cy<1360||cy>1640) continue;
                    if (isTrayCell(b,cx,cy,sx,sy)) cells.add(new TrayCell(cx,cy));
                }

                // Remove isolated false positives and normalize to a connected
                // shape on the 46px logical tray grid.
                if (cells.size()==0 || cells.size()>5) continue;
                float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE;
                for(TrayCell c:cells){minX=Math.min(minX,c.x);minY=Math.min(minY,c.y);maxX=Math.max(maxX,c.x);maxY=Math.max(maxY,c.y);}
                int cols=Math.max(1,Math.min(maxCells,Math.round((maxX-minX)/pitch)+1));
                int rows=Math.max(1,Math.min(maxCells,Math.round((maxY-minY)/pitch)+1));
                boolean[][] a=new boolean[rows][cols];
                for(TrayCell c:cells){
                    int cc=Math.max(0,Math.min(cols-1,Math.round((c.x-minX)/pitch)));
                    int rr=Math.max(0,Math.min(rows-1,Math.round((c.y-minY)/pitch)));
                    a[rr][cc]=true;
                }

                // The centre of an occupied tile is the safest DOWN point.
                float ax=0,ay=0; for(TrayCell c:cells){ax+=c.x;ay+=c.y;} ax/=cells.size(); ay/=cells.size();
                TrayCell bestCell=cells.get(0); float best=Float.MAX_VALUE;
                for(TrayCell c:cells){float d=(c.x-ax)*(c.x-ax)+(c.y-ay)*(c.y-ay);if(d<best){best=d;bestCell=c;}}

                Piece p=new Piece(); p.h=rows;p.w=cols;p.a=a;p.minR=0;p.minC=0;
                p.cx=bestCell.x;p.cy=bestCell.y;
                p.anchorC=Math.max(0,Math.min(cols-1,Math.round((bestCell.x-minX)/pitch)));
                p.anchorR=Math.max(0,Math.min(rows-1,Math.round((bestCell.y-minY)/pitch)));
                out.add(p);
            }
            return out;
        }

        static boolean isTrayCell(Bitmap b,float x,float y,float sx,float sy){
            int cx=Math.round(x*sx), cy=Math.round(y*sy);
            int rad=Math.max(8,Math.round(12*Math.min(sx,sy)));
            int ring=0, core=0, n=0;
            long cr=0,cg=0,cb=0, rr=0,rg=0,rb=0;
            for(int yy=-rad;yy<=rad;yy+=2) for(int xx=-rad;xx<=rad;xx+=2){
                int px=Math.max(0,Math.min(b.getWidth()-1,cx+xx)), py=Math.max(0,Math.min(b.getHeight()-1,cy+yy));
                int c=b.getPixel(px,py);
                // Central 18x18 area belongs to the tile; outer ring is local tray bg.
                if(Math.abs(xx)<=9 && Math.abs(yy)<=9){cr+=Color.red(c);cg+=Color.green(c);cb+=Color.blue(c);core++;}
                else {rr+=Color.red(c);rg+=Color.green(c);rb+=Color.blue(c);ring++;}
            }
            if(core==0||ring==0)return false;
            int cc=Color.rgb((int)(cr/core),(int)(cg/core),(int)(cb/core));
            int bg=Color.rgb((int)(rr/ring),(int)(rg/ring),(int)(rb/ring));
            float d=dist(cc,bg);
            // Tile faces are much more internally consistent and much farther from
            // the local green tray than the textured background. No colour/luma
            // direction is assumed, so red/yellow/purple/cyan all work.
            return d>34f;
        }

        static float avgX(ArrayList<TrayCell> l){float s=0;for(TrayCell o:l)s+=o.x;return s/l.size();}
        static float avgY(ArrayList<TrayCell> l){float s=0;for(TrayCell o:l)s+=o.y;return s/l.size();}
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
