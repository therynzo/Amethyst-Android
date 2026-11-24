package net.kdt.pojavlaunch.nick;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nick Launcher: built-in content browser. Searches Modrinth and installs mods, resource packs and
 * shaders straight into the chosen profile's game folder. Required mod dependencies are installed too.
 */
public final class NickContentBrowser {
    private static final String API = "https://api.modrinth.com/v2";
    private static final int GREEN = 0xFF5EC84F;
    private static final int PANEL = 0xFF16161B;
    private static final String[] TYPE_LABELS = {"Mods", "Resource Packs", "Shaders"};
    private static final String[] TYPE_FACET = {"mod", "resourcepack", "shader"};
    private static final String[] TYPE_DIR = {"mods", "resourcepacks", "shaderpacks"};

    private static final class Hit {
        String id, title, author, desc;
        long downloads;
    }

    private final Activity act;
    private final Dialog dialog;
    private final List<String> profileKeys = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private int type = 0;
    private Spinner profileSpinner;
    private EditText searchBox;
    private TextView status;
    private BaseAdapter adapter;
    private int searchToken = 0;

    private NickContentBrowser(Activity a) {
        act = a;
        dialog = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    public static void show(Activity activity) {
        try {
            new NickContentBrowser(activity).build();
        } catch (Throwable t) {
            Toast.makeText(activity, "Could not open the browser: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable box(int color, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private Button greenButton(String text) {
        Button b = new Button(act);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFF0A2A0A);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(13);
        b.setBackground(box(GREEN, 0xFF8EE07F, 6));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private void build() {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0D);
        root.setPadding(dp(14), dp(8), dp(14), dp(8));

        // header
        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(act);
        title.setText("Mod Browser");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = greenButton("Close");
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        root.addView(head);

        // target profile + type chips
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(4));
        TextView to = new TextView(act);
        to.setText("Install to: ");
        to.setTextColor(0xFFB0B0B8);
        row.addView(to);
        profileSpinner = new Spinner(act);
        List<String> names = new ArrayList<>();
        String current = net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF.getString(
                net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        int sel = 0;
        for (Map.Entry<String, MinecraftProfile> e : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
            MinecraftProfile p = e.getValue();
            String n = (p.name != null && !p.name.isEmpty()) ? p.name : "(unnamed)";
            names.add(n + "  •  " + (p.lastVersionId == null ? "?" : p.lastVersionId));
            profileKeys.add(e.getKey());
            if (e.getKey().equals(current)) sel = profileKeys.size() - 1;
        }
        ArrayAdapter<String> pa = new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, names);
        profileSpinner.setAdapter(pa);
        profileSpinner.setSelection(sel);
        profileSpinner.setBackground(box(PANEL, 0x665EC84F, 8));
        row.addView(profileSpinner, new LinearLayout.LayoutParams(0, dp(36), 1f));

        final List<Button> chips = new ArrayList<>();
        for (int i = 0; i < TYPE_LABELS.length; i++) {
            final int idx = i;
            Button c = new Button(act);
            c.setText(TYPE_LABELS[i]);
            c.setAllCaps(false);
            c.setTextSize(12);
            c.setMinHeight(0);
            c.setMinimumHeight(0);
            c.setPadding(dp(10), 0, dp(10), 0);
            c.setOnClickListener(v -> {
                type = idx;
                styleChips(chips);
                search();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            lp.setMargins(dp(6), 0, 0, 0);
            row.addView(c, lp);
            chips.add(c);
        }
        styleChips(chips);
        root.addView(row);

        // search
        LinearLayout sr = new LinearLayout(act);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        searchBox = new EditText(act);
        searchBox.setHint("Search (leave empty for popular)");
        searchBox.setHintTextColor(0xFF77777F);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setSingleLine(true);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setBackground(box(PANEL, 0x665EC84F, 8));
        searchBox.setPadding(dp(10), 0, dp(10), 0);
        searchBox.setOnEditorActionListener((v, a, ev) -> {
            search();
            return true;
        });
        sr.addView(searchBox, new LinearLayout.LayoutParams(0, dp(38), 1f));
        Button go = greenButton("Search");
        go.setOnClickListener(v -> search());
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        glp.setMargins(dp(6), 0, 0, 0);
        sr.addView(go, glp);
        root.addView(sr);

        status = new TextView(act);
        status.setTextColor(0xFFB0B0B8);
        status.setTextSize(12);
        status.setPadding(0, dp(4), 0, dp(4));
        root.addView(status);

        ListView list = new ListView(act);
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        adapter = new HitAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(root);
        dialog.show();
        search();
    }

    private void styleChips(List<Button> chips) {
        for (int i = 0; i < chips.size(); i++) {
            Button c = chips.get(i);
            boolean on = i == type;
            c.setTextColor(on ? 0xFF0A2A0A : Color.WHITE);
            c.setBackground(on ? box(GREEN, 0xFF8EE07F, 6) : box(PANEL, 0x665EC84F, 6));
        }
    }

    // ------------------------------------------------------------------ profile helpers

    private MinecraftProfile selectedProfile() {
        int i = profileSpinner.getSelectedItemPosition();
        if (i < 0 || i >= profileKeys.size()) return LauncherProfiles.getCurrentProfile();
        return LauncherProfiles.mainProfileJson.profiles.get(profileKeys.get(i));
    }

    private String mcVersionOf(MinecraftProfile p) {
        String id = p.lastVersionId == null ? "" : p.lastVersionId;
        if (id.startsWith("latest")) return null;
        String loader = NickModDeps.detectLoader(id);
        Matcher m = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?").matcher(id);
        String first = null, last = null;
        while (m.find()) {
            if (first == null) first = m.group();
            last = m.group();
        }
        return ("fabric".equals(loader) || "quilt".equals(loader)) ? last : first;
    }

    // ------------------------------------------------------------------ search

    private void search() {
        final int token = ++searchToken;
        final String q = searchBox.getText().toString().trim();
        final MinecraftProfile p = selectedProfile();
        final String mc = mcVersionOf(p);
        final String loader = NickModDeps.detectLoader(p.lastVersionId == null ? "" : p.lastVersionId);
        final int t = type;
        status.setText("Searching...");
        hits.clear();
        adapter.notifyDataSetChanged();
        new Thread(() -> {
            List<Hit> found = new ArrayList<>();
            String err = null;
            try {
                StringBuilder facets = new StringBuilder("[[\"project_type:" + TYPE_FACET[t] + "\"]");
                if (mc != null) facets.append(",[\"versions:").append(mc).append("\"]");
                if (t == 0 && loader != null) {
                    String cat = "quilt".equals(loader) ? "quilt" : loader;
                    facets.append(",[\"categories:").append(cat).append("\"");
                    if ("quilt".equals(loader)) facets.append(",\"categories:fabric\"");
                    facets.append("]");
                }
                facets.append("]");
                String url = API + "/search?query=" + Uri.encode(q) + "&limit=30&index=" + (q.isEmpty() ? "downloads" : "relevance")
                        + "&facets=" + Uri.encode(facets.toString());
                String body = NickModDeps.httpGet(url);
                if (body == null) err = "No internet or Modrinth is unreachable.";
                else {
                    JsonArray arr = new JsonParser().parse(body).getAsJsonObject().getAsJsonArray("hits");
                    for (JsonElement e : arr) {
                        JsonObject o = e.getAsJsonObject();
                        Hit h = new Hit();
                        h.id = o.get("project_id").getAsString();
                        h.title = o.get("title").getAsString();
                        h.author = o.has("author") ? o.get("author").getAsString() : "";
                        h.desc = o.has("description") ? o.get("description").getAsString() : "";
                        h.downloads = o.has("downloads") ? o.get("downloads").getAsLong() : 0;
                        found.add(h);
                    }
                }
            } catch (Throwable ex) {
                err = "Search failed.";
            }
            final String fErr = err;
            act.runOnUiThread(() -> {
                if (token != searchToken) return;
                hits.clear();
                hits.addAll(found);
                adapter.notifyDataSetChanged();
                String scope = (mc != null ? "Minecraft " + mc : "any version")
                        + (t == 0 && loader != null ? " / " + loader : "");
                status.setText(fErr != null ? fErr : (found.isEmpty() ? "Nothing found for " + scope
                        : found.size() + " results for " + scope));
            });
        }, "NickBrowserSearch").start();
    }

    // ------------------------------------------------------------------ install

    private void install(final Hit h, final Button btn) {
        final MinecraftProfile p = selectedProfile();
        final String mc = mcVersionOf(p);
        final String loader = NickModDeps.detectLoader(p.lastVersionId == null ? "" : p.lastVersionId);
        final int t = type;
        final File dir = new File(Tools.getGameDirPath(p), TYPE_DIR[t]);
        btn.setEnabled(false);
        btn.setText("...");
        new Thread(() -> {
            String result;
            try {
                List<String> done = new ArrayList<>();
                installProject(h.id, h.title, t, mc, loader, dir, 0, done);
                result = done.isEmpty() ? "Already installed" : "Installed: " + join(done);
                final boolean ok = true;
                act.runOnUiThread(() -> {
                    btn.setText("Installed");
                    Toast.makeText(act, "Installed: " + join(done), Toast.LENGTH_LONG).show();
                });
            } catch (Throwable ex) {
                final String m = ex.getMessage() == null ? "failed" : ex.getMessage();
                act.runOnUiThread(() -> {
                    btn.setEnabled(true);
                    btn.setText("Install");
                    Toast.makeText(act, "Could not install " + h.title + ": " + m, Toast.LENGTH_LONG).show();
                });
            }
        }, "NickBrowserInstall").start();
    }

    private void installProject(String projectId, String titleHint, int t, String mc, String loader,
                                File dir, int depth, List<String> done) throws Exception {
        String base = API + "/project/" + Uri.encode(projectId) + "/version";
        String loaders = null;
        if (t == 0 && loader != null) loaders = "quilt".equals(loader) ? "[\"quilt\",\"fabric\"]" : "[\"" + loader + "\"]";
        String body = null;
        // try strict (loader + version), then relax the version filter for packs
        String[] attempts = {
                filters(loaders, mc),
                t == 0 ? null : filters(null, null)
        };
        JsonArray versions = null;
        for (String f : attempts) {
            if (f == null) continue;
            body = NickModDeps.httpGet(base + f);
            if (body == null) throw new Exception("no internet");
            versions = new JsonParser().parse(body).getAsJsonArray();
            if (versions.size() > 0) break;
        }
        if (versions == null || versions.size() == 0) throw new Exception("no compatible version for this profile");
        JsonObject chosen = versions.get(0).getAsJsonObject();
        for (JsonElement v : versions) {
            if ("release".equals(optStr(v.getAsJsonObject(), "version_type"))) {
                chosen = v.getAsJsonObject();
                break;
            }
        }
        JsonObject file = null;
        for (JsonElement f : chosen.getAsJsonArray("files")) {
            JsonObject fo = f.getAsJsonObject();
            if (fo.has("primary") && fo.get("primary").getAsBoolean()) {
                file = fo;
                break;
            }
            if (file == null) file = fo;
        }
        if (file == null) throw new Exception("no file");
        String sha1 = null;
        if (file.has("hashes") && file.getAsJsonObject("hashes").has("sha1"))
            sha1 = file.getAsJsonObject("hashes").get("sha1").getAsString();
        String fileName = new File(file.get("filename").getAsString()).getName();
        if (downloadTo(dir, file.get("url").getAsString(), fileName, sha1)) done.add(titleHint != null ? titleHint : fileName);

        if (t == 0 && depth < 2 && chosen.has("dependencies")) {
            for (JsonElement d : chosen.getAsJsonArray("dependencies")) {
                JsonObject dep = d.getAsJsonObject();
                if (!"required".equals(optStr(dep, "dependency_type"))) continue;
                String pid = optStr(dep, "project_id");
                if (pid == null) continue;
                try {
                    installProject(pid, null, t, mc, loader, dir, depth + 1, done);
                } catch (Exception ignored) { // a missing dependency must not undo the main install
                }
            }
        }
    }

    private static String filters(String loaders, String mc) {
        StringBuilder sb = new StringBuilder();
        char sep = '?';
        if (loaders != null) {
            sb.append(sep).append("loaders=").append(Uri.encode(loaders));
            sep = '&';
        }
        if (mc != null) sb.append(sep).append("game_versions=").append(Uri.encode("[\"" + mc + "\"]"));
        return sb.toString();
    }

    private static String optStr(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : null;
    }

    private static boolean downloadTo(File dir, String address, String fileName, String sha1) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("cannot create " + dir.getName() + " folder");
        File target = new File(dir, fileName);
        if (target.exists()) return false;
        File tmp = new File(dir, fileName + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "NickLauncher/1.0 (content browser)");
            if (c.getResponseCode() != 200) throw new Exception("download error " + c.getResponseCode());
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                }
            }
            if (sha1 != null) {
                StringBuilder hex = new StringBuilder();
                for (byte b : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
                if (!hex.toString().equalsIgnoreCase(sha1)) {
                    tmp.delete();
                    throw new Exception("corrupted download");
                }
            }
            if (!tmp.renameTo(target)) throw new Exception("could not save file");
            return true;
        } catch (Exception e) {
            tmp.delete();
            throw e;
        } finally {
            c.disconnect();
        }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ list rows

    private final class HitAdapter extends BaseAdapter {
        @Override public int getCount() { return hits.size(); }
        @Override public Object getItem(int i) { return hits.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            final Hit h = hits.get(i);
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setBackground(box(PANEL, 0x335EC84F, 10));

            LinearLayout text = new LinearLayout(act);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(act);
            t.setText(h.title + (h.author.isEmpty() ? "" : "  by " + h.author));
            t.setTextColor(Color.WHITE);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextSize(14);
            t.setSingleLine(true);
            TextView d = new TextView(act);
            d.setText(h.desc);
            d.setTextColor(0xFFB0B0B8);
            d.setTextSize(12);
            d.setMaxLines(2);
            TextView n = new TextView(act);
            n.setText(String.format(Locale.US, "%,d downloads", h.downloads));
            n.setTextColor(0xFF7FE06F);
            n.setTextSize(11);
            text.addView(t);
            text.addView(d);
            text.addView(n);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final Button b = greenButton("Install");
            b.setOnClickListener(v -> install(h, b));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            lp.setMargins(dp(8), 0, 0, 0);
            row.addView(b, lp);
            return row;
        }
    }
}
