/* SPDX-License-Identifier: MIT
 * Test-only instrumentation: displays the APK's existing Lorie view without
 * modifying the target APK, its desktop registry, or its launch scripts.
 */
package org.termux.mutter.gputest;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.lang.reflect.*;
import org.json.JSONObject;
public final class SurfaceTest extends Instrumentation {
    private Activity activity;
    private Object view;
    private Class<?> viewClass;
    private volatile boolean connected, firstFrame;
    private File work;
    private int controls;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);start(); }
    private void status(String state, String error) throws Exception {
        JSONObject value=new JSONObject();
        value.put("controls",controls);value.put("state",state);value.put("connected",connected);value.put("first_frame",firstFrame);
        if(viewClass!=null)value.put("presented_frames",viewClass.getMethod("getRenderedFrameCount").invoke(null));
        if(error!=null)value.put("error",error);
        File temp=new File(work,"surface-status.tmp");
        try(FileOutputStream out=new FileOutputStream(temp)) {out.write(value.toString().getBytes("UTF-8"));}
        if(!temp.renameTo(new File(work,"surface-status.json")))throw new IOException("status rename failed");
    }
    private void control() throws Exception {
        File request=new File(work,"surface-control.json");
        if(!request.exists())return;
        byte[] bytes=new byte[(int)request.length()];
        try(DataInputStream in=new DataInputStream(new FileInputStream(request))) {in.readFully(bytes);}
        JSONObject value=new JSONObject(new String(bytes,"UTF-8"));
        request.delete();final Throwable[] failure={null};
        runOnMainSync(()-> {
            try {
                Field display=viewClass.getDeclaredField("displayView");display.setAccessible(true);
                Object screen=display.get(view);
                Object lorie=screen.getClass().getMethod("getLorieView").invoke(screen);
                Class<?> c=lorie.getClass();String action=value.getString("action");
                if(action.equals("click")) {
                    Method mouse=c.getMethod("sendMouseEvent",float.class,float.class,int.class,boolean.class,boolean.class);
                    float x=(float)value.getDouble("x"),y=(float)value.getDouble("y");
                    mouse.invoke(lorie,x,y,0,false,false);
                    mouse.invoke(lorie,x,y,1,true,false);mouse.invoke(lorie,x,y,1,false,false);
                }else if(action.equals("key")) {
                    Method key=c.getMethod("sendKeyEvent",int.class,int.class,boolean.class);
                    int code=value.getInt("code");key.invoke(lorie,0,code,true);key.invoke(lorie,0,code,false);
                }else if(action.equals("text")) {
                    c.getMethod("sendTextEvent",byte[].class).invoke(lorie,(Object)value.getString("text").getBytes("UTF-8"));
                }else if(action.equals("touch")) {
                    Method touch=c.getMethod("sendTouchEvent",int.class,int.class,int.class,int.class);
                    int x=value.getInt("x"),y=value.getInt("y");touch.invoke(lorie,18,0,x,y);touch.invoke(lorie,19,0,x,y);
                }else throw new IllegalArgumentException(action);
            }catch(Throwable e){failure[0]=e;}
        });
        if(failure[0]!=null)throw new RuntimeException(failure[0]);controls++;
    }
    @Override public void onStart() {
        work=new File(getTargetContext().getFilesDir(),"home/tusb-gnome-fold/gpu-system");work.mkdirs();
        File stop=new File(work,"surface-stop");stop.delete();
        int result=Activity.RESULT_CANCELED;Bundle output=new Bundle();
        try {
            Intent intent=new Intent().setComponent(new ComponentName("com.termux","com.termux.app.TermuxActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity=startActivitySync(intent);
            final Throwable[] startup={null};
            runOnMainSync(()-> {
                try {
                    activity.setIntent(new Intent());
                    ClassLoader loader=activity.getClassLoader();
                    viewClass=loader.loadClass("com.termux.x11.X11SessionView");
                    view=viewClass.getConstructor(Context.class).newInstance(activity);
                    Class<?> listenerClass=loader.loadClass("com.termux.x11.X11SessionView$Listener");
                    Object listener=Proxy.newProxyInstance(loader,new Class<?>[]{listenerClass},(p,m,a)-> {
                        if(m.getName().equals("onConnectionChanged"))connected=(Boolean)a[0];
                        if(m.getName().equals("onFirstFramePresented"))firstFrame=true;
                        if(m.getReturnType()==boolean.class)return false;
                        return null;
                    });
                    viewClass.getMethod("setImmersive",boolean.class).invoke(view,true);
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    activity.setContentView((View)view);
                    Method bind=null;
                    for(Method m:viewClass.getMethods())if(m.getName().equals("bind")&&m.getParameterCount()==3)bind=m;
                    if(bind==null)throw new NoSuchMethodException("X11SessionView.bind");
                    bind.invoke(view,activity,"1280x800",listener);
                    viewClass.getMethod("onHostResume").invoke(view);
                    viewClass.getMethod("onHostWindowFocusChanged",boolean.class).invoke(view,true);
                }catch(Throwable e){startup[0]=e;}
            });
            if(startup[0]!=null)throw new RuntimeException(startup[0]);
            long deadline=SystemClock.elapsedRealtime()+60*60*1000;
            while(!stop.exists()&&SystemClock.elapsedRealtime()<deadline) {
                control();status("listening",null);SystemClock.sleep(1000);
            }
            result=Activity.RESULT_OK;
        }catch(Throwable e) {
            StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));
            try {status("error",trace.toString());}catch(Exception ignored){}
            output.putString("error",trace.toString());
        }finally {
            if(activity!=null)runOnMainSync(()-> {
                try {if(view!=null)viewClass.getMethod("unbind").invoke(view);}catch(Exception ignored){}
                activity.finish();
            });
            finish(result,output);
        }
    }
}
