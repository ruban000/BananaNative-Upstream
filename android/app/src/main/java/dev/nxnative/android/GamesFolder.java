package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Games folders (android/README.md "Games folders"), the part with no Android classes, so the JVM test (GamesFolderJvmTest) runs it: what a game is in a folder,
 * the walk, what is kept between looks, and when a look runs. GamesFolders (Android) keeps the folders, lists them by path or through the provider, and adds
 * the cards.
 * <ul>
 * <li>A game: a .nup, a plaintext .nsp or an .nx file (Nup.extOf: lower-case extensions), or a dump folder (Pack.npdmOf). A file that is a part of a game beside it
 *     (a DLC, an older version, a base-less .nup or .nx of its game: {@link #companion}) is that game's, not one of its own; a .nup or .nx with a base package
 *     stands for itself. A file with an upper-case extension is reported (rename it), as Add game does.</li>
 * <li>The walk: the folder and the folders under it, {@link #DEPTH} levels deep; not hidden ones (".name"), not &lt;title ID&gt;-work folders (extraction and work
 *     files), not pack folders (pack.tsv), not inside a dump folder.</li>
 * <li>What each file is ({@link Facts}: its packages, or why it cannot be read) is kept by the file's identity (path or document, size, time) and the app build
 *     ({@link Cache}): a look at a folder whose files did not change lists folders and reads no file.</li>
 * <li>When: {@link Trigger}: a folder added, the app's start (once per process: {@link #startDue}), Look for new games. A start adds a game only when its file is
 *     new or changed since the last look ({@link Seen}), so a card the player removed stays removed; the other two add every game that has no card.</li>
 * </ul>
 */
final class GamesFolder {
    private GamesFolder() {}

    /** How many folder levels under a games folder are looked through (Game/Updates/DLC/, Switch2/&lt;game&gt;/base/ fit) */
    static final int DEPTH = 4;

    /** What starts a look through the games folders: exactly these three (no timer, no look when Home comes back) */
    enum Trigger { ADDED, START, LOOK }

    private static final AtomicBoolean started = new AtomicBoolean();

    /** Whether this process's start look is still to run: true once per process */
    static boolean startDue() { return started.compareAndSet(false, true); }

    /** Whether a found game that has no card is added: at the start only when its file is new or changed since a look last saw it; when added or asked, always */
    static boolean adds(Trigger t, boolean newOrChanged) { return t != Trigger.START || newOrChanged; }

    /**
     * Of what a look found in a games folder (found: its games, or its files to report), those it adds or reports: no card (hasCard) and the trigger's rule
     * ({@link #adds}). Every one is marked seen with its size and time
     */
    static List<Entry> pick(Trigger t, String folder, List<Entry> found, Seen seen, java.util.function.Predicate<Entry> hasCard) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : found) {
            boolean fresh = seen.isNew(folder, e);
            seen.mark(folder, e);
            if (!hasCard.test(e) && adds(t, fresh)) out.add(e);
        }
        return out;
    }

    // ---- What a listing gives ----

    /** A file or folder of a games folder: its name, its path under the folder ("" for the folder), the path the app reads it by (its real path, or a stand-in) */
    static final class Entry {
        final String name, rel;
        final boolean dir;
        final long size, mtime;
        final File file;
        /** What identifies its content: path or document, size, time */
        final String identity;
        /** The tree's own handle (a document ID), null for a path */
        final Object handle;

        Entry(String name, String rel, boolean dir, long size, long mtime, File file, String identity, Object handle) {
            this.name = name; this.rel = rel; this.dir = dir; this.size = size; this.mtime = mtime; this.file = file; this.identity = identity; this.handle = handle;
        }

        @Override public String toString() { return rel.isEmpty() ? name : rel; }
    }

    /** A dump folder as a game: one read by path, one the app cannot read by path (provider only: a dump needs All files access or a copy), or none */
    enum Dump { NONE, GAME, NEEDS_ACCESS }

    /** A games folder as a listing gives it: by path (PathTree) or through the provider (GamesFolders) */
    interface Tree {
        Entry root();

        /** The folder's entries, null when it cannot be listed */
        List<Entry> list(Entry dir);

        /** Whether a folder is a dump folder */
        Dump dump(Entry dir);

        /** Whether a folder is a pack folder (pack.tsv): not looked into */
        boolean pack(Entry dir);
    }

    /** A folder by its path (with All files access, and the JVM test) */
    static class PathTree implements Tree {
        final File root;

        PathTree(File root) { this.root = root.getAbsoluteFile(); }

        @Override public Entry root() { return new Entry(root.getName(), "", true, 0, 0, root, root.getPath(), null); }

        @Override public List<Entry> list(Entry dir) {
            File[] l = dir.file.listFiles();
            if (l == null) return null;
            List<Entry> out = new ArrayList<>();
            for (File f : l) {
                boolean d = f.isDirectory();
                long size = d ? 0 : f.length(), time = d ? 0 : f.lastModified();
                out.add(new Entry(f.getName(), dir.rel.isEmpty() ? f.getName() : dir.rel + "/" + f.getName(), d, size, time, f, f.getPath() + "|" + size + "|" + time, null));
            }
            return out;
        }

        @Override public Dump dump(Entry dir) { return Pack.npdmOf(dir.file) != null ? Dump.GAME : Dump.NONE; }

        @Override public boolean pack(Entry dir) { return new File(dir.file, "pack.tsv").isFile(); }
    }

    // ---- What a file is ----

    /** What the rule needs of a game file: whether it has a base, its newest package (title, version), its add-ons; or why it cannot be read */
    static final class Facts {
        final String error;
        final boolean base, hasNewest;
        final long titleId, version;
        final long[] addons;

        Facts(String error, boolean base, boolean hasNewest, long titleId, long version, long[] addons) {
            this.error = error; this.base = base; this.hasNewest = hasNewest; this.titleId = titleId; this.version = version; this.addons = addons;
        }

        /** The file's facts from its package records (Nup.peek: the superblock and records of a .nup; an NSP's and an .nx's own checks) */
        static Facts read(File f) {
            try {
                Nup me = Nup.peek(f);
                Nup.Pkg n = me.newest();
                List<Long> ad = new ArrayList<>();
                for (Nup.Pkg p : me.packages) if (p.kind == Nup.ADDON) ad.add(p.titleId);
                long[] a = new long[ad.size()];
                for (int i = 0; i < a.length; i++) a[i] = ad.get(i);
                return new Facts(null, me.base() != null, n != null, n == null ? 0 : n.titleId, n == null ? 0 : n.version, a);
            } catch (IOException | RuntimeException e) {
                String m = e.getMessage();
                return new Facts(m == null || m.isEmpty() ? e.toString() : m, false, false, 0, 0, new long[0]);
            }
        }

        /** One line, as the cache keeps it */
        String line() {
            if (error != null) return "err\t" + error.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
            StringBuilder a = new StringBuilder();
            for (long x : addons) a.append(a.length() == 0 ? "" : ",").append(Long.toHexString(x));
            return "ok\t" + (base ? 1 : 0) + "\t" + (hasNewest ? 1 : 0) + "\t" + Long.toHexString(titleId) + "\t" + version + "\t" + a;
        }

        /** A line of line(); null when it is not one */
        static Facts parse(String s) {
            String[] c = s.split("\t", -1);
            try {
                if (c.length == 2 && c[0].equals("err")) return new Facts(c[1], false, false, 0, 0, new long[0]);
                if (c.length != 6 || !c[0].equals("ok")) return null;
                long[] a = c[5].isEmpty() ? new long[0] : Arrays.stream(c[5].split(",")).mapToLong(x -> Long.parseUnsignedLong(x, 16)).toArray();
                return new Facts(null, c[1].equals("1"), c[2].equals("1"), Long.parseUnsignedLong(c[3], 16), Long.parseLong(c[4]), a);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * Whether a file is a part of a game that another file of its folder stands for, so that only that one is a game (it finds the rest beside it:
     * Nup.companions): a DLC NSP; an NSP of a version that a newer NSP of the game outranks, or of a game with a .nup or .nx beside it that has its base; a .nup or
     * .nx with no base package (an update or DLC of its own) beside a .nup or .nx of its game that has one. A .nup or .nx with a base package stands for itself,
     * whatever else is beside it; a file that cannot be read is a game (Add game says why). folder: every game file of the folder by name, with facts of each
     */
    static boolean companion(String name, Map<String, Facts> folder) {
        boolean nup = Nup.container(name);   // a .nup or an .nx
        if (!nup && !name.endsWith(Nup.EXT_NSP)) return false;
        Facts me = folder.get(name);
        if (me == null || me.error != null) return false;   // unreadable: listed, so that Add game says why
        if (nup && me.base) return false;
        if (!me.hasNewest && !nup) return true;   // a DLC NSP: it belongs to a game beside it, or to none (then nothing is lost: it is no game)
        for (Map.Entry<String, Facts> e : folder.entrySet()) {
            String g = e.getKey();
            Facts o = e.getValue();
            if (g.equals(name) || g.startsWith(".") || o == null || o.error != null || !o.hasNewest) continue;
            boolean gnup = Nup.container(g);
            if (!gnup && !g.endsWith(Nup.EXT_NSP)) continue;
            if (!me.hasNewest) {   // a .nup of DLC only: a part of a game beside it that has that DLC's application, else listed (Add game says why it is no game)
                for (long a : me.addons) {
                    long idx = a - o.titleId - 0x1000;
                    if (idx > 0 && idx <= 2000) return true;
                }
                continue;
            }
            if (o.titleId != me.titleId) continue;   // another game
            if (gnup || nup) { if (gnup && o.base) return true; continue; }
            if (o.version > me.version || o.version == me.version && g.compareTo(name) < 0) return true;
        }
        return false;
    }

    /** Whether a folder name is never looked into: hidden, or a &lt;title ID&gt;-work folder (extraction and work files) */
    static boolean skipped(String name) { return name.startsWith(".") || name.toLowerCase(Locale.ROOT).matches("[0-9a-f]{16}-work"); }

    // ---- The walk ----

    /** What a look found: the games (files and dump folders), files to rename (an upper-case extension), dump folders the app cannot read by path */
    static final class Result {
        final List<Entry> games = new ArrayList<>(), wrongCase = new ArrayList<>(), needAccess = new ArrayList<>();
        /** Whether the folder itself could be listed (false: gone, or its permission taken back) */
        boolean listed;
    }

    /**
     * The games under a games folder: the folder itself when it is a dump folder, else its games and those of the folders under it, depth levels deep (0: the folder
     * only, as Add game on a picked folder). facts: each game file's facts (a Cache's get, or Facts.read)
     */
    static Result scan(Tree t, int depth, Function<Entry, Facts> facts) {
        Result r = new Result();
        Entry root = t.root();
        List<Entry> kids = t.list(root);
        r.listed = kids != null;
        if (kids == null) return r;
        Dump d = t.dump(root);
        if (d == Dump.GAME) { r.games.add(root); return r; }
        if (d == Dump.NEEDS_ACCESS) { r.needAccess.add(root); return r; }
        walk(t, root, kids, 0, depth, facts, r);
        return r;
    }

    private static void walk(Tree t, Entry dir, List<Entry> kids, int level, int depth, Function<Entry, Facts> facts, Result r) {
        kids = new ArrayList<>(kids);
        kids.sort(Comparator.comparing(e -> e.name));
        List<Entry> here = new ArrayList<>(), files = new ArrayList<>(), subs = new ArrayList<>();
        for (Entry e : kids) {
            if (e.name == null || e.name.startsWith(".")) continue;
            if (e.dir) {
                if (skipped(e.name) || t.pack(e)) continue;
                Dump d = t.dump(e);
                if (d == Dump.GAME) here.add(e);
                else if (d == Dump.NEEDS_ACCESS) r.needAccess.add(e);
                else if (level < depth) subs.add(e);
            } else if (Nup.extOf(e.name) != null) files.add(e);
            else if (Nup.rightExt(e.name) != null) r.wrongCase.add(e);
        }
        Map<String, Facts> folder = new LinkedHashMap<>();
        for (Entry e : files) folder.put(e.name, facts.apply(e));
        for (Entry e : files) if (!companion(e.name, folder)) here.add(e);
        here.sort(Comparator.comparing(e -> e.name));
        r.games.addAll(here);
        for (Entry s : subs) {
            List<Entry> l = t.list(s);
            if (l != null) walk(t, s, l, level + 1, depth, facts, r);
        }
    }

    // ---- Kept between looks ----

    /**
     * Each game file's facts by its identity (path or document, size, time) and the app build: a file is read again only when one of them changed. Kept in one
     * file (GamesFolders: the app's no-backup folder), the entries a look used first, at most MAX
     */
    static final class Cache {
        static final int MAX = 4096;
        private static final String MAGIC = "# games folder facts 1";
        final String version;
        private final Map<String, Facts> map = new LinkedHashMap<>();
        private final Set<String> used = new HashSet<>();
        /** How many files were read (not found kept) since it was made: a look at unchanged folders reads none */
        int reads;
        private boolean changed;

        Cache(String version) { this.version = version == null ? "" : version; }

        String key(Entry e) { return version + "|" + e.identity; }

        /** The entry's facts: kept, else read (and kept) */
        synchronized Facts get(Entry e, Function<File, Facts> reader) {
            String k = key(e);
            used.add(k);
            Facts f = map.get(k);
            if (f != null) return f;
            reads++;
            f = reader.apply(e.file);
            map.put(k, f);
            changed = true;
            return f;
        }

        Facts get(Entry e) { return get(e, Facts::read); }

        static Cache load(File f, String version) {
            Cache c = new Cache(version);
            if (f == null || !f.isFile()) return c;
            try {
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                if (lines.isEmpty() || !lines.get(0).equals(MAGIC)) return c;
                for (String l : lines.subList(1, lines.size())) {
                    int tab = l.indexOf("\t\t");
                    if (tab <= 0) continue;
                    Facts x = Facts.parse(l.substring(tab + 2));
                    if (x != null) c.map.put(l.substring(0, tab), x);
                }
            } catch (IOException | RuntimeException e) {
                // read again: nothing is lost but time
            }
            return c;
        }

        /** Written when something was read: what this look used first, then the rest, at most MAX; entries of another app build go */
        synchronized void save(File f) {
            if (f == null || !changed) return;
            StringBuilder b = new StringBuilder(MAGIC).append('\n');
            int n = 0;
            for (int pass = 0; pass < 2; pass++)
                for (Map.Entry<String, Facts> e : map.entrySet()) {
                    if (n >= MAX) break;
                    boolean u = used.contains(e.getKey());
                    if (pass == 0 != u || !e.getKey().startsWith(version + "|")) continue;
                    String k = e.getKey().replace('\n', ' ');
                    b.append(k).append("\t\t").append(e.getValue().line()).append('\n');
                    n++;
                }
            writeAtomically(f, b.toString());
            changed = false;
        }
    }

    /**
     * The games a look has dealt with, by games folder and path under it, with the size and time they had: the start look adds a game only when its file is not
     * here or changed (a card the player removed stays removed; a file still being copied at one start is added at a later one)
     */
    static final class Seen {
        private static final String MAGIC = "# games folder seen 1";
        private final Map<String, String> map = new LinkedHashMap<>();
        private boolean changed;

        private static String key(String folder, Entry e) { return folder + "\t" + e.rel; }

        private static String value(Entry e) { return e.size + "\t" + e.mtime; }

        /** Whether the file is new or changed since a look last dealt with it */
        synchronized boolean isNew(String folder, Entry e) { return !value(e).equals(map.get(key(folder, e))); }

        synchronized void mark(String folder, Entry e) {
            String k = key(folder, e), v = value(e);
            if (!v.equals(map.put(k, v))) changed = true;
        }

        /** A folder no longer a games folder: what was seen in it goes */
        synchronized void forget(String folder) {
            if (map.keySet().removeIf(k -> k.startsWith(folder + "\t"))) changed = true;
        }

        static Seen load(File f) {
            Seen s = new Seen();
            if (f == null || !f.isFile()) return s;
            try {
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                if (lines.isEmpty() || !lines.get(0).equals(MAGIC)) return s;
                for (String l : lines.subList(1, lines.size())) {
                    String[] c = l.split("\t", -1);
                    if (c.length == 4) s.map.put(c[0] + "\t" + c[1], c[2] + "\t" + c[3]);
                }
            } catch (IOException | RuntimeException e) {
                // a start then adds what has no card again: Add game's summary says so
            }
            return s;
        }

        synchronized void save(File f) {
            if (f == null || !changed) return;
            StringBuilder b = new StringBuilder(MAGIC).append('\n');
            for (Map.Entry<String, String> e : map.entrySet()) b.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            writeAtomically(f, b.toString());
            changed = false;
        }
    }

    private static void writeAtomically(File f, String text) {
        try {
            File dir = f.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) {
                Files.deleteIfExists(f.toPath());
                if (!tmp.renameTo(f)) Files.deleteIfExists(tmp.toPath());
            }
        } catch (IOException | RuntimeException e) {
            // not kept: the next look reads or adds again
        }
    }
}
