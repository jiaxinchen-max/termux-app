package com.termux.app.activities.termuxbox;

import android.content.Context;
import android.util.AttributeSet;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.view.LayoutInflater;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.termux.R;

public class TermuxBoxDropdownField extends LinearLayout {

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int position, @NonNull String label, @NonNull String value);
    }

    private final TextInputLayout inputLayout;
    private final MaterialAutoCompleteTextView inputView;
    private String[] labels = new String[0];
    private String[] values = new String[0];
    private OnSelectionChangedListener listener;
    private int selectedIndex = -1;

    public TermuxBoxDropdownField(@NonNull Context context) {
        this(context, null);
    }

    public TermuxBoxDropdownField(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TermuxBoxDropdownField(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);

        LayoutInflater.from(context).inflate(R.layout.termux_box_dropdown_field, this, true);
        inputLayout = findViewById(R.id.termux_box_dropdown_layout);
        inputView = findViewById(R.id.termux_box_dropdown_input);
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setBoxStrokeColor(0xFFD3DEE8);
        inputLayout.setBoxBackgroundColor(0xFFFFFFFF);
        inputLayout.setShapeAppearanceModel(
            ShapeAppearanceModel.builder()
                .setAllCornerSizes(dp(5))
                .build());
        inputView.setInputType(android.text.InputType.TYPE_NULL);
        inputView.setKeyListener(null);
        inputView.setCursorVisible(false);
        inputView.setLongClickable(false);
        inputView.setTextIsSelectable(false);
        inputView.setShowSoftInputOnFocus(false);
        inputView.setBackground(null);
        inputView.setBackgroundColor(0x00000000);
        inputView.setMinimumHeight(0);
        inputView.setMinHeight(0);
        inputView.setPaddingRelative(inputView.getPaddingStart(), dp(2), inputView.getPaddingEnd(), dp(2));
    }

    public void setLabel(@Nullable String label) {
        inputLayout.setHint(label);
    }

    public void setOptions(@NonNull String[] labels, @Nullable String defaultValue) {
        setOptions(labels, labels, defaultValue);
    }

    public void setOptions(@NonNull String[] labels, @NonNull String[] values, @Nullable String defaultValue) {
        this.labels = labels.clone();
        this.values = values.clone();
        inputView.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, this.labels));
        selectedIndex = resolveDefaultIndex(defaultValue);
        inputView.setText(selectedIndex >= 0 ? this.labels[selectedIndex] : "", false);
        inputView.setOnItemClickListener((parent, view, position, id) -> {
            selectedIndex = position;
            if (listener != null && position >= 0 && position < this.labels.length && position < this.values.length) {
                listener.onSelectionChanged(position, this.labels[position], this.values[position]);
            }
        });
    }

    public void setOnSelectionChangedListener(@Nullable OnSelectionChangedListener listener) {
        this.listener = listener;
    }

    public String getValue() {
        CharSequence text = inputView.getText();
        return text == null ? "" : text.toString().trim();
    }

    public int getSelectedIndex() {
        return selectedIndex >= 0 ? selectedIndex : 0;
    }

    public void setValue(@Nullable String value) {
        selectedIndex = resolveDefaultIndex(value);
        inputView.setText(selectedIndex >= 0 ? labels[selectedIndex] : "", false);
    }

    /** When editable, the user can type a custom value instead of only picking from the dropdown. */
    public void setEditable(boolean editable) {
        if (editable) {
            inputView.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            inputView.setKeyListener(android.text.method.TextKeyListener.getInstance());
            inputView.setCursorVisible(true);
        } else {
            inputView.setInputType(android.text.InputType.TYPE_NULL);
            inputView.setKeyListener(null);
            inputView.setCursorVisible(false);
        }
    }

    private int resolveDefaultIndex(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return labels.length > 0 ? 0 : -1;
        }
        for (int i = 0; i < labels.length && i < values.length; i++) {
            if (TextUtils.equals(labels[i], value) || TextUtils.equals(values[i], value)) {
                return i;
            }
        }
        return labels.length > 0 ? 0 : -1;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
