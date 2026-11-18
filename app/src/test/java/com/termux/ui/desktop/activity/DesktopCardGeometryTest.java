package com.termux.ui.desktop.activity;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.android.material.theme.MaterialComponentsViewInflater;
import com.termux.ui.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Real resource and native text measurement using the AppCompat/Material inflater. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DesktopCardGeometryTest {
    private static final String LONG="很长的Ubuntu桌面名称与组件参数-Hyprland-";
    private Context context(float scale, boolean night) { return context(scale,night,"en"); }
    private Context context(float scale, boolean night, String locale) {
        Context app=RuntimeEnvironment.getApplication();
        Configuration c=new Configuration(app.getResources().getConfiguration());
        c.fontScale=scale;
        c.setLocale(java.util.Locale.forLanguageTag(locale));
        c.uiMode=(c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return new ContextThemeWrapper(app.createConfigurationContext(c),
            R.style.Theme_TermuxLocalGames_DayNight_NoActionBar);
    }
    private View inflate(Context c, int resource) {
        LayoutInflater inflater=LayoutInflater.from(c).cloneInContext(c);
        MaterialComponentsViewInflater material=new MaterialComponentsViewInflater();
        inflater.setFactory2(new LayoutInflater.Factory2() {
            public View onCreateView(View parent,String name,Context ctx,AttributeSet attrs) {
                return material.createView(parent,name,ctx,attrs,false,true,true,true);
            }
            public View onCreateView(String name,Context ctx,AttributeSet attrs) {
                return onCreateView(null,name,ctx,attrs);
            }
        });
        return inflater.inflate(resource,null);
    }
    private void measure(View v,int width) {
        for(int i=0;i<3;i++) {
            v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(760,View.MeasureSpec.AT_MOST));
            v.layout(0,0,v.getMeasuredWidth(),v.getMeasuredHeight());
        }
    }
    private void measureOption(View option,int width,float scale) {
        GridLayout grid=new GridLayout(option.getContext());
        GridLayout.LayoutParams params=new GridLayout.LayoutParams();
        params.width=width;
        params.height=Math.round(option.getResources().getDimensionPixelSize(
            R.dimen.desktop_card_cover_height)*Math.max(1f,scale));
        grid.addView(option,params);
        measure(grid,width);
        assertEquals(params.height,option.getHeight());
    }
    private void bind(View v,boolean longText) {
        ((TextView)v.findViewById(R.id.desktop_name)).setText(longText?LONG+LONG:"XFCE");
        ((TextView)v.findViewById(R.id.desktop_mode_badge)).setText("PRoot");
        ((TextView)v.findViewById(R.id.desktop_detail)).setText(longText?
            "Host: LLVMPIPE · Guest: Zink\n1920×1080 · 120Hz\n"+LONG:"System\n1280×720 · 60Hz");
        ((TextView)v.findViewById(R.id.desktop_status)).setText(longText?"安装失败，"+LONG:"Stopped");
        ((TextView)v.findViewById(R.id.desktop_primary)).setText(longText?"View installation progress":"Start");
    }
    private Rect bounds(View root,int id) {
        View v=root.findViewById(id);Rect r=new Rect();v.getDrawingRect(r);
        ((ViewGroup)root).offsetDescendantRectToMyCoords(v,r);return r;
    }
    private void textFits(TextView t) {
        assertNotNull(t.getLayout());
        int available=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom();
        assertTrue("text="+t.getText()+" layout="+t.getLayout().getHeight()+" available="+available,
            t.getLayout().getHeight()<=available);
    }
    @Test public void fixedManagementCardsKeepReadableTextAndActionsInside() {
        for(float scale:new float[]{1f,1.3f,2f}) for(boolean night:new boolean[]{false,true})
        for(boolean longText:new boolean[]{false,true}) {
            Context c=context(scale,night);View v=inflate(c,R.layout.item_desktop);bind(v,longText);
            GridLayout parent=new GridLayout(c);GridLayout.LayoutParams p=new GridLayout.LayoutParams();
            p.width=128;p.height=160;parent.addView(v,p);measure(parent,328);
            assertEquals(160,v.getHeight());assertEquals(52,v.findViewById(R.id.desktop_cover).getHeight());
            Rect primary=bounds(v,R.id.desktop_primary),more=bounds(v,R.id.desktop_more);
            assertEquals(48,primary.height());assertEquals(48,more.height());
            assertEquals(primary.top,more.top);assertTrue(more.bottom<=160);
            assertTrue(primary.right<=more.left);
            for(int id:new int[]{R.id.desktop_name,R.id.desktop_mode_badge,R.id.desktop_detail,
                R.id.desktop_status,R.id.desktop_primary,R.id.desktop_more}) textFits(v.findViewById(id));
            assertTrue(bounds(v,R.id.desktop_detail).bottom<=bounds(v,R.id.desktop_status).top);
            assertTrue(bounds(v,R.id.desktop_status).bottom<=primary.top);
            assertTrue(bounds(v,R.id.desktop_mode_badge).bottom<=bounds(v,R.id.desktop_name).top);
            assertTrue(bounds(v,R.id.desktop_name).bottom<=49);
        }
    }
    @Test public void listActionsCannotConsumeTheTextColumn() {
        for(int width:new int[]{240,328,688}) for(float scale:new float[]{1f,2f}) {
            Context c=context(scale,false);View v=inflate(c,R.layout.item_desktop_list);bind(v,true);measure(v,width);
            assertTrue("Text column width "+bounds(v,R.id.desktop_name),bounds(v,R.id.desktop_name).width()>=48);
            Rect action=bounds(v,R.id.desktop_primary);
            assertTrue(action.toString(),action.width()<=80);assertEquals(48,action.height());
            textFits(v.findViewById(R.id.desktop_primary));
            assertEquals(1,((TextView)v.findViewById(R.id.desktop_primary)).getMaxLines());
        }
    }
    @Test public void createTitlesKeepEqualHeightAndDoNotOverlapBadges() {
        for(float scale:new float[]{1f,1.3f,2f}) for(boolean night:new boolean[]{false,true}) {
            Context c=context(scale,night);int width=DesktopCardLayout.widthDp(328,scale);
            View a=inflate(c,R.layout.item_desktop_option),b=inflate(c,R.layout.item_desktop_option);
            ((TextView)a.findViewById(R.id.desktop_option_title)).setText("XFCE");
            ((TextView)b.findViewById(R.id.desktop_option_title)).setText(LONG+LONG);
            for(View v:new View[]{a,b}) {
                v.findViewById(R.id.desktop_option_soon).setVisibility(View.VISIBLE);
                v.findViewById(R.id.desktop_option_check).setVisibility(View.VISIBLE);
                measureOption(v,width,scale);
                assertTrue("soon="+bounds(v,R.id.desktop_option_soon)+" check="+bounds(v,R.id.desktop_option_check)+" width="+width, bounds(v,R.id.desktop_option_soon).right<=bounds(v,R.id.desktop_option_check).left);
                assertTrue(bounds(v,R.id.desktop_option_check).bottom<=bounds(v,R.id.desktop_option_title).top);
                textFits(v.findViewById(R.id.desktop_option_title));
            }
            assertEquals(a.getHeight(),b.getHeight());
            TextView title=b.findViewById(R.id.desktop_option_title);
            assertEquals(LONG+LONG,title.getText().toString());
            assertEquals(2,title.getLineCount());assertTrue(title.getLayout().getEllipsisCount(1)>0);
        }
    }
    private TextView actionLabel(View v) {
        if(v instanceof TextView && !(v instanceof com.google.android.material.button.MaterialButton))return (TextView)v;
        if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) {
            TextView found=actionLabel(((ViewGroup)v).getChildAt(i));if(found!=null)return found;
        }
        return null;
    }
    @Test public void addImportLabelsAreBoundedInGridAndList() {
        for(int layout:new int[]{R.layout.item_desktop_add,R.layout.item_desktop_import,
            R.layout.item_desktop_add_list,R.layout.item_desktop_import_list}) {
            Context c=context(2f,true);View v=inflate(c,layout);TextView text=actionLabel(v);assertNotNull(text);
            text.setText(LONG+LONG);boolean grid=layout==R.layout.item_desktop_add||layout==R.layout.item_desktop_import;
            if(grid) { GridLayout p=new GridLayout(c);GridLayout.LayoutParams lp=new GridLayout.LayoutParams();
                lp.width=128;lp.height=160;p.addView(v,lp);measure(p,328);assertEquals(160,v.getHeight());
            } else measure(v,328);
            assertEquals(1,text.getMaxLines());textFits(text);
            assertTrue(text.getLayout().getEllipsisCount(0)>0);
        }
    }
    @Test public void coversFitAllAspectRatiosWithoutChangingGeometry() {
        Context c=context(1f,false);
        for(int layout:new int[]{R.layout.item_desktop,R.layout.item_desktop_list,R.layout.item_desktop_option}) {
            View v=inflate(c,layout);boolean option=layout==R.layout.item_desktop_option;
            if(option)((TextView)v.findViewById(R.id.desktop_option_title)).setText("Ubuntu GNOME");else bind(v,false);
            ImageView cover=v.findViewById(option?R.id.desktop_option_icon:R.id.desktop_cover);
            ViewGroup parent=new android.widget.FrameLayout(c);
            parent.addView(v,new ViewGroup.LayoutParams(layout==R.layout.item_desktop_list?328:128,
                layout==R.layout.item_desktop?160:option?52:ViewGroup.LayoutParams.WRAP_CONTENT));
            measure(parent,328);int height=v.getHeight();
            for(int[] s:new int[][]{{800,80},{80,800},{400,400}}) {
                cover.setImageBitmap(Bitmap.createBitmap(s[0],s[1],Bitmap.Config.ARGB_8888));measure(parent,328);
                assertEquals(height,v.getHeight());assertEquals(ImageView.ScaleType.FIT_CENTER,cover.getScaleType());
                RectF r=new RectF(cover.getDrawable().getBounds());cover.getImageMatrix().mapRect(r);
                assertTrue(r.toString(),r.left>=-0.1&&r.top>=-0.1&&r.right<=cover.getWidth()+0.1&&r.bottom<=cover.getHeight()+0.1);
                assertEquals((float)s[0]/s[1],r.width()/r.height(),0.001f);
            }
        }
    }

    @Test public void fixedCardsStayAlignedInsideFiniteScrollableWindows() {
        for(int width:new int[]{240,328,688,900}) for(float scale:new float[]{1f,1.3f,2f}) {
            Context c=context(scale,false);GridLayout grid=new GridLayout(c);
            int columns=DesktopCardLayout.columns(width,1f);grid.setColumnCount(columns);
            for(int i=0;i<7;i++) {
                int resource=i==5?R.layout.item_desktop_add:i==6?R.layout.item_desktop_import:R.layout.item_desktop;
                View v=inflate(c,resource);if(i<5)bind(v,i%2==1);
                GridLayout.LayoutParams lp=new GridLayout.LayoutParams();
                lp.width=Math.min(128,width-6);lp.height=160;
                lp.columnSpec=GridLayout.spec(i%columns);lp.rowSpec=GridLayout.spec(i/columns,GridLayout.TOP);
                lp.setMargins(3,3,3,3);grid.addView(v,lp);
            }
            android.widget.ScrollView scroll=new android.widget.ScrollView(c);scroll.setFillViewport(true);scroll.addView(grid);
            for(int pass=0;pass<3;pass++) {
                scroll.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));
                scroll.layout(0,0,width,720);
            }
            for(int i=0;i<7;i++) {
                View v=grid.getChildAt(i);assertEquals(160,v.getHeight());assertTrue(v.getRight()<=width);
                if(i>0 && i%columns!=0) assertEquals(grid.getChildAt(i-1).getTop(),v.getTop());
            }
        }
    }
    @Test public void localizedActionAndStatusLabelsKeepTheirAvailableAreas() {
        for(String locale:new String[]{"en","zh-CN"}) for(float scale:new float[]{1f,2f})
        for(boolean night:new boolean[]{false,true}) for(int resource:new int[]{R.layout.item_desktop,R.layout.item_desktop_list}) {
            Context c=context(scale,night,locale);View v=inflate(c,resource);bind(v,true);
            android.widget.FrameLayout parent=new android.widget.FrameLayout(c);
            parent.addView(v,new ViewGroup.LayoutParams(resource==R.layout.item_desktop?128:328,
                resource==R.layout.item_desktop?160:ViewGroup.LayoutParams.WRAP_CONTENT));
            for(int mode:new int[]{R.string.desktop_mode_native_badge,R.string.desktop_mode_proot_badge}) {
                ((TextView)v.findViewById(R.id.desktop_mode_badge)).setText(mode);
                for(int action:new int[]{R.string.desktop_action_view_install,R.string.desktop_action_install,
                    R.string.desktop_state_stopping,R.string.desktop_state_starting,R.string.desktop_action_enter}) {
                    ((TextView)v.findViewById(R.id.desktop_primary)).setText(action);
                    ((TextView)v.findViewById(R.id.desktop_status)).setText(R.string.desktop_state_starting);
                    v.findViewById(R.id.desktop_install_progress).setVisibility(View.VISIBLE);
                    measure(parent,328);
                    for(int id:new int[]{R.id.desktop_primary,R.id.desktop_more,R.id.desktop_status,R.id.desktop_mode_badge})
                        textFits(v.findViewById(id));
                    assertTrue(bounds(v,R.id.desktop_primary).bottom<=v.getHeight());
                    assertTrue(bounds(v,R.id.desktop_install_progress).bottom<=bounds(v,R.id.desktop_cover).bottom);
                    if(resource==R.layout.item_desktop)
                        assertTrue(bounds(v,R.id.desktop_name).bottom<=bounds(v,R.id.desktop_install_progress).top);
                }
            }
        }
    }
    @Test public void pendingImportMenuAndCreatedBadgeFitAtLargeFont() {
        for(String locale:new String[]{"en","zh-CN"}) for(boolean night:new boolean[]{false,true}) {
            Context c=context(2f,night,locale);
            for(int resource:new int[]{R.layout.item_desktop_import,R.layout.item_desktop_import_list}) {
                View v=inflate(c,resource);v.findViewById(R.id.desktop_import_more).setVisibility(View.VISIBLE);
                android.widget.FrameLayout parent=new android.widget.FrameLayout(c);
                parent.addView(v,new ViewGroup.LayoutParams(resource==R.layout.item_desktop_import?128:328,
                    resource==R.layout.item_desktop_import?160:ViewGroup.LayoutParams.WRAP_CONTENT));
                measure(parent,328);textFits(v.findViewById(R.id.desktop_import_more));textFits(actionLabel(v));
                assertTrue(bounds(v,R.id.desktop_import_more).bottom<=v.getHeight());
            }
            View option=inflate(c,R.layout.item_desktop_option);
            ((TextView)option.findViewById(R.id.desktop_option_title)).setText(LONG+LONG);
            TextView badge=option.findViewById(R.id.desktop_option_soon);badge.setVisibility(View.VISIBLE);badge.setText(LONG+LONG);
            option.findViewById(R.id.desktop_option_check).setVisibility(View.VISIBLE);measureOption(option,128,2f);
            textFits(badge);textFits(option.findViewById(R.id.desktop_option_check));
            assertTrue(badge.getLayout().getEllipsisCount(0)>0);
        }
    }
}
