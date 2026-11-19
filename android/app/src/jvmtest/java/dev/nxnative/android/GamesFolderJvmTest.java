package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Games folders on the JVM (GamesFolder, MetaKeep, PackFind.gamesIn, Nup.companions through an opener), driven by dm/tools/tests/test_android_gamesfolder.py,
 * which makes the folder: Bananza.nup (nup_pack.py), NSP Game/ with base.nsp, update.nsp and dlc.nsp (test_nsp.py's writer), NX/ with game.nx and an update-only
 * "zz update.nx" (nx_pack.py), Dumps/Dump0/ (a dump folder), a/b/c/d/deep.nup and a/b/c/d/e/deeper.nup (the depth limit), .hidden/, a &lt;title ID&gt;-work
 * folder and a pack folder with .nup files in them, and Shout.NUP. Arguments: the folder, an empty state folder. Checks:
 * <ul>
 * <li>the rule: the games are the .nup, the NSP game's newest version (its older version and DLC beside it are its), the .nx with a base (the update-only .nx
 *     beside it is its), the dump folder and the .nup four levels down; not the fifth level, hidden, work or pack folders; Shout.NUP is to rename;
 *     PackFind.gamesIn is the same rule one level deep</li>
 * <li>what is kept: a second look at unchanged files reads none; a changed time reads that file again; another app build reads all again</li>
 * <li>when: exactly three triggers, the start once per process; a folder added adds every game; a start adds only new or changed files (a removed card stays
 *     removed); Look for new games adds every game without a card</li>
 * <li>the card details kept on disk (MetaKeep): the icon's bytes unchanged, the key (size, time) and keptAs (language, app build) invalidation</li>
 * <li>a games folder read through the provider: stand-in paths whose files open through Nup.opener (.nup, .nsp, .nx), the files beside a game found through
 *     Opener.siblings (Nup.companions), the host's fd: forms for a companion</li>
 * </ul>
 * Prints "ok &lt;checks&gt;"; exits non-zero on the first failure.
 */
public final class GamesFolderJvmTest {
    private static int n;

    static void check(boolean ok, String what) {
        n++;
        if (!ok) { System.out.println("FAIL " + what); System.exit(1); }
    }

    static Set<String> rels(List<GamesFolder.Entry> l) {
        Set<String> s = new TreeSet<>();
        for (GamesFolder.Entry e : l) s.add(e.rel);
        return s;
    }

