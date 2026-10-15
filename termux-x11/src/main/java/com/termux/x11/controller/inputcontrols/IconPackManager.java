package com.termux.x11.controller.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.preference.PreferenceManager;

import com.catfixture.inputbridge.core.iconmanager.BitmapData;
import com.catfixture.inputbridge.core.iconmanager.Icon;
import com.catfixture.inputbridge.core.iconmanager.IconPack;
import com.termux.x11.controller.core.FileUtils;
import com.termux.x11.controller.core.StreamUtils;
import com.termux.x11.controller.core.StringUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class IconPackManager {
    private static final byte[] JAVA_SERIALIZATION_HEADER = {(byte)0xAC, (byte)0xED, 0x00, 0x05};
    private static final String PREF_ACTIVE_PACK_ID = "controls_editor_active_icon_pack_id";
    private static final String PREF_ACTIVE_PACK_IDS = "controls_editor_active_icon_pack_ids";
    private static final String METADATA_FILENAME = "metadata.json";

    public static class PackIcon {
        public final String name;
        public final String path;
        public final int scaleType;
        public final File file;

        public PackIcon(String name, String path, int scaleType, File file) {
            this.name = name;
            this.path = path;
            this.scaleType = scaleType;
            this.file = file;
        }

        public byte[] readBytes() {
            return FileUtils.read(file);
        }
    }

    public static class StoredIconPack {
        public final String id;
        public final String name;
        public final String author;
        public final boolean enabled;
        public final long packSize;
        public final ArrayList<PackIcon> icons;
        public final PackIcon customIcon;
        public final File dir;

        public StoredIconPack(String id, String name, String author, boolean enabled, long packSize, ArrayList<PackIcon> icons, PackIcon customIcon, File dir) {
            this.id = id;
            this.name = name;
            this.author = author;
            this.enabled = enabled;
            this.packSize = packSize;
            this.icons = icons;
            this.customIcon = customIcon;
            this.dir = dir;
        }
    }

    public static class SourceIcon {
        public final String name;
        public final String path;
        public final int scaleType;
        public final byte[] bytes;

        public SourceIcon(String name, String path, int scaleType, byte[] bytes) {
            this.name = name != null ? name : "";
            this.path = path != null ? path : "";
            this.scaleType = scaleType;
            this.bytes = bytes;
        }
    }

    private final Context context;
    private final SharedPreferences preferences;

    public IconPackManager(Context context) {
        this.context = context;
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static File getIconPacksDir(Context context) {
        File dir = new File(context.getFilesDir(), "inputcontrols/iconpacks");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public ArrayList<StoredIconPack> getPacks() {
        File[] dirs = getIconPacksDir(context).listFiles(File::isDirectory);
        ArrayList<StoredIconPack> packs = new ArrayList<>();
        if (dirs != null) {
            for (File dir : dirs) {
                StoredIconPack pack = loadPack(dir);
                if (pack != null) packs.add(pack);
            }
        }
        Collections.sort(packs, Comparator.comparing(pack -> pack.name.toLowerCase()));
        return packs;
    }

    public StoredIconPack getActivePack() {
        ArrayList<StoredIconPack> activePacks = getActivePacks();
        return activePacks.isEmpty() ? null : activePacks.get(0);
    }

    public ArrayList<StoredIconPack> getActivePacks() {
        Set<String> activePackIds = getActivePackIds();
        ArrayList<StoredIconPack> activePacks = new ArrayList<>();
        if (activePackIds.isEmpty()) return activePacks;

        for (StoredIconPack pack : getPacks()) {
            if (activePackIds.contains(pack.id)) activePacks.add(pack);
        }
        return activePacks;
    }

    public StoredIconPack getPack(String packId) {
        if (packId == null || packId.isEmpty()) return null;
        return loadPack(new File(getIconPacksDir(context), packId));
    }

    public String getActivePackId() {
        for (String packId : getActivePackIds()) return packId;
        return null;
    }

    public Set<String> getActivePackIds() {
        LinkedHashSet<String> activePackIds = new LinkedHashSet<>();

        Set<String> storedPackIds = preferences.getStringSet(PREF_ACTIVE_PACK_IDS, null);
        if (storedPackIds != null) activePackIds.addAll(storedPackIds);
        else {
            String legacyPackId = preferences.getString(PREF_ACTIVE_PACK_ID, null);
            if (legacyPackId != null && !legacyPackId.isEmpty()) {
                activePackIds.add(legacyPackId);
                setActivePackIds(activePackIds);
            }
        }

        return activePackIds;
    }

    public void setActivePackId(String packId) {
        LinkedHashSet<String> activePackIds = new LinkedHashSet<>();
        if (packId != null && !packId.isEmpty()) activePackIds.add(packId);
        setActivePackIds(activePackIds);
    }

    public void setActivePackIds(Set<String> packIds) {
        LinkedHashSet<String> activePackIds = new LinkedHashSet<>();
        if (packIds != null) {
            for (String packId : packIds) {
                if (packId != null && !packId.isEmpty()) activePackIds.add(packId);
            }
        }

        SharedPreferences.Editor editor = preferences.edit();
        editor.putStringSet(PREF_ACTIVE_PACK_IDS, activePackIds);
        editor.putString(PREF_ACTIVE_PACK_ID, activePackIds.isEmpty() ? null : activePackIds.iterator().next());
        editor.apply();
    }

    public boolean isPackEnabled(String packId) {
        return packId != null && getActivePackIds().contains(packId);
    }

    public void setPackEnabled(String packId, boolean enabled) {
        if (packId == null || packId.isEmpty()) return;

        LinkedHashSet<String> activePackIds = new LinkedHashSet<>(getActivePackIds());
        if (enabled) activePackIds.add(packId);
        else activePackIds.remove(packId);
        setActivePackIds(activePackIds);
    }

    public StoredIconPack importPack(File file) throws IOException, ClassNotFoundException, JSONException {
        byte[] bytes = FileUtils.read(file);
        if (bytes == null || !isSerializedIpk(bytes)) throw new IOException(describeInvalidHeader(bytes));

        try {
            return importPack(deserializeIconPack(bytes));
        }
        catch (IOException | ClassNotFoundException firstException) {
            throw firstException;
        }
    }

    public StoredIconPack importPack(Uri uri) throws IOException, ClassNotFoundException, JSONException {
        byte[] bytes;
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) throw new IOException("Cannot open input stream");
            bytes = StreamUtils.copyToByteArray(inputStream);
        }

        if (!isSerializedIpk(bytes)) {
            throw new IOException(describeInvalidHeader(bytes));
        }

        try {
            return importPack(deserializeIconPack(bytes));
        }
        catch (IOException | ClassNotFoundException firstException) {
            File tempFile = FileUtils.createTempFile(context.getCacheDir(), "icon-pack-import");
            try {
                if (!FileUtils.write(tempFile, bytes)) throw new IOException("Failed to stage icon pack");
                try (ObjectInputStream objectInputStream = new ObjectInputStream(new FileInputStream(tempFile))) {
                    IconPack iconPack = (IconPack)objectInputStream.readObject();
                    return importPack(iconPack);
                }
            }
            catch (IOException | ClassNotFoundException secondException) {
                secondException.addSuppressed(firstException);
                throw secondException;
            }
            finally {
                FileUtils.delete(tempFile);
            }
        }
    }

    public StoredIconPack importPack(IconPack iconPack) throws JSONException {
        String packName = iconPack.name != null && !iconPack.name.trim().isEmpty() ? iconPack.name.trim() : "Imported Pack";
        String baseId = StringUtils.clearReservedChars(packName).replaceAll("\\s+", "-").toLowerCase();
        if (baseId.isEmpty()) baseId = "icon-pack";

        File dir = new File(getIconPacksDir(context), baseId);
        int suffix = 1;
        while (dir.exists()) dir = new File(getIconPacksDir(context), baseId+"-"+suffix++);
        dir.mkdirs();

        JSONObject metadata = new JSONObject();
        metadata.put("id", dir.getName());
        metadata.put("name", packName);
        metadata.put("author", iconPack.author != null ? iconPack.author : "");
        metadata.put("enabled", iconPack.isEnabled);
        metadata.put("packSize", iconPack.packSize);

        JSONArray iconsJSONArray = new JSONArray();
        if (iconPack.icons != null) {
            for (int i = 0; i < iconPack.icons.size(); i++) {
                Icon icon = iconPack.icons.get(i);
                if (icon == null || icon.bmpData == null || icon.bmpData.binData == null || icon.bmpData.binData.length == 0) continue;

                String filename = String.format("%03d.bin", i);
                FileUtils.write(new File(dir, filename), icon.bmpData.binData);

                JSONObject iconJSONObject = new JSONObject();
                iconJSONObject.put("name", icon.name != null ? icon.name : "");
                iconJSONObject.put("path", icon.path != null ? icon.path : "");
                iconJSONObject.put("scaleType", icon.scaleType);
                iconJSONObject.put("fileName", filename);
                iconsJSONArray.put(iconJSONObject);
            }
        }
        metadata.put("icons", iconsJSONArray);

        if (iconPack.customIcon != null && iconPack.customIcon.bmpData != null && iconPack.customIcon.bmpData.binData != null && iconPack.customIcon.bmpData.binData.length > 0) {
            String filename = "custom.bin";
            FileUtils.write(new File(dir, filename), iconPack.customIcon.bmpData.binData);

            JSONObject customIconJSONObject = new JSONObject();
            customIconJSONObject.put("name", iconPack.customIcon.name != null ? iconPack.customIcon.name : "");
            customIconJSONObject.put("path", iconPack.customIcon.path != null ? iconPack.customIcon.path : "");
            customIconJSONObject.put("scaleType", iconPack.customIcon.scaleType);
            customIconJSONObject.put("fileName", filename);
            metadata.put("customIcon", customIconJSONObject);
        }

        FileUtils.writeString(new File(dir, METADATA_FILENAME), metadata.toString());
        StoredIconPack storedIconPack = loadPack(dir);
        if (storedIconPack != null) setPackEnabled(storedIconPack.id, true);
        return storedIconPack;
    }

    public StoredIconPack createPack(String packName, String author, List<SourceIcon> sourceIcons) throws IOException, JSONException {
        IconPack iconPack = new IconPack();
        iconPack.name = packName != null && !packName.trim().isEmpty() ? packName.trim() : "Icon Pack";
        iconPack.author = author != null ? author.trim() : "";
        iconPack.isEnabled = true;
        iconPack.icons = new ArrayList<>();

        if (sourceIcons != null) for (SourceIcon sourceIcon : sourceIcons) {
            Icon icon = toSerializableIcon(sourceIcon);
            if (icon != null) iconPack.icons.add(icon);
        }

        byte[] bytes = serializeIconPack(iconPack);
        iconPack.packSize = bytes.length;
        return importPack(iconPack);
    }

    public StoredIconPack addIcons(String packId, List<SourceIcon> sourceIcons) throws IOException, JSONException {
        if (packId == null || packId.isEmpty() || sourceIcons == null || sourceIcons.isEmpty()) return getPack(packId);

        File dir = new File(getIconPacksDir(context), packId);
        JSONObject metadata = readMetadata(dir);
        if (metadata == null) return null;

        JSONArray iconsJSONArray = metadata.optJSONArray("icons");
        if (iconsJSONArray == null) iconsJSONArray = new JSONArray();

        int nextFileIndex = iconsJSONArray.length();
        for (SourceIcon sourceIcon : sourceIcons) {
            if (sourceIcon == null || sourceIcon.bytes == null || sourceIcon.bytes.length == 0) continue;

            String filename = generateNextIconFilename(dir, nextFileIndex++);
            FileUtils.write(new File(dir, filename), sourceIcon.bytes);

            JSONObject iconJSONObject = new JSONObject();
            iconJSONObject.put("name", sourceIcon.name);
            iconJSONObject.put("path", sourceIcon.path);
            iconJSONObject.put("scaleType", sourceIcon.scaleType);
            iconJSONObject.put("fileName", filename);
            iconsJSONArray.put(iconJSONObject);
        }

        metadata.put("icons", iconsJSONArray);
        return writeMetadataAndLoadPack(dir, metadata);
    }

    public StoredIconPack removeIcon(String packId, int iconIndex) throws IOException, JSONException {
        if (packId == null || packId.isEmpty() || iconIndex < 0) return getPack(packId);

        File dir = new File(getIconPacksDir(context), packId);
        JSONObject metadata = readMetadata(dir);
        if (metadata == null) return null;

        JSONArray iconsJSONArray = metadata.optJSONArray("icons");
        if (iconsJSONArray == null || iconIndex >= iconsJSONArray.length()) return getPack(packId);

        JSONObject iconJSONObject = iconsJSONArray.optJSONObject(iconIndex);
        if (iconJSONObject != null) {
            String fileName = iconJSONObject.optString("fileName", "");
            if (!fileName.isEmpty()) FileUtils.delete(new File(dir, fileName));
        }

        JSONArray updatedIconsJSONArray = new JSONArray();
        for (int i = 0; i < iconsJSONArray.length(); i++) {
            if (i == iconIndex) continue;
            updatedIconsJSONArray.put(iconsJSONArray.getJSONObject(i));
        }

        metadata.put("icons", updatedIconsJSONArray);
        return writeMetadataAndLoadPack(dir, metadata);
    }

    public boolean exportPack(StoredIconPack pack, File file) throws IOException {
        if (pack == null) return false;

        IconPack iconPack = toSerializablePack(pack);
        byte[] bytes = serializeIconPack(iconPack);
        iconPack.packSize = bytes.length;
        bytes = serializeIconPack(iconPack);

        return FileUtils.write(file, bytes);
    }

    public boolean exportPack(StoredIconPack pack, Uri uri) throws IOException {
        if (pack == null) return false;

        IconPack iconPack = toSerializablePack(pack);
        byte[] bytes = serializeIconPack(iconPack);
        iconPack.packSize = bytes.length;
        bytes = serializeIconPack(iconPack);

        try (OutputStream outputStream = context.getContentResolver().openOutputStream(uri)) {
            if (outputStream == null) return false;
            outputStream.write(bytes);
            outputStream.flush();
            return true;
        }
    }

    public boolean removePack(String packId) {
        if (packId == null || packId.isEmpty()) return false;
        setPackEnabled(packId, false);
        return FileUtils.delete(new File(getIconPacksDir(context), packId));
    }

    private StoredIconPack loadPack(File dir) {
        File metadataFile = new File(dir, METADATA_FILENAME);
        if (!metadataFile.isFile()) return null;

        try {
            JSONObject metadata = new JSONObject(FileUtils.readString(metadataFile));
            String id = metadata.optString("id", dir.getName());
            String name = metadata.optString("name", dir.getName());
            String author = metadata.optString("author", "");
            boolean enabled = metadata.optBoolean("enabled", true);
            long packSize = metadata.optLong("packSize", 0);

            ArrayList<PackIcon> icons = new ArrayList<>();
            JSONArray iconsJSONArray = metadata.optJSONArray("icons");
            if (iconsJSONArray != null) {
                for (int i = 0; i < iconsJSONArray.length(); i++) {
                    JSONObject iconJSONObject = iconsJSONArray.getJSONObject(i);
                    File file = new File(dir, iconJSONObject.getString("fileName"));
                    if (!file.isFile()) continue;
                    icons.add(new PackIcon(
                        iconJSONObject.optString("name", ""),
                        iconJSONObject.optString("path", ""),
                        iconJSONObject.optInt("scaleType", 0),
                        file
                    ));
                }
            }

            PackIcon customIcon = null;
            if (metadata.has("customIcon")) {
                JSONObject iconJSONObject = metadata.getJSONObject("customIcon");
                File file = new File(dir, iconJSONObject.getString("fileName"));
                if (file.isFile()) {
                    customIcon = new PackIcon(
                        iconJSONObject.optString("name", ""),
                        iconJSONObject.optString("path", ""),
                        iconJSONObject.optInt("scaleType", 0),
                        file
                    );
                }
            }

            return new StoredIconPack(id, name, author, enabled, packSize, icons, customIcon, dir);
        }
        catch (Exception e) {
            return null;
        }
    }

    private JSONObject readMetadata(File dir) throws JSONException {
        File metadataFile = new File(dir, METADATA_FILENAME);
        if (!metadataFile.isFile()) return null;
        return new JSONObject(FileUtils.readString(metadataFile));
    }

    private StoredIconPack writeMetadataAndLoadPack(File dir, JSONObject metadata) throws IOException, JSONException {
        File metadataFile = new File(dir, METADATA_FILENAME);
        metadata.put("id", metadata.optString("id", dir.getName()));
        FileUtils.writeString(metadataFile, metadata.toString());

        StoredIconPack storedIconPack = loadPack(dir);
        long packSize = calculatePackSize(storedIconPack);
        metadata.put("packSize", packSize);
        FileUtils.writeString(metadataFile, metadata.toString());
        return loadPack(dir);
    }

    private String generateNextIconFilename(File dir, int startIndex) {
        int index = Math.max(startIndex, 0);
        File file;
        do {
            file = new File(dir, String.format("%03d.bin", index++));
        }
        while (file.exists());
        return file.getName();
    }

    private static Icon toSerializableIcon(PackIcon packIcon) {
        if (packIcon == null) return null;
        byte[] bytes = packIcon.readBytes();
        if (bytes == null || bytes.length == 0) return null;

        BitmapData bitmapData = new BitmapData();
        bitmapData.binData = bytes;

        Icon icon = new Icon();
        icon.scaleType = packIcon.scaleType;
        icon.bmpData = bitmapData;
        icon.name = packIcon.name;
        icon.path = packIcon.path;
        return icon;
    }

    private static Icon toSerializableIcon(SourceIcon sourceIcon) {
        if (sourceIcon == null || sourceIcon.bytes == null || sourceIcon.bytes.length == 0) return null;

        BitmapData bitmapData = new BitmapData();
        bitmapData.binData = sourceIcon.bytes;

        Icon icon = new Icon();
        icon.scaleType = sourceIcon.scaleType;
        icon.bmpData = bitmapData;
        icon.name = sourceIcon.name;
        icon.path = sourceIcon.path;
        return icon;
    }

    private static IconPack toSerializablePack(StoredIconPack pack) {
        IconPack iconPack = new IconPack();
        iconPack.name = pack.name;
        iconPack.author = pack.author;
        iconPack.isEnabled = pack.enabled;
        iconPack.icons = new ArrayList<>();
        iconPack.customIcon = toSerializableIcon(pack.customIcon);

        for (PackIcon packIcon : pack.icons) {
            Icon icon = toSerializableIcon(packIcon);
            if (icon != null) iconPack.icons.add(icon);
        }
        return iconPack;
    }

    private static long calculatePackSize(StoredIconPack pack) throws IOException {
        if (pack == null) return 0;
        IconPack iconPack = toSerializablePack(pack);
        byte[] bytes = serializeIconPack(iconPack);
        iconPack.packSize = bytes.length;
        return serializeIconPack(iconPack).length;
    }

    private static byte[] serializeIconPack(IconPack iconPack) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream)) {
            objectOutputStream.writeObject(iconPack);
            objectOutputStream.flush();
            return outputStream.toByteArray();
        }
    }

    private static IconPack deserializeIconPack(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream objectInputStream = new CompatibleIconPackObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (IconPack)objectInputStream.readObject();
        }
    }

    private static class CompatibleIconPackObjectInputStream extends ObjectInputStream {
        CompatibleIconPackObjectInputStream(InputStream inputStream) throws IOException {
            super(inputStream);
        }

        @Override
        protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
            ObjectStreamClass descriptor = super.readClassDescriptor();
            Class<?> compatibleClass = getCompatibleIconPackClass(descriptor.getName());
            ObjectStreamClass compatibleDescriptor = compatibleClass != null ? ObjectStreamClass.lookup(compatibleClass) : null;
            return compatibleDescriptor != null ? compatibleDescriptor : descriptor;
        }
    }

    private static Class<?> getCompatibleIconPackClass(String className) {
        if (IconPack.class.getName().equals(className)) return IconPack.class;
        if (Icon.class.getName().equals(className)) return Icon.class;
        if (BitmapData.class.getName().equals(className)) return BitmapData.class;
        return null;
    }

    private static boolean isSerializedIpk(byte[] bytes) {
        if (bytes == null || bytes.length < JAVA_SERIALIZATION_HEADER.length) return false;
        for (int i = 0; i < JAVA_SERIALIZATION_HEADER.length; i++) {
            if (bytes[i] != JAVA_SERIALIZATION_HEADER[i]) return false;
        }
        return true;
    }

    private static String describeInvalidHeader(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "Empty file";

        int headerLength = Math.min(bytes.length, 8);
        StringBuilder builder = new StringBuilder("Invalid IPK header: ");
        for (int i = 0; i < headerLength; i++) {
            if (i > 0) builder.append(' ');
            builder.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return builder.toString();
    }
}