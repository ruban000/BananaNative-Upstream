package dev.nxnative.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Games folders (android/README.md "Games folders"): folders the user picks in Add game, kept with a read permission (ACTION_OPEN_DOCUMENT_TREE,
 * takePersistableUriPermission), which the app looks through for games: when a folder is added, once when the app starts (a new process), and from Settings ›
 * Games folders › Look for new games (GamesFolder.Trigger). What a game is, the walk and what is kept between looks are GamesFolder's (no Android classes).
 * <ul>
 * <li>With All files access a folder on the phone's storage or an SD card is read by its path (GamesFolder.PathTree): its games are plain paths, as Add game by
 *     path gives them, and a dump folder is a game too.</li>
 * <li>Without it the folder is read through the provider (ProviderTree): each of its directories that holds game files gets one stand-in folder in app storage,
 *     picked/&lt;PickedPath.dirKey&gt;/, remembered with the tree, the directory's document ID and its path under the folder; a file's stand-in path is
 *     &lt;that folder&gt;/&lt;its name&gt;, found by name in its directory when it is opened (PickedNup), and the files beside it are its directory's
 *     ({@link #siblings}: Nup.companions). A dump folder cannot be read this way (thousands of RomFS files by path): the look says so.</li>
 * <li>A card is the same file when its path is the found one's, or names the same path under the same games folder (a stand-in, or a real path under the folder's
 *     path): such a card moves to the way the app reads the file now (its real path once All files access is on, a stand-in once it is off). A look never removes
 *     a card.</li>
 * </ul>
 */
final class GamesFolders {
    private GamesFolders() {}

    private static final String PREFS = "games_folders", LIST = "folders";
    /** What PickedNup keeps for a stand-in folder of a games folder's directory: tree:&lt;tree URI&gt;\n&lt;directory document ID&gt;\n&lt;its path under the folder&gt; */
    static final String TREE = "tree:";

    private static SharedPreferences prefs(Context c) { return Prefs.open(c, PREFS); }

    // ---- The folders ----

    /** The games folders (tree URIs), in the order they were added */
    static List<String> folders(Context c) {
        List<String> out = new ArrayList<>();
        for (String s : prefs(c).getString(LIST, "").split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private static void folders(Context c, List<String> l) { prefs(c).edit().putString(LIST, String.join("\n", l)).commit(); }

    /** The folder picker for a games folder */
    static Intent pickIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        return i;
    }

    /** A picked folder becomes a games folder: its read permission kept (IOException with a sentence when the place does not allow it); its tree URI */
    static String add(Context c, Uri tree) throws IOException {
        try {
            c.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            throw new IOException(Tx.s(R.string.games_folders_cannot_keep));
        }
        String t = tree.toString();
        List<String> l = folders(c);
        if (!l.contains(t)) { l.add(t); folders(c, l); }
        return t;
    }

    /** No longer a games folder: its permission goes back (unless a file picked alone is in it: that has its own); its games' cards stay */
    static void remove(Context c, String tree) {
        List<String> l = folders(c);
        l.remove(tree);
        folders(c, l);
        Background.LOOK.execute(() -> {
            GamesFolder.Seen s = GamesFolder.Seen.load(seenFile(c));
            s.forget(tree);
            s.save(seenFile(c));
        });
        try {
            c.getContentResolver().releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) { /* not held any more */ }
    }

    /** A games folder's name for the user: its path when it is on a local volume, else its last part */
    static String name(String tree) {
        Uri u = Uri.parse(tree);
        String p = PathUtil.treeToPath(u);
        if (p != null) return p;
        try {
            String id = DocumentsContract.getTreeDocumentId(u);
            int k = Math.max(id.lastIndexOf('/'), id.lastIndexOf(':'));
            return k >= 0 && k + 1 < id.length() ? id.substring(k + 1) : id;
        } catch (RuntimeException e) {
            return tree;
        }
    }

    /** A games folder's path, when the app may read it there (All files access) and it is on a local volume; null otherwise */
    static File realRoot(String tree) {
        if (!Environment.isExternalStorageManager()) return null;
        String p = PathUtil.treeToPath(Uri.parse(tree));
        File f = p == null ? null : new File(p);
        return f != null && f.isDirectory() && f.canRead() ? f : null;
    }

    // ---- Stand-in folders (read through the provider) ----

    static boolean isDir(String stored) { return stored != null && stored.startsWith(TREE); }

    private static String[] parts(String stored) {
        String[] p = stored.substring(TREE.length()).split("\n", -1);
        return p.length == 3 ? p : new String[] {p[0], p.length > 1 ? p[1] : "", ""};
    }

    /** The tree URI a stand-in folder is in */
    static String treeOf(String stored) { return parts(stored)[0]; }

    /** One entry of a directory as the provider lists it */
    private static final class Child {
        final String id, name;
        final boolean dir;
        final long size, mtime;

        Child(String id, String name, boolean dir, long size, long mtime) { this.id = id; this.name = name; this.dir = dir; this.size = size; this.mtime = mtime; }
    }

    /** Directories' entries by tree and document ID, with when they were listed (a card's checks open the same files again and again) */
    private static final Map<String, Object[]> listed = new HashMap<>();
    private static final long LISTED_MS = 30_000;

    /** A directory's entries, sorted by name: listed again after LISTED_MS, or at once when fresh; empty when it cannot be listed (gone, permission taken back) */
    private static List<Child> children(Context c, String tree, String dirId, boolean fresh) {
        String k = tree + "\n" + dirId;
        long now = SystemClock.elapsedRealtime();
        if (!fresh) synchronized (listed) {
            Object[] hit = listed.get(k);
            if (hit != null && now - (Long) hit[0] < LISTED_MS) { @SuppressWarnings("unchecked") List<Child> l = (List<Child>) hit[1]; return l; }
        }
        List<Child> out = list(c, tree, dirId);
        if (out == null) out = new ArrayList<>();
        synchronized (listed) { listed.put(k, new Object[] {now, out}); }
        return out;
    }

    /** null when the directory cannot be listed */
    private static List<Child> list(Context c, String tree, String dirId) {
        List<Child> out = new ArrayList<>();
        Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(tree), dirId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try (Cursor k = c.getContentResolver().query(u, cols, null, null, null)) {
            if (k == null) return null;
            while (k.moveToNext()) {
                String name = k.getString(1);
                if (name == null) continue;
                out.add(new Child(k.getString(0), name, DocumentsContract.Document.MIME_TYPE_DIR.equals(k.getString(2)), k.isNull(3) ? 0 : k.getLong(3), k.isNull(4) ? 0 : k.getLong(4)));
            }
        } catch (RuntimeException e) {
            return null;
        }
        out.sort((a, b) -> a.name.compareTo(b.name));
        return out;
    }

    /** The document URI of a file of a games folder's directory by its name; null when it is not there (moved, renamed, deleted) */
    static String documentOf(Context c, String stored, String name) {
        String[] p = parts(stored);
        for (int pass = 0; pass < 2; pass++)
            for (Child ch : children(c, p[0], p[1], pass == 1))
                if (!ch.dir && ch.name.equals(name)) return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(p[0]), ch.id).toString();
        return null;
    }

    /** The game files of a games folder's directory as stand-in paths in its stand-in folder (Nup.companions) */
    static List<File> siblings(Context c, String stored, File standInDir) {
        String[] p = parts(stored);
        List<File> out = new ArrayList<>();
        for (Child ch : children(c, p[0], p[1], false)) if (!ch.dir && Nup.extOf(ch.name) != null && !ch.name.startsWith(".")) out.add(new File(standInDir, ch.name));
        return out;
    }

    /** The stand-in folder of a games folder's directory: remembered (PickedNup) and made */
    private static File standInDir(Context c, String tree, String dirId, String rel) {
        String key = PickedPath.dirKey(tree, dirId);
        PickedNup.store(c, key, TREE + tree + "\n" + dirId + "\n" + rel);
        File d = new File(PickedPath.root(AppStorage.root(c)), key);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** A games folder read through the provider: the walk's listing by document IDs; a file's path is its stand-in */
    private static final class ProviderTree implements GamesFolder.Tree {
        final Context c;
        final String tree, rootId;
        final Map<String, List<Child>> kids = new HashMap<>();   // this look's listings: each directory listed once

        ProviderTree(Context c, String tree) {
            this.c = c;
            this.tree = tree;
            this.rootId = DocumentsContract.getTreeDocumentId(Uri.parse(tree));
        }

        private List<Child> kids(String id) {
            List<Child> l = kids.get(id);
            if (l == null) {
                l = GamesFolders.list(c, tree, id);
                if (l != null) synchronized (listed) { listed.put(tree + "\n" + id, new Object[] {SystemClock.elapsedRealtime(), l}); }
                kids.put(id, l);
            }
            return l;
        }

        @Override public GamesFolder.Entry root() { return new GamesFolder.Entry(name(tree), "", true, 0, 0, new File(name(tree)), tree, rootId); }

        @Override public List<GamesFolder.Entry> list(GamesFolder.Entry dir) {
            List<Child> l = kids((String) dir.handle);
            if (l == null) return null;
            File stand = null;
            List<GamesFolder.Entry> out = new ArrayList<>();
            for (Child ch : l) {
                String rel = dir.rel.isEmpty() ? ch.name : dir.rel + "/" + ch.name;
                if (stand == null && !ch.dir && Nup.extOf(ch.name) != null && PickedPath.nameProblem(ch.name) == null)
                    stand = standInDir(c, tree, (String) dir.handle, dir.rel);
                File f = !ch.dir && stand != null && Nup.extOf(ch.name) != null ? new File(stand, ch.name) : new File(ch.name);
                out.add(new GamesFolder.Entry(ch.name, rel, ch.dir, ch.size, ch.mtime, f, tree + "#" + ch.id + "|" + ch.size + "|" + ch.mtime, ch.id));
            }
            return out;
        }

        private Child child(List<Child> l, String name, boolean dir) {
            if (l != null) for (Child ch : l) if (ch.dir == dir && ch.name.equals(name)) return ch;
            return null;
        }

        @Override public GamesFolder.Dump dump(GamesFolder.Entry dir) {
            List<Child> l = kids((String) dir.handle);
            Child exefs = child(l, "exefs", true), base = child(l, "base", true);
            if (exefs != null && child(kids(exefs.id), "main.npdm", false) != null) return GamesFolder.Dump.NEEDS_ACCESS;
            Child be = base == null ? null : child(kids(base.id), "exefs", true);
            return be != null && child(kids(be.id), "main.npdm", false) != null ? GamesFolder.Dump.NEEDS_ACCESS : GamesFolder.Dump.NONE;
        }

        @Override public boolean pack(GamesFolder.Entry dir) { return child(kids((String) dir.handle), "pack.tsv", false) != null; }
    }

    // ---- The look ----

    static File factsFile(Context c) { return new File(c.getNoBackupFilesDir(), "games_folders_facts.tsv"); }

    static File seenFile(Context c) { return new File(c.getNoBackupFilesDir(), "games_folders_seen.tsv"); }

    /** What a look found to add, and what it could not (one sentence each) */
    static final class Outcome {
        final List<File> add = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        /** Games found in all (with a card or not) */
        int games;
        /** Cards that now read their file another way (a stand-in ↔ its real path) */
        int moved;
        /** Dump folders read through a folder's grant, which the game cannot read by path: {tree, document ID, name}; Add game offers to copy them (GameCopy.copyDump) */
        final List<String[]> copyable = new ArrayList<>();
    }

    /** The path a card's file has under a games folder ("a/b.nup"), null when it is not in it */
    private static String relIn(Context c, String tree, String treePath, Game g) {
        File f = new File(g.gameDir);
        String s = PickedNup.stored(c, f);
        if (isDir(s)) {
            String[] p = parts(s);
            return p[0].equals(tree) ? (p[2].isEmpty() ? "" : p[2] + "/") + f.getName() : null;
        }
        if (s != null || treePath == null) return null;
        String root = new File(treePath).getAbsolutePath(), path = f.getAbsolutePath();
        return path.equals(root) ? "" : path.startsWith(root + "/") ? path.substring(root.length() + 1) : null;
    }

    /**
     * Looks through the games folders (off the main thread: Background.LOOK): each folder's games (GamesFolder.scan, by path with All files access, else through
     * the provider), the files whose facts are kept not read again; the games without a card that the trigger adds (GamesFolder.adds), and what cannot be added
     * (an upper-case extension, a dump folder without All files access) once per file. trees: the folders to look through (null: all)
     */
    static Outcome look(Context c, GamesFolder.Trigger t, List<String> trees) {
        Outcome out = new Outcome();
        GamesFolder.Cache cache = GamesFolder.Cache.load(factsFile(c), BuildConfig.NX_COMMIT);
        GamesFolder.Seen seen = GamesFolder.Seen.load(seenFile(c));
        List<Game> cards = Game.load(c);
        Map<String, String> moves = new HashMap<>();   // a card's game path → the path it reads its file by now
        for (String tree : trees != null ? trees : folders(c)) {
            File real = realRoot(tree);
            GamesFolder.Tree tr;
            try {
                tr = real != null ? new GamesFolder.PathTree(real) : new ProviderTree(c, tree);
            } catch (RuntimeException e) {
                out.problems.add(Tx.s(R.string.games_folders_cannot_read, name(tree)));
                continue;
            }
            GamesFolder.Result r = GamesFolder.scan(tr, GamesFolder.DEPTH, cache::get);
            if (!r.listed) { out.problems.add(Tx.s(R.string.games_folders_cannot_read, name(tree))); continue; }
            String treePath = PathUtil.treeToPath(Uri.parse(tree));
            Map<String, Game> byRel = new HashMap<>();
            for (Game g : cards) {
                String rel = relIn(c, tree, treePath, g);
                if (rel != null) byRel.putIfAbsent(rel, g);
            }
            out.games += r.games.size();
            for (GamesFolder.Entry e : GamesFolder.pick(t, tree, r.games, seen, e -> {
                Game card = null;
                for (Game g : cards) if (g.gameDir.equals(e.file.getPath())) card = g;
                if (card == null) card = byRel.get(e.rel);
                if (card != null && !card.gameDir.equals(e.file.getPath())) moves.put(card.gameDir, e.file.getPath());   // the same file, read another way now
                return card != null;
            })) out.add.add(e.file);
            for (GamesFolder.Entry e : GamesFolder.pick(t, tree, r.wrongCase, seen, e -> false)) out.problems.add(Tx.s(R.string.err_nup_rename, e.rel));
            for (GamesFolder.Entry e : GamesFolder.pick(t, tree, r.needAccess, seen, e -> false)) {
                out.problems.add(Tx.s(R.string.games_folders_dump_needs_access, e.rel.isEmpty() ? name(tree) : e.rel));
                if (e.handle instanceof String) out.copyable.add(new String[] {tree, (String) e.handle, e.rel.isEmpty() ? name(tree) : e.rel});   // read through the grant: it can be copied
            }
        }
        cache.save(factsFile(c));
        seen.save(seenFile(c));
        if (!moves.isEmpty()) {
            synchronized (Game.class) {
                List<Game> list = Game.load(c);
                for (int i = 0; i < list.size(); i++) {
                    Game g = list.get(i);
                    String to = moves.get(g.gameDir);
                    if (to != null) { list.set(i, new Game(g.titleId, g.name, to, g.packDir, g.used)); out.moved++; }
                }
                Game.save(c, list);
            }
        }
        android.util.Log.i("nxnative", "Games folders (" + t + "): " + out.games + " game(s), " + out.add.size() + " to add, " + out.problems.size() + " problem(s), "
                + out.moved + " card(s) moved, " + cache.reads + " file(s) read");
        return out;
    }

    // ---- Settings › Folders (the games folders; a game's own folders from there) ----

    /** The Settings row's line: the folders' names, else what the row is for */
    static String summary(Context c) {
        List<String> names = new ArrayList<>();
        for (String t : folders(c)) names.add(name(t));
        return names.isEmpty() ? Tx.s(R.string.games_folders_value) : String.join(", ", names);
    }

    /** Settings › Folders: each games folder with Remove, then Add a games folder and Look for new games, then a game's own game, pack and save folders (Folders.dialog) */
    static void sheet(MainActivity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        android.app.AlertDialog[] d = new android.app.AlertDialog[1];
        List<String> l = folders(a);
        if (l.isEmpty()) Ui.add(box, Ui.small(a, Tx.s(R.string.games_folders_none)), 4);
        for (String t : l) {
            LinearLayout c = Ui.inner(a, R.color.bn_tint);
            TextView v = Ui.text(a, name(t), 15, R.color.bn_text, 600);
            v.setTextIsSelectable(true);
            c.addView(v);
            LinearLayout r = Ui.row(a);
            r.addView(Ui.button(a, Tx.s(R.string.common_remove), Ui.Kind.QUIET, x -> {
                d[0].dismiss();
                remove(a, t);
                android.widget.Toast.makeText(a, Tx.s(R.string.games_folders_removed, name(t)), android.widget.Toast.LENGTH_LONG).show();
                a.refreshSettings();
                sheet(a);
            }));
            Ui.add(c, r, 8);
            Ui.add(box, c, 12);
        }
        LinearLayout r = Ui.row(a);
        r.addView(Ui.button(a, Tx.s(R.string.games_folders_add), Ui.Kind.PRIMARY, x -> { d[0].dismiss(); a.pickGamesFolder(); }));
        if (!l.isEmpty()) {
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.setMarginStart(Ui.dp(a, 10));
            r.addView(Ui.button(a, Tx.s(R.string.games_folders_look), Ui.Kind.QUIET, x -> { d[0].dismiss(); a.lookForGames(GamesFolder.Trigger.LOOK, null); }), lp);
        }
        Ui.add(box, r, 12);
        Ui.add(box, Ui.small(a, Tx.s(R.string.games_folders_hint)), 16);
        Ui.add(box, Ui.button(a, Tx.s(R.string.common_game_pack_and_save_folders), Ui.Kind.QUIET, x -> { d[0].dismiss(); Folders.dialog(a); }), 16);   // one Folders entry in Settings
        d[0] = Ui.sheet(a, Tx.s(R.string.common_folders), box).setPositiveButton(Tx.s(R.string.common_done), null).show();
    }
}
