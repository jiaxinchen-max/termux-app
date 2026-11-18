package com.termux.ui.activity;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import com.termux.ui.R;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Measures the repository XML directly; no copied card layouts or simulated text metrics. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WineCardGeometryTest {
    private static final String LONG = "很长的游戏名称与组件版本-rootfs_hangover_11.16-";

    private Context context(float fontScale, boolean night) {
        Context app = RuntimeEnvironment.getApplication();
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.fontScale = fontScale;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        Context result = new ContextThemeWrapper(app.createConfigurationContext(config),
            R.style.Theme_TermuxLocalGames_DayNight_NoActionBar);
        assertEquals(fontScale, result.getResources().getConfiguration().fontScale, 0.01f);
        return result;
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private View game(Context context, GridLayout grid, boolean longText) {
        View card = LayoutInflater.from(context).inflate(R.layout.item_local_game, grid, false);
        bind(card, longText);
        return card;
    }

    private void bind(View card, boolean longText) {
        ((TextView) card.findViewById(R.id.local_game_name)).setText(longText ? LONG + LONG : "wfm");
        ((TextView) card.findViewById(R.id.local_game_executable))
            .setText(longText ? "PRoot · " + LONG + LONG + ".exe" : "PRoot · wfm.exe");
        ((TextView) card.findViewById(R.id.local_game_wine))
            .setText(longText ? "Wine: " + LONG + LONG : "Wine: wine_10.0");
        ((TextView) card.findViewById(R.id.local_game_access_status))
            .setText(longText ? "Folder access available " + LONG : "Folder access available");
        ((TextView) card.findViewById(R.id.local_game_last_played)).setText("Never played");
    }

    private GridLayout grid(Context context, int width, boolean landscape, int count) {
        GridLayout grid = new GridLayout(context);
        int columns = LibraryGridColumns.columnsFor(width, landscape);
        grid.setColumnCount(columns);
        for (int i = 0; i < count; i++) {
            View card = i == count - 1
                ? LayoutInflater.from(context).inflate(R.layout.item_local_game_import, grid, false)
                : game(context, grid, i % 2 == 1);
            GridLayout.LayoutParams params = (GridLayout.LayoutParams) card.getLayoutParams();
            params.width = LibraryGridColumns.cardWidthPx(dp(context, width), columns,
                dp(context, 4), context.getResources().getDisplayMetrics().density);
            // Keep the actual XML height: this catches wrap_content regressions.
            params.columnSpec = GridLayout.spec(LibraryGridColumns.columnOf(i, columns));
            params.rowSpec = GridLayout.spec(LibraryGridColumns.rowOf(i, columns), GridLayout.TOP);
            params.setMargins(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 6));
            grid.addView(card, params);
        }
        return grid;
    }

    private void measure(View view, int width, int maxHeight) {
        // AppCompatButton auto-size can request a second traversal after its first layout.
        for (int pass = 0; pass < 3; pass++) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
            view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        }
    }

    private Rect bounds(View root, int id) {
        View child = root.findViewById(id);
        Rect rect = new Rect();
        child.getDrawingRect(rect);
        ((android.view.ViewGroup) root).offsetDescendantRectToMyCoords(child, rect);
        return rect;
    }

    private void assertCard(View card, Context context) {
        assertEquals(dp(context, 180), card.getHeight());
        assertEquals(dp(context, 56), card.findViewById(R.id.local_game_cover).getHeight());
        Rect details = bounds(card, R.id.local_game_details);
        Rect more = bounds(card, R.id.local_game_more);
        assertTrue(details.width() > 0);
        assertEquals(dp(context, 28), details.height());
        assertEquals(details.top, more.top);
        assertTrue(details.right <= more.left);
        assertTrue(more.right <= card.getWidth());
        assertTrue(more.bottom <= card.getHeight());
        for (int id : new int[]{R.id.local_game_details, R.id.local_game_more}) {
            TextView button = card.findViewById(id);
            assertNotNull("Button text must be laid out", button.getLayout());
            assertTrue(button.getLineHeight() <= button.getHeight());
        }
        for (int id : new int[]{R.id.local_game_name, R.id.local_game_executable,
                R.id.local_game_wine, R.id.local_game_access_status}) {
            TextView text = card.findViewById(id);
            Rect rect = bounds(card, id);
            assertTrue(rect.toString(), rect.height() > 0);
            assertTrue(rect.toString(), rect.bottom <= details.top);
            assertTrue("Font must fit its row: " + text.getText(),
                text.getLineHeight() <= text.getHeight());
            assertTrue("Text lines must fit (font=" + context.getResources().getConfiguration().fontScale
                    + ", row=" + text.getHeight() + ", layout=" + text.getLayout().getHeight()
                    + ", size=" + text.getTextSize() + "): " + text.getText(),
                text.getLayout().getHeight() <= text.getHeight());
        }
    }

    @Test public void shortAndLongCardsAlignAcrossWindowsFontsAndThemes() {
        for (int width : new int[]{328, 688, 900}) {
            for (float font : new float[]{1f, 1.3f, 2f}) {
                for (boolean night : new boolean[]{false, true}) {
                    Context context = context(font, night);
                    GridLayout grid = grid(context, width, width > 400, 3);
                    measure(grid, dp(context, width), dp(context, 760));
                    for (int i = 0; i < 2; i++) assertCard(grid.getChildAt(i), context);
                    assertEquals(grid.getChildAt(0).getTop(), grid.getChildAt(1).getTop());
                    assertEquals(grid.getChildAt(0).getBottom(), grid.getChildAt(1).getBottom());
                    assertEquals(dp(context, 180), grid.getChildAt(2).getHeight());
                    assertEquals(bounds(grid.getChildAt(0), R.id.local_game_details),
                        bounds(grid.getChildAt(1), R.id.local_game_details));
                }
            }
        }
    }

    @Test public void overflowingLabelsEllipsizeWithoutChangingTheirStoredText() {
        Context context = context(1f, false);
        GridLayout grid = grid(context, 328, false, 3);
        measure(grid, dp(context, 328), dp(context, 700));
        View card = grid.getChildAt(1);
        for (int id : new int[]{R.id.local_game_name, R.id.local_game_wine,
                R.id.local_game_access_status}) {
            TextView text = card.findViewById(id);
            assertEquals(1, text.getMaxLines());
            assertNotNull(text.getLayout());
            assertTrue("Expected ellipsis for " + text.getText(),
                text.getLayout().getEllipsisCount(text.getLineCount() - 1) > 0);
        }
        assertEquals("Wine: " + LONG + LONG,
            ((TextView) card.findViewById(R.id.local_game_wine)).getText().toString());
        assertEquals(2, ((TextView) card.findViewById(R.id.local_game_executable)).getMaxLines());
    }

    @Test public void imageReplacementFitsAllAspectRatiosWithoutMovingActions() {
        Context context = context(1f, false);
        GridLayout grid = grid(context, 688, true, 3);
        measure(grid, dp(context, 688), dp(context, 760));
        View card = grid.getChildAt(0);
        Rect button = bounds(card, R.id.local_game_details);
        ImageView cover = card.findViewById(R.id.local_game_cover);
        cover.setPadding(0, 0, 0, 0); // Same operation as asynchronous artwork completion.
        for (int[] size : new int[][]{{800, 80}, {80, 800}, {400, 400}}) {
            cover.setImageBitmap(Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888));
            measure(grid, dp(context, 688), dp(context, 760));
            assertEquals(ImageView.ScaleType.FIT_CENTER, cover.getScaleType());
            RectF mapped = new RectF(cover.getDrawable().getBounds());
            cover.getImageMatrix().mapRect(mapped);
            assertTrue(mapped.toString(), mapped.left >= -0.1f && mapped.top >= -0.1f);
            assertTrue(mapped.toString(), mapped.right <= cover.getWidth() + 0.1f
                && mapped.bottom <= cover.getHeight() + 0.1f);
            assertEquals((float) size[0] / size[1], mapped.width() / mapped.height(), 0.001f);
            assertEquals(button, bounds(card, R.id.local_game_details));
            assertCard(card, context);
        }
    }

    @Test public void finiteScrollViewportAndExtraRowDoNotStretchCards() {
        Context context = context(1f, false);
        GridLayout grid = grid(context, 328, false, 5);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(grid);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(dp(context, 328), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(context, 720), View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, scroll.getMeasuredWidth(), scroll.getMeasuredHeight());
        for (int i = 0; i < grid.getChildCount(); i++)
            assertEquals(dp(context, 180), grid.getChildAt(i).getHeight());
        assertTrue(grid.getChildAt(4).getTop() > grid.getChildAt(0).getBottom());
    }

    @Test public void emptyLibraryImportKeepsTheSameHeight() {
        Context context = context(2f, true);
        GridLayout grid = grid(context, 900, true, 1);
        measure(grid, dp(context, 900), dp(context, 720));
        assertEquals(dp(context, 180), grid.getChildAt(0).getHeight());
    }

    @Test public void listWineRemainsSingleLineAndImagesKeepTheirRatio() {
        for (float font : new float[]{1f, 2f}) {
            Context context = context(font, false);
            View shortRow = LayoutInflater.from(context).inflate(R.layout.item_local_game_list, null);
            View longRow = LayoutInflater.from(context).inflate(R.layout.item_local_game_list, null);
            bind(shortRow, false);
            bind(longRow, false);
            ((TextView) longRow.findViewById(R.id.local_game_wine)).setText(
                "Wine: rootfs_hangover_11.16-very-long-custom-wine-component-name");
            measure(shortRow, dp(context, 400), dp(context, 700));
            measure(longRow, dp(context, 400), dp(context, 700));
            assertEquals(shortRow.getHeight(), longRow.getHeight());
            assertEquals(1, ((TextView) longRow.findViewById(R.id.local_game_wine)).getMaxLines());
            assertEquals(ImageView.ScaleType.FIT_CENTER,
                ((ImageView) longRow.findViewById(R.id.local_game_cover)).getScaleType());
        }
    }

    @Test public void renderReviewPreviewsFromActualLayouts() throws Exception {
        for (boolean night : new boolean[]{false, true}) {
            Context context = context(1f, night);
            GridLayout grid = grid(context, 688, true, 3);
            measure(grid, dp(context, 688), dp(context, 360));
            StringBuilder diagnostic = new StringBuilder();
            for (int id : new int[]{R.id.local_game_details, R.id.local_game_more}) {
                TextView button = grid.getChildAt(0).findViewById(id);
                diagnostic.append(button.getText()).append(" color=").append(button.getCurrentTextColor())
                    .append(" size=").append(button.getTextSize()).append(" visibility=").append(button.getVisibility())
                    .append(" alpha=").append(button.getAlpha()).append(" height=").append(button.getHeight())
                    .append(" baseline=").append(button.getBaseline()).append(" layout=").append(button.getLayout())
                    .append(" willNotDraw=").append(button.willNotDraw()).append('\n');
                assertTrue(button.getTextSize() > 0);
                assertTrue(Color.alpha(button.getCurrentTextColor()) > 0);
            }
            java.nio.file.Files.write(new File("button-diagnostic-" + night + ".txt").toPath(),
                diagnostic.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Bitmap preview = Bitmap.createBitmap(grid.getWidth(), grid.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(preview);
            canvas.drawColor(night ? Color.rgb(25, 23, 22) : Color.rgb(247, 236, 229));
            grid.draw(canvas);
            File file = new File("preview-" + (night ? "dark" : "light") + ".png");
            try (FileOutputStream output = new FileOutputStream(file)) {
                assertTrue(preview.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        }
    }
}
