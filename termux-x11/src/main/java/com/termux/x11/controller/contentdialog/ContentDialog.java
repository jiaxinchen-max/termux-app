package com.termux.x11.controller.contentdialog;

import com.termux.x11.session.DialogPalette;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.x11.R;
import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.core.Callback;

import java.util.ArrayList;

public class ContentDialog extends Dialog {
    private Runnable onConfirmCallback;
    private Runnable onCancelCallback;
    private final View contentView;
    @Nullable
    private DialogPalette palette;

    public ContentDialog(@NonNull Context context) {
        this(context, 0);
    }

    public ContentDialog(@NonNull Context context, int layoutResId) {
        this(context, layoutResId, null);
    }

    public ContentDialog(@NonNull Context context, int layoutResId, @Nullable DialogPalette palette) {
        super(context, R.style.ContentDialog);
        contentView = LayoutInflater.from(context).inflate(R.layout.content_dialog, null);

        if (layoutResId > 0) {
            FrameLayout frameLayout = contentView.findViewById(R.id.FrameLayout);
            frameLayout.setVisibility(View.VISIBLE);
            View view = LayoutInflater.from(context).inflate(layoutResId, frameLayout, false);
            frameLayout.addView(view);
        }

        View confirmButton = contentView.findViewById(R.id.BTConfirm);
        confirmButton.setOnClickListener((v) -> {
            if (onConfirmCallback != null) onConfirmCallback.run();
            dismiss();
        });

        View cancelButton = contentView.findViewById(R.id.BTCancel);
        cancelButton.setOnClickListener((v) -> {
            if (onCancelCallback != null) onCancelCallback.run();
            dismiss();
        });

        setContentView(contentView);
        setPalette(palette);
    }

    @Nullable
    public DialogPalette getPalette() {
        return palette;
    }

