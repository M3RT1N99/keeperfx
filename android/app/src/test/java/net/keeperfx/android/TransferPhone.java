package net.keeperfx.android;

import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** One phone's private storage, the way the launcher lays it out, in a temporary folder. */
final class TransferPhone {

    static final String APP = "1.60";

    final File files;
    final File install;
    final File manifests;
    final File backup;
    final File exports;
    final File alphaCache;
    final File downloads;
    final File updates;
    final File work;
    final File trash;
    final FakePreferences preferences = new FakePreferences();
    final Prefs prefs = new Prefs(preferences);

    TransferPhone(File files) {
        this.files = files;
        install = new File(files, "keeperfx");
        manifests = new File(files, "manifests");
        backup = new File(files, "alpha-backup");
        exports = new File(files, "exports");
        alphaCache = new File(files, "alpha-cache");
        downloads = new File(files, "downloads");
        updates = new File(files, "updates");
        work = new File(files, "transfer");
        trash = new File(files, "transfer-trash");
    }

    /**
     * A whole installation: the release, the patch over it with its way back, the
     * original game, music and what the player made. The contents depend on the name,
     * so that two phones never hold the same bytes.
     */
    void installEverything(String name) throws IOException {
        final List<String> release = new ArrayList<>(Arrays.asList(
            "ldata/intromix.smk", "ldata/bullfrog.smk", "campgns/keeporig_eng/good01.mp3",
            "campgns/keeporig.cfg", "sound/sound.dat", "fxdata/gtext_eng.dat",
            "creatrs/imp.cfg", "levels/map00001.dat", "keeperfx.cfg"));
        final List<String> theRest = new ArrayList<>(Arrays.asList(
            "music/keeper02.ogg", "mods/load_order.cfg", "scrshots/scr00001.png",
            "save/fx1g0000.sav", "save/fx1g0001.sav", "save/fx1lastf.sav", "save/keeporig.cid",
            "save/settings.toml"));
        for (String original : GameData.ORIGINAL_DK_DATA_FILES) {
            theRest.add("data/" + original);
        }
        for (String original : GameData.ORIGINAL_DK_SOUND_FILES) {
            theRest.add("sound/" + original);
        }
        for (String path : release) {
            TestArchives.write(new File(install, path), name + " release " + path);
        }
        for (String path : theRest) {
            TestArchives.write(new File(install, path), name + " " + path);
        }
        TestArchives.write(new File(install, "fxdata/patched.cfg"), name + " the patch's version");
        TestArchives.write(new File(install, "fxdata/font12.fxfont"), name + " only the patch has this");
        FileUtil.ensureDirectory(new File(install, "replays"));

        final List<String> stable = new ArrayList<>(release);
        stable.add("fxdata/patched.cfg");
        InstallManifest.write(new File(manifests, TransferBundle.STABLE_MANIFEST), install, stable);
        InstallManifest.write(new File(manifests, TransferBundle.ALPHA_MANIFEST), install,
            Arrays.asList("fxdata/patched.cfg", "fxdata/font12.fxfont"));
        TestArchives.write(new File(backup, "fxdata/patched.cfg"), name + " the release's version");
        TestArchives.write(new File(backup, "added.txt"), "fxdata/font12.fxfont\n");

        prefs.setInstalledVersion("1.4.0");
        prefs.setInstalledAlphaVersion("1.4.0.5591");
    }

    /** What the launcher's Pack button runs, minus the checks on the Android side. */
    File export() throws IOException {
        return export(TestProgress.QUIET);
    }

    File export(TransferBundle.Progress progress) throws IOException {
        final TransferBundle.Plan plan = TransferBundle.plan(install, manifests, backup,
            prefs.transferableSettings());
        final TransferBundle.Marker marker = new TransferBundle.Marker(APP, 5591,
            prefs.getInstalledVersion(), prefs.getInstalledAlphaVersion(), plan.entries(),
            plan.bytes(), plan.saves);
        return TransferBundle.writeExport(exports, TransferBundle.fileName(
            prefs.getInstalledVersion(), prefs.getInstalledAlphaVersion()), plan, marker, progress);
    }

