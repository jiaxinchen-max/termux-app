package com.termux.app.terminal;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

public class StartEntryArrayAdapter extends ArrayAdapter<StartEntry.Entry> {
    private Context context;
    private List<StartEntry.Entry> dataList;

    public StartEntryArrayAdapter(Context context, List<StartEntry.Entry> dataList) {
        super(context, android.R.layout.simple_spinner_item, dataList);
        this.context = context;
        this.dataList = dataList;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(android.R.layout.simple_spinner_item, parent, false);
        TextView textView = view.findViewById(android.R.id.text1);

        StartEntry.Entry item = dataList.get(position);
        textView.setText(item.getFileName());

        return view;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(android.R.layout.simple_spinner_dropdown_item, parent, false);
        TextView textView = view.findViewById(android.R.id.text1);

        StartEntry.Entry item = dataList.get(position);
        textView.setText(item.getFileName());

        return view;
    }
}