    /** Restyles the dialog with a host palette; null keeps the stock termux-x11 look. */
    public void setPalette(@Nullable DialogPalette palette) {
        this.palette = palette;
        if (palette == null) return;
        float density = getContext().getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.background);
        bg.setCornerRadius(palette.cornerRadiusDp * density);
        bg.setStroke(Math.max(1, Math.round(density)), palette.outline);
        contentView.setBackground(bg);
        Window window = getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        applyPalette(contentView, palette, density);
    }

    private static void applyPalette(View view, DialogPalette p, float density) {
        int id = view.getId();
        if (id == R.id.VTitleLine || id == R.id.VBottomLine) {
            view.setBackgroundColor(p.outline);
        } else if (view instanceof CompoundButton) {
            CompoundButton box = (CompoundButton) view;
            box.setTextColor(p.onSurface);
            box.setButtonTintList(ColorStateList.valueOf(p.accent));
        } else if (view instanceof Button) {
            Button button = (Button) view;
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(p.buttonFill);
            fill.setCornerRadius(6 * density);
            button.setBackground(fill);
            button.setTextColor(p.buttonText);
        } else if (view instanceof EditText) {
            EditText edit = (EditText) view;
            GradientDrawable box = new GradientDrawable();
            box.setColor(p.panel);
            box.setCornerRadius(6 * density);
            box.setStroke(Math.max(1, Math.round(density)), p.outline);
            edit.setBackground(box);
            edit.setTextColor(p.onSurface);
            edit.setHintTextColor(p.onSurfaceSecondary);
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(id == R.id.TVTitle || id == R.id.TVBottomBarText ? p.accent : p.onSurface);
        } else if (view instanceof ImageView && id == R.id.IVIcon) {
            ImageViewCompat.setImageTintList((ImageView) view, ColorStateList.valueOf(p.accent));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyPalette(group.getChildAt(i), p, density);
        }
    }

    public View getContentView() {
        return contentView;
    }

    public void setOnConfirmCallback(Runnable onConfirmCallback) {
        this.onConfirmCallback = onConfirmCallback;
    }

    public void setOnCancelCallback(Runnable onCancelCallback) {
        this.onCancelCallback = onCancelCallback;
    }

    @Override
    public void setTitle(int titleResId) {
        setTitle(getContext().getString(titleResId));
    }

    public void setIcon(int iconResId) {
        ImageView imageView = findViewById(R.id.IVIcon);
        imageView.setImageResource(iconResId);
        imageView.setVisibility(View.VISIBLE);
    }

    public void setTitle(String title) {
        LinearLayout titleBar = findViewById(R.id.LLTitleBar);
        TextView tvTitle = findViewById(R.id.TVTitle);

        if (title != null && !title.isEmpty()) {
            tvTitle.setText(title);
            titleBar.setVisibility(View.VISIBLE);
        }
        else {
            tvTitle.setText("");
            titleBar.setVisibility(View.GONE);
        }
    }

    public void setBottomBarText(String bottomBarText) {
        TextView tvBottomBarText = findViewById(R.id.TVBottomBarText);

        if (bottomBarText != null && !bottomBarText.isEmpty()) {
            tvBottomBarText.setText(bottomBarText);
            tvBottomBarText.setVisibility(View.VISIBLE);
        }
        else {
            tvBottomBarText.setText("");
            tvBottomBarText.setVisibility(View.GONE);
        }
    }

    public void setMessage(int msgResId) {
        setMessage(getContext().getString(msgResId));
    }

    public void setMessage(String message) {
        TextView tvMessage = findViewById(R.id.TVMessage);

        if (message != null && !message.isEmpty()) {
            tvMessage.setText(message);
            tvMessage.setVisibility(View.VISIBLE);
        }
        else {
            tvMessage.setText("");
            tvMessage.setVisibility(View.GONE);
        }
    }

    public static void confirm(Context context, int msgResId, Runnable callback) {
        confirm(context, msgResId, null, callback);
    }

    public static void confirm(Context context, int msgResId, @Nullable DialogPalette palette, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context, 0, palette);
        dialog.setMessage(msgResId);
        dialog.setOnConfirmCallback(callback);
        dialog.show();
    }

    public static void prompt(Context context, int titleResId, String defaultText, Callback<String> callback) {
        prompt(context, titleResId, defaultText, null, callback);
    }

    public static void prompt(Context context, int titleResId, String defaultText, @Nullable DialogPalette palette, Callback<String> callback) {
        ContentDialog dialog = new ContentDialog(context, 0, palette);

        final EditText editText = dialog.findViewById(R.id.EditText);
        // Keep the fixed 280 dp field inside very narrow windows.
        float density = context.getResources().getDisplayMetrics().density;
        int maxWidth = Math.round(context.getResources().getDisplayMetrics().widthPixels - 96 * density);
        editText.getLayoutParams().width = Math.max(Math.round(160 * density), Math.min(Math.round(280 * density), maxWidth));
        editText.setHint(R.string.untitled);
        if (defaultText != null) editText.setText(defaultText);
        editText.setVisibility(View.VISIBLE);

        dialog.setTitle(titleResId);
        dialog.setOnConfirmCallback(() -> {
            String text = editText.getText().toString().trim();
            if (!text.isEmpty()) callback.call(text);
        });

        dialog.show();
    }

    public static void showMultipleChoiceList(Context context, int titleResId, final String[] items, Callback<ArrayList<Integer>> callback) {
        ContentDialog dialog = new ContentDialog(context);

        final ListView listView = dialog.findViewById(R.id.ListView);
        listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_multiple_choice, items));
        listView.setVisibility(View.VISIBLE);

        dialog.setTitle(titleResId);
        dialog.setOnConfirmCallback(() -> {
            ArrayList<Integer> result = new ArrayList<>();
            SparseBooleanArray checkedItemPositions = listView.getCheckedItemPositions();
            for (int i = 0; i < checkedItemPositions.size(); i++) {
                if (checkedItemPositions.valueAt(i)) result.add(checkedItemPositions.keyAt(i));
            }
            callback.call(result);
        });

        dialog.show();
    }
}
