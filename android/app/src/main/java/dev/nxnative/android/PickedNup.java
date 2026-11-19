package dev.nxnative.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * A .nup used where it is, without All files access: the user picks it with the system file picker (ACTION_OPEN_DOCUMENT), the app keeps the read permission
 * (takePersistableUriPermission: it lasts across restarts until the file is moved or deleted, or the user takes it back) and reads the file through a descriptor
 * the provider opens (ContentResolver.openFileDescriptor), never through a /storage path. Nothing is copied.
 * <ul>
 * <li>The launcher names the game by its stand-in path (PickedPath: &lt;app storage&gt;/picked/&lt;key&gt;/&lt;name&gt;), which this class's Nup.Opener resolves
 *     to the document in every process (NxApp installs it): the launcher's checks, the readiness check and the packer's Java side read it as any .nup.</li>
 * <li>The game's process opens a descriptor and gives the host fd:&lt;n&gt;/&lt;name&gt; in place of the path (GameActivity: DM_ROMFS, the add-on links); the
 *     host duplicates it for each image it opens (nup_image.h). The packer's process opens one too and gives the tools DM_NUP_FD=&lt;n&gt;:&lt;stand-in path&gt;
 *     (dm/tools/nup.py reads the stand-in path through it).</li>
 * <li>The provider's descriptor must be a regular file (pread at any offset): the phone's storage and SD cards give one; a cloud provider's pipe is refused with a
 *     sentence.</li>
 * </ul>
 * A folder dump is not read this way: the game opens thousands of RomFS files by path, which would each need a descriptor from the provider. Folders are copied into
 * the app (GameCopy) or read with All files access.
 * <p>
 * A games folder's files (GamesFolders, without All files access) are read the same way: each directory of the folder that holds game files has one stand-in folder,
 * picked/&lt;PickedPath.dirKey&gt;/, kept here as tree:&lt;tree URI&gt;\n&lt;directory document ID&gt;\n&lt;its path in the folder&gt;; a file is found by its
 * name in its directory when it is opened, its read permission is the folder's, and the files beside it are its directory's ({@link #siblings}, for
 * Nup.companions): the host gets a descriptor of each file that holds a part of the game ({@link #forHost}), the tools one DM_NUP_FD line each ({@link #toolsFd}).
 */
final class PickedNup implements Nup.Opener {
    private static final String PREFS = "picked";
    private final Context app;
    private final File root;   // app storage, once: owns() is asked for every .nup path

    private PickedNup(Context c) {
        app = c.getApplicationContext();
        root = AppStorage.root(app);
    }

    /** Every process: picked .nup files open through this (NxApp.onCreate) */
    static void install(Context c) { Nup.opener = new PickedNup(c); }

    private static SharedPreferences prefs(Context c) { return Prefs.open(c, PREFS); }

    /** What the app keeps for a stand-in path: a picked document's URI, or a games folder directory's (GamesFolders.TREE...); null: none */
    static String stored(Context c, File f) {
        String k = PickedPath.keyOf(AppStorage.root(c), f);
        return k == null ? null : prefs(c).getString(k, null);
    }

    /** Keeps what a stand-in folder stands for under its key (GamesFolders: a games folder's directory) */
    static void store(Context c, String key, String value) {
        SharedPreferences p = prefs(c);
        if (!value.equals(p.getString(key, null))) p.edit().putString(key, value).commit();
    }

    /** The document URI of a stand-in path (a games folder's file: found by its name in its directory), null when f is not one the app knows, or is gone */
    static String uriOf(Context c, File f) {
        String s = stored(c, f);
        return GamesFolders.isDir(s) ? GamesFolders.documentOf(c, s, f.getName()) : s;
    }

    static boolean is(Context c, File f) { return stored(c, f) != null; }

    static boolean is(Context c, String gameDir) { return gameDir != null && is(c, new File(gameDir)); }

    @Override public boolean owns(File f) {
        String k = PickedPath.keyOf(root, f);
        return k != null && prefs(app).getString(k, null) != null;
    }

    /** A games folder's file: the game files of its directory (Nup.companions); null for a file picked alone */
    @Override public java.util.List<File> siblings(File f) {
        String s = stored(app, f);
        return GamesFolders.isDir(s) ? GamesFolders.siblings(app, s, f.getAbsoluteFile().getParentFile()) : null;
    }

    @Override public Nup.Source open(File f) throws IOException {
        ParcelFileDescriptor pfd = descriptor(app, f);
        try {
            StructStat st = Os.fstat(pfd.getFileDescriptor());
            ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            return new Nup.Source(in.getChannel(), st.st_size, uriOf(app, f) + "|" + st.st_size + "|" + st.st_mtime, in);
        } catch (ErrnoException e) {
            pfd.close();
            throw new IOException(f.getName() + " cannot be read (" + e.getMessage() + ")");
        }
    }

    /** A read descriptor of the picked document (a regular file); an IOException with a plain sentence when it cannot be opened */
    static ParcelFileDescriptor descriptor(Context c, File f) throws IOException {
        if (!is(c, f)) throw new IOException(f.getName() + " is not a file this app was given.");
        String u = uriOf(c, f);
        if (u == null) throw new Lost(f.getName(), "it is no longer in its games folder");
        ParcelFileDescriptor pfd;
        try {
            pfd = c.getContentResolver().openFileDescriptor(Uri.parse(u), "r");
        } catch (FileNotFoundException | SecurityException | IllegalArgumentException | IllegalStateException e) {
            throw new Lost(f.getName(), e.getMessage());
        }
        if (pfd == null) throw new Lost(f.getName(), "the provider gave no descriptor");
        try {
            StructStat st = Os.fstat(pfd.getFileDescriptor());
            if (!OsConstants.S_ISREG(st.st_mode)) {
                pfd.close();
                throw new Streamed(f.getName());
            }
        } catch (ErrnoException e) {
            pfd.close();
            throw new IOException(f.getName() + " cannot be read (" + e.getMessage() + ")");
        }
        return pfd;
    }

    /** The provider streams the file (a cloud folder): the app must read a .nup at any position, so it can only be copied */
    static final class Streamed extends IOException {
        Streamed(String name) {
            super(name + " is in a place that only streams files (a cloud folder?), and the game reads a .nup at any position. Copy it into the app, or put it on the "
                    + "phone's storage or an SD card and pick it there.");
        }
    }

    /** The app can no longer open a picked .nup: moved, renamed, deleted, or its permission was taken back */
    static final class Lost extends IOException {
        Lost(String name, String detail) {
            super("The app can no longer open " + name + ". It may have been moved, renamed or deleted. Pick it again." + (detail == null ? "" : " (" + detail + ")"));
        }
    }

    /**
     * A descriptor for the host or the packer in this process, kept open for the process's life (detached: the native side owns it; the host duplicates it per image,
     * nup_image.h). The process ends with the game or the pack job, which closes it
     */
    static int detachedFd(Context c, File f) throws IOException { return descriptor(c, f).detachFd(); }

    /**
     * A launcher path (a stand-in, or &lt;stand-in&gt;:&lt;selector&gt;/...) with the stand-in of the file it names replaced by fd:&lt;n&gt;/&lt;name&gt; for the host
     * (PickedPath.forHost); one detached descriptor per file, kept in fds by path (a games folder's game and the update or DLC files beside it). Anything else as
     * it is
     */
    static String forHost(Context c, String spec, java.util.Map<String, Integer> fds) throws IOException {
        Nup.Ref r;
        try { r = Nup.parse(spec); } catch (IllegalArgumentException e) { return spec; }
        if (r == null) return spec;
        File f = new File(r.path);
        if (!is(c, f)) return spec;
        Integer fd = fds.get(f.getAbsolutePath());
        if (fd == null) fds.put(f.getAbsolutePath(), fd = detachedFd(c, f));
        return PickedPath.forHost(spec, f, fd);
    }

    /**
     * The tools' DM_NUP_FD for a picked game (dm/tools/nup.py): &lt;fd&gt;:&lt;stand-in path&gt; of the game, then one line for each file beside it that holds a
     * part of it (a games folder read through the provider: Nup.companions), each descriptor opened once and kept in held (by path) for the process's life. ""
     * for a game that is not picked
     */
    static String toolsFd(Context c, File game, java.util.Map<String, ParcelFileDescriptor> held) throws IOException {
        if (!Nup.picked(game)) return "";
        java.util.List<File> files = new java.util.ArrayList<>(java.util.List.of(game));
        for (Nup n : Nup.companions(game)) if (Nup.picked(n.file)) files.add(n.file);
        StringBuilder b = new StringBuilder();
        synchronized (held) {
            for (File f : files) {
                ParcelFileDescriptor p = held.get(f.getAbsolutePath());
                if (p == null) held.put(f.getAbsolutePath(), p = descriptor(c, f));
                b.append(b.length() == 0 ? "" : "\n").append(p.getFd()).append(':').append(f.getAbsolutePath());
            }
        }
        return b.toString();
    }

    /** null when the picked .nup opens, else what to tell the user (Lost's sentence, or why it cannot be read) */
    static String problem(Context c, File f) {
        try (ParcelFileDescriptor p = descriptor(c, f)) {
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /** The picker for a .nup to use in place: its result goes to {@link #picked} */
    static Intent pickIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    }

    /**
     * A picked document → its stand-in path: the read permission kept, the name checked (a lower-case .nup), the document opened once (a regular file), remembered
     * under its key. IOException with a sentence when it cannot be used in place
     */
    static File picked(Context c, Uri u) throws IOException {
        ContentResolver cr = c.getContentResolver();
        String name = displayName(cr, u);
        String bad = PickedPath.nameProblem(name);
        if (bad != null) throw new IOException(bad);
        try {
            cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            throw new IOException("The place " + name + " is in does not let the app keep reading it after a restart. Put the .nup on the phone's storage or an SD card and "
                    + "pick it there, or copy it into the app.");
        }
        String key = PickedPath.key(u.toString());
        File f = PickedPath.standIn(AppStorage.root(c), key, name);
        File dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot make " + dir + ".");
        prefs(c).edit().putString(key, u.toString()).commit();
        try (ParcelFileDescriptor p = descriptor(c, f)) {
            return f;
        } catch (IOException e) {
            forget(c, f);
            throw e;
        }
    }

    /** Remove from list: the permission goes back unless another entry uses the same document (the stand-in folder, with Prepare's work files, stays) */
    static void forget(Context c, File f) {
        String u = stored(c, f), k = PickedPath.keyOf(AppStorage.root(c), f);
        if (u == null || GamesFolders.isDir(u)) return;   // a games folder's file: the folder's permission stays (GamesFolders.remove)
        for (Game g : Game.load(c)) if (!g.gameDir.equals(f.getPath()) && u.equals(stored(c, new File(g.gameDir)))) return;
        prefs(c).edit().remove(k).commit();
        try {
            c.getContentResolver().releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) { /* not held any more */ }
    }

    /** Whether the app still holds the read permission of a picked .nup (the readiness check says which part is missing) */
    static boolean granted(Context c, File f) {
        String u = stored(c, f);
        if (u == null) return false;
        if (GamesFolders.isDir(u)) u = GamesFolders.treeOf(u);   // a games folder's file: the folder's permission
        for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) if (p.isReadPermission() && p.getUri().toString().equals(u)) return true;
        return false;
    }

    /** A picked game whose file name is name (a frontend that sends the file's path, which the app cannot open without All files access): null unless exactly one */
    static Game byName(Context c, String name) {
        Game found = null;
        for (Game g : Game.load(c)) {
            if (!is(c, g.gameDir) || !new File(g.gameDir).getName().equals(name)) continue;
            if (found != null) return null;
            found = g;
        }
        return found;
    }

    private static String displayName(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