    TransferSwap swap() {
        return new TransferSwap(install, manifests, backup,
            new File[] {alphaCache, downloads, updates, exports}, work, trash,
            prefs::replaceWithTransferred);
    }

    void importFile(File bundle) throws IOException {
        importFile(bundle, TestProgress.QUIET);
    }

    void importFile(File bundle, TransferBundle.Progress progress) throws IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(bundle)) {
            swap().install(in, java.util.Collections.<String>emptySet(), APP, progress);
        }
    }

    /** Every file with its bytes, and the settings: all there is to compare a phone by. */
    Map<String, String> snapshot() throws IOException {
        final Map<String, String> all = new TreeMap<>();
        addTree(files, "", all);
        for (Map.Entry<String, ?> setting : preferences.getAll().entrySet()) {
            all.put("setting " + setting.getKey(), String.valueOf(setting.getValue()));
        }
        return all;
    }

    /** One folder of it, with the paths relative to that folder. */
    static Map<String, String> tree(File folder) throws IOException {
        final Map<String, String> all = new TreeMap<>();
        addTree(folder, "", all);
        return all;
    }

    private static void addTree(File folder, String prefix, Map<String, String> into)
            throws IOException {
        final File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            final String path = prefix + child.getName();
            if (child.isDirectory()) {
                into.put(path + "/", "");
                addTree(child, path + "/", into);
            } else {
                into.put(path, new String(Files.readAllBytes(child.toPath()),
                    StandardCharsets.ISO_8859_1));
            }
        }
    }

    /** The real thing writes through to disk; what matters here is that the editor works alike. */
    static final class FakePreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        boolean refuseCommit = false;

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String fallback) {
            return values.containsKey(key) ? (String) values.get(key) : fallback;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> fallback) {
            return fallback;
        }

        @Override
        public int getInt(String key, int fallback) {
            return values.containsKey(key) ? (Integer) values.get(key) : fallback;
        }

        @Override
        public long getLong(String key, long fallback) {
            return fallback;
        }

        @Override
        public float getFloat(String key, float fallback) {
            return values.containsKey(key) ? (Float) values.get(key) : fallback;
        }

        @Override
        public boolean getBoolean(String key, boolean fallback) {
            return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        }

        @Override
        public Editor edit() {
            return new Editor() {
                /** Null stands for a removal, as the last call for a key wins in the real one. */
                private final Map<String, Object> changes = new HashMap<>();

                @Override
                public Editor putString(String key, String value) {
                    changes.put(key, value);
                    return this;
                }

                @Override
                public Editor putStringSet(String key, Set<String> value) {
                    return this;
                }

                @Override
                public Editor putInt(String key, int value) {
                    changes.put(key, value);
                    return this;
                }

                @Override
                public Editor putLong(String key, long value) {
                    changes.put(key, value);
                    return this;
                }

                @Override
                public Editor putFloat(String key, float value) {
                    changes.put(key, value);
                    return this;
                }

                @Override
                public Editor putBoolean(String key, boolean value) {
                    changes.put(key, value);
                    return this;
                }

                @Override
                public Editor remove(String key) {
                    changes.put(key, null);
                    return this;
                }

                @Override
                public Editor clear() {
                    values.clear();
                    return this;
                }

                @Override
                public boolean commit() {
                    if (refuseCommit) {
                        return false;
                    }
                    for (Map.Entry<String, Object> change : changes.entrySet()) {
                        if (change.getValue() == null) {
                            values.remove(change.getKey());
                        } else {
                            values.put(change.getKey(), change.getValue());
                        }
                    }
                    return true;
                }

                @Override
                public void apply() {
                    commit();
                }
            };
        }
    }
}