    static final Set<String> GAMES = new TreeSet<>(List.of("Bananza.nup", "Dumps/Dump0", "NSP Game/update.nsp", "NX/game.nx", "a/b/c/d/deep.nup"));

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]), state = new File(args[1]);
        rule(root, state);
        cache(root, state);
        triggers(root, state);
        meta(state);
        standIns(root, state);
        System.out.println("ok " + n);
    }

    static void rule(File root, File state) {
        GamesFolder.Result r = GamesFolder.scan(new GamesFolder.PathTree(root), GamesFolder.DEPTH, e -> GamesFolder.Facts.read(e.file));
        check(r.listed, "the folder is listed");
        check(rels(r.games).equals(GAMES), "the games: " + rels(r.games));
        check(rels(r.wrongCase).equals(Set.of("Shout.NUP")), "to rename: " + rels(r.wrongCase));
        check(r.needAccess.isEmpty(), "by path a dump folder is a game");
        List<String> names = new ArrayList<>();
        for (File f : PackFind.gamesIn(root)) names.add(f.getName());
        check(names.equals(List.of("Bananza.nup", "Shout.NUP")), "Add game on the folder (one level): " + names);
        names.clear();
        for (File f : PackFind.gamesIn(new File(root, "NSP Game"))) names.add(f.getName());
        check(names.equals(List.of("update.nsp")), "Add game on the NSP folder: the newest version stands for the game: " + names);
        names.clear();
        for (File f : PackFind.gamesIn(new File(root, "NX"))) names.add(f.getName());
        check(names.equals(List.of("game.nx")), "Add game on the NX folder: the update-only .nx beside it is its: " + names);
        GamesFolder.Result one = GamesFolder.scan(new GamesFolder.PathTree(new File(root, "Dumps/Dump0")), GamesFolder.DEPTH, e -> GamesFolder.Facts.read(e.file));
        check(one.games.size() == 1 && one.games.get(0).rel.isEmpty(), "a games folder that is a dump folder is that game");
        GamesFolder.Result gone = GamesFolder.scan(new GamesFolder.PathTree(new File(root, "missing")), GamesFolder.DEPTH, e -> GamesFolder.Facts.read(e.file));
        check(!gone.listed && gone.games.isEmpty(), "a folder that is gone: not listed, no games");
        check(GamesFolder.skipped(".x") && GamesFolder.skipped("0400AAAA00002000-WORK") && !GamesFolder.skipped("Games"), "skipped folder names");
        GamesFolder.Facts f = GamesFolder.Facts.read(new File(root, "NSP Game/dlc.nsp"));
        GamesFolder.Facts p = GamesFolder.Facts.parse(f.line());
        check(p != null && p.line().equals(f.line()) && !f.hasNewest && f.addons.length == 1, "facts kept as a line: " + f.line());
        GamesFolder.Facts bad = GamesFolder.Facts.read(new File(root, "Shout.NUP"));
        check(bad.error != null && GamesFolder.Facts.parse(bad.line()).error != null, "an unreadable file's facts: " + bad.line());
    }

    static void cache(File root, File state) throws IOException {
        File kept = new File(state, "facts.tsv");
        GamesFolder.Cache c1 = GamesFolder.Cache.load(kept, "build1");
        GamesFolder.Result r1 = GamesFolder.scan(new GamesFolder.PathTree(root), GamesFolder.DEPTH, c1::get);
        check(c1.reads == 7, "the first look reads each game file once: " + c1.reads);
        c1.save(kept);
        GamesFolder.Cache c2 = GamesFolder.Cache.load(kept, "build1");
        GamesFolder.Result r2 = GamesFolder.scan(new GamesFolder.PathTree(root), GamesFolder.DEPTH, c2::get);
        check(c2.reads == 0 && rels(r2.games).equals(rels(r1.games)), "a look at unchanged files reads none: " + c2.reads);
        File up = new File(root, "NSP Game/update.nsp");
        check(up.setLastModified(up.lastModified() + 5000), "touch");
        GamesFolder.Cache c3 = GamesFolder.Cache.load(kept, "build1");
        GamesFolder.Result r3 = GamesFolder.scan(new GamesFolder.PathTree(root), GamesFolder.DEPTH, c3::get);
        check(c3.reads == 1 && rels(r3.games).equals(GAMES), "a changed time: that file is read again: " + c3.reads);
        GamesFolder.Cache c4 = GamesFolder.Cache.load(kept, "build2");
        GamesFolder.scan(new GamesFolder.PathTree(root), GamesFolder.DEPTH, c4::get);
        check(c4.reads == 7, "another app build reads every file again: " + c4.reads);
        Files.write(kept.toPath(), "junk\n".getBytes(StandardCharsets.UTF_8));
        GamesFolder.Cache c5 = GamesFolder.Cache.load(kept, "build1");
        GamesFolder.scan(new GamesFolder.PathTree(root), GamesFolder.DEPTH, c5::get);
        check(c5.reads == 7, "a damaged kept file: read again, nothing else");
    }

    static void triggers(File root, File state) throws IOException {
        check(Arrays.equals(GamesFolder.Trigger.values(), new GamesFolder.Trigger[] {GamesFolder.Trigger.ADDED, GamesFolder.Trigger.START, GamesFolder.Trigger.LOOK}),
                "exactly three triggers: a folder added, the app's start, Look for new games");
        check(GamesFolder.startDue() && !GamesFolder.startDue() && !GamesFolder.startDue(), "the start look runs once per process");
        String folder = "content://tree/Games";
        File seenFile = new File(state, "seen.tsv");
        GamesFolder.Seen seen = GamesFolder.Seen.load(seenFile);
        Set<String> cards = new HashSet<>();
        GamesFolder.Cache c = new GamesFolder.Cache("build1");
        List<GamesFolder.Entry> found = GamesFolder.scan(new GamesFolder.PathTree(root), GamesFolder.DEPTH, c::get).games;
        List<GamesFolder.Entry> add = GamesFolder.pick(GamesFolder.Trigger.ADDED, folder, found, seen, e -> cards.contains(e.rel));
        check(rels(add).equals(GAMES), "a folder added: every game");
        for (GamesFolder.Entry e : add) cards.add(e.rel);
        seen.save(seenFile);
        seen = GamesFolder.Seen.load(seenFile);
        check(GamesFolder.pick(GamesFolder.Trigger.START, folder, found, seen, e -> cards.contains(e.rel)).isEmpty(), "a start: nothing new");
        cards.remove("Bananza.nup");   // the player removes a card
        check(GamesFolder.pick(GamesFolder.Trigger.START, folder, found, seen, e -> cards.contains(e.rel)).isEmpty(), "a start: a removed card stays removed");
        check(rels(GamesFolder.pick(GamesFolder.Trigger.LOOK, folder, found, seen, e -> cards.contains(e.rel))).equals(Set.of("Bananza.nup")),
                "Look for new games: every game without a card");
        Files.copy(new File(root, "Bananza.nup").toPath(), new File(root, "New.nup").toPath());
        found = GamesFolder.scan(new GamesFolder.PathTree(root), GamesFolder.DEPTH, c::get).games;
        check(rels(GamesFolder.pick(GamesFolder.Trigger.START, folder, found, seen, e -> cards.contains(e.rel))).equals(Set.of("New.nup")), "a start: the new file only");
        cards.add("New.nup");
        File b = new File(root, "Bananza.nup");
        check(b.setLastModified(b.lastModified() + 7000), "touch");
        found = GamesFolder.scan(new GamesFolder.PathTree(root), GamesFolder.DEPTH, c::get).games;
        check(rels(GamesFolder.pick(GamesFolder.Trigger.START, folder, found, seen, e -> cards.contains(e.rel))).equals(Set.of("Bananza.nup")),
                "a start: a changed file without a card is added again");
        check(GamesFolder.pick(GamesFolder.Trigger.START, "content://tree/Other", found, seen, e -> true).isEmpty(), "with cards: nothing");
        seen.forget("content://tree/Other");
        Files.delete(new File(root, "New.nup").toPath());
    }

    static void meta(File state) {
        File dir = new File(state, "game-meta");
        byte[] jpg = new byte[5000];
        for (int i = 0; i < jpg.length; i++) jpg[i] = (byte) (i * 31 + 7);
        jpg[0] = (byte) 0xFF;
        jpg[1] = (byte) 0xD8;
        String as = MetaKeep.keptAs("/storage/Games/Bananza.nup", "040074A01BF12000", "en-US", "build1");
        String key = "/storage/Games/Bananza.nup|123456|1000";
        check(MetaKeep.load(dir, as) == null, "nothing kept yet");
        MetaKeep.keep(dir, as, new MetaKeep.Kept(key, true, "1.1.0", "Nintendo", jpg));
        MetaKeep.Kept k = MetaKeep.load(dir, as);
        check(k != null && k.has && k.current(key) && "1.1.0".equals(k.version) && "Nintendo".equals(k.publisher), "kept details");
        check(Arrays.equals(k.icon, jpg), "the icon's own bytes, unchanged");
        check(!k.current("/storage/Games/Bananza.nup|123457|1000") && !k.current("/storage/Games/Bananza.nup|123456|2000"), "another size or time: read again");
        check(MetaKeep.load(dir, MetaKeep.keptAs("/storage/Games/Bananza.nup", "040074A01BF12000", "de-DE", "build1")) == null, "another language: not this one");
        check(MetaKeep.load(dir, MetaKeep.keptAs("/storage/Games/Bananza.nup", "040074A01BF12000", "en-US", "build2")) == null, "another app build: not this one");
        check(MetaKeep.load(dir, MetaKeep.keptAs("/storage/Games/Bananza.nup", "040074A01BF12000:program1", "en-US", "build1")) == null, "another program's details: not this one");
        MetaKeep.keep(dir, as, new MetaKeep.Kept(key, false, null, null, null));
        k = MetaKeep.load(dir, as);
        check(k != null && !k.has && k.icon == null && k.current(key), "files without details: kept as none");
        File d = new File(state, "control");
        check(MetaKeep.folderKey(d) == null, "no control data: no key");
    }

    /** A games folder read through the provider, as PickedNup does it: stand-in paths in one stand-in folder per directory, read through the opener */
    static void standIns(File root, File state) throws IOException {
        File app = new File(state, "app");
        Map<File, File> real = new HashMap<>();
        Map<String, File> dirOf = new HashMap<>();
        for (String dir : List.of("NSP Game", "NX", "")) {
            File stand = new File(PickedPath.root(app), PickedPath.dirKey("content://tree/Games", dir));
            stand.mkdirs();
            dirOf.put(dir, stand);
            File[] l = (dir.isEmpty() ? root : new File(root, dir)).listFiles();
            for (File f : l) if (f.isFile() && Nup.extOf(f.getName()) != null) real.put(new File(stand, f.getName()).getAbsoluteFile(), f);
        }
        Nup.opener = new Nup.Opener() {
            public boolean owns(File f) { return real.containsKey(f.getAbsoluteFile()); }
            public Nup.Source open(File f) throws IOException {
                File r = real.get(f.getAbsoluteFile());
                if (r == null) throw new IOException("The app can no longer open " + f.getName() + ".");
                RandomAccessFile raf = new RandomAccessFile(r, "r");
                return new Nup.Source(raf.getChannel(), raf.length(), "content://doc/" + r.getName() + "|" + raf.length() + "|" + r.lastModified(), raf);
            }
            public List<File> siblings(File f) {
                List<File> out = new ArrayList<>();
                for (File s : real.keySet()) if (s.getParentFile().equals(f.getAbsoluteFile().getParentFile())) out.add(s);
                return out;
            }
        };
        try {
            File nsp = new File(dirOf.get("NSP Game"), "update.nsp"), nx = new File(dirOf.get("NX"), "game.nx"), nup = new File(dirOf.get(""), "Bananza.nup");
            check(!nsp.exists() && Nup.picked(nsp) && Nup.picked(nx) && Nup.picked(nup), "stand-ins: nothing at them, read through the opener");
            check(Nup.open(nsp).newest().version == 131072 && Nup.open(nx).base() != null && Nup.open(nup).base() != null, "a .nsp, an .nx and a .nup open by stand-in path");
            List<String> names = new ArrayList<>();
            for (Nup c : Nup.companions(nsp)) names.add(c.file.getName());
            check(names.equals(List.of("base.nsp", "dlc.nsp")), "the files beside the NSP game, by stand-in paths: " + names);
            names.clear();
            for (Nup c : Nup.companions(nx)) names.add(c.file.getName());
            check(names.equals(List.of("zz update.nx")), "the update-only .nx beside the .nx: " + names);
            File dlc = Nup.holder(nsp, "addon/" + String.format("%016x", Nup.open(new File(dirOf.get("NSP Game"), "dlc.nsp")).packages.get(0).titleId));
            check(dlc.getName().equals("dlc.nsp") && Nup.picked(dlc), "the DLC's holder is its stand-in");
            String link = Nup.spec(dlc, "addon/x", "romfs");
            check(PickedPath.forHost(link, dlc, 9).startsWith("fd:9/dlc.nsp:addon/"), "the host's fd: form for a file beside the game: " + PickedPath.forHost(link, dlc, 9));
            // the walk over stand-ins: the same games, each file's facts read through the opener
            GamesFolder.Result r = GamesFolder.scan(new GamesFolder.PathTree(root) {
                @Override public List<GamesFolder.Entry> list(GamesFolder.Entry d) {
                    List<GamesFolder.Entry> l = super.list(d), out = new ArrayList<>();
                    if (l == null) return null;
                    for (GamesFolder.Entry e : l) {
                        File stand = dirOf.get(d.rel);
                        File f = !e.dir && stand != null && Nup.extOf(e.name) != null ? new File(stand, e.name) : e.file;
                        out.add(new GamesFolder.Entry(e.name, e.rel, e.dir, e.size, e.mtime, f, "content://tree/Games#" + e.rel + "|" + e.size + "|" + e.mtime, null));
                    }
                    return out;
                }
            }, GamesFolder.DEPTH, e -> GamesFolder.Facts.read(e.file));
            check(rels(r.games).equals(GAMES), "the walk over stand-ins: " + rels(r.games));
            for (GamesFolder.Entry e : r.games) if (e.rel.equals("NSP Game/update.nsp")) check(Nup.picked(e.file), "a stand-in game");
        } finally {
            Nup.opener = null;
        }
    }
}
