package com.ezvisuals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude agent shown in the side panel of the menu.
 * Talks to the Anthropic API with the player's own API key (console.anthropic.com).
 * The key is stored only in config/ezvisuals/assistant.json and is never part of saved configs.
 * The agent can PROPOSE module / setting changes; nothing is applied until the player presses Apply.
 */
public final class Ai {
    private Ai() {}

    public static final String[] MODEL_NAMES = {"Sonnet 5.5", "Haiku 5.5 (cheaper)", "Opus 5.5 (smartest)"};
    public static final String[] MODEL_SHORT = {"Sonnet", "Haiku", "Opus"};
    private static final String[] MODEL_IDS = {"claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5"};
    private static final Pattern CHANGES = Pattern.compile("<changes>(.*?)</changes>", Pattern.DOTALL);

    /** kind: 0 = player, 1 = Claude, 2 = info / error (not sent back to the API). raw = what the API sees. */
    public static final class Msg {
        public final int kind;
        public final String text, raw;
        Msg(int kind, String text, String raw) { this.kind = kind; this.text = text; this.raw = raw; }
        Msg(int kind, String text) { this(kind, text, text); }
    }

    /** One proposed change, already validated against the real modules. */
    public static final class Change {
        final Module m;
        final Setting s;   // null = enable / disable the module
        final double v;
        final boolean on;
        public final String label;
        Change(Module m, Setting s, double v, boolean on, String label) {
            this.m = m; this.s = s; this.v = v; this.on = on; this.label = label;
        }
    }

    public static final List<Msg> LOG = new CopyOnWriteArrayList<>();
    public static volatile List<Change> pending = Collections.emptyList();
    public static volatile boolean busy;
    public static int model = 0;
    private static String key = "";
    private static boolean loaded;

