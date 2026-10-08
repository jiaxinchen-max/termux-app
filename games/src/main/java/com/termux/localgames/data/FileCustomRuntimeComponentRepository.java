package com.termux.localgames.data;

import com.termux.localgames.domain.CustomRuntimeComponent;
import com.termux.localgames.domain.RuntimeTranslator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/** Registry of user-installed custom Wine/Box64 builds -- separate from the read-only bundled
 *  component catalog (ComponentIndex), since entries here are created at runtime from a
 *  locally-picked file. See CustomRuntimeComponent. */
public final class FileCustomRuntimeComponentRepository {
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final PropertiesTaskStore<CustomRuntimeComponent> store;

    public FileCustomRuntimeComponentRepository(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory required");
        this.store = new PropertiesTaskStore<>(directory, new CustomRuntimeComponentCodec(),
            ID_PATTERN, "Games custom runtime component");
    }

    public void save(CustomRuntimeComponent component) throws IOException {
        store.save(component);
    }

    public Optional<CustomRuntimeComponent> find(String id) throws IOException {
        return store.find(id);
    }

    public List<CustomRuntimeComponent> list() throws IOException {
        List<CustomRuntimeComponent> result = new ArrayList<>(store.list());
        result.sort(Comparator.comparingLong(CustomRuntimeComponent::getInstalledAtEpochSeconds));
        return result;
    }

    public List<CustomRuntimeComponent> listByKind(CustomRuntimeComponent.Kind kind) throws IOException {
        List<CustomRuntimeComponent> result = list();
        result.removeIf(component -> component.getKind() != kind);
        return result;
    }

    private static final class CustomRuntimeComponentCodec
        implements PropertiesTaskStore.Codec<CustomRuntimeComponent> {
        @Override
        public String taskId(CustomRuntimeComponent component) {
            return component.getId();
        }

        @Override
        public Properties toProperties(CustomRuntimeComponent component) {
            Properties value = new Properties();
            value.setProperty("schemaVersion", String.valueOf(CustomRuntimeComponent.SCHEMA_VERSION));
            value.setProperty("id", component.getId());
            value.setProperty("kind", component.getKind().name());
            value.setProperty("translator",
                component.getTranslator() == null ? "" : component.getTranslator().getStorageValue());
            value.setProperty("displayName", component.getDisplayName());
            value.setProperty("installedAtEpochSeconds",
                String.valueOf(component.getInstalledAtEpochSeconds()));
            value.setProperty("sha256", component.getSha256());
            return value;
        }

        @Override
        public CustomRuntimeComponent fromProperties(Properties value) throws IOException {
            try {
                if (!"1".equals(value.getProperty("schemaVersion"))) {
                    throw new IOException("custom_component_schema_unsupported");
                }
                CustomRuntimeComponent.Kind kind =
                    CustomRuntimeComponent.Kind.valueOf(required(value, "kind"));
                String translatorValue = value.getProperty("translator", "");
                RuntimeTranslator translator = translatorValue.isEmpty()
                    ? null : RuntimeTranslator.fromStorageValue(translatorValue);
                return new CustomRuntimeComponent(required(value, "id"), kind, translator,
                    required(value, "displayName"),
                    Long.parseLong(required(value, "installedAtEpochSeconds")),
                    required(value, "sha256"));
            } catch (IllegalArgumentException error) {
                throw new IOException("custom_component_invalid", error);
            }
        }

        private static String required(Properties value, String key) throws IOException {
            String result = value.getProperty(key);
            if (result == null || result.isEmpty()) {
                throw new IOException("custom_component_field_missing:" + key);
            }
            return result;
        }
    }
}
