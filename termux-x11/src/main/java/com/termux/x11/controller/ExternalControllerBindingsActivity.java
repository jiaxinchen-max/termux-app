package com.termux.x11.controller;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.core.LocaleHelper;
import com.termux.x11.controller.inputcontrols.Binding;
import com.termux.x11.controller.inputcontrols.ControlElement;
import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.inputcontrols.ExternalController;
import com.termux.x11.controller.inputcontrols.ExternalControllerBinding;
import com.termux.x11.controller.inputcontrols.GamepadState;
import com.termux.x11.controller.inputcontrols.InputControlsManager;
import com.termux.x11.controller.math.Mathf;
import com.termux.x11.R;

public class ExternalControllerBindingsActivity extends AppCompatActivity {
    private TextView emptyTextView;
    private ControlsProfile profile;
    private ExternalController controller;
    private RecyclerView recyclerView;
    private ControllerBindingsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.external_controller_bindings_activity);

        Intent intent = getIntent();
        int profileId = intent.getIntExtra("profile_id", 0);
        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, profileId));
        String controllerId = intent.getStringExtra("controller_id");

        if (profile == null) {
            AppUtils.showToast(this, R.string.no_profile_selected);
            finish();
            return;
        }

        controller = profile.getController(controllerId);
        if (controller == null) {
            controller = profile.addController(controllerId);
            if (controller == null) {
                // 该控制器既不在配置里也不在当前连接列表中（例如已断开），
                // 原实现会继续往下走并在 controller.getName() / profile.save() 处 NPE 闪退
                AppUtils.showToast(this, R.string.press_any_button_on_your_controller);
                finish();
                return;
            }
            profile.save();
        }

        Toolbar toolbar = findViewById(R.id.Toolbar);
        toolbar.setTitle(controller.getName());
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_back);

        emptyTextView = findViewById(R.id.TVEmptyText);
        recyclerView = findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        DividerItemDecoration itemDecoration = new DividerItemDecoration(this, DividerItemDecoration.VERTICAL);
        itemDecoration.setDrawable(ContextCompat.getDrawable(this, R.drawable.list_item_divider));
        recyclerView.addItemDecoration(itemDecoration);
        recyclerView.setAdapter(adapter = new ControllerBindingsAdapter());
        updateEmptyTextView();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    private void updateControllerBinding(int keyCode, Binding binding) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return;

        ExternalControllerBinding controllerBinding = controller.getControllerBinding(keyCode);
        int position;
        if (controllerBinding == null) {
            controllerBinding = new ExternalControllerBinding();
            controllerBinding.setKeyCode(keyCode);
            controllerBinding.setBinding(binding);

            controller.addControllerBinding(controllerBinding);
            profile.save();
            adapter.notifyDataSetChanged();
            updateEmptyTextView();
            position = controller.getPosition(controllerBinding);
        }
        else animateItemView(position = controller.getPosition(controllerBinding));
        recyclerView.scrollToPosition(position);
    }

    private void processJoystickInput() {
        int keyCode = KeyEvent.KEYCODE_UNKNOWN;
        Binding binding = Binding.NONE;
        final int[] axes = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
        GamepadState state = controller.getGamepadState();
        final float[] values = {state.thumbLX, state.thumbLY, state.thumbRX, state.thumbRY, state.getDPadX(), state.getDPadY()};

        byte sign;
        for (int i = 0; i < axes.length; i++) {
            if ((sign = Mathf.sign(values[i])) != 0) {
                if (axes[i] == MotionEvent.AXIS_X || axes[i] == MotionEvent.AXIS_Z) {
                    binding = sign > 0 ? Binding.MOUSE_MOVE_RIGHT : Binding.MOUSE_MOVE_LEFT;
                }
                else if (axes[i] == MotionEvent.AXIS_Y || axes[i] == MotionEvent.AXIS_RZ) {
                    binding = sign > 0 ? Binding.MOUSE_MOVE_DOWN : Binding.MOUSE_MOVE_UP;
                }
                else if (axes[i] == MotionEvent.AXIS_HAT_X) {
                    binding = sign > 0 ? Binding.KEY_D : Binding.KEY_A;
                }
                else if (axes[i] == MotionEvent.AXIS_HAT_Y) {
                    binding = sign > 0 ? Binding.KEY_S : Binding.KEY_W;
                }

                keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign);
                break;
            }
        }

        updateControllerBinding(keyCode, binding);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getDeviceId() == controller.getDeviceId() && controller.updateStateFromMotionEvent(event)) {
            GamepadState state = controller.getGamepadState();
            // 叠加扳机轴值:部分手柄只以 AXIS_BRAKE/AXIS_GAS 上报扳机而不发按键事件,
            // 只看 isPressed 会导致这类手柄无法在绑定界面录制 L2/R2。
            // 死区阈值必须与触发侧(InputControlsView)一致,否则 0~死区 的弱轴值录进去也永远触发不了,
            // 静止噪声还会误录
            if (state.isPressed(ExternalController.IDX_BUTTON_L2) || state.triggerL > ControlElement.STICK_DEAD_ZONE) updateControllerBinding(KeyEvent.KEYCODE_BUTTON_L2, Binding.NONE);
            if (state.isPressed(ExternalController.IDX_BUTTON_R2) || state.triggerR > ControlElement.STICK_DEAD_ZONE) updateControllerBinding(KeyEvent.KEYCODE_BUTTON_R2, Binding.NONE);
            processJoystickInput();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getDeviceId() == controller.getDeviceId() && event.getRepeatCount() == 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) updateControllerBinding(event.getKeyCode(), Binding.NONE);
            return true;
        }
        else return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        finish();
        return true;
    }

    private class ControllerBindingsAdapter extends RecyclerView.Adapter<ControllerBindingsAdapter.ViewHolder> {
        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageButton removeButton;
            private final TextView title;
            private final Spinner bindingType;
            private final Spinner binding;

            private ViewHolder(View view) {
                super(view);
                this.title = view.findViewById(R.id.TVTitle);
                this.bindingType = view.findViewById(R.id.SBindingType);
                this.binding = view.findViewById(R.id.SBinding);
                this.removeButton = view.findViewById(R.id.BTRemove);
            }
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.external_controller_binding_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            final ExternalControllerBinding item = controller.getControllerBindingAt(position);
            holder.title.setText(item.toString());
            loadBindingSpinner(holder, item);
            holder.removeButton.setOnClickListener((view) -> {
                controller.removeControllerBinding(item);
                profile.save();
                notifyDataSetChanged();
                updateEmptyTextView();
            });
        }

        @Override
        public final int getItemCount() {
            return controller.getControllerBindingCount();
        }

        private void loadBindingSpinner(ViewHolder holder, final ExternalControllerBinding item) {
            final Context $this = ExternalControllerBindingsActivity.this;

            Runnable update = () -> {
                String[] bindingEntries = null;
                switch (holder.bindingType.getSelectedItemPosition()) {
                    case 0:
                        bindingEntries = Binding.keyboardBindingLabels($this);
                        break;
                    case 1:
                        bindingEntries = Binding.mouseBindingLabels($this);
                        break;
                    case 2:
                        bindingEntries = Binding.gamepadBindingLabels($this);
                        break;
                }

                holder.binding.setAdapter(new ArrayAdapter<>($this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
                AppUtils.setSpinnerSelectionFromValue(holder.binding, item.getBinding().getDisplayName($this));
            };

            holder.bindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    update.run();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            Binding selectedBinding = item.getBinding();
            if (selectedBinding.isKeyboard()) {
                holder.bindingType.setSelection(0, false);
            }
            else if (selectedBinding.isMouse()) {
                holder.bindingType.setSelection(1, false);
            }
            else if (selectedBinding.isGamepad()) {
                holder.bindingType.setSelection(2, false);
            }

            holder.binding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    Binding binding = Binding.NONE;
                    switch (holder.bindingType.getSelectedItemPosition()) {
                        case 0:
                            binding = Binding.keyboardBindingValues()[position];
                            break;
                        case 1:
                            binding = Binding.mouseBindingValues()[position];
                            break;
                        case 2:
                            binding = Binding.gamepadBindingValues()[position];
                            break;
                    }

                    if (binding != item.getBinding()) {
                        item.setBinding(binding);
                        profile.save();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            update.run();
        }
    }

    private void updateEmptyTextView() {
        emptyTextView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void animateItemView(int position) {
        final ControllerBindingsAdapter.ViewHolder holder = (ControllerBindingsAdapter.ViewHolder)recyclerView.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            final int color = AppUtils.getThemeColor(this, R.attr.colorAccent);
            final ValueAnimator animator = ValueAnimator.ofFloat(0.4f, 0.0f);
            animator.setDuration(200);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
            animator.addUpdateListener((animation) -> {
                float alpha = (float)animation.getAnimatedValue();
                holder.itemView.setBackgroundColor(Color.argb((int)(alpha * 255), Color.red(color), Color.green(color), Color.blue(color)));
            });
            animator.start();
        }
    }
}
