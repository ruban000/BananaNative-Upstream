package dev.nxnative.android;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The icon, version and publisher of a game, for the game cards: what a .nup carries (Nup.gameMeta), or a folder dump's control data (DumpLayout.controlDir,
 * Nacp: control.nacp and icon_&lt;Language&gt;.dat, in the phone's language). Read off the main thread (Background.META, one at a time), handed to the card on
 * the main thread.
 * <ul>
 * <li>Kept on disk between runs (MetaKeep, in the app's cache folder, keepDir): by the game's path, the phone's language and the app build, with the identity
 *     of the files it was read from (path or document, size, time) and the icon's own bytes. A start shows what is kept at once, then reads the files only when
 *     their identity changed (a .nup's identity is a stat, or one descriptor of a picked file: no container is opened for an unchanged game).</li>
 * <li>The icon is decoded at the size it is shown at, at most {@link #iconPx} on a side (the largest Home tile, selected or lifted; inSampleSize, then a last
 *     scale), never larger; the original bytes stay on disk, so its quality is the original's up to that size. Decoded icons are kept in memory, at most
 *     {@link #MEM_BYTES}, least recently used first out.</li>
 * </ul>
 */
final class GameMeta {
    final Bitmap icon;
    final String version, publisher;

    private GameMeta(Bitmap icon, String version, String publisher) { this.icon = icon; this.version = version; this.publisher = publisher; }

    /** For example "1.8.0 · Nintendo", with whatever parts it has; null if it has neither */
    String line() {
        String s = (version != null ? version : "") + (version != null && publisher != null ? " · " : "") + (publisher != null ? publisher : "");
        return s.isEmpty() ? null : s;
    }

    /** Where the details are kept between runs (NxApp: the app's cache folder; null: not kept) */
    static volatile File keepDir;
    /** The app build (NxApp: BuildConfig.NX_COMMIT): another build reads each game's details again once */
    static volatile String appVersion = "";

    private static final Handler main = new Handler(Looper.getMainLooper());

    /** The decoded icons' memory: at most this many bytes (an eighth of the heap, at most 32 MiB) */
    static final int MEM_BYTES = (int) Math.min(32L << 20, Runtime.getRuntime().maxMemory() / 8);
    private static final LruCache<String, Bitmap> icons = new LruCache<String, Bitmap>(MEM_BYTES) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getAllocationByteCount(); }
    };

    /**
     * The largest side an icon is shown at, in pixels: the selected tile of Home's sideways row (MainActivity.gameRow: at most 180 dp, or 56 % of the screen's width
     * on a tall screen, grown 1.2 times when selected), or a grid tile (HomeLayout: at most 190 dp, lifted 1.06 times in move mode); the game page's is smaller
     */
    static int iconPx() {
        Resources r = Resources.getSystem();
        float row = Math.max(180f, r.getConfiguration().screenWidthDp * 0.56f) * 1.2f, grid = 190f * 1.06f;
        return Math.max(64, Math.round(Math.max(row, grid) * r.getDisplayMetrics().density));
    }

    /** Reads the game's details and passes them to done on the main thread. Does nothing for a dump without them, or one that can't be read */
    static void load(File dump, Consumer<GameMeta> done) { loadControl(dump, "", () -> null, done); }

    /**
     * As load; control gives the control folder to read instead of the dump's own (another program's, such as a Switch 2 Edition's program 1: Edition.control),
     * null for the dump's: a folder's path, or the full name of a control folder inside an .nx. It is asked off the main thread (it may open the game's file);
     * tag names which it will be without opening anything (the card's title ID and switch), for the kept details. done may be called twice: kept, then read
     */
    static void loadControl(File dump, String tag, Supplier<String> control, Consumer<GameMeta> done) {
        String keptAs = MetaKeep.keptAs(dump.getAbsolutePath(), tag, Nacp.phoneLanguage(), appVersion);
        Background.META.execute(() -> {
            MetaKeep.Kept kept = MetaKeep.load(keepDir, keptAs);
            if (kept != null && kept.has) post(keptAs, kept, done);
            String ctl;
            try { ctl = control.get(); } catch (RuntimeException e) { ctl = null; }
            boolean nup = ctl == null && (Nup.isNup(dump) || Nup.picked(dump));
            Nup.Ref spec = ctl == null ? null : parseSpec(ctl);   // a control folder inside an .nx (Edition.control)
            File folder = nup || spec != null ? null : ctl != null ? new File(ctl) : dump.isDirectory() ? DumpLayout.controlDir(dump) : null;
            String key = nup ? nupKey(dump) : spec != null ? specKey(ctl, spec) : MetaKeep.folderKey(folder);
            if (key == null) return;   // cannot be read now (or no details): what is kept stays (the card's check says what is wrong)
            if (kept != null && kept.current(key)) return;   // unchanged: what is kept is what the files say
            MetaKeep.Kept k = read(dump, nup, ctl, spec, folder, key);
            MetaKeep.keep(keepDir, keptAs, k);
            if (k.has) post(keptAs, k, done);
        });
    }

    private static void post(String keptAs, MetaKeep.Kept k, Consumer<GameMeta> done) {
        GameMeta m = new GameMeta(k.icon == null ? null : icon(keptAs + "\n" + k.key, k.icon), k.version, k.publisher);
        if (m.icon != null || m.version != null || m.publisher != null) main.post(() -> done.accept(m));
    }

    /** The details as the files have them (the icon's bytes as they are) */
    private static MetaKeep.Kept read(File dump, boolean nup, String ctl, Nup.Ref spec, File folder, String key) {
        try {
            if (nup) {
                Nup.Meta m = Nup.peek(dump).gameMeta();
                return kept(key, m.icon, m.version, m.publisher);
            }
            Nacp.Control c = spec != null ? Nacp.read(ctl, Nacp.phoneLanguage()) : Nacp.read(folder, Nacp.phoneLanguage());
            return c == null ? new MetaKeep.Kept(key, false, null, null, null) : kept(key, c.icon, c.version, c.publisher);
        } catch (IOException | RuntimeException e) {
            return new MetaKeep.Kept(key, false, null, null, null);
        }
    }

    private static MetaKeep.Kept kept(String key, byte[] icon, String version, String publisher) {
        boolean has = icon != null || version != null || publisher != null;
        return new MetaKeep.Kept(key, has, version, publisher, icon);
    }

    /** What changes when a .nup's details may: its source's key (path or document, size, time); null when it cannot be opened */
    private static String nupKey(File dump) {
        try (Nup.Source s = Nup.source(dump)) { return s.key; } catch (IOException | RuntimeException e) { return null; }
    }

    private static Nup.Ref parseSpec(String control) {
        try { return Nup.parse(control); } catch (IllegalArgumentException e) { return null; }
    }

    /** A control folder inside an .nx: its name and the archive's source key */
    private static String specKey(String control, Nup.Ref spec) {
        String k = nupKey(new File(spec.path));
        return k == null ? null : control + "|" + k;
    }

    /** The icon decoded at most iconPx() on a side (kept in memory by its card and file identity) */
    private static Bitmap icon(String memKey, byte[] b) {
        int px = iconPx();
        memKey += "\n" + px;
        Bitmap hit = icons.get(memKey);
        if (hit != null) return hit;
        Bitmap bm = decode(b, px);
        if (bm != null) icons.put(memKey, bm);
        return bm;
    }

    /** An image's bytes decoded no larger than max on a side: the largest power-of-two step down that stays at least max, then scaled to max */
    static Bitmap decode(byte[] b, int max) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        int side = Math.max(o.outWidth, o.outHeight);
        if (side <= 0) return null;
        int sample = 1;
        while (side / (sample * 2) >= max) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, d);
        if (bm == null) return null;
        int s = Math.max(bm.getWidth(), bm.getHeight());
        if (s <= max) return bm;
        float k = max / (float) s;
        Bitmap out = Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(bm.getWidth() * k)), Math.max(1, Math.round(bm.getHeight() * k)), true);
        if (out != bm) bm.recycle();
        return out;
    }
}