    // ---------------- key / settings ----------------
    private static Path file() { return Config.dir().resolve("assistant.json"); }

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("key")) key = o.get("key").getAsString();
            if (o.has("model")) model = Math.max(0, Math.min(MODEL_IDS.length - 1, o.get("model").getAsInt()));
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("key", key);
            o.addProperty("model", model);
            Files.writeString(file(), o.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public static boolean hasKey() { return !key.isEmpty(); }

    public static void setKey(String k) { key = k.trim(); save(); }

    public static void clearKey() { key = ""; save(); }

    public static void setModel(int m) { model = Math.max(0, Math.min(MODEL_IDS.length - 1, m)); save(); }

    public static void clearChat() { LOG.clear(); pending = Collections.emptyList(); }

    // ---------------- apply / dismiss proposed changes ----------------
    public static int applyPending() {
        List<Change> list = pending;
        pending = Collections.emptyList();
        int n = 0;
        for (Change ch : list) {
            if (ch.s == null) {
                ch.m.enabled = ch.on;
            } else {
                ch.s.set(ch.v);
                if (ch.s.type == Setting.BOOL) ch.s.anim = ch.s.b() ? 1f : 0f;
            }
            n++;
        }
        if (n > 0) LOG.add(new Msg(2, "Applied " + n + " change" + (n == 1 ? "" : "s") + "."));
        return n;
    }

    public static void dismissPending() { pending = Collections.emptyList(); }

    // ---------------- chat ----------------
    public static void send(String text) {
        if (busy || text == null || text.isBlank()) return;
        if (!hasKey()) { LOG.add(new Msg(2, "Set your API key first (Set API key button).")); return; }
        LOG.add(new Msg(0, text.trim()));
        pending = Collections.emptyList();
        busy = true;
        final String system = systemPrompt();
        final List<Msg> snap = new ArrayList<>(LOG);
        final String id = MODEL_IDS[model];
        final String k = key;
        Thread t = new Thread(() -> {
            try {
                String raw = call(k, id, system, snap);
                String shown = CHANGES.matcher(raw).replaceAll("").trim();
                if (shown.isEmpty()) shown = "Here are the changes I suggest:";
                LOG.add(new Msg(1, shown, raw));
                pending = parseChanges(raw);
            } catch (Exception e) {
                LOG.add(new Msg(2, e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                busy = false;
            }
        }, "ezvisuals-assistant");
        t.setDaemon(true);
        t.start();
    }

    private static String call(String apiKey, String modelId, String system, List<Msg> snap) throws IOException {
        List<Msg> hist = new ArrayList<>();
        for (Msg m : snap) if (m.kind != 2) hist.add(m);
        int from = Math.max(0, hist.size() - 16);
        while (from < hist.size() && hist.get(from).kind != 0) from++;
        JsonArray msgs = new JsonArray();
        for (int i = from; i < hist.size(); i++) {
            JsonObject o = new JsonObject();
            o.addProperty("role", hist.get(i).kind == 0 ? "user" : "assistant");
            o.addProperty("content", hist.get(i).raw);
            msgs.add(o);
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.addProperty("max_tokens", 1500);
        body.addProperty("system", system);
        body.add("messages", msgs);

        HttpURLConnection con = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout(15000);
        con.setReadTimeout(90000);
        con.setDoOutput(true);
        con.setRequestProperty("content-type", "application/json");
        con.setRequestProperty("x-api-key", apiKey);
        con.setRequestProperty("anthropic-version", "2023-06-01");
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = con.getResponseCode();
        InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
        String resp = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);

        JsonObject j = null;
        try { j = JsonParser.parseString(resp).getAsJsonObject(); } catch (Exception ignored) { }
        if (code >= 400) {
            String m = resp;
            if (j != null && j.has("error") && j.get("error").isJsonObject() && j.getAsJsonObject("error").has("message")) {
                m = j.getAsJsonObject("error").get("message").getAsString();
            }
            if (code == 401) m = "Invalid API key. " + m;
            throw new IOException("API " + code + ": " + m);
        }
        if (j == null || !j.has("content")) throw new IOException("Unexpected answer from the API");
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : j.getAsJsonArray("content")) {
            JsonObject b = e.getAsJsonObject();
            if (b.has("type") && "text".equals(b.get("type").getAsString())) sb.append(b.get("text").getAsString());
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "(empty answer)" : out;
    }

    // ---------------- parsing the <changes> block ----------------
    private static Module findModule(String id) {
        for (Module m : Modules.ALL) if (m.id.equals(id)) return m;
        return null;
    }

    private static double norm(Setting s, double v) {
        if (s.type == Setting.NUM && s.step > 0) v = s.min + Math.round((v - s.min) / s.step) * s.step;
        return Math.max(s.min, Math.min(s.max, v));
    }

    private static String describe(Setting s, double v) {
        v = norm(s, v);
        if (s.type == Setting.BOOL) return v > 0.5 ? "On" : "Off";
        if (s.type == Setting.MODE) return s.options[Math.max(0, Math.min(s.options.length - 1, (int) Math.round(v)))];
        return s.step >= 1 ? String.valueOf((int) Math.round(v)) : String.format(Locale.ROOT, "%.2f", v);
    }

    private static double parseValue(Setting s, JsonElement v) {
        if (s.type == Setting.BOOL) {
            if (v.getAsJsonPrimitive().isBoolean()) return v.getAsBoolean() ? 1 : 0;
            String t = v.getAsString().trim().toLowerCase(Locale.ROOT);
            return (t.equals("true") || t.equals("on") || t.equals("1") || t.equals("yes")) ? 1 : 0;
        }
        if (s.type == Setting.MODE && v.getAsJsonPrimitive().isString()) {
            String t = v.getAsString().trim();
            for (int i = 0; i < s.options.length; i++) if (s.options[i].equalsIgnoreCase(t)) return i;
            return Double.parseDouble(t);
        }
        return v.getAsDouble();
    }

    private static List<Change> parseChanges(String raw) {
        List<Change> out = new ArrayList<>();
        Matcher mt = CHANGES.matcher(raw);
        if (!mt.find()) return out;
        try {
            JsonArray arr = JsonParser.parseString(mt.group(1).trim()).getAsJsonArray();
            for (JsonElement e : arr) {
                if (out.size() >= 12) break;
                try {
                    JsonObject o = e.getAsJsonObject();
                    Module m = findModule(o.get("module").getAsString());
                    if (m == null) continue;
                    if (o.has("setting")) {
                        Setting s = null;
                        for (Setting x : m.settings) if (x.id.equals(o.get("setting").getAsString())) s = x;
                        if (s == null) continue;
                        double v = parseValue(s, o.get("value"));
                        out.add(new Change(m, s, v, false,
                                m.name + ": " + s.name + "  " + s.display() + " > " + describe(s, v)));
                    } else if (o.has("enabled")) {
                        boolean on = o.get("enabled").getAsBoolean();
                        out.add(new Change(m, null, 0, on, m.name + ": " + (on ? "turn ON" : "turn OFF")));
                    }
                } catch (Exception ignored) {
                    // skip a broken entry, keep the rest
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---------------- what the agent knows about the mod ----------------
    private static String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Claude, an agent built into the menu of the EzVisuals mod (Fabric, Minecraft 1.21.4). ")
          .append("Help the player use the mod: explain modules and settings, suggest good setups, answer Minecraft questions, ")
          .append("and write Lua scripts for the mod's script API. Answer in the language the player writes in. ")
          .append("The chat panel is small: keep answers short, use plain text without markdown headings, tables or bold, ")
          .append("and put code on plain lines. If you are not sure about something in the mod, say so instead of guessing.\n\n")
          .append("CHANGING SETTINGS: you can propose changes, the player applies them with a button. When the player asks you to set ")
          .append("something up or change something, explain in one or two short sentences, then put this block at the very end of your reply:\n")
          .append("<changes>[{\"module\":\"<module id>\",\"enabled\":true},{\"module\":\"<module id>\",\"setting\":\"<setting id>\",\"value\":<number, true/false or option name>}]</changes>\n")
          .append("Use only module ids and setting ids from the list below, at most 12 items. Never say a change is already applied: ")
          .append("the player must press Apply. Do not add the block when no change is requested.\n\n")
          .append("MODULES (id | category | name | state | description | settings as id \"name\" (range or options) = current value):\n");
        List<String> open = new ArrayList<>();
        for (Module m : Modules.ALL) {
            if (m.expanded) open.add(m.name);
            sb.append("- ").append(m.id).append(" | ").append(m.category).append(" | ").append(m.name)
              .append(" | ").append(m.enabled ? "ON" : "OFF").append(" | ").append(m.desc);
            if (!m.settings.isEmpty()) {
                sb.append(" | ");
                boolean first = true;
                for (Setting s : m.settings) {
                    if (!first) sb.append("; ");
                    first = false;
                    sb.append(s.id).append(" \"").append(s.name).append("\"");
                    if (s.type == Setting.BOOL) sb.append(" (on/off)");
                    else if (s.type == Setting.NUM) sb.append(" (").append(s.min).append("..").append(s.max).append(")");
                    else sb.append(" (").append(String.join("/", s.options)).append(")");
                    sb.append(" = ").append(s.display());
                }
            }
            sb.append("\n");
        }
        sb.append("\nModule(s) the player currently has open in the menu: ")
          .append(open.isEmpty() ? "none" : String.join(", ", open)).append(".\n");
        sb.append("Menu: Right Shift opens it. Chat commands: .hud (move HUD blocks), .cfg save/load/delete/list/reset <name>.\n");
        try {
            Path api = Scripts.dir().resolve("API.md");
            if (Files.exists(api)) {
                String s = Files.readString(api, StandardCharsets.UTF_8);
                if (s.length() > 14000) s = s.substring(0, 14000);
                sb.append("\nLUA SCRIPT API (scripts go into config/ezvisuals/scripts):\n").append(s).append("\n");
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }
}
