package net.kdt.pojavlaunch.nick;

import android.app.Activity;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Nick Launcher: mod dependency helper.
 *
 * Before the game starts, every mod in the selected profile's mods folder is inspected
 * (fabric.mod.json, quilt.mod.json, mods.toml, neoforge.mods.toml). Required mods that are missing
 * are looked up on Modrinth and the player is asked once: install them all, ignore, or cancel.
 * After installing, the new mods are scanned too (dependencies of dependencies, up to 3 rounds).
 * Any failure falls back to launching the game normally.
 */
public final class NickModDeps {
    private static final String API = "https://api.modrinth.com/v2";
    private static final String PREF_IGNORED = "nick_ignored_deps";
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    /** Provided by the game / loader itself, never a separate download. */
    private static final Set<String> BUILTIN = new HashSet<>(Arrays.asList(
            "minecraft", "java", "forge", "neoforge", "fml", "javafml", "lowcodefml", "mclanguage",
            "fabricloader", "fabric-loader", "fabric_loader", "quilt_loader", "quilt_base", "quilt-loader",
            "mixinextras", "mixin"));

    /** Mod id -> Modrinth slug, where the two differ. */
    private static final Map<String, String> SLUGS = new HashMap<>();

    static {
        SLUGS.put("architectury", "architectury-api");
        SLUGS.put("yet_another_config_lib_v3", "yacl");
        SLUGS.put("forgeconfigapiport", "forge-config-api-port");
        SLUGS.put("cloth_config", "cloth-config");
        SLUGS.put("cloth-config2", "cloth-config");
        SLUGS.put("owo", "owo-lib");
        SLUGS.put("fabric-api", "fabric-api");
        SLUGS.put("quilted_fabric_api", "qsl");
        SLUGS.put("qsl", "qsl");
        SLUGS.put("geckolib3", "geckolib");
        SLUGS.put("playerAnimator", "playeranimator");
        SLUGS.put("ftbquests", "ftb-quests-forge");
    }

    private static final class Need {
        String id;
        final Set<String> neededBy = new TreeSet<>();
        String title;
        String fileName;
        String url;
        String sha1;
        String error; // null when it can be installed
    }

    private static final class Info {
        final Set<String> provided = new HashSet<>();
        final Set<String> required = new HashSet<>();
    }

    private NickModDeps() {
    }

    // ------------------------------------------------------------------ entry point

    /** Call instead of launching directly. {@code launch} runs on the UI thread when it is OK to start. */
    public static void checkBeforeLaunch(final Activity activity, final Runnable launch) {
        if (!BUSY.compareAndSet(false, true)) return;
        new Thread(() -> {
            List<Need> needs = null;
            String loader = null;
            String mc = null;
            File modsDir = null;
            try {
                MinecraftProfile profile = LauncherProfiles.getCurrentProfile();
                String versionId = profile.lastVersionId == null ? "" : profile.lastVersionId;
                loader = detectLoader(versionId);
                if (loader != null) {
                    modsDir = new File(Tools.getGameDirPath(profile), "mods");
                    mc = detectMcVersion(versionId, loader);
                    needs = scan(modsDir, loader, ignoredIds());
                    if (!needs.isEmpty()) {
                        for (Need n : needs) resolve(n, loader, mc);
                    }
                }
            } catch (Throwable t) {
                needs = null;
            }
            if (needs == null || needs.isEmpty()) {
                proceed(activity, launch);
                return;
            }
            final List<Need> fNeeds = needs;
            final String fLoader = loader;
            final String fMc = mc;
            final File fMods = modsDir;
            runOnUi(activity, launch, () -> showMissingDialog(activity, launch, fNeeds, fLoader, fMc, fMods));
        }, "NickModDeps").start();
    }

    private static void proceed(Activity activity, Runnable launch) {
        BUSY.set(false);
        try {
            activity.runOnUiThread(launch);
        } catch (Throwable ignored) {
        }
    }

