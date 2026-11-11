package com.termux.app.terminal;

import com.termux.x11.controller.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;

public class StartEntry {

    public static class Entry {
        private String path;
        private String fileName;
        private String iconPath;

        public String getPath() {
            return path;
        }

        public void setPath(String mPath) {
            this.path = mPath;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String mFileName) {
            this.fileName = mFileName;
        }

        public String getIconPath() {
            return iconPath;
        }

        public void setIconPath(String mIconPath) {
            this.iconPath = mIconPath;
        }

        public JSONObject toJSONObject() {
            try {
                JSONObject elementJSONObject = new JSONObject();
                elementJSONObject.put("path", path);
                elementJSONObject.put("fileName", fileName);
                elementJSONObject.put("iconPath", iconPath);
                return elementJSONObject;
            } catch (JSONException e) {
                return null;
            }
        }
    }

    private final File configFile;
    private final ArrayList<Entry> mStartEntryList = new ArrayList<>();
    private final HashSet<String> mStartSet = new HashSet<>();
    private int mCurrentStartItemIdx = -1;

    public StartEntry(File configFile) {
        this.configFile = configFile;
    }

    public ArrayList<Entry> getStartItemList() {
        return mStartEntryList;
    }

    public int getCurrentStartItemIdx() {
        return mCurrentStartItemIdx;
    }

    public void setCurrentStartItemIdx(int startItemIdx) {
        mCurrentStartItemIdx = startItemIdx >= 0 && startItemIdx < mStartEntryList.size()
            ? startItemIdx : -1;
    }

    public Entry getSelectedEntry() {
        return mCurrentStartItemIdx >= 0 && mCurrentStartItemIdx < mStartEntryList.size()
            ? mStartEntryList.get(mCurrentStartItemIdx) : null;
    }

    public void saveStartItems() throws IOException {
        File parent = configFile.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create script settings directory");
        try {
            JSONObject data = new JSONObject();
            data.put("version", "1.0");
            data.put("name", "startItemEntries");
            data.put("currentStartItem", mCurrentStartItemIdx);

            JSONArray elementsJSONArray = new JSONArray();
            for (Entry itm : mStartEntryList) {
                elementsJSONArray.put(itm.toJSONObject());
                if (!mStartSet.contains(itm.getPath())) {
                    mStartSet.add(itm.getPath());
                }
            }
            data.put("elements", elementsJSONArray);
            if (!FileUtils.writeString(configFile, data.toString()))
                throw new IOException("Cannot save startup scripts");
        } catch (JSONException e) {
            throw new IOException("Cannot save startup scripts", e);
        }
    }

    public void loadStartItems() throws IOException {
        mStartEntryList.clear();
        mStartSet.clear();
        mCurrentStartItemIdx = -1;
        if (!configFile.exists()) {
            return;
        }

        try {
            byte[] contents = FileUtils.read(configFile);
            if (contents == null) throw new IOException("Cannot read startup scripts");
            JSONObject startItemEntriesJSONObject = new JSONObject(new String(contents, StandardCharsets.UTF_8));
            JSONArray startItemsJSONArray = startItemEntriesJSONObject.getJSONArray("elements");
            for (int i = 0; i < startItemsJSONArray.length(); i++) {
                JSONObject controllerJSONObject = startItemsJSONArray.getJSONObject(i);
                String path = controllerJSONObject.getString("path");
                String fileName = controllerJSONObject.getString("fileName");
                String iconPath = controllerJSONObject.getString("iconPath");
                Entry entry = new Entry();
                entry.setPath(path);
                entry.setFileName(fileName);
                entry.setIconPath(iconPath);
                String key = entry.getPath();
                if (!mStartSet.contains(key)) {
                    mStartSet.add(key);
                    mStartEntryList.add(entry);
                }
            }
            if (startItemEntriesJSONObject.has("currentStartItem")) {
                setCurrentStartItemIdx(startItemEntriesJSONObject.getInt("currentStartItem"));
            }
        } catch (JSONException e) {
            throw new IOException("Cannot read startup scripts", e);
        }
    }

    public boolean addStartEntry(Entry item) {
        String key = item.getPath();
        if (!mStartSet.contains(key)) {
            mStartSet.add(key);
            mStartEntryList.add(item);
            return true;
        }
        return false;
    }

    public void deleteStartEntry(Entry currentCommand) {
        if (mStartSet.contains(currentCommand.getPath())) {
            Entry selected = getSelectedEntry();
            mStartEntryList.remove(currentCommand);
            mStartSet.remove(currentCommand.getPath());
            mCurrentStartItemIdx = mStartEntryList.indexOf(selected);
        }
    }
}
