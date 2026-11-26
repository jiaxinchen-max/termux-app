package com.termux.ui.ide;

import java.io.File;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Adapts the pinned AndroidIDE Gradle model to language-service inputs, never guesses build scripts.
 * Adapts AndroidIDE's ModuleProject/AndroidModule/JavaModule classpath rules (GPL-3.0-or-later),
 * with visited sets to terminate cyclic module/library graphs. Missing generated outputs are retained.
 */
public final class IdeModuleContexts {
    public static final class Module {
        public final String path, type, variant, sourceVersion;
        public final File directory;
        public final Set<File> sources, classpaths, bootClasspaths, resources;
        public final Set<String> dependencies;
        private Module(JSONObject raw, Map<String,JSONObject> all) throws Exception {
            JSONObject metadata = raw.getJSONObject("metadata");
            path = metadata.getString("projectPath"); type = raw.getString("type");
            directory = absolute(metadata.getString("projectDir"));
            variant = raw.optString("variant", "");
            JSONObject compiler = metadata.optJSONObject("javaCompileOptions");
            if (compiler == null) compiler = metadata.optJSONObject("compilerSettings");
            sourceVersion = compiler == null ? "" : compiler.optString("javaSourceVersion", "");
            LinkedHashSet<File> sourceFiles = new LinkedHashSet<>(), jars = new LinkedHashSet<>(),
                boot = new LinkedHashSet<>(), res = new LinkedHashSet<>();
            LinkedHashSet<String> projects = new LinkedHashSet<>();
            collect(raw, all, new HashSet<>(), sourceFiles, jars, boot, res, projects);
            sources = Collections.unmodifiableSet(sourceFiles); classpaths = Collections.unmodifiableSet(jars);
            bootClasspaths = Collections.unmodifiableSet(boot); resources = Collections.unmodifiableSet(res);
            projects.remove(path); dependencies = Collections.unmodifiableSet(projects);
        }
        public JSONObject toJson() throws Exception {
            return new JSONObject().put("path", path).put("type", type).put("variant", variant)
                .put("projectDir", directory.getPath()).put("sourceVersion", sourceVersion)
                .put("sources", strings(sources)).put("classpaths", strings(classpaths))
                .put("bootClasspaths", strings(bootClasspaths)).put("resources", strings(resources))
                .put("dependencies", new JSONArray(dependencies));
        }
    }
    public final List<Module> modules;
    public IdeModuleContexts(JSONObject model) throws Exception {
        LinkedHashMap<String,JSONObject> raw = new LinkedHashMap<>();
        JSONArray input = model.getJSONArray("modules");
        for (int i = 0; i < input.length(); i++) {
            JSONObject module = input.getJSONObject(i);
            String path = module.getJSONObject("metadata").getString("projectPath");
            if (raw.put(path, module) != null) throw new IOException("Duplicate Gradle module: " + path);
        }
        ArrayList<Module> result = new ArrayList<>();
        for (JSONObject module : raw.values()) {
            String type = module.getString("type");
            if (type.equals("Android") || type.equals("Java")) result.add(new Module(module, raw));
        }
        modules = Collections.unmodifiableList(result);
    }
    /** Chooses the owning module, not a dependant which merely sees this source transitively. */
    public Module forFile(File input) throws IOException {
        File file = input.getCanonicalFile(); Module result = null; int best = -1;
        for (Module module : modules) {
            String root = module.directory.getPath();
            if ((file.getPath().equals(root) || file.getPath().startsWith(root + File.separator)) && root.length() > best) {
                result = module; best = root.length();
            }
        }
        return result;
    }
    public JSONArray toJson() throws Exception {
        JSONArray result = new JSONArray(); for (Module module : modules) result.put(module.toJson()); return result;
    }
    private static void collect(JSONObject raw, Map<String,JSONObject> all, Set<String> visited,
            Set<File> sources, Set<File> jars, Set<File> boot, Set<File> resources, Set<String> dependencies) throws Exception {
        JSONObject metadata = raw.getJSONObject("metadata"); String path = metadata.getString("projectPath");
        if (!visited.add(path)) return;
        addFile(metadata, "classesJar", jars);
        String type = raw.getString("type");
        if (type.equals("Java")) {
            JSONArray roots = raw.optJSONArray("sources");
            if (roots != null) for (int i = 0; i < roots.length(); i++) {
                JSONArray dirs = roots.getJSONObject(i).optJSONArray("sourceDirectories");
                if (dirs != null) for (int j = 0; j < dirs.length(); j++) {
                    JSONObject dir = dirs.getJSONObject(j);
                    addFile(dir, "directory", sources);
                }
            }
            JSONArray deps = raw.optJSONArray("dependencies");
            if (deps != null) for (int i = 0; i < deps.length(); i++) {
                JSONObject dep = deps.getJSONObject(i);
                if (!"COMPILE".equals(dep.optString("scope"))) continue;
                addFile(dep, "jarFile", jars);
                String target = dep.optString("projectPath", "");
                if (!target.isEmpty()) collectProject(target, all, visited, sources, jars, boot, resources, dependencies);
            }
        } else if (type.equals("Android")) {
            addFiles(raw.optJSONArray("bootClasspaths"), boot);
            JSONObject set = raw.optJSONObject("sources");
            if (set != null) {
                JSONObject provider = set.optJSONObject("sourceProvider");
                if (provider != null) {
                    addFiles(provider.optJSONArray("javaDirectories"), sources);
                    addFiles(provider.optJSONArray("kotlinDirectories"), sources);
                    addFiles(provider.optJSONArray("resDirectories"), resources);
                }
            }
            JSONObject artifact = raw.optJSONObject("artifact");
            if (artifact != null) artifact = artifact.optJSONObject("mainArtifact");
            if (artifact != null) {
                addFiles(artifact.optJSONArray("generatedSourceFolders"), sources);
                addFiles(artifact.optJSONArray("generatedResourceFolders"), resources);
                addFiles(artifact.optJSONArray("classJars"), jars);
            }
            JSONObject libraries = raw.optJSONObject("libraries");
            if (libraries != null) {
                HashSet<String> seen = new HashSet<>();
                Iterator<String> keys = libraries.keys();
                while (keys.hasNext()) collectLibrary(keys.next(), libraries, seen, all, visited, sources, jars, boot, resources, dependencies);
            }
        }
    }
    private static void collectLibrary(String key, JSONObject libraries, Set<String> seen,
            Map<String,JSONObject> all, Set<String> visited, Set<File> sources, Set<File> jars,
            Set<File> boot, Set<File> resources, Set<String> projects) throws Exception {
        if (!seen.add(key)) return;
        JSONObject lib = libraries.optJSONObject(key); if (lib == null) return;
        String type = lib.optString("type");
        if (type.equals("PROJECT")) {
            JSONObject project = lib.optJSONObject("projectInfo");
            if (project != null) collectProject(project.getString("projectPath"), all, visited, sources, jars, boot, resources, projects);
        } else if (type.equals("ANDROID_LIBRARY")) {
            JSONObject android = lib.optJSONObject("androidLibraryData");
            if (android != null) {
                addFiles(android.optJSONArray("compileJarFiles"), jars);
                addFile(android, "resFolder", resources);
            }
        } else if (type.equals("JAVA_LIBRARY")) addFile(lib, "artifact", jars);
        JSONArray deps = lib.optJSONArray("dependencies");
        if (deps != null) for (int i = 0; i < deps.length(); i++)
            collectLibrary(deps.getString(i), libraries, seen, all, visited, sources, jars, boot, resources, projects);
    }
    private static void collectProject(String path, Map<String,JSONObject> all, Set<String> visited,
            Set<File> sources, Set<File> jars, Set<File> boot, Set<File> resources, Set<String> projects) throws Exception {
        JSONObject target = all.get(path);
        if (target == null) throw new IOException("Missing Gradle dependency module: " + path);
        projects.add(path); collect(target, all, visited, sources, jars, boot, resources, projects);
    }
    private static void addFiles(JSONArray array, Set<File> target) throws Exception {
        if (array != null) for (int i = 0; i < array.length(); i++) target.add(absolute(array.getString(i)));
    }
    private static void addFile(JSONObject object, String key, Set<File> target) throws Exception {
        if (!object.isNull(key) && object.has(key)) {
            String path = object.getString(key);
            // Upstream Java models use this sentinel when no output jar has been built yet.
            if (path.equals("module-jar-does-not-exist.jar")) return;
            target.add(absolute(path));
        }
    }
    private static File absolute(String path) throws IOException {
        File file = new File(path);
        if (path.isEmpty() || !file.isAbsolute()) throw new IOException("Non-absolute Gradle model path: " + path);
        return file.getCanonicalFile();
    }
    private static JSONArray strings(Set<File> files) {
        JSONArray result = new JSONArray(); for (File file : files) result.put(file.getPath()); return result;
    }
}