    private static void runOnUi(Activity activity, Runnable fallbackLaunch, Runnable action) {
        try {
            if (activity.isFinishing()) {
                BUSY.set(false);
                return;
            }
            activity.runOnUiThread(() -> {
                try {
                    action.run();
                } catch (Throwable t) { // e.g. window already gone: never block the game
                    proceed(activity, fallbackLaunch);
                }
            });
        } catch (Throwable t) {
            BUSY.set(false);
        }
    }

    // ------------------------------------------------------------------ dialogs

    private static void showMissingDialog(final Activity activity, final Runnable launch, final List<Need> needs,
                                          final String loader, final String mc, final File modsDir) {
        StringBuilder sb = new StringBuilder("Some of your mods need other mods to work:\n\n");
        int installable = 0;
        for (Need n : needs) {
            sb.append("• ").append(n.title != null ? n.title : n.id);
            if (n.error != null) sb.append("  (not found automatically)");
            else installable++;
            sb.append("\n    needed by: ").append(join(n.neededBy)).append("\n");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("Missing mod requirements")
                .setMessage(sb.toString())
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> BUSY.set(false))
                .setNeutralButton("Ignore & launch", (d, w) -> {
                    rememberIgnored(needs);
                    proceed(activity, launch);
                });
        if (installable > 0) {
            b.setPositiveButton("Install " + installable, (d, w) -> installAll(activity, launch, needs, loader, mc, modsDir));
        }
        b.show();
    }

