package com.example.blockblastbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.util.*;
import java.util.concurrent.Executors;

public class BotAccessibilityService extends AccessibilityService {
    private static BotAccessibilityService instance;
    private static volatile boolean running=false;
    private final Handler h=new Handler(Looper.getMainLooper());
    public static void startBot(){ if(instance!=null){ running=true; instance.loop(); } }
    public static void stopBot(){ running=false; }
    @Override public void onServiceConnected(){ instance=this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){ running=false; }

    private void loop(){ if(!running)return; takeScreen(); }
    private void takeScreen(){
        if(android.os.Build.VERSION.SDK_INT<30){return;}
        takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult r){
                HardwareBuffer hb=r.getHardwareBuffer(); Bitmap b=Bitmap.wrapHardwareBuffer(hb,r.getColorSpace());
                if(b!=null){ Bitmap c=b.copy(Bitmap.Config.ARGB_8888,false); b.recycle(); hb.close(); process(c); c.recycle(); }
                else { hb.close(); schedule(); }
            }
            @Override public void onFailure(int errorCode){ schedule(); }
        });
    }
    private void schedule(){ if(running) h.postDelayed(this::loop,700); }

    private void process(Bitmap bm){
        Board board=Vision.readBoard(bm); List<Piece> pieces=Vision.readPieces(bm);
        if(pieces.size()!=3){ schedule(); return; }
        Solver.Move m=Solver.best(board,pieces);
        if(m==null){schedule();return;}
        float sx=bm.getWidth()/921f, sy=bm.getHeight()/2048f;
        int cellW=Math.round(board.cellW*sx), cellH=Math.round(board.cellH*sy);
        for(int k=0;k<3;k++){
            int idx=m.order[k]; int row=m.pos[k][0], col=m.pos[k][1];
            float x=pieces.get(idx).cx*sx, y=pieces.get(idx).cy*sy;
            float tx=(board.x+(col+0.5f)*board.cellW)*sx;
            float ty=(board.y+(row+0.5f)*board.cellH)*sy;
            drag(x,y,tx,ty);
            try{Thread.sleep(420);}catch(Exception ignored){}
        }
        schedule();
    }
    private void drag(float x1,float y1,float x2,float y2){
        h.post(()->{ Path p=new Path(); p.moveTo(x1,y1); p.lineTo(x2,y2);
            GestureDescription.Builder g=new GestureDescription.Builder();
            g.addStroke(new GestureDescription.StrokeDescription(p,0,280));
            dispatchGesture(g.build(),null,null);
        });
    }

    static class Board { boolean[][] a=new boolean[10][10]; float x,y,cellW,cellH; }
    static class Piece { boolean[][] a; int h,w,minR,minC; float cx,cy; }

    static class Vision {
        static boolean red(int c){int r=Color.red(c),g=Color.green(c),b=Color.blue(c);return r>175 && r>g*1.45 && r>b*1.35;}
        static Board readBoard(Bitmap b){
            Board z=new Board(); float sx=b.getWidth()/921f, sy=b.getHeight()/2048f;
            z.x=47*sx; z.y=447*sy; z.cellW=73.8f*sx; z.cellH=74.0f*sy;
            for(int r=0;r<10;r++)for(int c=0;c<10;c++){int px=Math.round(z.x+(c+.5f)*z.cellW),py=Math.round(z.y+(r+.5f)*z.cellH);z.a[r][c]=red(b.getPixel(Math.min(b.getWidth()-1,px),Math.min(b.getHeight()-1,py)));}
            return z;
        }
        static List<Piece> readPieces(Bitmap b){
            int w=b.getWidth(),h=b.getHeight(); boolean[][] m=new boolean[h][w];
            int y0=(int)(h*.62), y1=(int)(h*.79);
            for(int y=y0;y<y1;y+=2)for(int x=0;x<w;x+=2)m[y][x]=red(b.getPixel(x,y));
            boolean[][] seen=new boolean[h][w]; ArrayList<Piece> out=new ArrayList<>();
            int[] dx={-2,2,0,0},dy={0,0,-2,2};
            for(int sy=y0;sy<y1;sy+=2)for(int sx=0;sx<w;sx+=2)if(m[sy][sx]&&!seen[sy][sx]){
                ArrayList<int[]> q=new ArrayList<>(); q.add(new int[]{sx,sy}); seen[sy][sx]=true; int minx=sx,maxx=sx,miny=sy,maxy=sy;
                for(int qi=0;qi<q.size();qi++){int[] p=q.get(qi);minx=Math.min(minx,p[0]);maxx=Math.max(maxx,p[0]);miny=Math.min(miny,p[1]);maxy=Math.max(maxy,p[1]);for(int d=0;d<4;d++){int nx=p[0]+dx[d],ny=p[1]+dy[d];if(nx>=0&&nx<w&&ny>=y0&&ny<y1&&!seen[ny][nx]&&m[ny][nx]){seen[ny][nx]=true;q.add(new int[]{nx,ny});}}}
                if(q.size()<80)continue; // split by approximate tile grid
                float ts=41* (w/921f); int pw=Math.max(1,Math.round((maxx-minx+1)/ts)), ph=Math.max(1,Math.round((maxy-miny+1)/ts));
                Piece p=new Piece();p.w=pw;p.h=ph;p.a=new boolean[ph][pw];p.minC=0;p.minR=0;p.cx=(minx+maxx)/2f;p.cy=(miny+maxy)/2f;
                for(int rr=0;rr<ph;rr++)for(int cc=0;cc<pw;cc++){int cx=Math.round(minx+(cc+.5f)*ts),cy=Math.round(miny+(rr+.5f)*ts); if(cx<w&&cy<h)p.a[rr][cc]=red(b.getPixel(cx,cy));}
                // normalize bounding box of active cells
                int ar=ph,br=-1,ac=pw,bc=-1;for(int rr=0;rr<ph;rr++)for(int cc=0;cc<pw;cc++)if(p.a[rr][cc]){ar=Math.min(ar,rr);br=Math.max(br,rr);ac=Math.min(ac,cc);bc=Math.max(bc,cc);} if(br>=0){boolean[][] n=new boolean[br-ar+1][bc-ac+1];for(int rr=ar;rr<=br;rr++)for(int cc=ac;cc<=bc;cc++)n[rr-ar][cc-ac]=p.a[rr][cc];p.a=n;p.h=n.length;p.w=n[0].length;out.add(p);}
            }
            out.sort(Comparator.comparingDouble(p->p.cx)); return out;
        }
    }

    static class Solver {
        static class Move{int[] order=new int[3];int[][] pos=new int[3][2];int score=-999999;}
        static Move best(Board b,List<Piece> ps){Move best=null;int[] ord={0,1,2};do{Board c=copy(b);int score=0;int[][] pp=new int[3][2];boolean ok=true;for(int k=0;k<3;k++){Piece p=ps.get(ord[k]);int[] q=bestPos(c,p);if(q==null){ok=false;break;}place(c,p,q[0],q[1]);score+=evaluate(c);pp[k]=q;}if(ok&&(best==null||score>best.score)){best=new Move();best.score=score;for(int k=0;k<3;k++){best.order[k]=ord[k];best.pos[k]=pp[k];}}}while(next(ord));return best;}
        static int[] bestPos(Board b,Piece p){int best=-1,br=-1,bc=-1;for(int r=0;r<=10-p.h;r++)for(int c=0;c<=10-p.w;c++){boolean ok=true;for(int y=0;y<p.h;y++)for(int x=0;x<p.w;x++)if(p.a[y][x]&&b.a[r+y][c+x])ok=false;if(!ok)continue;Board t=copy(b);place(t,p,r,c);int s=evaluate(t);if(s>best){best=s;br=r;bc=c;}}return br<0?null:new int[]{br,bc};}
        static void place(Board b,Piece p,int r,int c){for(int y=0;y<p.h;y++)for(int x=0;x<p.w;x++)if(p.a[y][x])b.a[r+y][c+x]=true;for(int r0=0;r0<10;r0++){boolean full=true;for(int c0=0;c0<10;c0++)if(!b.a[r0][c0])full=false;if(full)for(int c0=0;c0<10;c0++)b.a[r0][c0]=false;}for(int c0=0;c0<10;c0++){boolean full=true;for(int r0=0;r0<10;r0++)if(!b.a[r0][c0])full=false;if(full)for(int r0=0;r0<10;r0++)b.a[r0][c0]=false;}}
        static int evaluate(Board b){int empty=0,adj=0;for(int r=0;r<10;r++)for(int c=0;c<10;c++){if(!b.a[r][c])empty++;else{if(r<9&&b.a[r+1][c])adj++;if(c<9&&b.a[r][c+1])adj++;}}return empty*3+adj;}
        static Board copy(Board b){Board n=new Board();for(int r=0;r<10;r++)System.arraycopy(b.a[r],0,n.a[r],0,10);n.x=b.x;n.y=b.y;n.cellW=b.cellW;n.cellH=b.cellH;return n;}
        static boolean next(int[] a){int i=a.length-2;while(i>=0&&a[i]>a[i+1])i--;if(i<0)return false;int j=a.length-1;while(a[j]<a[i])j--;int t=a[i];a[i]=a[j];a[j]=t;for(int l=i+1,r=a.length-1;l<r;l++,r--){t=a[l];a[l]=a[r];a[r]=t;}return true;}
    }
}
