package dev.nxnative.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * The launcher's .nx reading on the JVM (no Android): Nx (header slots, digests, structure, packages, block reads with their CRC-32C), through Nup as every
 * launcher place reads it (DumpLayout, Pack's DM_ROMFS and add-on links, companions, PackFind, a picked file's opener). Compiled with Nx, Nup, Nsp, Zstd, Pack,
 * DumpLayout, BuildCheck, RomfsCheck, Profile, ShaderLibs, PickedPath, CopyPlan, PackFind, Words and a stub BuildConfig (dm/tools/tests/test_android_nx.py).
 * <p>
 * {@code <repo> <scratch folder> <python>}: makes small dump folders, packs them with dm/tools/nx_pack.py (a second implementation, written from the spec:
 * RAW and ZSTD blocks, shared blocks, a second generation, NCA trees, a collection, an unknown required extension), and writes the Catalog-metadata and
 * unknown-type cases with this file's own small writer ({@link W}); prints "ok &lt;n&gt;" and exits 0, or the first failure and exits 1.
 */
public final class NxJvmTest {
    private static int n;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    static void write(File f, byte[] b) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), b);
    }

    static byte[] bytes(File f) throws IOException { return Files.readAllBytes(f.toPath()); }

    static final String TID = "040074A01BF12000", AOC = "040074a01bf13001", OTHER = "0100000000010000";

    static byte[] npdm(long id) {
        byte[] b = new byte[0x100];
        b[0] = 'M'; b[1] = 'E'; b[2] = 'T'; b[3] = 'A';
        b[0x70] = (byte) 0x80;
        b[0x80] = 'A'; b[0x81] = 'C'; b[0x82] = 'I'; b[0x83] = '0';
        for (int i = 0; i < 8; i++) b[0x90 + i] = (byte) (id >>> (8 * i));
        return b;
    }

    /** An NSO-like file: NSO0, a build ID at 0x40 (bytes seed*31+i), then a body that compresses */
    static byte[] nso(int seed, int size) {
        byte[] b = new byte[size];
        b[0] = 'N'; b[1] = 'S'; b[2] = 'O'; b[3] = '0';
        for (int i = 0; i < 32; i++) b[0x40 + i] = (byte) (seed * 31 + i);
        fill(b, 0x100, seed);
        return b;
    }

    static void fill(byte[] b, int from, int seed) {
        Random r = new Random(seed);
        String[] words = {"bananza ", "kong ", "dk ", "\0\0\0\0", "ldr x0, [x1, #8]\n", "été "};
        int i = from;
        while (i < b.length) {
            int kind = r.nextInt(10);
            if (kind < 6) { byte[] w = words[r.nextInt(words.length)].getBytes(StandardCharsets.UTF_8); for (int k = 0; k < w.length && i < b.length; k++) b[i++] = w[k]; }
            else if (kind < 8) { int len = 1 + r.nextInt(40); for (int k = 0; k < len && i < b.length; k++) b[i++] = (byte) r.nextInt(256); }
            else { int back = 1 + r.nextInt(Math.max(1, Math.min(i - from, 3000))), len = 4 + r.nextInt(300); for (int k = 0; k < len && i < b.length; k++, i++) b[i] = b[Math.max(from, i - back)]; }
        }
    }

    static byte[] random(int seed, int size) { byte[] b = new byte[size]; new Random(seed).nextBytes(b); return b; }

    static final String[] FILES = {"exefs/main", "exefs/sdk", "exefs/main.npdm", "exefs/rtld", "romfs/empty.bin", "romfs/Pack/Actor/a.szs", "romfs/Pack/Actor/b.txt",
            "romfs/Pack/zeros.bin", "romfs/System/mixed.bin", "romfs/日本.dat", "romfs/exact.bin"};

    /** base/ (version 0), updates/458752/, dlc/&lt;aoc&gt;/0/ as NupJvmTest's, with files of exactly one block, of several, with a short last block, empty, shared */
    static void dump(File d, boolean base, boolean update, boolean dlc) throws IOException {
        long id = Long.parseUnsignedLong(TID, 16);
        String[] vers = {"base", "updates/458752"};
        for (int v = 0; v < vers.length; v++) {
            if (v == 0 && !base || v == 1 && !update) continue;
            File p = new File(d, vers[v]);
            write(new File(p, "exefs/main"), nso(v + 1, 300_000 + v * 7777));
            write(new File(p, "exefs/sdk"), nso(v + 11, 90_000));
            write(new File(p, "exefs/main.npdm"), npdm(id));
            write(new File(p, "exefs/rtld"), nso(99, 5000));   // equal in both versions: one file record
            write(new File(p, "romfs/empty.bin"), new byte[0]);
            write(new File(p, "romfs/Pack/Actor/a.szs"), random(v, 70_000));
            write(new File(p, "romfs/Pack/Actor/b.txt"), "hello romfs\n".repeat(5000 + v).getBytes(StandardCharsets.UTF_8));
            byte[] rle = new byte[200_000];
            Arrays.fill(rle, (byte) 7);
            write(new File(p, "romfs/Pack/zeros.bin"), rle);   // equal chunks: one shared block
            byte[] mixed = new byte[1_500_000];
            fill(mixed, 0, 1234 + v);
            write(new File(p, "romfs/System/mixed.bin"), mixed);
            write(new File(p, "romfs/日本.dat"), "utf-8 name".getBytes(StandardCharsets.UTF_8));
            write(new File(p, "romfs/exact.bin"), random(50 + v, 65536));   // exactly one 64 KiB block
        }
        if (dlc) write(new File(d, "dlc/" + AOC + "/0/romfs/dlc.bin"), nso(5, 40_000));
    }

    static String python;
    static File repo;

    static File pack(List<File> dumps, File out, String... more) throws IOException, InterruptedException {
        out.getAbsoluteFile().getParentFile().mkdirs();
        List<String> cmd = new ArrayList<>(List.of(python, "-I", new File(repo, "dm/tools/nx_pack.py").getPath()));
        for (File d : dumps) cmd.add(d.getPath());
        cmd.add(out.getPath());
        cmd.addAll(List.of(more));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0 || !out.isFile()) throw new AssertionError("nx_pack.py failed: " + String.join(" ", cmd) + "\n" + o);
        return out;
    }

    static File pack(File dump, File out, String... more) throws IOException, InterruptedException { return pack(List.of(dump), out, more); }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("real")) { real(new File(args[1])); System.out.println("ok " + n); return; }
        repo = new File(args[0]);
        File t = new File(args[1]);
        python = args[2];
        names();
        File d = new File(t, "Game");
        dump(d, true, true, true);
        File auto = pack(d, new File(t, "game.nx"));
        File raw = pack(d, new File(t, "game-raw.nx"), "--codec", "raw");
        File zstd = pack(d, new File(t, "game-zstd.nx"), "--codec", "zstd", "--chunk-log2", "17");
        File nodedup = pack(d, new File(t, "game-nodedup.nx"), "--no-dedup", "--codec", "raw");
        int[] rz = codecs(raw), zz = codecs(zstd), az = codecs(auto);
        check(rz[1] == 0 && rz[0] > 0 && zz[0] == 0 && zz[1] > 0 && az[0] > 0 && az[1] > 0, "codecs: raw " + Arrays.toString(rz) + ", zstd " + Arrays.toString(zz) + ", auto " + Arrays.toString(az));
        for (File f : new File[] {auto, raw, zstd, nodedup}) reader(f, d);
        shared(auto, nodedup);
        ranges(auto, d);
        ranges(zstd, d);
        damage(raw, t, "raw");
        damage(zstd, t, "zstd");
        slots(auto, d, t);
        refusals(d, new File(t, "refused"));
        meta(new File(t, "meta"));
        packRows(auto, t);
        layout(auto, d, t);
        beside(t);
        picked(auto, d, t);
        programs(new File(t, "programs"));
        nca(d, new File(t, "nca"));
        memory(auto, new File(t, "memory"));
        System.out.println("ok " + n);
    }

    // ---- Memory at open: chunked reads, one read of a file opened by several threads at once, the Manifest budget ----

    static void memory(File nx, File t) throws Exception {
        t.mkdirs();
        // a read larger than READ_CHUNK, at an odd offset: the bytes as written, in chunks of at most READ_CHUNK (the temporary direct buffer the JDK keeps per thread)
        byte[] want = new byte[2 * Nup.READ_CHUNK + 12345];
        new java.util.Random(7).nextBytes(want);
        File big = new File(t, "big.bin");
        java.nio.file.Files.write(big.toPath(), want);
        try (Nup.Source src = Nup.source(big)) {
            byte[] got = new byte[want.length - 7 + 3];
            src.read(7, got, 3, want.length - 7);
            check(Arrays.equals(Arrays.copyOfRange(got, 3, got.length), Arrays.copyOfRange(want, 7, want.length)), "a read across several chunks reads the file's bytes");
        }
        // eight threads open one .nx not opened before: one result, the same object for all (the others waited for the first one's read)
        File copy = new File(t, "fresh.nx");
        java.nio.file.Files.copy(nx.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        int k = 8;
        Nup[] got = new Nup[k];
        Throwable[] err = new Throwable[1];
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        Thread[] th = new Thread[k];
        for (int i = 0; i < k; i++) {
            int j = i;
            th[i] = new Thread(() -> {
                try { go.await(); got[j] = Nup.open(copy); } catch (Throwable e) { synchronized (err) { err[0] = e; } }
            });
            th[i].start();
        }
        go.countDown();
        for (Thread x : th) x.join();
        boolean same = err[0] == null && got[0] != null;
        for (Nup x : got) same &= x == got[0];
        check(same, "eight threads opening one .nx at once share one result" + (err[0] != null ? ": " + err[0] : ""));
        check(Nup.open(copy) == got[0], "and it is the cached one afterwards");
        // the Manifest budget: a small Manifest takes one MiB of it, a large one its size, one larger than the budget all of it
        check(Nx.manifestPermits(96) == 1 && Nx.manifestPermits(30L << 20) == 30 && Nx.manifestPermits((30L << 20) + 1) == 31
                && Nx.manifestPermits(300L << 20) == Nx.MANIFEST_BUDGET_MIB, "the Manifest budget's permits");
    }

    // ---- A real archive (read only; dm/tools/tests/test_android_nx.py runs it when NX_TEST_DKB_NX names one) ----

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    /** NXConvert's own archive (DKB from pack-nup: base, update 458752, an add-on): open time, packages, main.npdm, the card's details, a few files read whole */
    static void real(File nx) throws IOException {
        long t0 = System.nanoTime();
        Nup x = Nup.open(nx);
        System.out.printf("opened in %.2f s: generation %d, version (0,%d), %d blocks, %d dirs, %d files%n", (System.nanoTime() - t0) / 1e9, x.nx.generation, x.nx.minor,
                x.nx.blockCount, x.nx.dirCount, x.nx.fileCount);
        for (Nup.Pkg p : x.packages) {
            List<String> root = new ArrayList<>();
            for (Nup.Node c : x.children(x.dir(p.rootDir))) root.add(c.name + (c.dir ? "/" : ""));
            System.out.println("  " + p.selector() + String.format(" title %016X", p.titleId) + " flags " + p.flags + " root " + root);
            Nup.Node data = x.lookup(p, "data");
            if (data != null && data.dir) {
                List<String> dl = new ArrayList<>();
                for (Nup.Node c : x.children(data)) dl.add(c.name + " " + c.size);
                System.out.println("    data/ " + dl);
            }
        }
        check(x.programId().equals(TID), "program ID " + x.programId());
        check(x.base() != null && x.find("update/458752") != null && x.find("addon/" + AOC) != null, "base, update/458752, addon/" + AOC);
        Nup.Meta m = x.gameMeta();
        System.out.println("  card: " + m.title + " | " + m.publisher + " | " + m.version + " | icon " + (m.icon == null ? "none" : m.icon.length + " bytes"));
        Nup.Pkg up = x.find("update/458752");
        for (String f : new String[] {"exefs/main.npdm", "exefs/main"}) {
            Nup.Node node = x.lookup(up, f);
            long t1 = System.nanoTime();
            MessageDigest h = Nup.sha256();
            x.hashTo(node, h);
            System.out.printf("  update/458752/%s %d bytes, SHA-256 %s, %.2f s%n", f, node.size, hex(h.digest()), (System.nanoTime() - t1) / 1e9);
        }
        Nup.Node rd = x.lookup(up, "romfs");
        int k = 0;
        List<Nup.Node> q = new ArrayList<>(List.of(rd));
        while (!q.isEmpty() && k < 6) {
            Nup.Node d = q.remove(0);
            for (Nup.Node c : x.children(d)) {
                if (c.dir) { q.add(c); continue; }
                if (k >= 6 || c.size > (64 << 20)) continue;
                byte[] b = x.read(c, -1);   // its SHA-256 checked
                System.out.printf("  romfs %s %d bytes, SHA-256 %s%n", c.name, b.length, hex(Nup.sha256().digest(b)));
                k++;
            }
        }
        check(k > 0, "RomFS files read");
    }

    // ---- NCA snapshots (NX.md "NCA snapshots"): a decrypted NSP or XCI imported as its NCAs ----

    /**
     * nx_pack.py --nca: the same packages and trees as the extracted snapshots (reader), the update's RomFS through its BKTR patch over the base's section,
     * NCA3 headers too, compressed (LZ4, zeros) RomFS sections, a program 1 of its own, and the launcher's checks on it (DumpLayout, PackFind, Pack)
     */
    static void nca(File d, File t) throws IOException, InterruptedException {
        File g = pack(d, new File(t, "game/Game Nca.nx"), "--nca");
        Nup x = Nup.open(g);
        check(!x.nx.views.isEmpty() && x.packages.size() == 3, "an NCA .nx: its packages from the CNMTs " + x.packages.size());
        reader(g, d);   // base, update/458752 and the add-on, each file as the folder's (the update's RomFS through the patch)
        Nup.Pkg up = x.find("update/458752");
        check(up.titleId == Long.parseUnsignedLong(TID, 16) && x.base().titleId == up.titleId, "the Program NCA's program ID");
        File g3 = pack(d, new File(t, "nca3/Game.nx"), "--nca", "--nca-magic", "NCA3", "--codec", "raw");
        reader(g3, d);
        ranges(g, d);
        // the launcher's checks on it
        check(DumpLayout.versions(g).equals(List.of(0L, 458752L)) && Pack.isDump(g), "versions of an NCA .nx");
        DumpLayout.Found f = DumpLayout.find(g);
        check(f.error == null && f.version == 458752 && f.dlc == 1, "find on an NCA .nx (romfs_check read through the patch): " + f.error);
        check(PackFind.gamesIn(g.getParentFile()).equals(List.of(g)), "Add game lists it");
        Pack p = Pack.read(packDir(t, "pack-nca", g.getName() + ":update/458752/romfs", "1\t" + g.getName() + ":addon/" + AOC + "/romfs"));
        check((g.getAbsolutePath() + ":update/458752/romfs").equals(p.romfsPath(g)) && g.getAbsolutePath().equals(p.aocLinks(g).get("1.nx")), "DM_ROMFS and the add-on link");
        check(BuildCheck.gameKey(g, TID) != null && BuildCheck.gameKey(g, TID).equals(BuildCheck.gameKey(new File(d.getParentFile(), "game.nx"), TID)), "the same game key as the extracted .nx");
        // compressed RomFS sections: the base (no update in the dump) and the add-on
        File bd = new File(t, "BaseDlc");
        dump(bd, true, false, true);
        File c = pack(bd, new File(t, "comp/Comp.nx"), "--nca", "--nca-compress");
        Nup y = Nup.open(c);
        check(y.packages.size() == 2 && y.base() != null, "compressed: base and add-on");
        for (String fn : FILES) {
            if (!fn.startsWith("romfs/")) continue;
            check(Arrays.equals(y.read(y.lookup(y.base(), fn), -1), bytes(new File(bd, "base/" + fn))), "compressed RomFS: " + fn);
        }
        check(Arrays.equals(y.read(y.lookup(y.find("addon/" + AOC), "romfs/dlc.bin"), -1), bytes(new File(bd, "dlc/" + AOC + "/0/romfs/dlc.bin"))), "compressed add-on file");
        try (Nup.Source src = Nup.source(c)) {   // a range across compressed blocks
            Nup.Node m = y.lookup(y.base(), "romfs/System/mixed.bin");
            byte[] want = bytes(new File(bd, "base/romfs/System/mixed.bin"));
            check(Arrays.equals(y.nx.read(src, m, 65536 - 100, 200_000), Arrays.copyOfRange(want, 65536 - 100, 65536 - 100 + 200_000)), "a range across LZ4 blocks");
        }
        // program 1 of its own (the console's layout)
        File con = new File(t, "Console");
        long app = Long.parseUnsignedLong(APP, 16), p1 = Long.parseUnsignedLong(P1, 16);
        for (String v : new String[] {"base", "updates/327680"}) {
            write(new File(con, v + "/program/0/exefs/main"), nso(v.equals("base") ? 60 : 61, 9000));
            write(new File(con, v + "/program/0/exefs/main.npdm"), npdm(app));
            write(new File(con, v + "/program/0/romfs/p0.bin"), random(v.length(), 3000));
        }
        write(new File(con, "updates/327680/program/1/exefs/main"), nso(62, 9000));
        write(new File(con, "updates/327680/program/1/exefs/main.npdm"), npdm(p1));
        write(new File(con, "updates/327680/program/1/romfs/p1.bin"), random(77, 3000));
        control(new File(con, "updates/327680/program/0/data"));   // the update's control NCA (nx_pack.py writes one, of program 0): its RomFS is the package's data/
        File ed = pack(con, new File(t, "ed/Edition.nx"), "--nca");
        String cs = DumpLayout.nxControl(ed.getAbsolutePath() + ":update/327680");
        Nacp.Control cc = cs == null ? null : Nacp.read(cs, "en-US");
        check((ed.getAbsolutePath() + ":update/327680/data").equals(cs) && cc != null && "Wonder Edition".equals(cc.title) && "1.2.0".equals(cc.version) && Arrays.equals(cc.icon, ICON),
                "an NCA package's data/: its control NCA's RomFS, read as a control folder: " + cs);
        Nup e = Nup.open(ed);
        List<String> sels = new ArrayList<>();
        for (Nup.Pkg q : e.packages) sels.add(q.selector());
        check(sels.contains("base") && sels.contains("update/327680") && sels.contains("program1/update/327680"), "an NCA Edition's packages: " + sels);
        check((ed.getAbsolutePath() + ":program1/update/327680").equals(DumpLayout.programSpec(ed, 1)) && Pack.playableIdOf(ed).equals(P1), "program 1 of an NCA .nx: " + DumpLayout.programSpec(ed, 1));
        check(Arrays.equals(e.read(e.lookup(e.find("program1/update/327680"), "romfs/p1.bin"), -1), bytes(new File(con, "updates/327680/program/1/romfs/p1.bin")))
                && Arrays.equals(e.read(e.lookup(e.find("update/327680"), "romfs/p0.bin"), -1), bytes(new File(con, "updates/327680/program/0/romfs/p0.bin"))), "program 0's and 1's RomFS");
        BuildCheck.Mod mod = BuildCheck.mod(ed, "program/1", "main");
        check(mod.exists() && Arrays.equals(mod.head(0x60), Arrays.copyOf(nso(62, 9000), 0x60)), "program 1's module from its NCA");
        // a damaged NCA header: its block's CRC-32C fails, so the archive does not open (an NCA that is not plain: refusals)
        byte[] b = bytes(g3);
        int at = indexOf(b, "NCA3".getBytes(StandardCharsets.US_ASCII));
        check(at > 0, "an NCA3 header in the Data region");
        b[at] = 'X';
        File bad = new File(t, "bad/Bad.nx");
        write(bad, b);
        String why = refusal(bad);
        check(why != null && why.contains("CRC-32C"), "a damaged NCA header: " + why);
    }

    /** Program 1's control data (Edition.control): control.nacp with a title, publisher and version, and an American English icon */
    static void control(File dir) throws IOException {
        byte[] nacp = new byte[0x4000];
        byte[] tt = "Wonder Edition".getBytes(StandardCharsets.UTF_8), pb = "Nintendo".getBytes(StandardCharsets.UTF_8), vv = "1.2.0".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(tt, 0, nacp, 0, tt.length);
        System.arraycopy(pb, 0, nacp, 0x200, pb.length);
        System.arraycopy(vv, 0, nacp, 0x3060, vv.length);
        write(new File(dir, "control.nacp"), nacp);
        write(new File(dir, "icon_AmericanEnglish.dat"), ICON);
    }

    static final byte[] ICON = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, 5, 6, 7, 8, (byte) 0xFF, (byte) 0xD9};

    /** Edition.control's part without Android: program 1's control data in the .nx (DumpLayout.nxControl) read as a folder's (Nacp) */
    static void checkControl(File ed, String sub) {
        String spec = DumpLayout.nxControl(DumpLayout.programSpec(ed, 1));
        check(spec != null && spec.equals(DumpLayout.programSpec(ed, 1) + sub), ed.getName() + ": program 1's control data at " + spec);
        Nacp.Control c = Nacp.read(spec, "en-US");
        check(c != null && "Wonder Edition".equals(c.title) && "Nintendo".equals(c.publisher) && "1.2.0".equals(c.version) && Arrays.equals(c.icon, ICON),
                ed.getName() + ": program 1's title, publisher, version and icon: " + (c == null ? null : c.title + " / " + c.publisher + " / " + c.version));
    }

    static int indexOf(byte[] b, byte[] w) {
        outer:
        for (int i = 8192; i + w.length <= b.length; i++) {
            for (int k = 0; k < w.length; k++) if (b[i + k] != w[k]) continue outer;
            return i;
        }
        return -1;
    }

    // ---- Programs (NX.md "Programs"): a Switch 2 Edition's program 1 ----

    static final String APP = "010015100B514000", P1 = "040015100B514001", EDLC = "010015100b515001";

    static File packTsv(File t, String name, String rows) throws IOException {
        File p = new File(t, name);
        write(new File(p, "main.patched.bin"), new byte[1]);
        write(new File(p, "pack.tsv"), ("format\t1\ntarget\tandroid\nmodules\tmain\n" + rows).getBytes(StandardCharsets.UTF_8));
        return p;
    }

    /**
     * Program selectors, and an Edition in the console's layout (program/1/ a program of its own: program1/ packages) and in the flattened one (program1/ a
     * folder in program 0's tree), through DumpLayout.programSpec, Pack (the card's title, a program pack's DM_ROMFS and DM_ROMFS_PROGRAM_0), BuildCheck, PackFind
     */
    static void programs(File t) throws IOException, InterruptedException {
        Nup.Ref r = Nup.parse("/s/G.nx:program1/update/327680/romfs");
        check(r.selector.equals("program1/update/327680") && r.sub.equals("romfs"), "program1/: " + r);
        check(Nup.parse("G.nx:program0/base/exefs/main").selector.equals("base") && Nup.parse("G.nx:program0/update/010").selector.equals("update/10"), "program0/ is program 0");
        check(Nup.parse("G.nx:program1/addon/" + EDLC.toUpperCase() + "/romfs").selector.equals("addon/" + EDLC), "an add-on under any program is the application's");
        r = Nup.parse("G.nx:base/program1/romfs");
        check(r.selector.equals("base") && r.sub.equals("program1/romfs"), "the program1/ folder in program 0's base: " + r);
        for (String bad : new String[] {"G.nx:program1", "G.nx:program1/", "G.nx:programx/base", "G.nx:program1/romfs"}) {
            boolean threw = false;
            try { Nup.parse(bad); } catch (IllegalArgumentException e) { threw = true; }
            check(threw, "refused: " + bad);
        }
        long app = Long.parseUnsignedLong(APP, 16), p1 = Long.parseUnsignedLong(P1, 16);
        // the console's layout: program/0/ and program/1/ in each version folder
        File con = new File(t, "Console");
        for (String v : new String[] {"base", "updates/327680"}) {
            write(new File(con, v + "/program/0/exefs/main"), nso(v.equals("base") ? 60 : 61, 9000));
            write(new File(con, v + "/program/0/exefs/main.npdm"), npdm(app));
            write(new File(con, v + "/program/0/romfs/p0.bin"), random(v.length(), 3000));
        }
        write(new File(con, "updates/327680/program/1/exefs/main"), nso(62, 9000));
        write(new File(con, "updates/327680/program/1/exefs/main.npdm"), npdm(p1));
        write(new File(con, "updates/327680/program/1/romfs/p1.bin"), random(77, 3000));
        write(new File(con, "dlc/" + EDLC + "/0/romfs/d.bin"), random(78, 300));
        control(new File(con, "updates/327680/program/1/data/control"));   // a dump's control/, where NXConvert keeps it: data/control/
        File ed = pack(con, new File(t, "games/Edition.nx"));
        checkControl(ed, "/data/control");
        String abs = ed.getAbsolutePath();
        Nup x = Nup.open(ed);
        List<String> sels = new ArrayList<>();
        for (Nup.Pkg p : x.packages) sels.add(p.selector());
        check(sels.containsAll(List.of("base", "update/327680", "program1/update/327680", "addon/" + EDLC)) && sels.size() == 4, "the Edition's packages: " + sels);
        Nup.Pkg q1 = x.find("program1/update/327680");
        check(q1.program == 1 && q1.titleId == p1 && x.find(Nup.parse("G.nx:program1/update/327680").selector) == q1, "program 1's update, found by its selector");
        check(x.base().program == 0 && x.newest() == x.find("update/327680") && x.newest(1) == q1 && x.of(Nup.UPDATE).size() == 1 && x.of(Nup.ADDON, 1).size() == 1,
                "without a program: program 0 (base, newest); the add-on under either");
        check(x.programId().equals(APP) && DumpLayout.versions(ed).equals(List.of(0L, 327680L)), "program 0's ID and versions: " + DumpLayout.versions(ed));
        check(DumpLayout.contents(ed, null).size() == 3, "contents: program 0's versions and the DLC: " + DumpLayout.contents(ed, null));
        check(DumpLayout.programDir(ed, 1) == null && (abs + ":program1/update/327680").equals(DumpLayout.programSpec(ed, 1)) && DumpLayout.programSpec(ed, 2) == null,
                "programSpec: program 1's own package: " + DumpLayout.programSpec(ed, 1));
        check(Pack.programId(DumpLayout.read(DumpLayout.programSpec(ed, 1), "exefs/main.npdm"), "x").equals(P1), "program 1's main.npdm");
        check(Pack.titleIdOf(ed).equals(APP) && Pack.playableIdOf(ed).equals(P1), "the card is program 1 (the APK runs it, not the game): " + Pack.playableIdOf(ed));
        check(DumpLayout.find(ed).error != null && DumpLayout.find(ed).error.contains(APP), "Prepare of program 0, which this APK does not run, says so: " + DumpLayout.find(ed).error);
        check(PackFind.gamesIn(ed.getParentFile()).equals(List.of(ed)), "Add game lists the Edition");
        String rows = "title_id\t" + P1 + "\nname\tEdition\nversion\t327680\nprogram\t1\napplication\t" + APP + "\nromfs\tEdition.nx:program1/update/327680/romfs\nromfs_program\t0\tEdition.nx:update/327680/romfs\n";
        Pack pk = Pack.read(packTsv(t, "pack-ed", rows));
        check((abs + ":program1/update/327680/romfs").equals(pk.romfsPath(ed)), "a program pack's DM_ROMFS: program 1's RomFS " + pk.romfsPath(ed));
        check(pk.romfsProgramPaths(ed).equals(Map.of(0, abs + ":update/327680/romfs")), "DM_ROMFS_PROGRAM_0: program 0's " + pk.romfsProgramPaths(ed));
        check(pk.gameVersion(ed).equals("program/1"), "the program pack's version");
        BuildCheck.Mod mod = BuildCheck.mod(ed, "program/1", "main");
        check(mod.exists() && mod.label.equals("program1/update/327680/exefs/main") && Arrays.equals(mod.head(0x60), Arrays.copyOf(nso(62, 9000), 0x60)), "program 1's module: " + mod.label);
        check(!BuildCheck.mod(ed, "program/2", "main").exists(), "no program 2");
        pk = Pack.read(packTsv(t, "pack-ed-folder", rows.replace("Edition.nx:program1/update/327680/romfs", "updates/327680/program/1/romfs")));
        check((abs + ":program1/update/327680/romfs").equals(pk.romfsPath(ed)), "a folder's program pack with the .nx: program 1's own RomFS " + pk.romfsPath(ed));
        // the flattened layout: program1/ a folder in program 0's update
        File flat = new File(t, "Flat");
        write(new File(flat, "updates/327680/exefs/main"), nso(61, 9000));
        write(new File(flat, "updates/327680/exefs/main.npdm"), npdm(app));
        write(new File(flat, "updates/327680/romfs/p0.bin"), random(5, 3000));
        write(new File(flat, "updates/327680/program1/exefs/main"), nso(62, 9000));
        write(new File(flat, "updates/327680/program1/exefs/main.npdm"), npdm(p1));
        write(new File(flat, "updates/327680/program1/romfs/p1.bin"), random(77, 3000));
        File fl = pack(flat, new File(t, "flat/Flat.nx"));
        String fa = fl.getAbsolutePath();
        Nup y = Nup.open(fl);
        check(y.packages.size() == 1 && y.newest().selector().equals("update/327680"), "flattened: one package of program 0");
        check((fa + ":update/327680/program1").equals(DumpLayout.programSpec(fl, 1)), "programSpec: the program1/ folder " + DumpLayout.programSpec(fl, 1));
        check(Pack.playableIdOf(fl).equals(P1), "its card: program 1");
        pk = Pack.read(packTsv(t, "pack-flat", rows.replace("Edition.nx:program1/update/327680/romfs", "Flat.nx:update/327680/program1/romfs").replace("Edition.nx", "Flat.nx")));
        check((fa + ":update/327680/program1/romfs").equals(pk.romfsPath(fl)) && pk.romfsProgramPaths(fl).equals(Map.of(0, fa + ":update/327680/romfs")), "flattened DM_ROMFS: " + pk.romfsPath(fl));
        check(BuildCheck.mod(fl, "program/1", "main").exists() && BuildCheck.mod(fl, "program/1", "main").label.equals("update/327680/program1/exefs/main"), "flattened program 1's module");
    }

    // ---- Names ----

    static void names() {
        Nup.Ref r = Nup.parse("/s/Game.nx:update/458752/romfs");
        check(r.path.equals("/s/Game.nx") && r.selector.equals("update/458752") && r.sub.equals("romfs"), "nx update/romfs: " + r);
        r = Nup.parse("/s/Game.nx:base/program1/romfs");
        check(r.selector.equals("base") && r.sub.equals("program1/romfs"), "nx program1: " + r);
        r = Nup.parse("Game.nx:addon/040074A01BF13001/romfs");
        check(r.path.equals("Game.nx") && r.selector.equals("addon/040074a01bf13001"), "nx addon: " + r);
        r = Nup.parse("/a.nup:dir/b.nx:update/007/romfs");
        check(r.path.equals("/a.nup:dir/b.nx") && r.selector.equals("update/7"), "split at the last container name: " + r.path);
        r = Nup.parse("/a.nx:dir/b.nup:base");
        check(r.path.equals("/a.nx:dir/b.nup") && r.selector.equals("base"), "and the other way round: " + r.path);
        check(Nup.parse("/s/Game.nx").selector == null && Nup.parse("/s/Game.nup:update/1/romfs").path.equals("/s/Game.nup") && Nup.parse("/s/G.nsp:base").path.equals("/s/G.nsp"),
                "plain .nx, and .nup / .nsp as before");
        check(Nup.parse(".nx") == null && Nup.parse("Game.NX") == null && Nup.parse("updates/1/romfs") == null, "not .nx names");
        boolean threw = false;
        try { Nup.parse("G.nx:update/x"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "a bad .nx selector is refused");
        check(Nup.isNup(new File("/nonexistent/G.nx")) && !Nup.isNup(new File("/nonexistent/G.NX")) && Nx.isNx("G.nx") && !Nx.isNx(".nx"), "isNup / isNx by name");
        check(".nx".equals(Nup.extOf("Game.nx")) && ".nsp".equals(Nup.extOf("Game.nsp")) && ".nup".equals(Nup.extOf("Game.nup")) && Nup.extOf("Game.NX") == null, "extOf");
        check(Nup.container("G.nx") && Nup.container("G.nup") && !Nup.container("G.nsp") && !Nup.container("G"), "container");
        check(".nx".equals(Nup.rightExt("Game.NX")) && ".nup".equals(Nup.rightExt("Game.Nup")) && Nup.rightExt("Game.nx") == null && Nup.rightExt("Game.zip") == null, "rightExt");
        check(Nup.word(new File("G.nx")).equals(".nx") && Nup.word(new File("G.nup")).equals(".nup") && Nup.word(new File("G.nsp")).equals(".nup"), "word");
        check(PickedPath.nameProblem("Game.nx") == null && PickedPath.nameProblem("Game.NX").contains("\".nx\"") && PickedPath.nameProblem("Game.NX").contains("lower case")
                && PickedPath.nameProblem("x.nx:y.nx") != null && PickedPath.nameProblem("Game.zip").contains("not a .nup"), "names an .nx in place may have: " + PickedPath.nameProblem("Game.NX"));
        byte[] nine = "123456789".getBytes(StandardCharsets.US_ASCII);
        check(Nx.crc32c(nine, 0, 9) == 0xe3069283, "CRC-32C check value");
    }

    // ---- The archive's own fields, read here from its bytes (SPEC 3, 7) ----

    static long u64(byte[] b, int o) { return Nup.u64(b, o); }
    static long u32(byte[] b, int o) { return Nup.u32(b, o); }

    /** {RAW blocks, ZSTD blocks} of an archive's index (header slot A) */
    static int[] codecs(File f) throws IOException {
        byte[] b = bytes(f);
        long idx = u64(b, 88), cnt = u64(b, 96);
        int[] out = new int[2];
        for (int i = 0; i < cnt; i++) out[b[(int) (idx + 24L * i + 16)]]++;
        return out;
    }

    /** Block i's data offset and stored size */
    static long[] block(byte[] b, int i) {
        long idx = u64(b, 88);
        return new long[] {u64(b, (int) (idx + 24L * i)), u32(b, (int) (idx + 24L * i + 8))};
    }

    /** A header slot's CRC-32C written again after a change to it */
    static void reCrc(byte[] b, int slot) {
        int o = slot * Nx.PAGE, c = Nx.crc32c(b, o, 252);
        for (int i = 0; i < 4; i++) b[o + 252 + i] = (byte) (c >>> (8 * i));
    }

    // ---- The reader ----

    static String folderOf(Nup.Pkg p) { return p.kind == Nup.BASE ? "base" : p.kind == Nup.UPDATE ? "updates/" + p.version : "dlc/" + AOC + "/0"; }

    static void reader(File nx, File d) throws IOException {
        String tag = nx.getName() + ": ";
        Nup x = Nup.open(nx);
        check(x.nx != null && Nup.open(nx) == x && Nx.open(nx) == x.nx, tag + "an .nx, cached");
        check(x.packages.size() == 3, tag + "3 packages, not " + x.packages.size());
        check(x.base() != null && x.base().selector().equals("base") && x.base().flags == 0, tag + "base");
        Nup.Pkg up = x.find("update/458752");
        check(up != null && up.kind == Nup.UPDATE && up.flags == 0 && x.newest() == up && x.resolve(null) == up, tag + "update/458752, the newest");
        Nup.Pkg ao = x.find("addon/" + AOC);
        check(ao != null && ao.kind == Nup.ADDON && ao.exefsDir == Nup.NONE && ao.romfsDir != Nup.NONE && ao.titleId == Long.parseUnsignedLong(AOC, 16), tag + "addon");
        check(x.find("update/1") == null && x.of(Nup.ADDON).size() == 1, tag + "no other packages");
        check(x.programId().equals(TID) && Pack.titleIdOf(nx).equals(TID), tag + "program ID");
        for (Nup.Pkg p : List.of(x.base(), up)) {
            File pd = new File(d, folderOf(p));
            for (String f : FILES) {
                Nup.Node node = x.lookup(p, f);
                check(node != null && !node.dir && node.codec == (x.nx.views.isEmpty() ? Nup.CODEC_NX : Nup.CODEC_NCA), tag + p.selector() + "/" + f + " found");
                byte[] want = bytes(new File(pd, f));
                check(node.size == want.length, tag + f + " size");
                check(Arrays.equals(x.read(node, -1), want), tag + p.selector() + "/" + f + " reads as the folder's file");
                int h = Math.min(0x60, want.length);
                check(Arrays.equals(x.read(node, 0x60), Arrays.copyOf(want, h)), tag + f + " head");
                MessageDigest md = Nup.sha256();
                x.hashTo(node, md);
                check(Arrays.equals(md.digest(), Nup.sha256().digest(want)), tag + f + " hashTo");
            }
            check(x.lookup(p, "romfs/nothing") == null && x.lookup(p, "exefs/main/x") == null, tag + "missing: null");
            Nup.Node rd = x.lookup(p, "romfs");
            List<String> names = new ArrayList<>();
            for (Nup.Node c : x.children(rd)) names.add(c.name);
            check(rd.dir && names.equals(List.of("Pack", "System", "empty.bin", "exact.bin", "日本.dat")), tag + "romfs entries sorted bytewise: " + names);
            check(x.dir(p.romfsDir).childCount == 5 && x.dir(p.exefsDir).childCount == 4, tag + "the package's exefs and romfs");
        }
        check(Arrays.equals(x.read(x.lookup(ao, "romfs/dlc.bin"), -1), bytes(new File(d, "dlc/" + AOC + "/0/romfs/dlc.bin"))), tag + "addon file");
        check(Nup.peek(nx) == x, tag + "peek is open (checked whole)");
        check(DumpLayout.versions(nx).equals(List.of(0L, 458752L)), tag + "versions " + DumpLayout.versions(nx));
    }

    /** Deduplication: the unchanged rtld is one file record in both snapshots; equal chunks are one block. Without it, the reads are the same */
    static void shared(File dedup, File nodedup) throws IOException {
        Nup a = Nup.open(dedup), b = Nup.open(nodedup);
        check(a.lookup(a.base(), "exefs/rtld").index == a.lookup(a.find("update/458752"), "exefs/rtld").index, "an unchanged file: one record in both snapshots");
        check(b.lookup(b.base(), "exefs/rtld").index != b.lookup(b.find("update/458752"), "exefs/rtld").index, "--no-dedup: two records");
        check(a.nx.blockCount < b.nx.blockCount, "shared blocks: " + a.nx.blockCount + " blocks, " + b.nx.blockCount + " without");
    }

    /** Reads of any range (Nx.read by offset): across block and slice boundaries, the last short block, one byte, the whole file */
    static void ranges(File nx, File d) throws IOException {
        Nup x = Nup.open(nx);
        Nup.Pkg up = x.find("update/458752");
        Random r = new Random(7);
        try (Nup.Source src = Nup.source(nx)) {
            for (String f : new String[] {"romfs/System/mixed.bin", "exefs/main", "romfs/Pack/zeros.bin", "romfs/exact.bin"}) {
                Nup.Node node = x.lookup(up, f);
                byte[] want = bytes(new File(d, "updates/458752/" + f));
                List<long[]> cases = new ArrayList<>(List.of(new long[] {0, want.length}, new long[] {want.length - 1, 1}, new long[] {65536 - 3, 7}, new long[] {16384 - 1, 2},
                        new long[] {want.length - 5000, 5000}, new long[] {want.length, 0}));
                for (int i = 0; i < 20; i++) { long o = r.nextInt(want.length); cases.add(new long[] {o, r.nextInt((int) Math.min(300_000, want.length - o) + 1)}); }
                for (long[] c : cases) {
                    if (c[0] < 0 || c[0] + c[1] > want.length) continue;
                    byte[] got = x.nx.read(src, node, c[0], (int) c[1]);
                    check(Arrays.equals(got, Arrays.copyOfRange(want, (int) c[0], (int) (c[0] + c[1]))), nx.getName() + ": " + f + " [" + c[0] + ", +" + c[1] + ")");
                }
                boolean threw = false;
                try { x.nx.read(src, node, want.length - 1, 2); } catch (IOException e) { threw = true; }
                check(threw, "a read past the end is refused");
            }
        }
    }

    private static int seq;

    /** A new file for each changed copy: Nup caches by path, size and time, and two copies of one size written within a millisecond would be one entry */
    static File fresh(File t, byte[] b) throws IOException {
        File f = new File(t, "changed-" + (seq++) + ".nx");
        write(f, b);
        return f;
    }

    static String refusal(File f) {
        try {
            Nup.open(f);
            return null;
        } catch (Nsp.Refused e) {
            return e.plain + " | " + e.getMessage();
        } catch (IOException e) {
            return "IOException | " + e.getMessage();
        }
    }

    static void expect(File f, String plain, String part, String what) {
        String r = refusal(f);
        check(r != null && r.startsWith(plain + " | ") && r.contains(part), what + ": " + r);
    }

    /**
     * A damaged block: the archive opens (Data is not covered by the digests), a read of a file with that block fails with the file and the block named, and
     * the other files still read. A damaged Catalog, Manifest or index, a cut file, junk: refused at open, with what failed
     */
    static void damage(File good, File t, String tag) throws IOException {
        byte[] b = bytes(good);
        Nup g = Nup.open(good);
        int target = -1;
        for (int i = 0; i < codecs(good)[0] + codecs(good)[1]; i++) {   // a block of the update's exefs/main: its first
            target = i;
            break;
        }
        File c = new File(t, "damaged-" + tag + ".nx");
        byte[] x = b.clone();
        long[] blk = block(x, target);
        x[(int) (blk[0] + blk[1] / 2)] ^= 0x5A;
        c = fresh(t, x);
        Nup dn = Nup.open(c);
        int bad = 0, okay = 0;
        for (Nup.Pkg p : dn.packages)
            for (String f : FILES) {
                Nup.Node node = dn.lookup(p, f);
                if (node == null) continue;
                try {
                    dn.read(node, -1);
                    okay++;
                } catch (IOException e) {
                    bad++;
                    check(e.getMessage().contains("block " + target) && e.getMessage().contains("damaged") && e.getMessage().contains(node.name), tag + ": a damaged block read: " + e.getMessage());
                }
            }
        check(bad >= 1 && okay >= 10, tag + ": the damaged block's file fails, the others read (" + bad + " failed, " + okay + " read)");
        // the digests and the size
        long cat = u64(b, 24), man = u64(b, 72), idx = u64(b, 88);
        for (long[] at : new long[][] {{cat + 20, 'C'}, {man + 100, 'M'}, {idx + 4, 'I'}}) {
            x = b.clone();
            x[(int) at[0]] ^= 1;
            c = fresh(t, x);
            expect(c, Nx.DAMAGED, at[1] == 'C' ? "Catalog does not match its SHA-256" : at[1] == 'M' ? "Manifest does not match its SHA-256" : "block index does not match its SHA-256",
                    tag + ": a byte of the " + (char) at[1]);
        }
        c = fresh(t, Arrays.copyOf(b, b.length - 1));
        expect(c, Nx.DAMAGED, "incomplete", tag + ": one byte short");
        c = fresh(t, Arrays.copyOf(b, 6000));
        expect(c, Nx.DAMAGED, "cut off", tag + ": cut inside its header pages");
        c = fresh(t, "not an archive at all, only text".repeat(400).getBytes(StandardCharsets.UTF_8));
        expect(c, Nx.NOT_NX, "no NXAR", tag + ": junk");
        c.delete();
        Nup.open(good);
        check(g == Nup.open(good), tag + ": the good file is still the cached one");
    }

    /** Two header slots: a damaged slot A with a valid slot B opens; both damaged is refused; a newer generation whose slot is damaged falls back to the older one */
    static void slots(File good, File d, File t) throws IOException, InterruptedException {
        byte[] b = bytes(good);
        File c = new File(t, "slots.nx");
        byte[] x = b.clone();
        x[100] ^= 0x10;   // slot A's catalog digest: its CRC-32C no longer matches
        c = fresh(t, x);
        check(refusal(c) == null && Nup.open(c).packages.size() == 3, "slot A damaged, slot B valid: opens");
        x[Nx.PAGE + 100] ^= 0x10;
        c = fresh(t, x);
        expect(c, Nx.DAMAGED, "neither header slot", "both slots damaged");
        x = b.clone();
        x[300] = 1;   // slot A's page padding
        c = fresh(t, x);
        check(refusal(c) == null, "slot A's padding not zero: slot B");
        x = b.clone();
        x[Nx.PAGE + 6] = 9;   // slot B a newer format, slot A as written: the same generation but not the same bytes
        reCrc(x, 1);
        c = fresh(t, x);
        expect(c, Nx.DAMAGED, "differ", "both slots valid, same generation, different bytes");
        for (int s = 0; s < 2; s++) { x[s * Nx.PAGE + 6] = 4; reCrc(x, s); }
        c = fresh(t, x);
        expect(c, Nx.VERSION, "(0,4)", "header version (0,4)");
        x = b.clone();
        for (int s = 0; s < 2; s++) { x[s * Nx.PAGE + 12] = 3; reCrc(x, s); }
        c = fresh(t, x);
        expect(c, Nx.VERSION, "required 0x3", "an unknown required feature bit");
        x = b.clone();
        for (int s = 0; s < 2; s++) { x[s * Nx.PAGE + 230] = 1; reCrc(x, s); }
        c = fresh(t, x);
        expect(c, Nx.DAMAGED, "reserved", "a reserved header byte");
        // generation 1 in slot B (the DLC appended), generation 0 in slot A
        File g2 = pack(d, new File(t, "gen2.nx"), "--second-generation");
        byte[] y = bytes(g2);
        check(u64(y, Nx.PAGE + 216) == 1 && u64(y, 216) == 0, "nx_pack --second-generation: slot B generation 1");
        Nup n2 = Nup.open(g2);
        check(n2.nx.generation == 1 && n2.packages.size() == 3, "the newer generation is read: " + n2.packages.size() + " packages");
        reader(g2, d);
        y[Nx.PAGE + 40] ^= 4;
        c = fresh(t, y);
        Nup n1 = Nup.open(c);
        check(n1.nx.generation == 0 && n1.packages.size() == 2 && n1.find("update/458752") != null, "slot B damaged: generation 0 in slot A, without the appended snapshot " + n1.packages.size());
        y = bytes(g2);
        long tail = u64(y, Nx.PAGE + 24);   // generation 1's Catalog: damaged; its header is fine, so the older generation is tried
        y[(int) tail + 30] ^= 1;
        c = fresh(t, y);
        n1 = Nup.open(c);
        check(n1.nx.generation == 0 && n1.packages.size() == 2, "generation 1's Catalog damaged: generation 0");
        c.delete();
    }

    /** What the reader does not read, with what to tell the player (NX.md "What is read") */
    static void refusals(File d, File t) throws IOException, InterruptedException {
        File nca = new File(t, "NcaDump");
        write(new File(nca, "base/0123456789abcdef0123456789abcdef.nca"), random(3, 30000));
        write(new File(nca, "base/fedcba9876543210fedcba9876543210.cnmt.nca"), random(4, 30000));
        File f = pack(nca, new File(t, "nca.nx"), "--nca-tree", "--app-id", TID);
        expect(f, Nx.NCA, "no plain NCA header", "an NCA tree whose NCAs are not plain (encrypted)");
        File other = new File(t, "Other");
        write(new File(other, "base/exefs/main"), nso(40, 3000));
        write(new File(other, "base/exefs/main.npdm"), npdm(Long.parseUnsignedLong(OTHER, 16)));
        write(new File(other, "base/romfs/x.bin"), random(41, 100));
        f = pack(List.of(d, other), new File(t, "collection.nx"));
        expect(f, Nx.COLLECTION, OTHER, "a collection names the applications");
        check(refusal(f).contains(TID), "both applications");
        f = pack(d, new File(t, "ext-required.nx"), "--extension", "7fff0001:required");
        expect(f, Nx.VERSION, "7fff0001", "an unknown required Catalog extension");
        f = pack(d, new File(t, "ext-optional.nx"), "--extension", "7fff0001");
        check(refusal(f) == null && Nup.open(f).packages.size() == 3, "an unknown optional extension is skipped: " + refusal(f));
        W w = new W();
        w.snap(0, 0, 0, Map.of("exefs/main.npdm", npdm(Long.parseUnsignedLong(TID, 16)), "romfs/a.bin", random(1, 1000)), null);
        w.snap(4, 65536, 0, Map.of("exefs/main.npdm", npdm(Long.parseUnsignedLong(TID, 16))), null);
        f = w.write(new File(t, "type4.nx"));
        expect(f, Nx.UNKNOWN, "type 4", "a snapshot of type 4");
        w = new W();
        w.snap(2, 0, Long.parseUnsignedLong(AOC, 16), Map.of("romfs/dlc.bin", random(9, 5000)), null);
        f = w.write(new File(t, "dlconly.nx"));
        Nup dl = Nup.open(f);
        check(dl.packages.size() == 1 && dl.find("addon/" + AOC) != null && dl.base() == null && dl.newest() == null, "an .nx of DLC alone: its add-on");
        try { Pack.titleIdOf(f); check(false, "a DLC .nx is a game"); } catch (Nsp.Refused e) { check(e.plain.equals(Nx.DLC_ONLY), "DLC alone: " + e.plain); }
        File own = new File(t, "own.nx");
        w = new W();
        w.snap(0, 0, 0, Map.of("exefs/main.npdm", npdm(Long.parseUnsignedLong(TID, 16)), "exefs/main", nso(1, 3000), "romfs/a.bin", random(1, 70000)), "1.0.0");
        w.write(own);
        check(refusal(own) == null, "this test's own writer makes what the reader takes: " + refusal(own));
        f = new File(t, "own-short.nx");
        Files.write(f.toPath(), Arrays.copyOf(bytes(own), 3));
        expect(f, Nx.NOT_NX, "too short", "three bytes");
    }

    /** The game card's details from the Catalog: title, publisher, icon (its SHA-256 checked), the newest update's display version; the localized entries when the defaults are absent */
    static void meta(File t) throws IOException {
        byte[] png = new byte[300];
        png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
        for (int i = 4; i < png.length; i++) png[i] = (byte) i;
        W w = new W();
        w.title = "Donkey Kong Bananza";
        w.publisher = "Nintendo";
        w.icon = png;
        w.snap(0, 0, 0, Map.of("exefs/main.npdm", npdm(Long.parseUnsignedLong(TID, 16)), "romfs/a.bin", random(1, 1000)), "1.0.0");
        w.snap(1, 458752, 0, Map.of("exefs/main.npdm", npdm(Long.parseUnsignedLong(TID, 16)), "romfs/a.bin", random(2, 1000)), "1.1.0");
        File f = w.write(new File(t, "meta.nx"));
        Nup.Meta m = Nup.open(f).gameMeta();
        check("Donkey Kong Bananza".equals(m.title) && "Nintendo".equals(m.publisher) && Arrays.equals(m.icon, png) && "1.1.0".equals(m.version),
                "gameMeta: " + m.title + " / " + m.publisher + " / " + m.version + " / icon " + (m.icon == null ? 0 : m.icon.length));
        w.title = null;
        w.icon = null;
        w.localized = new String[][] {{"de", "Donkey Kong Bananza (de)"}, {"en-us", "Donkey Kong Bananza (en)"}};
        w.locIcon = png;
        f = w.write(new File(t, "meta-loc.nx"));
        m = Nup.open(f).gameMeta();
        check("Donkey Kong Bananza (en)".equals(m.title) && Arrays.equals(m.icon, png) && "Nintendo".equals(m.publisher), "gameMeta from the localized entries, English first: " + m.title);
        byte[] b = bytes(f);
        long media = u64(b, 40);
        b[(int) media + 96 + 10] ^= 1;   // the icon's bytes: its SHA-256 no longer matches (the Media region is not under the header's digests)
        Files.write(f.toPath(), b);
        m = Nup.open(f).gameMeta();
        check(m.icon == null && m.title != null, "a damaged icon is not shown");
        Nup.Meta none = Nup.open(new File(t.getParentFile(), "game.nx")).gameMeta();
        check(none.title == null && none.icon == null && none.version == null, "no metadata in the Catalog: none");
    }

    // ---- Pack: DM_ROMFS and the add-on links ----

    static File packDir(File t, String name, String romfs, String aoc) throws IOException {
        File p = new File(t, name);
        write(new File(p, "main.patched.bin"), new byte[1]);
        write(new File(p, "pack.tsv"), ("format\t1\ntarget\tandroid\ntitle_id\t" + TID + "\nname\tGame\nversion\t458752\nromfs\t" + romfs + "\nmodules\tmain\n"
                + (aoc == null ? "" : "aoc\t" + aoc + "\n")).getBytes(StandardCharsets.UTF_8));
        return p;
    }

    static void packRows(File nx, File t) throws IOException {
        String abs = nx.getAbsolutePath();
        Pack p = Pack.read(packDir(t, "pack-nx", nx.getName() + ":update/458752/romfs", "1\t" + nx.getName() + ":addon/" + AOC + "/romfs"));
        check(p.romfsSelector().equals("update/458752") && p.romfsPath(nx).equals(abs + ":update/458752/romfs"), "DM_ROMFS " + p.romfsPath(nx));
        Map<String, String> l = p.aocLinks(nx);
        check(l.size() == 1 && abs.equals(l.get("1.nx")), "DM_AOC_DIR/1.nx → the .nx: " + l);
        p = Pack.read(packDir(t, "pack-nx-base", nx.getName() + ":base/romfs", null));
        check(p.romfsPath(nx).equals(abs + ":base/romfs") && abs.equals(p.aocLinks(nx).get("1.nx")), "base selector; an add-on without a row linked all the same");
        p = Pack.read(packDir(t, "pack-nx-folder", "updates/458752/romfs", "1\tdlc/" + AOC + "/0/romfs"));
        check(p.romfsPath(nx).equals(abs + ":update/458752/romfs") && abs.equals(p.aocLinks(nx).get("1.nx")), "a folder's pack with the .nx");
        p = Pack.read(packDir(t, "pack-nx-missing", nx.getName() + ":update/1/romfs", null));
        check(p.romfsPath(nx) == null, "a package the .nx has not got: no RomFS");
    }

    /** DumpLayout and DumpCheck's part of it on an .nx: versions, contents, the tools' paths, Prepare's version by build ID, the profile's romfs_check read from the .nx */
    static void layout(File nx, File d, File t) throws IOException, InterruptedException {
        String abs = nx.getAbsolutePath();
        check(Pack.isDump(nx) && DumpLayout.preparable(nx) && DumpLayout.newest(nx) == 458752, "a dump Prepare starts from");
        check(DumpLayout.path(nx, 458752, "romfs").equals(abs + ":update/458752/romfs") && DumpLayout.path(nx, 458752, "exefs/main").equals(abs + ":update/458752/exefs/main")
                && DumpLayout.path(nx, 0, "romfs").equals(abs + ":base/romfs"), "the tools' paths: " + DumpLayout.path(nx, 458752, "romfs"));
        check(DumpLayout.exists(nx, 458752, "exefs/main.npdm") && DumpLayout.hasRomfs(nx, 0) && !DumpLayout.exists(nx, 458752, "exefs/nothing"), "exists, hasRomfs");
        check(DumpLayout.npdmTitle(nx, 458752).equals(TID), "npdmTitle");
        check(DumpLayout.mainBuild(nx, 458752).equals(NupJvmTestBuild.of(nso(2, 0x100))), "main's build ID from the .nx: " + DumpLayout.mainBuild(nx, 458752));
        List<DumpLayout.Item> items = DumpLayout.contents(nx, Profile.of(TID));
        String all = items.toString();
        check(items.size() == 3 && all.contains("base: Base game") && all.contains("update: Update") && all.contains("DLC " + AOC.toUpperCase()), "contents: " + all);
        DumpLayout.Found f = DumpLayout.find(nx);
        check(f.error == null && f.version == 458752 && TID.equals(f.titleId) && f.dlc == 1 && f.buildIds.get("main").startsWith("3e3f4041"), "find: " + f.error + " " + f.version);
        File bad = new File(t, "Bad.NX");
        Files.copy(nx.toPath(), bad.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        check(Nup.wrongCase(bad) && DumpLayout.find(bad).error.contains("\".nx\" (lower case)"), "an .NX: rename it to .nx: " + DumpLayout.find(bad).error);
        bad.delete();
        check(BuildCheck.gameKey(nx, TID) != null && BuildCheck.gameKey(nx, TID).startsWith(TID + ":458752:"), "the game key: " + BuildCheck.gameKey(nx, TID));
        // the profile's romfs_check on another RomFS: refused, with the .nx named
        File r2 = new File(t, "Romfs2");
        dump(r2, true, true, false);
        write(new File(r2, "updates/458752/romfs/Pack/Actor/b.txt"), "other data\n".getBytes(StandardCharsets.UTF_8));
        File nx2 = pack(r2, new File(t, "romfs2.nx"));
        String e = DumpLayout.find(nx2).error;
        check(e != null && e.startsWith("This game .nx's update data is not") && e.contains("other data"), "romfs_check read from the .nx: " + e);
    }

    /** An NSO's build ID as DumpCheck.nsoBuildId writes it, first 32 hex digits (DumpLayout.mainBuild's) */
    static final class NupJvmTestBuild {
        static String of(byte[] h) {
            StringBuilder b = new StringBuilder();
            for (int i = 0x40; i < 0x50; i++) b.append(String.format("%02x", h[i] & 0xff));
            return b.toString();
        }
    }

    /**
     * Files beside each other (NX.md, Nup.companions): an .nx is one file per game (NXConvert puts the updates and DLC in it), so an .nx game has no companions,
     * an .nx is never one (not opened for an NSP game beside it either), and Add game lists each .nx as itself. A games folder of large .nx files was opened whole
     * for each check of each game while they were companions
     */
    static void beside(File t) throws IOException, InterruptedException {
        File dir = new File(t, "beside");
        File db = new File(t, "DumpBase"), du = new File(t, "DumpUpdate"), dd = new File(t, "DumpDlc");
        dump(db, true, false, false);
        dump(du, false, true, false);
        dump(dd, false, false, true);
        File g = pack(db, new File(dir, "Game Base.nx"));
        File u = pack(du, new File(dir, "zz update.nx"));
        File a = pack(dd, new File(dir, "some dlc.nx"), "--app-id", TID);
        Nup un = Nup.open(u);
        check(un.base() == null && un.newest() != null && un.newest().flags == 1, "an update-only .nx: a standalone update (base-less, flag 1)");
        check(Nup.companions(g).isEmpty() && Nup.companions(u).isEmpty() && Nup.companions(a).isEmpty(), "an .nx has no companions");
        check(DumpLayout.versions(g).equals(List.of(0L)), "an .nx game's versions are its own: " + DumpLayout.versions(g));
        check(DumpLayout.versions(u).equals(List.of(458752L)), "the update-only .nx is a complete game of its own (standalone update): " + DumpLayout.versions(u));
        check(Nup.holder(g, "addon/" + AOC).equals(g), "an add-on is looked for in the .nx itself only");
        List<File> games = PackFind.gamesIn(dir);
        check(games.size() == 3, "Add game lists each .nx as itself: " + games);
        // an NSP game beside .nx files does not open them as its companions
        File nspDir = new File(t, "beside-nsp");
        nspDir.mkdirs();
        java.nio.file.Files.copy(g.toPath(), new File(nspDir, "Game Base.nx").toPath());
        check(Nup.companions(new File(nspDir, "Game Base.nx")).isEmpty(), "still none in another folder");
    }

    /** An .nx used where it is (PickedNup): its stand-in path, read through the opener, the host's fd:<n>/<name> forms */
    static void picked(File nx, File d, File t) throws IOException {
        File app = new File(t, "app");
        String uri = "content://com.android.externalstorage.documents/document/primary%3AGames%2FGame%20Name.nx";
        String key = PickedPath.key(uri);
        File stand = PickedPath.standIn(app, key, "Game Name.nx");
        check(key.equals(PickedPath.keyOf(app, stand)), "the stand-in's key");
        boolean[] lost = {false};
        Nup.opener = new Nup.Opener() {
            public boolean owns(File f) { return key.equals(PickedPath.keyOf(app, f)); }
            public Nup.Source open(File f) throws IOException {
                if (lost[0]) throw new IOException("The app can no longer open " + f.getName() + ".");
                java.io.RandomAccessFile r = new java.io.RandomAccessFile(nx, "r");
                return new Nup.Source(r.getChannel(), r.length(), uri + "|" + r.length(), r);
            }
        };
        try {
            check(!stand.exists() && Nup.picked(stand) && Nup.isNup(stand), "the stand-in: nothing there, a picked .nx");
            Nup x = Nup.open(stand);
            check(x.nx != null && x.programId().equals(TID) && x.packages.size() == 3 && Nup.open(stand) == x, "read through the opener, cached");
            check(Arrays.equals(x.read(x.lookup(x.find("update/458752"), "romfs/System/mixed.bin"), -1), bytes(new File(d, "updates/458752/romfs/System/mixed.bin"))), "a file read through the opener");
            check(Pack.isDump(stand) && DumpLayout.find(stand).error == null && DumpLayout.find(stand).version == 458752, "Add game's checks on the stand-in");
            Pack p = Pack.read(packDir(t, "pack-picked", "Game Name.nx:update/458752/romfs", "1\tGame Name.nx:addon/" + AOC + "/romfs"));
            String romfs = p.romfsPath(stand);
            check(PickedPath.forHost(romfs, stand, 7).equals("fd:7/Game Name.nx:update/458752/romfs"), "the host's DM_ROMFS: " + PickedPath.forHost(romfs, stand, 7));
            String link = p.aocLinks(stand).get("1.nx");
            check(link != null && PickedPath.forHost(link, stand, 7).equals("fd:7/Game Name.nx"), "the add-on link's target: " + link);
            check(DumpLayout.path(stand, 458752, "exefs/main").equals(stand.getAbsolutePath() + ":update/458752/exefs/main"), "the packer's path (DM_NUP_FD names the stand-in)");
            lost[0] = true;
            try { Nup.open(stand); check(false, "a lost file opens"); } catch (IOException e) { check(e.getMessage().contains("no longer open"), "a lost file: " + e.getMessage()); }
        } finally {
            Nup.opener = null;
        }
    }

    // ---- A small NX writer (SPEC 1.0.4, version (0,2)): one application and its program, RAW blocks of 64 KiB, Catalog metadata and a Media region ----

    static final class W {
        String title, publisher;
        byte[] icon, locIcon;
        String[][] localized;
        final long app = Long.parseUnsignedLong(TID, 16);
        final List<Object[]> snaps = new ArrayList<>();   // {type, version, add-on title ID, files, display version}

        void snap(int type, long version, long tid, Map<String, byte[]> files, String display) { snaps.add(new Object[] {type, version, tid, files, display}); }

        private static final class Dir { final TreeMap<String, Object> kids = new TreeMap<>(); int id; }

        static long align(long v, long a) { return (v + a - 1) / a * a; }

        File write(File out) throws IOException {
            // the trees: directories numbered in breadth order per snapshot, entries in directory order
            List<Dir> dirs = new ArrayList<>();
            List<byte[]> files = new ArrayList<>();
            int[] roots = new int[snaps.size()];
            for (int s = 0; s < snaps.size(); s++) {
                Dir root = new Dir();
                @SuppressWarnings("unchecked") Map<String, byte[]> fs = (Map<String, byte[]>) snaps.get(s)[3];
                for (Map.Entry<String, byte[]> e : fs.entrySet()) {
                    String[] parts = e.getKey().split("/");
                    Dir at = root;
                    for (int i = 0; i < parts.length - 1; i++) at = (Dir) at.kids.computeIfAbsent(parts[i], k -> new Dir());
                    at.kids.put(parts[parts.length - 1], e.getValue());
                }
                List<Dir> q = new ArrayList<>(List.of(root));
                for (int i = 0; i < q.size(); i++) {
                    q.get(i).id = dirs.size();
                    dirs.add(q.get(i));
                    for (Object k : q.get(i).kids.values()) if (k instanceof Dir) q.add((Dir) k);
                }
                roots[s] = root.id;
            }
            ByteArrayOutputStream pool = new ByteArrayOutputStream();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            List<long[]> entries = new ArrayList<>(), blocks = new ArrayList<>(), frec = new ArrayList<>();
            List<byte[]> fsha = new ArrayList<>();
            List<Integer> refs = new ArrayList<>(), crcs = new ArrayList<>();
            long[][] drec = new long[dirs.size()][];
            long data0 = 8192;
            for (Dir dd : dirs) {
                drec[dd.id] = new long[] {entries.size(), dd.kids.size()};
                for (Map.Entry<String, Object> k : dd.kids.entrySet()) {
                    byte[] nm = k.getKey().getBytes(StandardCharsets.UTF_8);
                    long no = pool.size();
                    pool.write(nm, 0, nm.length);
                    if (k.getValue() instanceof Dir) { entries.add(new long[] {no, nm.length, ((Dir) k.getValue()).id, 1}); continue; }
                    byte[] content = (byte[]) k.getValue();
                    entries.add(new long[] {no, nm.length, files.size(), 2});
                    files.add(content);
                    frec.add(new long[] {content.length, refs.size(), (content.length + 65535) / 65536});
                    fsha.add(Nup.sha256().digest(content));
                    for (int o = 0; o < content.length; o += 65536) {
                        int len = Math.min(65536, content.length - o);
                        while (data.size() % 16 != 0) data.write(0);
                        blocks.add(new long[] {data0 + data.size(), len, crcs.size()});
                        data.write(content, o, len);
                        for (int c = 0; c < len; c += Nx.CHECK) crcs.add(Nx.crc32c(content, o + c, Math.min(Nx.CHECK, len - c)));
                        refs.add(blocks.size() - 1);
                    }
                }
            }
            // the Manifest
            byte[] pp = pool.toByteArray();
            long mDir = 96, mFile = align(mDir + 16L * dirs.size(), 8), mEnt = align(mFile + 64L * files.size(), 8), mRef = align(mEnt + 24L * entries.size(), 4);
            long mPool = align(mRef + 4L * refs.size(), 8), mSize = mPool + pp.length;
            if (files.isEmpty()) { mEnt = align(mDir + 16L * dirs.size(), 8); mFile = 0; }
            if (refs.isEmpty()) { mRef = 0; mPool = align(mEnt + 24L * entries.size(), 8); mSize = mPool + pp.length; }
            ByteBuffer m = le((int) mSize);
            m.put("NXMF".getBytes(StandardCharsets.US_ASCII)).putShort((short) 1).putShort((short) 96).putLong(mSize).putInt(dirs.size()).putInt(files.size()).putInt(entries.size())
                    .putInt(refs.size()).putLong(mDir).putLong(mFile).putLong(mEnt).putLong(mRef).putLong(mPool).putLong(pp.length);
            for (int i = 0; i < dirs.size(); i++) m.position((int) (mDir + 16L * i)).putInt((int) drec[i][0]).putInt((int) drec[i][1]);
            for (int i = 0; i < files.size(); i++) m.position((int) (mFile + 64L * i)).putLong(frec.get(i)[0]).putInt((int) frec.get(i)[1]).putInt((int) frec.get(i)[2]).put((byte) 16)
                    .position((int) (mFile + 64L * i + 20)).put(fsha.get(i));
            for (int i = 0; i < entries.size(); i++) {
                long[] e = entries.get(i);
                m.position((int) (mEnt + 24L * i)).putLong(mPool + e[0]).putInt((int) e[1]).putInt((int) e[2]).put((byte) e[3]);
            }
            for (int i = 0; i < refs.size(); i++) m.position((int) (mRef + 4L * i)).putInt(refs.get(i));
            m.position((int) mPool).put(pp);
            // the Catalog
            ByteArrayOutputStream sp = new ByteArrayOutputStream();
            Map<String, long[]> strs = new LinkedHashMap<>();
            java.util.function.Function<String, long[]> str = s -> {
                if (s == null) return new long[] {0xFFFFFFFFL, 0};
                return strs.computeIfAbsent(s, k -> { byte[] b = k.getBytes(StandardCharsets.UTF_8); long o = sp.size(); sp.write(b, 0, b.length); return new long[] {o, b.length}; });
            };
            int ns = snaps.size(), nl = localized == null ? 0 : localized.length;
            int prog = 0;
            for (Object[] s : snaps) if ((int) s[0] != 2) prog = 1;
            boolean hasBase = false, hasDlc = false;
            for (Object[] s : snaps) { hasBase |= (int) s[0] == 0; hasDlc |= (int) s[0] == 2; }
            List<byte[]> media = new ArrayList<>();
            if (icon != null) media.add(icon);
            if (locIcon != null) media.add(locIcon);
            long cApp = 128, cProg = align(cApp + 80, 8), cSnap = prog == 1 ? align(cProg + 64, 8) : cProg, cLoc = align(cSnap + 120L * ns, 8);
            long cPool = nl == 0 ? cLoc : align(cLoc + 24L * nl, 8);
            long[][] snapStr = new long[ns][];
            for (int i = 0; i < ns; i++) snapStr[i] = str.apply((String) snaps.get(i)[4]);
            long[] tS = str.apply(title), pS = str.apply(publisher);
            long[][] locS = new long[nl][];
            if (nl > 0) for (int i = 0; i < nl; i++) locS[i] = new long[] {str.apply(localized[i][0])[0], str.apply(localized[i][0])[1], str.apply(localized[i][1])[0], str.apply(localized[i][1])[1]};
            byte[] spb = sp.toByteArray();
            long cSize = align(cPool + spb.length, 8) == cPool + spb.length ? cPool + spb.length : cPool + spb.length;
            ByteBuffer c = le((int) cSize);
            c.put("NXCT".getBytes(StandardCharsets.US_ASCII)).putShort((short) 1).putShort((short) 128).putLong(cSize).putInt(0).putInt(1).putInt(prog).putInt(ns).putInt(nl).putInt(0).putInt(0).putInt(0)
                    .putLong(cApp).putLong(prog == 1 ? cProg : 0).putLong(cSnap).putLong(nl == 0 ? 0 : cLoc).putLong(0).putLong(0).putLong(cPool).putLong(spb.length);
            boolean anyMeta = title != null || publisher != null || icon != null || nl > 0;
            int metaSnap = -1;
            for (int i = 0; i < ns; i++) if ((int) snaps.get(i)[0] != 2) metaSnap = i;
            c.position((int) cApp).putLong(app).put((byte) 2).put((byte) ((hasBase ? 1 : 0) | (hasDlc ? 2 : 0))).putShort((short) 0).putInt(prog == 1 ? 0 : -1).putInt(prog).putInt(0).putInt(ns)
                    .putInt((int) tS[0]).putInt((int) tS[1]).putInt((int) pS[0]).putInt((int) pS[1]).putInt(icon != null ? 0 : -1).putInt(0).putInt(nl).putInt(-1).putInt(0).putInt(0)
                    .putInt(anyMeta && metaSnap >= 0 ? metaSnap : -1);
            int progSnaps = 0;
            for (Object[] s : snaps) if ((int) s[0] != 2) progSnaps++;
            if (prog == 1) c.position((int) cProg).putLong(app).putInt(0).put((byte) 0).put((byte) 0).putShort((short) 0).position((int) cProg + 32).putInt(0).putInt(progSnaps).putInt(-1);
            for (int i = 0; i < ns; i++) {
                Object[] s = snaps.get(i);
                int type = (int) s[0];
                c.position((int) (cSnap + 120L * i)).putInt(type == 2 ? -1 : 0).put((byte) type).put((byte) (type == 2 ? 2 : 0)).putShort((short) 0).putInt((int) (long) (Long) s[1]).putInt(roots[i])
                        .putLong(0).putInt((int) snapStr[i][0]).putInt((int) snapStr[i][1]);
                if (type == 2) c.putLong((Long) s[2]);
                c.position((int) (cSnap + 120L * i + 48)).putInt(0).putInt(0);
            }
            for (int i = 0; i < nl; i++)
                c.position((int) (cLoc + 24L * i)).putInt((int) locS[i][0]).putInt((int) locS[i][1]).putInt((int) locS[i][2]).putInt((int) locS[i][3]).putInt(locIcon != null ? media.size() - 1 : -1).putInt(0);
            c.position((int) cPool).put(spb);
            // the Media region
            byte[] md = null;
            if (!media.isEmpty()) {
                long pay = align(32 + 64L * media.size(), 16), end = pay;
                long[] offs = new long[media.size()];
                for (int i = 0; i < media.size(); i++) { end = align(end, 16); offs[i] = end; end += media.get(i).length; }
                ByteBuffer mb = le((int) end);
                mb.put("NXMD".getBytes(StandardCharsets.US_ASCII)).putShort((short) 1).putShort((short) 32).putLong(end).putInt(media.size()).putShort((short) 64).putShort((short) 0).putInt(32).putInt((int) pay);
                for (int i = 0; i < media.size(); i++) {
                    byte[] im = media.get(i);
                    mb.position(32 + 64 * i).putShort((short) (im[0] == (byte) 0x89 ? 1 : 2)).put((byte) 0).put((byte) 0).putLong(im.length).putLong(im.length).putLong(offs[i]).put(Nup.sha256().digest(im));
                    mb.position((int) offs[i]).put(im);
                }
                md = mb.array();
            }
            // the index and the integrity table
            ByteBuffer ix = le(24 * blocks.size()), it = le(4 * crcs.size());
            for (long[] b : blocks) ix.putLong(b[0]).putInt((int) b[1]).putInt((int) b[1]).put((byte) 0).put((byte) 0).putShort((short) 0).putInt((int) b[2]);
            for (int x : crcs) it.putInt(x);
            byte[] db = data.toByteArray();
            long dataEnd = blocks.isEmpty() ? 8192 : data0 + db.length;
            long at = align(dataEnd, 16), mOff = 0, cOff, manOff, idxOff = 0, intOff = 0;
            if (md != null) { mOff = at; at = align(at + md.length, 16); }
            cOff = at; at = align(at + cSize, 16);
            manOff = at; at += mSize;
            if (!blocks.isEmpty()) { idxOff = align(at, 16); at = idxOff + ix.capacity(); intOff = align(at, 16); at = intOff + it.capacity(); }
            long size = at;
            ByteBuffer f = le((int) size);
            f.position(8192).put(db);
            if (md != null) f.position((int) mOff).put(md);
            f.position((int) cOff).put(c.array());
            f.position((int) manOff).put(m.array());
            if (!blocks.isEmpty()) { f.position((int) idxOff).put(ix.array()); f.position((int) intOff).put(it.array()); }
            MessageDigest ih = Nup.sha256();
            ih.update(ix.array());
            ih.update(it.array());
            ByteBuffer h = le(256);
            h.put("NXAR".getBytes(StandardCharsets.US_ASCII)).putShort((short) 0).putShort((short) 2).putInt(256).putShort((short) 1).putShort((short) 0).putLong(size).putLong(cOff).putLong(cSize)
                    .putLong(mOff).putLong(md == null ? 0 : md.length).putLong(blocks.isEmpty() ? 0 : 8192).putLong(blocks.isEmpty() ? 0 : db.length).putLong(manOff).putLong(mSize)
                    .putLong(idxOff).putLong(blocks.size()).putLong(intOff).putLong(it.capacity()).put(Nup.sha256().digest(c.array())).put(Nup.sha256().digest(m.array())).put(ih.digest())
                    .putLong(0).putInt(Nx.CHECK);
            byte[] hb = h.array();
            int crc = Nx.crc32c(hb, 0, 252);
            for (int i = 0; i < 4; i++) hb[252 + i] = (byte) (crc >>> (8 * i));
            byte[] all = f.array();
            System.arraycopy(hb, 0, all, 0, 256);
            System.arraycopy(hb, 0, all, 4096, 256);
            NxJvmTest.write(out, all);
            return out;
        }

        static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }
    }
}