    private static void installAll(final Activity activity, final Runnable launch, final List<Need> firstNeeds,
                                   final String loader, final String mc, final File modsDir) {
        final AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("Installing required mods")
                .setMessage("Starting...")
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            final List<String> failed = new ArrayList<>();
            int installed = 0;
            Set<String> attempted = new HashSet<>();
            List<Need> todo = firstNeeds;
            try {
                for (int round = 0; round < 3 && !todo.isEmpty(); round++) {
                    for (Need n : todo) {
                        if (!attempted.add(n.id)) continue;
                        if (n.error != null) {
                            failed.add(n.title != null ? n.title : n.id);
                            continue;
                        }
                        final String msg = "Downloading " + (n.title != null ? n.title : n.id) + "...";
                        setMessage(activity, progress, msg);
                        if (download(n, modsDir)) installed++;
                        else failed.add(n.title != null ? n.title : n.id);
                    }
                    // dependencies of the mods we just installed
                    List<Need> next = scan(modsDir, loader, ignoredIds());
                    for (Need n : next) {
                        if (!attempted.contains(n.id)) resolve(n, loader, mc);
                    }
                    todo = next;
                }
            } catch (Throwable t) {
                failed.add("unexpected error");
            }
            final int fInstalled = installed;
            runOnUi(activity, launch, () -> {
                try {
                    progress.dismiss();
                } catch (Throwable ignored) {
                }
                if (failed.isEmpty()) {
                    Toast.makeText(activity, "Installed " + fInstalled + " required mod(s)", Toast.LENGTH_SHORT).show();
                    proceed(activity, launch);
                } else {
                    new AlertDialog.Builder(activity)
                            .setTitle("Some mods could not be installed")
                            .setMessage("Installed: " + fInstalled + "\nFailed: " + join(new TreeSet<>(failed))
                                    + "\n\nYou can add them manually to the mods folder.")
                            .setCancelable(false)
                            .setNegativeButton("Cancel", (d, w) -> BUSY.set(false))
                            .setPositiveButton("Launch anyway", (d, w) -> proceed(activity, launch))
                            .show();
                }
            });
        }, "NickModDepsInstall").start();
    }

    private static void setMessage(Activity activity, AlertDialog dialog, String msg) {
        try {
            activity.runOnUiThread(() -> dialog.setMessage(msg));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ scanning

    private static String detectLoader(String versionId) {
        String v = versionId.toLowerCase(Locale.ROOT);
        if (v.contains("neoforge")) return "neoforge";
        if (v.contains("forge")) return "forge";
        if (v.contains("quilt")) return "quilt";
        if (v.contains("fabric")) return "fabric";
        return null;
    }

    private static String detectMcVersion(String versionId, String loader) {
        try {
            String vanilla = Tools.getSelectedVanillaMcVer();
            if (vanilla != null && !vanilla.isEmpty()) return vanilla;
        } catch (Throwable ignored) {
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+\\.\\d+(\\.\\d+)?").matcher(versionId);
        String first = null, last = null;
        while (m.find()) {
            if (first == null) first = m.group();
            last = m.group();
        }
        return ("fabric".equals(loader) || "quilt".equals(loader)) ? last : first;
    }

    /** Returns required-but-missing mods of every jar in the mods folder. */
    private static List<Need> scan(File modsDir, String loader, Set<String> ignored) {
        List<Need> result = new ArrayList<>();
        File[] jars = modsDir.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) return result;

        Set<String> provided = new HashSet<>();
        Map<String, Set<String>> requiredBy = new LinkedHashMap<>();
        for (File jar : jars) {
            Info info = readInfo(jar, loader);
            for (String p : info.provided) provided.add(p.toLowerCase(Locale.ROOT));
            for (String r : info.required) {
                String id = normalizeId(r, loader);
                Set<String> by = requiredBy.get(id);
                if (by == null) {
                    by = new TreeSet<>();
                    requiredBy.put(id, by);
                }
                by.add(jar.getName());
            }
        }
        for (Map.Entry<String, Set<String>> e : requiredBy.entrySet()) {
            String id = e.getKey();
            if (BUILTIN.contains(id) || provided.contains(id) || ignored.contains(id)) continue;
            Need n = new Need();
            n.id = id;
            n.neededBy.addAll(e.getValue());
            result.add(n);
        }
        return result;
    }

    private static String normalizeId(String raw, String loader) {
        String id = raw.toLowerCase(Locale.ROOT).trim();
        boolean fabricApiModule = id.equals("fabric") || id.equals("fabric-api-base")
                || (id.startsWith("fabric-") && id.matches(".*-v\\d+$"));
        if (fabricApiModule) id = "fabric-api";
        if ("quilt".equals(loader) && id.equals("fabric-api")) id = "quilted_fabric_api";
        return id;
    }

    private static Info readInfo(File jar, String loader) {
        Info info = new Info();
        try (ZipFile zf = new ZipFile(jar)) {
            boolean fabricLike = "fabric".equals(loader) || "quilt".equals(loader);
            String fabricJson = readEntry(zf, "fabric.mod.json");
            if (fabricJson != null) parseFabric(fabricJson, info, "fabric".equals(loader) || "quilt".equals(loader));
            String quiltJson = readEntry(zf, "quilt.mod.json");
            if (quiltJson != null) parseQuilt(quiltJson, info, "quilt".equals(loader));
            String neoToml = readEntry(zf, "META-INF/neoforge.mods.toml");
            if (neoToml != null) parseToml(neoToml, info, "neoforge".equals(loader));
            String forgeToml = readEntry(zf, "META-INF/mods.toml");
            if (forgeToml != null) parseToml(forgeToml, info, "forge".equals(loader) || ("neoforge".equals(loader) && neoToml == null));
            if (fabricLike && fabricJson == null && quiltJson == null) return info;
        } catch (Throwable ignored) {
        }
        return info;
    }

    private static String readEntry(ZipFile zf, String name) {
        try {
            ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                String s = new String(out.toByteArray(), StandardCharsets.UTF_8);
                return s.startsWith("﻿") ? s.substring(1) : s;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void parseFabric(String json, Info info, boolean relevant) {
        try {
            JsonObject o = new JsonParser().parse(json).getAsJsonObject();
            String id = str(o, "id");
            if (id != null) info.provided.add(id);
            JsonElement provides = o.get("provides");
            if (provides != null && provides.isJsonArray()) {
                for (JsonElement p : provides.getAsJsonArray()) {
                    if (p.isJsonPrimitive()) info.provided.add(p.getAsString());
                }
            }
            JsonElement depends = o.get("depends");
            if (relevant && depends != null && depends.isJsonObject()) {
                for (Map.Entry<String, JsonElement> d : depends.getAsJsonObject().entrySet()) {
                    info.required.add(d.getKey());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void parseQuilt(String json, Info info, boolean relevant) {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            JsonObject o = root.getAsJsonObject("quilt_loader");
            if (o == null) return;
            String id = str(o, "id");
            if (id != null) info.provided.add(id);
            JsonElement provides = o.get("provides");
            if (provides != null && provides.isJsonArray()) {
                for (JsonElement p : provides.getAsJsonArray()) {
                    if (p.isJsonPrimitive()) info.provided.add(p.getAsString());
                    else if (p.isJsonObject() && str(p.getAsJsonObject(), "id") != null)
                        info.provided.add(str(p.getAsJsonObject(), "id"));
                }
            }
            JsonElement depends = o.get("depends");
            if (relevant && depends != null && depends.isJsonArray()) {
                for (JsonElement d : depends.getAsJsonArray()) {
                    if (d.isJsonPrimitive()) {
                        info.required.add(d.getAsString());
                    } else if (d.isJsonObject()) {
                        JsonObject dep = d.getAsJsonObject();
                        boolean optional = dep.has("optional") && dep.get("optional").isJsonPrimitive()
                                && dep.get("optional").getAsBoolean();
                        if (optional || dep.has("unless")) continue;
                        String depId = str(dep, "id");
                        if (depId != null && !depId.contains(":")) info.required.add(depId);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Minimal reader for the parts of (neo)forge mods.toml that matter here. */
    private static void parseToml(String text, Info info, boolean relevant) {
        boolean inMods = false;
        boolean inDep = false;
        String depId = null;
        Boolean mandatory = null;
        String type = null;
        String side = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                if (inDep && relevant && depId != null && isRequiredDep(mandatory, type, side)) info.required.add(depId);
                inMods = line.startsWith("[[mods]]");
                inDep = line.startsWith("[[dependencies.");
                depId = null;
                mandatory = null;
                type = null;
                side = null;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String val = stripComment(line.substring(eq + 1)).trim();
            String sval = unquote(val);
            if (inMods && key.equals("modId")) info.provided.add(sval);
            if (inDep) {
                if (key.equals("modId")) depId = sval;
                else if (key.equals("mandatory")) mandatory = val.startsWith("true");
                else if (key.equals("type")) type = sval.toLowerCase(Locale.ROOT);
                else if (key.equals("side")) side = sval.toUpperCase(Locale.ROOT);
            }
        }
        if (inDep && relevant && depId != null && isRequiredDep(mandatory, type, side)) info.required.add(depId);
    }

    private static boolean isRequiredDep(Boolean mandatory, String type, String side) {
        if ("SERVER".equals(side)) return false;
        if (type != null) return type.equals("required");
        return mandatory == null || mandatory;
    }

    private static String stripComment(String s) {
        boolean inQuote = false;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == q) inQuote = false;
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                q = c;
            } else if (c == '#') {
                return s.substring(0, i);
            }
        }
        return s;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : null;
    }

    // ------------------------------------------------------------------ Modrinth

    private static void resolve(Need n, String loader, String mc) {
        try {
            String slug = SLUGS.containsKey(n.id) ? SLUGS.get(n.id) : n.id;
            String loaders = "quilt".equals(loader) ? "[\"quilt\",\"fabric\"]" : "[\"" + loader + "\"]";

            String body = httpGet(API + "/project/" + Uri.encode(slug));
            String projectKey = slug;
            String title = null;
            if (body != null) {
                JsonObject p = new JsonParser().parse(body).getAsJsonObject();
                title = str(p, "title");
                String projType = str(p, "project_type");
                if (projType != null && !projType.equals("mod")) body = null;
            }
            if (body == null) { // slug differs from the mod id: search
                String q = API + "/search?query=" + Uri.encode(n.id) + "&limit=5&facets="
                        + Uri.encode("[[\"project_type:mod\"]]");
                String res = httpGet(q);
                projectKey = null;
                if (res != null) {
                    JsonArray hits = new JsonParser().parse(res).getAsJsonObject().getAsJsonArray("hits");
                    String want = alnum(n.id);
                    for (JsonElement h : hits) {
                        JsonObject ho = h.getAsJsonObject();
                        String hs = alnum(str(ho, "slug"));
                        String ht = alnum(str(ho, "title"));
                        if (hs.contains(want) || want.contains(hs) || ht.contains(want)) {
                            projectKey = str(ho, "project_id");
                            title = str(ho, "title");
                            break;
                        }
                    }
                }
            }
            if (projectKey == null) {
                n.error = "not found";
                return;
            }
            n.title = title;

            String url = API + "/project/" + Uri.encode(projectKey) + "/version?loaders=" + Uri.encode(loaders)
                    + (mc != null ? "&game_versions=" + Uri.encode("[\"" + mc + "\"]") : "");
            String vBody = httpGet(url);
            if (vBody == null) {
                n.error = "no network";
                return;
            }
            JsonArray versions = new JsonParser().parse(vBody).getAsJsonArray();
            if (versions.size() == 0) {
                n.error = "no compatible version";
                return;
            }
            JsonObject chosen = versions.get(0).getAsJsonObject();
            for (JsonElement v : versions) { // prefer a stable release
                if ("release".equals(str(v.getAsJsonObject(), "version_type"))) {
                    chosen = v.getAsJsonObject();
                    break;
                }
            }
            JsonArray files = chosen.getAsJsonArray("files");
            JsonObject file = null;
            for (JsonElement f : files) {
                JsonObject fo = f.getAsJsonObject();
                if (fo.has("primary") && fo.get("primary").getAsBoolean()) {
                    file = fo;
                    break;
                }
                if (file == null) file = fo;
            }
            if (file == null) {
                n.error = "no file";
                return;
            }
            n.url = str(file, "url");
            n.fileName = str(file, "filename");
            JsonObject hashes = file.has("hashes") ? file.getAsJsonObject("hashes") : null;
            n.sha1 = hashes != null ? str(hashes, "sha1") : null;
            if (n.url == null || n.fileName == null) n.error = "no file";
            else n.error = null;
        } catch (Throwable t) {
            n.error = "lookup failed";
        }
    }

    private static String alnum(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String httpGet(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "NickLauncher/1.0 (mod dependency helper)");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean download(Need n, File modsDir) {
        HttpURLConnection c = null;
        File tmp = null;
        try {
            if (!modsDir.exists() && !modsDir.mkdirs()) return false;
            File target = new File(modsDir, new File(n.fileName).getName());
            if (target.exists()) return true;
            tmp = new File(modsDir, target.getName() + ".part");
            c = (HttpURLConnection) new URL(n.url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "NickLauncher/1.0 (mod dependency helper)");
            if (c.getResponseCode() != 200) return false;
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                    sha.update(buf, 0, len);
                }
            }
            if (n.sha1 != null) {
                StringBuilder hex = new StringBuilder();
                for (byte b : sha.digest()) hex.append(String.format("%02x", b));
                if (!hex.toString().equalsIgnoreCase(n.sha1)) {
                    tmp.delete();
                    return false; // corrupted download
                }
            }
            return tmp.renameTo(target);
        } catch (Throwable t) {
            if (tmp != null) tmp.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ prefs / util

    private static Set<String> ignoredIds() {
        try {
            return new HashSet<>(LauncherPreferences.DEFAULT_PREF.getStringSet(PREF_IGNORED, new HashSet<>()));
        } catch (Throwable t) {
            return new HashSet<>();
        }
    }

    private static void rememberIgnored(List<Need> needs) {
        try {
            Set<String> all = ignoredIds();
            for (Need n : needs) all.add(n.id);
            LauncherPreferences.DEFAULT_PREF.edit().putStringSet(PREF_IGNORED, all).apply();
        } catch (Throwable ignored) {
        }
    }

    private static String join(Set<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }
}
