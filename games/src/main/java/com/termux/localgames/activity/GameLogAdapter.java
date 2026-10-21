package com.termux.localgames.activity;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.localgames.R;

import java.util.Collections;
import java.util.List;

/**
 * Rows of the log viewer: absolute line number, level badge and the line text. ERROR/WARN lines get
 * a tinted row and level-colored text, DEBUG/TRACE lines are dimmed.
 */
final class GameLogAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final int errorColor;
    private final int errorFill;
    private final int warnColor;
    private final int warnFill;
    private final int normalColor;
    private final int dimColor;

    private List<GameLogLine> lines = Collections.emptyList();
    /** Digits of the largest line number, so every gutter has the same width. */
    private int numberDigits = 1;

    GameLogAdapter(@NonNull Context context) {
        inflater = LayoutInflater.from(context);
        errorColor = ContextCompat.getColor(context, R.color.local_games_error);
        errorFill = ContextCompat.getColor(context, R.color.local_games_error_container);
        warnColor = ContextCompat.getColor(context, R.color.local_games_log_warn);
        warnFill = ContextCompat.getColor(context, R.color.local_games_log_warn_container);
        normalColor = ContextCompat.getColor(context, R.color.local_games_on_surface);
        dimColor = ContextCompat.getColor(context, R.color.local_games_log_debug);
    }

    void setLines(@NonNull List<GameLogLine> newLines) {
        lines = newLines;
        numberDigits = newLines.isEmpty() ? 1
            : String.valueOf(newLines.get(newLines.size() - 1).number).length();
        notifyDataSetChanged();
    }

    @Override public int getCount() {
        return lines.size();
    }

    @Override public GameLogLine getItem(int position) {
        return lines.get(position);
    }

    @Override public long getItemId(int position) {
        return lines.get(position).number;
    }

    @Override public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View row = convertView != null ? convertView
            : inflater.inflate(R.layout.item_game_log_line, parent, false);
        GameLogLine line = lines.get(position);
        TextView number = row.findViewById(R.id.game_log_number);
        TextView badge = row.findViewById(R.id.game_log_badge);
        TextView text = row.findViewById(R.id.game_log_text);

        number.setMinEms(numberDigits);
        number.setText(String.valueOf(line.number));
        badge.setText(String.valueOf(line.level.badge));
        int color = colorOf(line.level);
        badge.setTextColor(color);
        text.setTextColor(color);
        text.setText(line.text);

        if (line.level == GameLogLine.Level.ERROR) row.setBackgroundColor(errorFill);
        else if (line.level == GameLogLine.Level.WARN) row.setBackgroundColor(warnFill);
        else row.setBackgroundColor(0);
        return row;
    }

    private int colorOf(@NonNull GameLogLine.Level level) {
        switch (level) {
            case ERROR: return errorColor;
            case WARN: return warnColor;
            case DEBUG:
            case TRACE: return dimColor;
            default: return normalColor;
        }
    }
}
