package dev.nxnative.android;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * An NX archive (.nx; android/NX.md; github.com/fab918/nxformat docs/SPEC.md, release 1.0.4, header versions (0,2) and (0,3)), read only, never written. A game's
 * file read where it is, as a .nup is: {@link #read} checks it at open (header slots and their CRC-32C, versions and features, section ranges, the SHA-256 of the
 * Catalog, the Manifest and the block index before any of their values is used, then the structure this reader relies on: tables, names, sorted children, no
 * node twice in a snapshot, block references and each file's block count and sizes) and makes its packages; Nup then reads it as a NUP file whose tree and file
 * reads come from here (Nup.nx). A file is read block by block: a RAW block by the 16 KiB slices that cover the read, a ZSTD block decoded whole (Zstd); every
 * slice is checked against its CRC-32C, so a damaged block is an error naming the file and the block, never wrong bytes.
 * <p>
 * Packages (NX.md "Packages and names"): the snapshots of each program of the application are base, update/&lt;version&gt; (a standalone update is a base-less
 * one, flag 1), with program&lt;n&gt;/ before them for program n &gt; 0 (Nup.Pkg.program); a DLC snapshot with its add-on title ID is addon/&lt;16 hex digits&gt;. A snapshot made from a
 * decrypted NSP or XCI (NCA files at its root) gives the same packages through its CNMT ({@link NxNca}). An NCA that is not plain, a snapshot of an unknown
 * type and an archive of several games are refused with what to tell the player ({@link Nsp.Refused}). The Catalog's title, publisher and icon
 * are the game card's ({@link #meta}). No Android classes: NxJvmTest runs it on the JVM.
 */
final class Nx {
    static final String EXT = ".nx";

    static final String NOT_NX = "This file is not an NX archive (it has no NXAR header), although its name ends in .nx. It may be another kind of file, or a download that did not finish.";
    static final String DAMAGED = "This .nx file is incomplete or damaged (a check of its header or tables failed). Copy or download it again, and check the file's size.";
    static final String VERSION = "This .nx file uses a version or a feature of the NX format that this app does not read. Convert the game again with an NXConvert release this app supports, "
            + "or use a dump folder or a .nup.";
    static final String NCA = "This .nx file holds the game as NCA files (converted from an NSP or XCI) that this app cannot read: an NCA is encrypted, sparse, compressed as an "
            + "NCZ, or missing. Convert the game again from a decrypted NSP or XCI, or from a dump folder or a .nup.";
    static final String COLLECTION = "This .nx file holds more than one game. The app reads an .nx of one game (with its updates and DLC): convert each game into an .nx of its own.";
    static final String UNKNOWN = "This .nx file holds a game version of a kind the app cannot place. Convert the game again with NXConvert, from a dump folder or a .nup.";
    static final String DLC_ONLY = "This .nx file holds only DLC, no game. Add the game's own file; the DLC is found beside it when they are in the same folder.";

    static final long NONE = 0xFFFFFFFFL;
    static final int PAGE = 4096, HEADER = 256, CHECK = 16384;
    static final long MANIFEST_MAX = 256L << 20;   // this reader's own limit (SPEC 13 allows lower limits): the Manifest is read whole once, at open
    /**
     * Manifests read and checked at once, in MiB of Manifest: each is held whole while its archive is opened (30 MB for Fire Emblem Fortune's Weave, 25-26 MB
     * for Mario Kart World and Ocarina of Time), and Home checks every card on a thread of its own while the games folders scan opens every .nx. Unbounded,
     * a library of large Switch 2 games ran out of the launcher's 512 MB heap at start. A Manifest larger than the budget takes all of it
     */
    static final int MANIFEST_BUDGET_MIB = 64;
    private static final Semaphore MANIFESTS = new Semaphore(MANIFEST_BUDGET_MIB, true);

    /** The budget's permits an archive with this Manifest takes while it is opened */
    static int manifestPermits(long size) { return (int) Math.max(1, Math.min(MANIFEST_BUDGET_MIB, (size + (1 << 20) - 1) >> 20)); }
    static final long BLOCKS_MAX = 1L << 26;
    static final long ICON_MAX = 8L << 20;
    private static final long NXSR = 0x00010008L;
    private static final Set<Long> KNOWN_REQUIRED = new HashSet<>(Arrays.asList(0x00010001L, 0x00010002L, 0x00010003L, 0x00010004L, 0x00010005L, NXSR));

    /** A check of the format that failed: the archive is damaged (or not what it says), not unreadable */
    private static final class Bad extends IOException {
        private static final long serialVersionUID = 1L;
        Bad(String s) { super(s); }
    }

    private static void need(boolean ok, String what) throws Bad { if (!ok) throw new Bad(what); }

    final String name;
    final int minor;
    final long generation, archiveSize;
    final long mediaOff, mediaSize, manifestOff, indexOff, blockCount, integrityOff;
    private final long dirTab, fileTab, entryTab, refTab;
    final long dirCount, fileCount;
    private final byte[] cat;
    private final long catPool, catPoolSize;

    /** What this archive keeps in memory beyond its packages: its Catalog (Nup's cache bound) */
    long kept() { return cat.length; }
    final List<Nup.Pkg> packages;
    private final Map<Integer, Integer> snapOf = new LinkedHashMap<>();   // package index -> Catalog snapshot index
    /** The package trees of NCA snapshots (NxNca), by the view number in their nodes' virtual IDs */
    final List<NxNca.View> views = new ArrayList<>();
    private final int app;   // the application whose packages these are (the game's, else the first with DLC), its Catalog index

    private Nx(String name, Slot s, byte[] cat, long catPool, long catPoolSize, Man m, List<Nup.Pkg> packages, Map<Integer, Integer> snapOf, int app) {
        this.name = name;
        this.minor = s.minor;
        this.generation = s.generation;
        this.archiveSize = s.archiveSize;
        this.mediaOff = s.mediaOff;
        this.mediaSize = s.mediaSize;
        this.manifestOff = s.manifestOff;
        this.indexOff = s.indexOff;
        this.blockCount = s.blockCount;
        this.integrityOff = s.integrityOff;
        this.dirTab = s.manifestOff + m.dirTab;
        this.fileTab = s.manifestOff + m.fileTab;
        this.entryTab = s.manifestOff + m.entryTab;
        this.refTab = s.manifestOff + m.refTab;
        this.dirCount = m.nd;
        this.fileCount = m.nf;
        this.cat = cat;
        this.catPool = catPool;
        this.catPoolSize = catPoolSize;
        this.packages = Collections.unmodifiableList(packages);
        this.snapOf.putAll(snapOf);
        this.app = app;
    }

    /** Whether a file name is an .nx's (lower case, something before it) */
    static boolean isNx(String name) { return name != null && name.endsWith(EXT) && name.length() > EXT.length(); }

    /** The .nx at f, checked (through Nup's cache: by path or picked document, size and time) */
    static Nx open(File f) throws IOException {
        Nup n = Nup.open(f);
        if (n.nx == null) throw new IOException(f.getName() + " is not an .nx");
        return n.nx;
    }

    // ---- Little-endian fields ----

    static int u8(byte[] b, int o) { return b[o] & 0xff; }
    static int u16(byte[] b, int o) { return Nup.u16(b, o); }
    static long u32(byte[] b, int o) { return Nup.u32(b, o); }
    static long u64(byte[] b, int o) { return Nup.u64(b, o); }

    private static boolean zero(byte[] b, int o, int n) {
        for (int i = 0; i < n; i++) if (b[o + i] != 0) return false;
        return true;
    }

    private static long align(long v, long a) { return (v + a - 1) / a * a; }

    /** Whether [off, off + size) lies in [0, limit), with no overflow */
    private static boolean in(long off, long size, long limit) { return off >= 0 && size >= 0 && off <= limit && size <= limit - off; }

    // ---- CRC-32C (Castagnoli, reflected 0x82F63B78; SPEC 3), by table: the launcher reads small files ----

    private static final int[] CRC = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0x82F63B78 : c >>> 1;
            CRC[i] = c;
        }
    }

    static int crc32c(byte[] b, int off, int len) {
        int c = ~0;
        for (int i = off; i < off + len; i++) c = CRC[(c ^ b[i]) & 0xff] ^ (c >>> 8);
        return ~c;
    }

    // ---- Opening ----

    /** The fields of one header slot (SPEC 3) */
    private static final class Slot {
        int minor;
        long generation, archiveSize, catalogOff, catalogSize, mediaOff, mediaSize, dataOff, dataSize, manifestOff, manifestSize, indexOff, blockCount, integrityOff, integritySize;
        byte[] catalogSha, manifestSha, indexSha;
    }

    /** The Manifest's tables (offsets relative to it) */
    private static final class Man {
        long nd, nf, ne, nr, dirTab, fileTab, entryTab, refTab, pathOff, pathSize;
    }

    /** The .nx in r (name for messages), checked; a Refused for anything it does not read, whatever way the file is wrong; another IOException when it cannot be read */
    static Nx read(Nup.Source r, String name) throws IOException {
        try {
            return read0(r, name);
        } catch (Nsp.Refused e) {
            throw e;
        } catch (Bad e) {
            throw new Nsp.Refused(DAMAGED, name + ": " + e.getMessage(), e);
        } catch (EOFException | RuntimeException e) {
            throw new Nsp.Refused(DAMAGED, name + ": " + e, e);
        }
    }

    private static Nx read0(Nup.Source r, String name) throws IOException {
        if (r.size < 4) throw new Nsp.Refused(NOT_NX, name + " is too short for an .nx");
        byte[] magic = new byte[4];
        r.read(0, magic, 0, 4);
        boolean nxar = magic[0] == 'N' && magic[1] == 'X' && magic[2] == 'A' && magic[3] == 'R';
        if (r.size < 2 * PAGE) {
            if (!nxar) throw new Nsp.Refused(NOT_NX, name + " is not an .nx (no NXAR header)");
            throw new Bad("the file is " + r.size + " bytes, shorter than its two header pages: it is cut off");
        }
        byte[] area = new byte[2 * PAGE];
        r.read(0, area, 0, area.length);
        byte[] chosen = null, older = null;
        boolean anyMagic = false;
        for (int s = 0; s < 2; s++) {
            int o = s * PAGE;
            if (!(area[o] == 'N' && area[o + 1] == 'X' && area[o + 2] == 'A' && area[o + 3] == 'R')) continue;
            anyMagic = true;
            if (crc32c(area, o, 252) != (int) u32(area, o + 252) || !zero(area, o + HEADER, PAGE - HEADER)) continue;   // a damaged slot is ignored when the other is valid
            byte[] c = Arrays.copyOfRange(area, o, o + HEADER);
            if (chosen == null) chosen = c;
            else {
                long gc = u64(c, 216), gh = u64(chosen, 216);
                if (Long.compareUnsigned(gc, gh) > 0) { older = chosen; chosen = c; }
                else if (Long.compareUnsigned(gc, gh) < 0) older = c;
                else need(Arrays.equals(c, chosen), "both header slots are valid with the same generation but differ");
            }
        }
        if (chosen == null) {
            if (!anyMagic) throw new Nsp.Refused(NOT_NX, name + " is not an .nx (no NXAR header)");
            throw new Bad("neither header slot passes its CRC-32C (or its page's zero padding)");
        }
        try {
            return open(r, name, chosen);
        } catch (Bad e) {
            if (older == null) throw e;
            try {
                return open(r, name, older);   // the newer generation's tail is damaged: the older one, when it is whole (SPEC 1)
            } catch (Bad e2) {
                throw e;
            }
        }
    }

    private static Slot slot(byte[] h) throws IOException {
        int major = u16(h, 4), minor = u16(h, 6);
        if (major != 0 || (minor != 2 && minor != 3) || u32(h, 8) != HEADER)
            throw new Nsp.Refused(VERSION, "NX header version (" + major + "," + minor + "), header size " + u32(h, 8) + "; this app reads (0,2) and (0,3)");
        if (u16(h, 12) != 1 || u16(h, 14) != 0)
            throw new Nsp.Refused(VERSION, "NX features: required 0x" + Integer.toHexString(u16(h, 12)) + ", optional 0x" + Integer.toHexString(u16(h, 14)) + "; this app knows required bit 0 only");
        need(u32(h, 224) == CHECK, "check size " + u32(h, 224) + ", not 16384");
        need(zero(h, 228, 24), "nonzero reserved header bytes");
        Slot s = new Slot();
        s.minor = minor;
        s.archiveSize = u64(h, 16);
        s.catalogOff = u64(h, 24); s.catalogSize = u64(h, 32);
        s.mediaOff = u64(h, 40); s.mediaSize = u64(h, 48);
        s.dataOff = u64(h, 56); s.dataSize = u64(h, 64);
        s.manifestOff = u64(h, 72); s.manifestSize = u64(h, 80);
        s.indexOff = u64(h, 88); s.blockCount = u64(h, 96);
        s.integrityOff = u64(h, 104); s.integritySize = u64(h, 112);
        s.catalogSha = Arrays.copyOfRange(h, 120, 152);
        s.manifestSha = Arrays.copyOfRange(h, 152, 184);
        s.indexSha = Arrays.copyOfRange(h, 184, 216);
        s.generation = u64(h, 216);
        return s;
    }

    private static Nx open(Nup.Source r, String name, byte[] h) throws IOException {
        Slot s = slot(h);
        need(s.archiveSize >= 0 && s.archiveSize <= r.size, "the header says the archive is " + s.archiveSize + " bytes, the file is " + r.size + ": it is incomplete (still being copied?) or damaged");
        need(s.catalogSize >= 128 && s.catalogSize <= (16L << 20) && in(s.catalogOff, s.catalogSize, s.archiveSize), "the Catalog's range or size");
        need(s.manifestSize >= 96 && s.manifestSize <= (4L << 30) && in(s.manifestOff, s.manifestSize, s.archiveSize), "the Manifest's range or size");
        need(s.blockCount >= 0 && s.blockCount <= (1L << 32) && s.integritySize >= 0 && s.integritySize % 4 == 0, "block count and integrity size disagree");
        if (s.blockCount == 0) need(s.indexOff == 0 && s.integrityOff == 0 && s.integritySize == 0 && s.dataOff == 0 && s.dataSize == 0, "an archive with no blocks has block tables or a Data region");
        else need(s.dataOff == 2 * PAGE && s.indexOff != 0 && s.integrityOff != 0 && s.dataSize != 0 && s.integritySize != 0 && in(s.dataOff, s.dataSize, s.archiveSize), "the Data region or the block tables are missing");
        need(s.mediaSize == 0 ? s.mediaOff == 0 : s.mediaSize >= 32 && s.mediaSize <= (4L << 30), "the Media region's offset or size");
        // the tail: [Media] Catalog Manifest [index, integrity], each at the 16-byte boundary after the one before; the first after the Data region
        List<long[]> tail = new ArrayList<>();
        if (s.mediaSize != 0) tail.add(new long[] {s.mediaOff, s.mediaSize});
        tail.add(new long[] {s.catalogOff, s.catalogSize});
        tail.add(new long[] {s.manifestOff, s.manifestSize});
        if (s.blockCount != 0) {
            need(s.blockCount <= BLOCKS_MAX, s.blockCount + " blocks, more than this app reads");
            tail.add(new long[] {s.indexOff, s.blockCount * 24});
            tail.add(new long[] {s.integrityOff, s.integritySize});
        }
        long dataEnd = s.blockCount != 0 ? s.dataOff + s.dataSize : 2 * PAGE;
        need(tail.get(0)[0] % 16 == 0 && tail.get(0)[0] >= align(dataEnd, 16), "the tail does not start after the Data region");
        long cursor = tail.get(0)[0];
        for (int i = 0; i < tail.size(); i++) {
            long[] t = tail.get(i);
            if (i > 0) need(t[0] == align(cursor, 16), "a tail section is not at its packed offset");
            need(in(t[0], t[1], s.archiveSize), "a tail section is outside the archive");
            cursor = t[0] + t[1];
        }
        need(cursor == s.archiveSize, "the archive has bytes after its tail that its header counts");
        if (s.mediaSize != 0) {
            byte[] mh = new byte[32];
            r.read(s.mediaOff, mh, 0, 32);
            long n = u32(mh, 16);
            need(mh[0] == 'N' && mh[1] == 'X' && mh[2] == 'M' && mh[3] == 'D' && u16(mh, 4) == 1 && u16(mh, 6) == 32 && u64(mh, 8) == s.mediaSize && n >= 1 && n <= 65535
                    && u16(mh, 20) == 64 && u16(mh, 22) == 0 && u32(mh, 24) == 32 && u32(mh, 28) == align(32 + 64 * n, 16) && u32(mh, 28) <= s.mediaSize, "the Media region's header");
        }
        // the three digests first: no value of a section is used before its SHA-256 matches
        need(s.manifestSize <= MANIFEST_MAX, "a Manifest of " + s.manifestSize + " bytes, more than this app reads");
        byte[] cat = new byte[(int) s.catalogSize];
        r.read(s.catalogOff, cat, 0, cat.length);
        need(Arrays.equals(Nup.sha256().digest(cat), s.catalogSha), "the Catalog does not match its SHA-256");
        int permits = manifestPermits(s.manifestSize);
        MANIFESTS.acquireUninterruptibly(permits);   // released when the Manifest is no longer needed: packages() is its last reader
        try {
            byte[] man = new byte[(int) s.manifestSize];
            r.read(s.manifestOff, man, 0, man.length);
            need(Arrays.equals(Nup.sha256().digest(man), s.manifestSha), "the Manifest does not match its SHA-256");
            MessageDigest ih = Nup.sha256();
            hashRange(r, s.indexOff, s.blockCount * 24, ih);
            hashRange(r, s.integrityOff, s.integritySize, ih);
            need(Arrays.equals(ih.digest(), s.indexSha), "the block index does not match its SHA-256");

            Cat c = catalog(cat, s);
            Man m = manifest(man, s.manifestSize);
            int[] blockSize = blocks(r, s);
            for (long fi = 0; fi < m.nf; fi++) {   // each file's blocks are its chunks: full ones, then the rest
                int at = (int) (m.fileTab + 64 * fi);
                long size = u64(man, at), first = u32(man, at + 8), cnt = u32(man, at + 12);
                long chunk = 1L << u8(man, at + 16);
                for (long j = 0; j < cnt; j++) {
                    long id = u32(man, (int) (m.refTab + 4 * (first + j)));
                    need(id < s.blockCount, "a block reference past the block index");
                    need(blockSize[(int) id] == Math.min(chunk, size - j * chunk), "file " + fi + ": block " + id + " is not of its chunk's size");
                }
            }
            for (int si = 0; si < c.ns; si++) need(c.root[si] < m.nd, "snapshot " + si + "'s root is not a directory of the Manifest");
            int[] dirSeen = new int[(int) m.nd], fileSeen = new int[(int) m.nf];
            for (int si = 0; si < c.ns; si++) walk(man, m, c.root[si], si, dirSeen, fileSeen);
            return packages(r, name, s, cat, c, man, m);
        } finally {
            MANIFESTS.release(permits);
        }
    }

    private static void hashRange(Nup.Source r, long off, long n, MessageDigest h) throws IOException {
        byte[] buf = new byte[1 << 16];
        while (n > 0) {
            int k = (int) Math.min(buf.length, n);
            r.read(off, buf, 0, k);
            h.update(buf, 0, k);
            off += k;
            n -= k;
        }
    }

    /** The block index (SPEC 7): in Data order, aligned, inside the Data region, slices counted; each block's logical size */
    private static int[] blocks(Nup.Source r, Slot s) throws IOException {
        int[] size = new int[(int) s.blockCount];
        long prevEnd = s.dataOff, checks = 0;
        byte[] buf = new byte[24 * 2730];
        for (long b = 0; b < s.blockCount; ) {
            int n = (int) Math.min(2730, s.blockCount - b);
            r.read(s.indexOff + 24 * b, buf, 0, 24 * n);
            for (int i = 0; i < n; i++, b++) {
                int o = 24 * i;
                long off = u64(buf, o), stored = u32(buf, o + 8), logical = u32(buf, o + 12), first = u32(buf, o + 20);
                int codec = u8(buf, o + 16);
                need(codec <= 1 && buf[o + 17] == 0 && u16(buf, o + 18) == 0, "block " + b + ": codec " + codec + " or flags this app does not know");
                need(stored >= 1 && stored <= (1 << 20) + (64 << 10) && logical >= 1 && logical <= (1 << 20) && (codec == 1 || stored == logical), "block " + b + ": its sizes");
                need(off % 16 == 0 && off >= align(prevEnd, 16) && in(off, stored, s.dataOff + s.dataSize), "block " + b + " overlaps another or is outside the Data region");
                need(first == checks, "block " + b + ": its first check is not the running slice count");
                checks += (logical + CHECK - 1) / CHECK;
                size[(int) b] = (int) logical;
                prevEnd = off + stored;
            }
        }
        if (s.blockCount != 0) need(prevEnd == s.dataOff + s.dataSize, "the Data region does not end at the last block");
        need(checks * 4 == s.integritySize, "the integrity table's size disagrees with the blocks' slices");
        return size;
    }

    /** The Manifest's tables (SPEC 6), as the reference reader checks them: layout, directories and their sorted, valid names, files and their block references */
    private static Man manifest(byte[] b, long size) throws IOException {
        need(b.length >= 96 && b[0] == 'N' && b[1] == 'X' && b[2] == 'M' && b[3] == 'F' && u16(b, 4) == 1 && u16(b, 6) == 96 && u64(b, 8) == size && zero(b, 80, 16), "the Manifest's header");
        Man m = new Man();
        m.nd = u32(b, 16); m.nf = u32(b, 20); m.ne = u32(b, 24); m.nr = u32(b, 28);
        need(m.nd >= 1 && m.nd <= 16777216 && m.nf <= 33554432 && m.ne <= 50331648 && u64(b, 72) <= (2L << 30), "the Manifest's counts");
        long[] off = {u64(b, 32), u64(b, 40), u64(b, 48), u64(b, 56)}, count = {m.nd, m.nf, m.ne, m.nr}, width = {16, 64, 24, 4}, al = {8, 8, 8, 4};
        long cur = 96;
        for (int i = 0; i < 4; i++) {
            if (count[i] == 0) { need(off[i] == 0, "an empty Manifest table with an offset"); continue; }
            long a = align(cur, al[i]);
            need(a <= size && zero(b, (int) cur, (int) (a - cur)), "the Manifest's table padding");
            need(off[i] == a && count[i] <= (size - a) / width[i], "a Manifest table's offset or range");
            cur = a + count[i] * width[i];
        }
        long a = align(cur, 8);
        need(a <= size && zero(b, (int) cur, (int) (a - cur)), "the Manifest's path pool padding");
        m.pathOff = u64(b, 64); m.pathSize = u64(b, 72);
        need(m.pathOff == a && in(m.pathOff, m.pathSize, size) && m.pathOff + m.pathSize == size, "the Manifest's path pool range");
        m.dirTab = off[0]; m.fileTab = off[1]; m.entryTab = off[2]; m.refTab = off[3];
        long ec = 0;
        for (long d = 0; d < m.nd; d++) {
            int at = (int) (m.dirTab + 16 * d);
            long first = u32(b, at), n = u32(b, at + 4);
            need(first == ec && n <= m.ne - ec && u32(b, at + 8) == 0 && u32(b, at + 12) == 0, "directory " + d + ": its entry range or flags");
            int pa = -1, pl = 0;
            for (long j = first; j < first + n; j++) {
                int e = (int) (m.entryTab + 24 * j);
                long no = u64(b, e), nl = u32(b, e + 8), target = u32(b, e + 12);
                int kind = u8(b, e + 16);
                need(kind >= 1 && kind <= 2 && b[e + 17] == 0 && u16(b, e + 18) == 0 && u32(b, e + 20) == 0 && nl >= 1 && nl <= 4096, "directory " + d + ": an entry's kind, flags or name length");
                need(no >= m.pathOff && no <= m.pathOff + m.pathSize && nl <= m.pathOff + m.pathSize - no, "directory " + d + ": a name outside the path pool");
                checkName(b, (int) no, (int) nl, "directory " + d);
                if (pa >= 0) need(compare(b, pa, pl, (int) no, (int) nl) < 0, "directory " + d + ": its names are not sorted, or one is there twice");
                pa = (int) no; pl = (int) nl;
                need(kind == 1 ? target < m.nd : target < m.nf, "directory " + d + ": an entry names a node past the tables");
            }
            ec += n;
        }
        need(ec == m.ne, "the directories do not cover the entry table");
        long rc = 0;
        for (long f = 0; f < m.nf; f++) {
            int at = (int) (m.fileTab + 64 * f);
            long sz = u64(b, at), first = u32(b, at + 8), n = u32(b, at + 12);
            int lg = u8(b, at + 16);
            need(sz >= 0 && lg >= 16 && lg <= 20 && b[at + 17] == 0 && u16(b, at + 18) == 0 && zero(b, at + 52, 12), "file " + f + ": its record");
            long chunk = 1L << lg;
            need(n == sz / chunk + (sz % chunk != 0 ? 1 : 0) && first == rc && n <= m.nr - rc, "file " + f + ": its block count or reference range");
            rc += n;
        }
        need(rc == m.nr, "the files do not cover the block reference table");
        return m;
    }

    /** A path component (SPEC 6, 10): UTF-8, no NUL, no slash or backslash, not . or .. */
    private static void checkName(byte[] b, int o, int n, String where) throws Bad {
        for (int i = 0; i < n; i++) need(b[o + i] != 0 && b[o + i] != '/' && b[o + i] != '\\', where + ": a name with a NUL or a separator");
        need(!(n == 1 && b[o] == '.') && !(n == 2 && b[o] == '.' && b[o + 1] == '.'), where + ": a . or .. name");
        need(utf8(b, o, n) != null, where + ": a name that is not UTF-8");
    }

    private static String utf8(byte[] b, int o, int n) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, o, n)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static int compare(byte[] b, int ao, int al, int bo, int bl) {
        for (int i = 0; i < Math.min(al, bl); i++) {
            int c = (b[ao + i] & 0xff) - (b[bo + i] & 0xff);
            if (c != 0) return c;
        }
        return al - bl;
    }

    /**
     * One snapshot's tree from its root: no directory or file reached twice (no cycle, no shared node inside one snapshot), at most 256 levels deep. dirSeen and
     * fileSeen hold the number (+1) of the last snapshot that reached each node
     */
    private static void walk(byte[] b, Man m, long root, int snap, int[] dirSeen, int[] fileSeen) throws Bad {
        int stamp = snap + 1;
        long[] stack = new long[64];
        int[] depth = new int[64];
        int sp = 0;
        stack[sp] = root; depth[sp++] = 0;
        need(dirSeen[(int) root] != stamp, "snapshot " + snap + ": a directory twice");
        dirSeen[(int) root] = stamp;
        while (sp > 0) {
            long d = stack[--sp];
            int dp = depth[sp];
            need(dp <= 256, "snapshot " + snap + ": a tree deeper than 256 levels");
            int at = (int) (m.dirTab + 16 * d);
            long first = u32(b, at), n = u32(b, at + 4);
            for (long j = first; j < first + n; j++) {
                int e = (int) (m.entryTab + 24 * j);
                int t = (int) u32(b, e + 12);
                if (u8(b, e + 16) == 2) {
                    need(fileSeen[t] != stamp, "snapshot " + snap + ": file " + t + " twice in its tree");
                    fileSeen[t] = stamp;
                } else {
                    need(dirSeen[t] != stamp, "snapshot " + snap + ": directory " + t + " twice in its tree (a cycle?)");
                    dirSeen[t] = stamp;
                    if (sp == stack.length) { stack = Arrays.copyOf(stack, sp * 2); depth = Arrays.copyOf(depth, sp * 2); }
                    stack[sp] = t; depth[sp++] = dp + 1;
                }
            }
        }
    }

    // ---- The Catalog (SPEC 4) ----

    private static final class Cat {
        int na, np, ns, nl;
        long appTab, progTab, snapTab, locTab, pool, poolSize;
        long[] root;
        int[] snapApp;
    }

    private static Cat catalog(byte[] b, Slot s) throws IOException {
        need(b[0] == 'N' && b[1] == 'X' && b[2] == 'C' && b[3] == 'T' && u16(b, 4) == 1 && u16(b, 6) == 128 && u64(b, 8) == b.length && u32(b, 16) == 0 && u32(b, 44) == 0 && zero(b, 112, 16),
                "the Catalog's header");
        Cat c = new Cat();
        long na = u32(b, 20), np = u32(b, 24), ns = u32(b, 28), nl = u32(b, 32), nd = u32(b, 36), ne = u32(b, 40);
        need(na >= 1 && na <= 4096 && np <= 16384 && ns >= 1 && ns <= 65536 && nl <= 16384 && nd <= 32768 && ne <= 4096, "the Catalog's counts");
        c.na = (int) na; c.np = (int) np; c.ns = (int) ns; c.nl = (int) nl;
        long[] off = {u64(b, 48), u64(b, 56), u64(b, 64), u64(b, 72), u64(b, 80), u64(b, 88)}, count = {na, np, ns, nl, nd, ne}, width = {80, 64, 120, 24, 56, 32};
        long cur = 128, size = b.length;
        for (int i = 0; i < 6; i++) {
            if (count[i] == 0) { need(off[i] == 0, "an empty Catalog table with an offset"); continue; }
            long a = align(cur, 8);
            need(off[i] == a && a <= size && count[i] <= (size - a) / width[i], "a Catalog table's offset or range");
            cur = a + count[i] * width[i];
        }
        c.appTab = off[0]; c.progTab = off[1]; c.snapTab = off[2]; c.locTab = off[3];
        c.pool = u64(b, 96); c.poolSize = u64(b, 104);
        need(c.pool == align(cur, 8) && in(c.pool, c.poolSize, size), "the Catalog's string pool range");
        // applications: ascending, their program and snapshot ranges partition the tables
        long progCur = 0, snapCur = 0, prevPlatform = -1, prevId = 0;
        c.snapApp = new int[c.ns];
        for (int a = 0; a < c.na; a++) {
            int at = (int) (c.appTab + 80 * a);
            long id = u64(b, at), fp = u32(b, at + 12), pc = u32(b, at + 16), fs = u32(b, at + 20), sc = u32(b, at + 24);
            int platform = u8(b, at + 8), flags = u8(b, at + 9);
            need(id != 0 && (flags & ~3) == 0 && u16(b, at + 10) == 0 && zero(b, at + 72, 8), "application " + a + ": its record");
            need(prevPlatform < platform || prevPlatform == platform && Long.compareUnsigned(prevId, id) < 0, "the applications are not sorted by platform and ID");
            prevPlatform = platform;
            prevId = id;
            if (pc == 0) need(fp == NONE, "application " + a + ": an empty program range with a start");
            else { need(fp == progCur && pc <= np - progCur, "application " + a + ": its program range"); progCur += pc; }
            need(sc >= 1 && fs == snapCur && sc <= ns - snapCur, "application " + a + ": its snapshot range");
            for (long si = fs; si < fs + sc; si++) c.snapApp[(int) si] = a;
            snapCur += sc;
            str(b, c, u32(b, at + 28), u32(b, at + 32), 4096);
            str(b, c, u32(b, at + 36), u32(b, at + 40), 4096);
            long fl = u32(b, at + 48), lc = u32(b, at + 52);
            need(lc == 0 || fl + lc <= nl, "application " + a + ": its localized entry range");
            long ms = u32(b, at + 68);
            need(ms == NONE || ms >= fs && ms < fs + sc, "application " + a + ": its metadata snapshot");
        }
        need(progCur == np && snapCur == ns, "the applications do not cover the program and snapshot tables");
        for (int p = 0; p < c.np; p++) {
            int at = (int) (c.progTab + 64 * p);
            long ai = u32(b, at + 8), fs = u32(b, at + 32), sc = u32(b, at + 36);
            need(u64(b, at) != 0 && ai < na && u8(b, at + 12) <= 2 && (u8(b, at + 13) & ~1) == 0 && u16(b, at + 14) == 0 && u32(b, at + 44) == 0 && zero(b, at + 48, 16), "program " + p + ": its record");
            need(sc >= 1 && fs < ns && sc <= ns - fs, "program " + p + ": its snapshot range");
        }
        c.root = new long[c.ns];
        for (int si = 0; si < c.ns; si++) {
            int at = (int) (c.snapTab + 120 * si);
            long pi = u32(b, at);
            int type = u8(b, at + 4), flags = u8(b, at + 5);
            need(pi == NONE || pi < np, "snapshot " + si + ": its program");
            need(type <= 4 && (flags & ~3) == 0 && u16(b, at + 6) == 0, "snapshot " + si + ": its type or flags");
            need(((flags & 1) != 0) == !zero(b, at + 88, 32), "snapshot " + si + ": its build ID flag");
            if ((flags & 2) != 0)
                need(type == 2 && pi == NONE && u64(b, at + 32) != 0 && zero(b, at + 40, 8), "snapshot " + si + ": an add-on title ID on a snapshot that is not a DLC of its own");
            if (pi != NONE) {
                long pa = u32(b, (int) (c.progTab + 64 * pi) + 8);
                need(pa == c.snapApp[si], "snapshot " + si + ": its program is another application's");
            }
            str(b, c, u32(b, at + 24), u32(b, at + 28), 256);
            c.root[si] = u32(b, at + 12);
        }
        for (int i = 0; i < c.nl; i++) {
            int at = (int) (c.locTab + 24 * i);
            need(u32(b, at + 20) == 0, "localized entry " + i + ": its flags");
            need(str(b, c, u32(b, at), u32(b, at + 4), 63) != null, "localized entry " + i + ": no language");
            str(b, c, u32(b, at + 8), u32(b, at + 12), 4096);
        }
        // extensions: sorted, packed after the pool; an unknown required one refuses the archive (SPEC 11)
        long ec = c.pool + c.poolSize;
        long[] prev = null;
        boolean nxsr = false;
        for (int i = 0; i < (int) ne; i++) {
            int at = (int) (off[5] + 32 * i);
            long type = u32(b, at), flags = u32(b, at + 4), owner = u16(b, at + 8), oi = u32(b, at + 12), doff = u64(b, at + 16), dsz = u64(b, at + 24);
            need((flags & ~1L) == 0 && owner <= 3 && u16(b, at + 10) == 0 && (owner != 0 || oi == NONE) && (owner != 1 || oi < na) && (owner != 2 || oi < np) && (owner != 3 || oi < ns),
                    "extension " + i + ": its descriptor");
            long[] key = {type, owner, oi};
            if (prev != null) need(prev[0] < key[0] || prev[0] == key[0] && (prev[1] < key[1] || prev[1] == key[1] && prev[2] < key[2]), "the extensions are not sorted, or one is there twice");
            prev = key;
            long a = align(ec, 8);
            need(a <= size && zero(b, (int) ec, (int) (a - ec)) && doff == a && in(doff, dsz, size), "extension " + i + ": its payload range");
            ec = doff + dsz;
            if ((flags & 1) != 0 && !KNOWN_REQUIRED.contains(type))
                throw new Nsp.Refused(VERSION, "a required Catalog extension 0x" + String.format(Locale.ROOT, "%08x", type) + " this app does not know");
            if (type == NXSR) nxsr = true;
        }
        need(ec == size, "the Catalog has bytes after its last extension");
        need(nxsr == (s.minor == 3), s.minor == 3 ? "an NX 0.3 archive with no Source Resources" : "an NX 0.2 archive with Source Resources");
        return c;
    }

    /** A string of the Catalog's pool (absent: null); a Bad when the reference is out of the pool, too long, or not UTF-8 without NUL */
    private static String str(byte[] b, Cat c, long off, long len, int max) throws Bad {
        if (off == NONE && len == 0) return null;
        need(len >= 1 && len <= max && in(off, len, c.poolSize), "a string outside the Catalog's pool");
        int o = (int) (c.pool + off);
        for (int i = 0; i < len; i++) need(b[o + i] != 0, "a string with a NUL");
        String s = utf8(b, o, (int) len);
        need(s != null, "a string that is not UTF-8");
        return s;
    }

    private String str(long off, long len) {
        if (off == NONE || len == 0 || !in(off, len, catPoolSize)) return null;
        return utf8(cat, (int) (catPool + off), (int) len);
    }

    // ---- Packages ----

    /** A directory's entry by name, as {kind, target}, or null (from the Manifest's bytes, at open) */
    private static long[] child(byte[] b, Man m, long dir, String name) {
        int at = (int) (m.dirTab + 16 * dir);
        long first = u32(b, at), n = u32(b, at + 4);
        byte[] want = name.getBytes(StandardCharsets.UTF_8);
        for (long j = first; j < first + n; j++) {
            int e = (int) (m.entryTab + 24 * j);
            int no = (int) u64(b, e), nl = (int) u32(b, e + 8);
            if (nl == want.length && Arrays.equals(Arrays.copyOfRange(b, no, no + nl), want)) return new long[] {u8(b, e + 16), u32(b, e + 12)};
        }
        return null;
    }

    private static boolean ncaTree(byte[] b, Man m, long dir) {
        int at = (int) (m.dirTab + 16 * dir);
        long first = u32(b, at), n = u32(b, at + 4);
        for (long j = first; j < first + n; j++) {
            int e = (int) (m.entryTab + 24 * j);
            int no = (int) u64(b, e), nl = (int) u32(b, e + 8);
            if (u8(b, e + 16) == 2 && nl > 4 && new String(b, no, nl, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).endsWith(".nca")) return true;
        }
        return false;
    }

    private static String hex(long id) { return String.format(Locale.ROOT, "%016X", id); }

    /**
     * The packages (NX.md): the one application with a base or an update (several: a collection, refused, the applications named), each program's snapshots
     * as base and update/&lt;v&gt; (program n &gt; 0: program&lt;n&gt;/base, program&lt;n&gt;/update/&lt;v&gt;), and its DLC snapshots with their add-on title ID as
     * addon/&lt;tid&gt;; an archive of DLC alone: every such DLC snapshot
     */
    private static Nx packages(Nup.Source r, String name, Slot s, byte[] b, Cat c, byte[] man, Man m) throws IOException {
        List<Integer> games = new ArrayList<>();
        for (int a = 0; a < c.na; a++) {
            int at = (int) (c.appTab + 80 * a);
            long fs = u32(b, at + 20), sc = u32(b, at + 24);
            for (long si = fs; si < fs + sc; si++) {
                int type = u8(b, (int) (c.snapTab + 120 * si) + 4);
                if (type != 2) { games.add(a); break; }
            }
        }
        if (games.size() > 1) {
            List<String> ids = new ArrayList<>();
            for (int a : games) ids.add(hex(u64(b, (int) (c.appTab + 80 * a))));
            throw new Nsp.Refused(COLLECTION, name + " holds " + games.size() + " games (applications " + String.join(", ", ids) + "): a collection, which this app does not read");
        }
        int app = games.isEmpty() ? 0 : games.get(0);
        // the programs (NX.md "Programs"): program 0 is the one whose ID is the application's, else the lowest index (not program_kind: NXConvert marks a
        // Switch 2 Edition's program 1 primary); every other is program (ID & 0xf)
        Map<Long, Integer> progIndex = new LinkedHashMap<>();
        if (!games.isEmpty()) {
            int at = (int) (c.appTab + 80 * app);
            long appId = u64(b, at), fp = u32(b, at + 12), pc = u32(b, at + 16);
            long zero = NONE;
            for (long p = fp; pc != 0 && p < fp + pc; p++) if (u64(b, (int) (c.progTab + 64 * p)) == appId) zero = p;
            if (zero == NONE)
                for (long p = fp; pc != 0 && p < fp + pc; p++)
                    if (zero == NONE || (u64(b, (int) (c.progTab + 64 * p)) & 0xf) < (u64(b, (int) (c.progTab + 64 * zero)) & 0xf)) zero = p;
            Set<Integer> used = new HashSet<>();
            for (long p = fp; pc != 0 && p < fp + pc; p++) {
                int idx = p == zero ? 0 : (int) (u64(b, (int) (c.progTab + 64 * p)) & 0xf);
                if (!used.add(idx))
                    throw new Nsp.Refused(UNKNOWN, name + ": two programs of application " + hex(appId) + " are both program " + idx + ", which this app cannot tell apart");
                progIndex.put(p, idx);
            }
        }
        List<Nup.Pkg> out = new ArrayList<>();
        Map<Integer, Integer> snapOf = new LinkedHashMap<>();
        Set<String> sels = new HashSet<>();
        List<long[]> ncaSnaps = new ArrayList<>();   // {snapshot, root directory, type}
        for (int si = 0; si < c.ns; si++) {
            if (!games.isEmpty() && c.snapApp[si] != app) continue;   // another application's DLC: ignored (NX.md)
            int at = (int) (c.snapTab + 120 * si);
            long pi = u32(b, at), ver = u32(b, at + 8), root = u32(b, at + 12);
            int type = u8(b, at + 4), flags = u8(b, at + 5);
            if (type == 4) throw new Nsp.Refused(UNKNOWN, name + ": snapshot " + si + " (version " + ver + ") is of type 4 (unknown), which this app does not read");
            long[] ex = child(man, m, root, "exefs"), ro = child(man, m, root, "romfs");
            long exefs = ex != null && ex[0] == 1 ? ex[1] : Nup.NONE, romfs = ro != null && ro[0] == 1 ? ro[1] : Nup.NONE;
            if (exefs == Nup.NONE && romfs == Nup.NONE && ncaTree(man, m, root)) { ncaSnaps.add(new long[] {si, root, type}); continue; }   // read below (NxNca)
            int kind, program = 0;
            long tid;
            if (type == 2) {
                if ((flags & 2) == 0) continue;   // a DLC without its add-on title ID and without NCAs: nothing to name it by
                kind = Nup.ADDON;
                tid = u64(b, at + 32);
            } else {
                Integer idx = progIndex.get(pi);
                if (idx == null) continue;   // a snapshot of no program of the game's application (not written by NXConvert): nothing to name it by
                kind = type == 0 ? Nup.BASE : Nup.UPDATE;
                tid = u64(b, (int) (c.progTab + 64 * pi));
                program = idx;
            }
            Nup.Pkg p = new Nup.Pkg(out.size(), tid, root, exefs, romfs, kind == Nup.BASE ? 0 : ver, kind, type == 3 ? 1 : 0);
            p.program = program;
            String sel = p.selector();   // two bases of a program, an update and a standalone update of one version: ambiguous
            if (!sels.add(sel)) throw new Nsp.Refused(UNKNOWN, name + ": two snapshots are both " + sel + ", which this app cannot tell apart");
            snapOf.put(out.size(), si);
            out.add(p);
        }
        Nx nx = new Nx(name, s, b, c.pool, c.poolSize, m, out, snapOf, app);
        // NCA snapshots (NX.md "NCA snapshots"): their CNMT names the packages, one per program; a selector two snapshots give is ambiguous, except the same
        // tree given again (one NCA snapshot reached from two programs of the Catalog)
        Map<String, Long> ncaRoot = new LinkedHashMap<>();
        for (long[] ns : ncaSnaps) {
            int si = (int) ns[0];
            Map<String, Nup.Node> files = new LinkedHashMap<>();
            int at = (int) (m.dirTab + 16 * ns[1]);
            for (long j = u32(man, at); j < u32(man, at) + u32(man, at + 4); j++) {
                int e = (int) (m.entryTab + 24 * j);
                if (u8(man, e + 16) != 2) continue;
                String nm = new String(man, (int) u64(man, e), (int) u32(man, e + 8), StandardCharsets.UTF_8), lower = nm.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".ncz")) throw new Nsp.Refused(NCA, name + ": snapshot " + si + " holds " + nm + ", an NCZ (compressed NCA), which this app does not read");
                if (lower.endsWith(".nca")) files.put(lower, nx.file(r, u32(man, e + 12), nm));
            }
            String where = name + ": snapshot " + si;
            for (Nup.Pkg q : NxNca.packages(nx, r, (int) ns[2], files, where)) {
                Nup.Pkg p = new Nup.Pkg(out.size(), q.titleId, q.rootDir, q.exefsDir, q.romfsDir, q.version, q.kind, q.flags);
                p.program = q.program;
                String sel = p.selector();
                if (ncaRoot.containsKey(sel) && ncaRoot.get(sel) == ns[1]) continue;
                if (!sels.add(sel)) throw new Nsp.Refused(UNKNOWN, name + ": two snapshots are both " + sel + ", which this app cannot tell apart");
                ncaRoot.put(sel, ns[1]);
                nx.snapOf.put(out.size(), si);
                out.add(p);
            }
        }
        return nx;
    }

    // ---- The tree, read by position (the Manifest's SHA-256 was checked at open; Nup's cache keys on the file's size and time) ----

    private byte[] at(Nup.Source r, long off, int n) throws IOException {
        byte[] b = new byte[n];
        r.read(off, b, 0, n);
        return b;
    }

    /** A directory by its ID (its name is not known from the ID: "") */
    Nup.Node dir(Nup.Source r, long id) throws IOException {
        if (NxNca.virtual(id)) return NxNca.dir(this, r, id);   // a directory of an NCA snapshot's package
        if (id < 0 || id >= dirCount) throw new IOException(name + ": directory " + id + " past the table");
        byte[] d = at(r, dirTab + 16 * id, 16);
        return new Nup.Node(true, id, "", 0, 0, 0, 0, null, u32(d, 0), u32(d, 4));
    }

    /** A directory's entries, in their (sorted) order */
    List<Nup.Node> children(Nup.Source r, Nup.Node dir) throws IOException {
        List<Nup.Node> out = new ArrayList<>();
        if (!dir.dir || dir.childCount == 0) return out;
        if (NxNca.virtual(dir.index)) return NxNca.children(this, r, dir.index);
        if (dir.childCount > (1 << 22)) throw new IOException(name + ": a directory of " + dir.childCount + " entries");
        byte[] e = at(r, entryTab + 24 * dir.firstChild, (int) (24 * dir.childCount));
        long lo = Long.MAX_VALUE, hi = 0;
        for (int i = 0; i < dir.childCount; i++) { lo = Math.min(lo, u64(e, 24 * i)); hi = Math.max(hi, u64(e, 24 * i) + u32(e, 24 * i + 8)); }
        byte[] names = hi - lo <= (1 << 20) ? at(r, manifestOff + lo, (int) (hi - lo)) : null;   // a directory's names are usually together in the pool: one read
        for (int i = 0; i < dir.childCount; i++) {
            long no = u64(e, 24 * i), nl = u32(e, 24 * i + 8), t = u32(e, 24 * i + 12);
            String nm = names != null ? new String(names, (int) (no - lo), (int) nl, StandardCharsets.UTF_8) : new String(at(r, manifestOff + no, (int) nl), StandardCharsets.UTF_8);
            if (u8(e, 24 * i + 16) == 1) {
                Nup.Node d = dir(r, t);
                out.add(new Nup.Node(true, t, nm, 0, 0, 0, 0, null, d.firstChild, d.childCount));
            } else out.add(file(r, t, nm));
        }
        return out;
    }

    /** A file node: its size and SHA-256 (the file's ID is the node's index) */
    Nup.Node file(Nup.Source r, long id, String nm) throws IOException {
        if (id < 0 || id >= fileCount) throw new IOException(name + ": file " + id + " past the table");
        byte[] f = at(r, fileTab + 64 * id, 64);
        return new Nup.Node(false, id, nm, u64(f, 0), 0, 0, Nup.CODEC_NX, Arrays.copyOfRange(f, 20, 52), 0, 0);
    }

    // ---- File reads: by blocks, each slice checked against its CRC-32C ----

    /** Decoded ZSTD blocks, least recently used first (keyed by archive and block), at most CACHE_BYTES */
    private static final Map<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);
    private static long cacheBytes;
    static final long CACHE_BYTES = 8L << 20;

    /** A file's bytes [off, off + n): each block that covers them read and checked (a RAW block by the slices that cover the read, a ZSTD block decoded whole) */
    byte[] read(Nup.Source r, Nup.Node f, long off, int n) throws IOException {
        if (f.dir) throw new IOException(f.name + " is a directory");
        if (f.codec == Nup.CODEC_NCA) return NxNca.read(this, r, f, off, n);   // a file of an NCA snapshot's package: through its section's layers, down to the blocks
        if (off < 0 || n < 0 || off > f.size || n > f.size - off) throw new IOException(f.name + ": a read past its end");
        byte[] out = new byte[n];
        if (n == 0) return out;
        byte[] rec = at(r, fileTab + 64 * f.index, 64);
        long first = u32(rec, 8);
        int lg = u8(rec, 16);
        long chunk = 1L << lg;
        for (long pos = off; pos < off + n; ) {
            long k = pos >>> lg, in = pos - (k << lg);
            int take = (int) Math.min(chunk - in, off + n - pos);
            long id = u32(at(r, refTab + 4 * (first + k), 4), 0);
            block(r, f, id, in, take, out, (int) (pos - off));
            pos += take;
        }
        return out;
    }

    /** Bytes [in, in + n) of block id into out at o, checked (f names the file in an error) */
    private void block(Nup.Source r, Nup.Node f, long id, long in, int n, byte[] out, int o) throws IOException {
        if (id >= blockCount) throw new IOException(name + ": " + f.name + ": block " + id + " past the index");
        byte[] d = at(r, indexOff + 24 * id, 24);
        long off = u64(d, 0), stored = u32(d, 8), logical = u32(d, 12), first = u32(d, 20);
        int codec = u8(d, 16);
        if (codec == 0) {
            long s0 = in / CHECK, s1 = (in + n - 1) / CHECK;
            long a = s0 * CHECK, z = Math.min((s1 + 1) * CHECK, logical);
            byte[] b = at(r, off + a, (int) (z - a));
            byte[] crc = at(r, integrityOff + 4 * (first + s0), (int) (4 * (s1 - s0 + 1)));
            for (long s = s0; s <= s1; s++) {
                int p = (int) ((s - s0) * CHECK), len = (int) Math.min(CHECK, z - s * CHECK);
                if (crc32c(b, p, len) != (int) u32(crc, (int) (4 * (s - s0)))) throw damaged(f, id);
            }
            System.arraycopy(b, (int) (in - a), out, o, n);
            return;
        }
        byte[] b = decoded(r, f, id, off, stored, logical, first);
        System.arraycopy(b, (int) in, out, o, n);
    }

    private IOException damaged(Nup.Node f, long id) {
        return new IOException(name + ": " + f.name + ": block " + id + " does not match its CRC-32C: the file is damaged");
    }

    /** A ZSTD block decoded whole and all its slices checked (from the cache when it is there) */
    private byte[] decoded(Nup.Source r, Nup.Node f, long id, long off, long stored, long logical, long first) throws IOException {
        String key = r.key + "#" + id;
        synchronized (cache) {
            byte[] b = cache.get(key);
            if (b != null) return b;
        }
        byte[] z = at(r, off, (int) stored);
        byte[] b;
        try {
            if (Zstd.contentSize(z) != logical) throw new IOException("its frame does not declare its " + logical + " bytes");
            b = Zstd.decode(z, 0, z.length, -1);
        } catch (IOException e) {
            throw new IOException(name + ": " + f.name + ": block " + id + ": " + e.getMessage() + ": the file is damaged", e);
        }
        if (b.length != logical) throw damaged(f, id);
        int slices = (int) ((logical + CHECK - 1) / CHECK);
        byte[] crc = at(r, integrityOff + 4 * first, 4 * slices);
        for (int s = 0; s < slices; s++) {
            int len = (int) Math.min(CHECK, logical - (long) s * CHECK);
            if (crc32c(b, s * CHECK, len) != (int) u32(crc, 4 * s)) throw damaged(f, id);
        }
        synchronized (cache) {
            cache.put(key, b);
            cacheBytes += b.length;
            while (cacheBytes > CACHE_BYTES && !cache.isEmpty()) {
                String k = cache.keySet().iterator().next();
                cacheBytes -= cache.remove(k).length;
            }
        }
        return b;
    }

    /** A file's bytes: max &lt; 0 all of them (checked against its SHA-256 too), else its first min(size, max) */
    byte[] read(Nup.Source r, Nup.Node f, long max) throws IOException {
        if (f.dir) throw new IOException(f.name + " is a directory");
        long n = max < 0 ? f.size : Math.min(f.size, max);
        if (n > Integer.MAX_VALUE - 16) throw new IOException(f.name + " is too large to read into memory");
        byte[] b = read(r, f, 0, (int) n);
        if (max < 0 && f.sha256 != null && !Arrays.equals(Nup.sha256().digest(b), f.sha256)) throw new IOException(name + ": " + f.name + " does not match its SHA-256: the file is damaged");
        return b;
    }

    /** A file's bytes into h, block by block (never the whole file in memory), checked against its SHA-256 */
    void hashTo(Nup.Source r, Nup.Node f, MessageDigest h) throws IOException {
        if (f.dir) throw new IOException(f.name + " is a directory");
        if (f.codec == Nup.CODEC_NCA) {   // a file of an NCA snapshot: no SHA-256 of its own (its blocks have their CRC-32C)
            for (long pos = 0; pos < f.size; pos += 1 << 20) h.update(read(r, f, pos, (int) Math.min(1 << 20, f.size - pos)));
            return;
        }
        MessageDigest own = Nup.sha256();
        byte[] rec = at(r, fileTab + 64 * f.index, 64);
        long chunk = 1L << u8(rec, 16);
        for (long pos = 0; pos < f.size; pos += chunk) {
            byte[] b = read(r, f, pos, (int) Math.min(chunk, f.size - pos));
            own.update(b);
            h.update(b);
        }
        if (!Arrays.equals(own.digest(), f.sha256)) throw new IOException(name + ": " + f.name + " does not match its SHA-256: the file is damaged");
    }

    // ---- Metadata (the game cards) ----

    /** A package's display version (its snapshot's), null when it has none */
    String displayVersion(Nup.Pkg p) {
        Integer si = snapOf.get(p.index);
        if (si == null) return null;
        int at = (int) (u64(cat, 64) + 120L * si);
        return str(u32(cat, at + 24), u32(cat, at + 28));
    }

    /** An icon of the Media region (PNG or JPEG), its SHA-256 checked; null when absent or not a picture */
    private byte[] icon(Nup.Source r, long index) throws IOException {
        if (index == NONE || mediaSize == 0) return null;
        byte[] mh = at(r, mediaOff, 32);
        if (index >= u32(mh, 16)) return null;
        byte[] rec = at(r, mediaOff + 32 + 64 * index, 64);
        int type = u16(rec, 0);
        long stored = u64(rec, 4), off = u64(rec, 20);
        if ((type != 1 && type != 2) || rec[2] != 0 || stored < 8 || stored > ICON_MAX || !in(off, stored, mediaSize)) return null;
        byte[] b = at(r, mediaOff + off, (int) stored);
        if (!Arrays.equals(Nup.sha256().digest(b), Arrays.copyOfRange(rec, 28, 60))) return null;
        boolean jpeg = (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8, png = (b[0] & 0xff) == 0x89 && b[1] == 'P';
        return jpeg || png ? b : null;
    }

    private static final String[] PREFERRED = {"en-us", "en-gb", "en"};

    /**
     * The game card's details (Nup.Meta's shape): the application's default title, publisher and icon from the Catalog, else its localized entries' (English
     * first); the version is the newest update's display version, else the base's
     */
    Nup.Meta meta(Nup.Source r) throws IOException {
        Nup.Meta out = new Nup.Meta();
        int at = (int) (u64(cat, 48) + 80L * app);
        out.title = str(u32(cat, at + 28), u32(cat, at + 32));
        out.publisher = str(u32(cat, at + 36), u32(cat, at + 40));
        out.icon = icon(r, u32(cat, at + 44));
        long fl = u32(cat, at + 48), lc = u32(cat, at + 52), loc = u64(cat, 72);
        List<Integer> order = new ArrayList<>();
        for (String want : PREFERRED)
            for (long i = fl; i < fl + lc; i++) {
                int l = (int) (loc + 24 * i);
                if (want.equals(str(u32(cat, l), u32(cat, l + 4))) && !order.contains((int) i)) order.add((int) i);
            }
        for (long i = fl; i < fl + lc; i++) if (!order.contains((int) i)) order.add((int) i);
        for (int i : order) {
            int l = (int) (loc + 24L * i);
            if (out.title == null) out.title = str(u32(cat, l + 8), u32(cat, l + 12));
            if (out.icon == null) out.icon = icon(r, u32(cat, l + 16));
        }
        Nup.Pkg ver = null;
        for (Nup.Pkg p : packages) if (p.kind == Nup.UPDATE && p.program == 0 && (ver == null || p.version > ver.version)) ver = p;
        if (ver == null) for (Nup.Pkg p : packages) if (p.kind == Nup.BASE && p.program == 0) ver = p;
        out.version = ver == null ? null : displayVersion(ver);
        if (out.title != null && out.title.trim().isEmpty()) out.title = null;
        return out;
    }

    // ---- NCA snapshots (a nested class, so that every runner that compiles Nx.java gets it) ----

    /**
     * An NCA snapshot of an .nx (android/NX.md "NCA snapshots"): the decrypted NCAs of an NSP or XCI import, read as the package trees an extracted snapshot has.
     * {@link #packages} reads the snapshot's CNMTs and gives its packages (base, update/&lt;v&gt;, addon/&lt;tid&gt;; program&lt;n&gt;/ by the Program record's ID
     * offset), each a {@link View} whose exefs/ is the Program NCA's PartitionFS section and whose romfs/ is the RomFS of its RomFS section. Nx gives their nodes
     * virtual IDs ({@link #VBASE}) and reads them here. The header, CNMT, PartitionFS and RomFS layouts are Nsp.java's (a plaintext NSP), written again over a
     * {@link Storage} because these bytes come through the .nx's blocks, not from a file. A file's bytes come through, from the top:
     * <ol>
     * <li>the RomFS or PartitionFS data (a range of the section's file system data),</li>
     * <li>compressed storage, when the compression info (FS header 0x178) is set ({@link Compressed}, an LZ4 block decoder of its own),</li>
     * <li>the hash layer's data: IVFC level 6 (RomFS) or the PFS0 region of the HierarchicalSha256 data (its hashes are not checked: the .nx blocks have their
     *     CRC-32C),</li>
     * <li>for an update's RomFS, the patch: an indirect storage (BKTR, {@link Indirect}) over the base's raw section (storage 0) and the update's own (storage 1),</li>
     * <li>the section's bytes of the NCA file, then the .nx blocks (Nx.read).</li>
     * </ol>
     * The order is Atmosphère's (libstratosphere fssystem_nca_file_system_driver.cpp, NcaFileSystemDriver::OpenStorageImpl: body, decryption, indirect storage
     * with storage 0 the original section and storage 1 the patch's own data up to the indirect table, then CreateStorageByRawStorage: the hash layer, then
     * CreateCompressedStorage over the hash layer's output, its table at compression_info.bucket.offset of that output), and the bucket tree layout is its
     * fssystem_bucket_tree.hpp (0x4000-byte nodes with a 0x10-byte header {index, count, offset}; QueryNodeStorageSize = (1 + GetNodeL2Count) nodes, the entry
     * sets after them), checked 2026-11-19. Nothing here is decrypted: an NCA that is not plain is refused. No Android classes: NxJvmTest runs it on the JVM.
     */
    static final class NxNca {
        private NxNca() {}

        /** Virtual node IDs: VBASE + view &lt;&lt; 34 + kind &lt;&lt; 32 + local (kind 0: the root (0) and exefs/ (1); 1: a RomFS directory by its table offset; 2: an ExeFS file by its index; 3: a RomFS file by its table offset) */
        static final long VBASE = 1L << 48;
        private static final int ROOT = 0, EXEFS = 1, RDIR = 1, XFILE = 2, RFILE = 3;

        static long vid(int view, int kind, long local) { return VBASE + ((long) view << 34) + ((long) kind << 32) + local; }

        static boolean virtual(long id) { return id >= VBASE; }

        private static void need(boolean ok, String what) throws IOException { if (!ok) throw new IOException(what); }

        private static long u32(byte[] b, int o) { return Nup.u32(b, o); }
        private static long u64(byte[] b, int o) { return Nup.u64(b, o); }

        // ---- Storages ----

        /** Bytes by position, through the archive's source */
        interface Storage {
            long size();

            void read(Nup.Source r, long off, byte[] b, int o, int n) throws IOException;
        }

        static byte[] read(Storage s, Nup.Source r, long off, long n) throws IOException {
            need(n >= 0 && n < Integer.MAX_VALUE - 16 && off >= 0 && off <= s.size() && n <= s.size() - off, "a read outside its storage");
            byte[] b = new byte[(int) n];
            s.read(r, off, b, 0, (int) n);
            return b;
        }

        /** An NCA file of the snapshot, through Nx's block reader (each slice's CRC-32C checked) */
        static final class FileStorage implements Storage {
            final Nx nx;
            final Nup.Node f;

            FileStorage(Nx nx, Nup.Node f) { this.nx = nx; this.f = f; }

            public long size() { return f.size; }

            public void read(Nup.Source r, long off, byte[] b, int o, int n) throws IOException {
                byte[] x = nx.read(r, f, off, n);
                System.arraycopy(x, 0, b, o, n);
            }
        }

        /** A range of another storage */
        static final class Sub implements Storage {
            final Storage s;
            final long at, size;

            Sub(Storage s, long at, long size) throws IOException {
                need(at >= 0 && size >= 0 && at <= s.size() && size <= s.size() - at, "a range outside its storage (" + at + " + " + size + " of " + s.size() + ")");
                this.s = s; this.at = at; this.size = size;
            }

            public long size() { return size; }

            public void read(Nup.Source r, long off, byte[] b, int o, int n) throws IOException {
                need(off >= 0 && n >= 0 && off <= size && n <= size - off, "a read outside its range");
                s.read(r, at + off, b, o, n);
            }
        }

        /** A bucket tree's entries (fssystem_bucket_tree.hpp): the raw entries, in order, and the tree's end offset */
        static final class Table {
            byte[] entries;
            int count, width;
            long end;

            long virt(int i) { return u64(entries, i * width); }
        }

        static final int NODE = 0x4000;

        /** The bucket tree of count entries of width bytes at [off, off + size) of s */
        static Table table(Storage s, Nup.Source r, long off, long size, long count, int width) throws IOException {
            need(count > 0 && count < (1 << 26), "a table of " + count + " entries");
            int perSet = (NODE - 0x10) / width, perNode = (NODE - 0x10) / 8;
            long sets = (count + perSet - 1) / perSet, l2 = 0;
            if (sets > perNode) {
                long nodeL2 = (sets + perNode - 1) / perNode;
                l2 = (sets - (perNode - (nodeL2 - 1)) + perNode - 1) / perNode;
            }
            long nodes = (long) NODE * (1 + l2);
            need(nodes + sets * NODE <= size, "a bucket table larger than its range");
            Table t = new Table();
            t.width = width;
            t.count = (int) count;
            t.end = u64(read(s, r, off, 0x10), 8);
            t.entries = new byte[(int) count * width];
            int at = 0;
            for (long j = 0; j < sets; j++) {
                byte[] set = read(s, r, off + nodes + j * NODE, NODE);
                int n = (int) u32(set, 4);
                need(n >= 1 && n <= perSet && at + n <= count, "a bucket with " + n + " entries");
                System.arraycopy(set, 0x10, t.entries, at * width, n * width);
                at += n;
            }
            need(at == count, "the buckets hold " + at + " entries, the header says " + count);
            for (int i = 1; i < t.count; i++) need(t.virt(i) > t.virt(i - 1), "unsorted bucket entries");
            need(t.virt(0) == 0 && t.end > t.virt(t.count - 1), "the bucket table's offsets");
            return t;
        }

        /** The entry that covers pos (the last whose virtual offset is at most pos) */
        private static int find(Table t, long pos) {
            int lo = 0, hi = t.count - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (t.virt(mid) <= pos) lo = mid; else hi = mid - 1;
            }
            return lo;
        }

        /** The patch: entries {virtual offset, physical offset, storage index} over storage 0 (the base's section) and 1 (the update's own data) */
        static final class Indirect implements Storage {
            final Table t;
            final Storage[] st;

            Indirect(Table t, Storage base, Storage own) throws IOException {
                need(t.width == 0x14, "indirect entries");
                this.t = t;
                this.st = new Storage[] {base, own};
            }

            public long size() { return t.end; }

            public void read(Nup.Source r, long off, byte[] b, int o, int n) throws IOException {
                need(off >= 0 && n >= 0 && off <= t.end && n <= t.end - off, "a read past the patched section");
                while (n > 0) {
                    int k = find(t, off);
                    long v = t.virt(k), end = k + 1 < t.count ? t.virt(k + 1) : t.end;
                    long phys = u64(t.entries, k * 0x14 + 8);
                    int idx = (int) u32(t.entries, k * 0x14 + 16);
                    need(idx == 0 || idx == 1, "an indirect entry of storage " + idx);
                    int take = (int) Math.min(n, end - off);
                    st[idx].read(r, phys + (off - v), b, o, take);
                    off += take; o += take; n -= take;
                }
            }
        }

        /** Compressed storage: entries {virtual offset, physical offset, type (0 none, 1 zeros, 3 LZ4), level, reserved, physical size} over the data before the table */
        static final class Compressed implements Storage {
            final Table t;
            final Storage data;
            private int lastEntry = -1;
            private byte[] last;

            Compressed(Table t, Storage data) throws IOException {
                need(t.width == 0x18, "compression entries");
                this.t = t;
                this.data = data;
            }

            public long size() { return t.end; }

            public synchronized void read(Nup.Source r, long off, byte[] b, int o, int n) throws IOException {
                need(off >= 0 && n >= 0 && off <= t.end && n <= t.end - off, "a read past the compressed section");
                while (n > 0) {
                    int k = find(t, off);
                    int e = k * 0x18;
                    long v = t.virt(k), end = k + 1 < t.count ? t.virt(k + 1) : t.end, phys = u64(t.entries, e + 8), psize = u32(t.entries, e + 20);
                    int type = t.entries[e + 16] & 0xff;
                    int take = (int) Math.min(n, end - off);
                    if (type == 0) data.read(r, phys + (off - v), b, o, take);
                    else if (type == 1) Arrays.fill(b, o, o + take, (byte) 0);
                    else if (type == 3) {
                        need(end - v <= (64 << 20), "a compressed block of " + (end - v) + " bytes");
                        if (lastEntry != k) {
                            last = lz4(NxNca.read(data, r, phys, psize), (int) (end - v));
                            lastEntry = k;
                        }
                        System.arraycopy(last, (int) (off - v), b, o, take);
                    } else throw new IOException("compression type " + type + ", which this app does not read");
                    off += take; o += take; n -= take;
                }
            }
        }

        /** An LZ4 block (lz4 block format: tokens of literal and match lengths, 16-bit offsets), decoded to exactly n bytes */
        static byte[] lz4(byte[] src, int n) throws IOException {
            byte[] out = new byte[n];
            int ip = 0, op = 0;
            try {
                while (true) {
                    int token = src[ip++] & 0xff, lit = token >>> 4;
                    if (lit == 15) { int x; do { x = src[ip++] & 0xff; lit += x; } while (x == 255); }
                    need(lit <= src.length - ip && lit <= n - op, "LZ4: literals past the end");
                    System.arraycopy(src, ip, out, op, lit);
                    ip += lit; op += lit;
                    if (ip >= src.length) break;   // the last sequence has literals only
                    int dist = (src[ip] & 0xff) | (src[ip + 1] & 0xff) << 8;
                    ip += 2;
                    need(dist > 0 && dist <= op, "LZ4: a match before the start");
                    int len = token & 15;
                    if (len == 15) { int x; do { x = src[ip++] & 0xff; len += x; } while (x == 255); }
                    len += 4;
                    need(len <= n - op, "LZ4: a match past the end");
                    for (int i = 0; i < len; i++, op++) out[op] = out[op - dist];
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IOException("LZ4: the block is cut off");
            }
            need(op == n, "LZ4: " + op + " bytes, the table says " + n);
            return out;
        }

        // ---- NCAs ----

        /** An NCA of the snapshot: its file, its plain header */
        static final class Nca {
            final String name;
            final Storage file;
            final byte[] h;
            final int contentType;
            final long programId;

            Nca(String name, Storage file, byte[] h) {
                this.name = name; this.file = file; this.h = h;
                contentType = h[0x205] & 0xff;
                programId = u64(h, 0x210);
            }

            /** Section i's FS header (checked: its SHA-256 in the header, version 2, no encryption, no sparse layer), null when the section is not there */
            byte[] fs(int i) throws IOException {
                long st = u32(h, 0x240 + 16 * i), en = u32(h, 0x244 + 16 * i);
                if (st == 0 && en == 0) return null;
                need(en > st && en * 0x200 <= file.size(), name + ": section " + i + " is outside the NCA");
                byte[] fh = Arrays.copyOfRange(h, 0x400 + 0x200 * i, 0x600 + 0x200 * i);
                need(Arrays.equals(Nup.sha256().digest(fh), Arrays.copyOfRange(h, 0x280 + 0x20 * i, 0x2A0 + 0x20 * i)), name + ": section " + i + "'s header does not match its SHA-256");
                need(Nup.u16(fh, 0) == 2, name + ": section " + i + " of FS header version " + Nup.u16(fh, 0));
                if ((fh[4] & 0xff) != 1) throw new Nsp.Refused(Nx.NCA, name + ": section " + i + " is encrypted (type " + (fh[4] & 0xff) + ")");
                for (int k = 0x148; k < 0x178; k++) if (fh[k] != 0) throw new Nsp.Refused(Nx.NCA, name + ": section " + i + " is sparse");
                return fh;
            }

            Storage raw(int i) throws IOException {
                return new Sub(file, u32(h, 0x240 + 16 * i) * 0x200, (u32(h, 0x244 + 16 * i) - u32(h, 0x240 + 16 * i)) * 0x200);
            }

            /** The first section of a file system type (0 RomFS, 1 PartitionFS), -1 when none */
            int first(int fsType) throws IOException {
                for (int i = 0; i < 4; i++) {
                    byte[] fh = fs(i);
                    if (fh != null && (fh[2] & 0xff) == fsType) return i;
                }
                return -1;
            }
        }

        static Nca nca(Nx nx, Nup.Source r, Nup.Node f) throws IOException {
            if (f.size < 0xC00) throw new Nsp.Refused(Nx.NCA, f.name + " is shorter than an NCA header");
            byte[] h = nx.read(r, f, 0, 0xC00);
            boolean plain = h[0x200] == 'N' && h[0x201] == 'C' && h[0x202] == 'A' && h[0x203] == '3' || h[0x200] == 'D' && h[0x201] == 'N' && h[0x202] == 'C' && h[0x203] == 'A';
            if (!plain) throw new Nsp.Refused(Nx.NCA, f.name + ": no plain NCA header (DNCA or NCA3): an encrypted NCA");
            return new Nca(f.name, new FileStorage(nx, f), h);
        }

        /**
         * Section i's file system data: the patch over base's section i when its patch info has an indirect table, then the hash layer's data (IVFC level 6 for
         * a RomFS, the PFS0 region for a PartitionFS), then the compressed storage when its compression info is set (the order: the class comment)
         */
        static Storage data(Nup.Source r, Nca n, int i, Nca base) throws IOException {
            byte[] fh = n.fs(i);
            Storage raw = n.raw(i), st = raw;
            int fsType = fh[2] & 0xff, hashType = fh[3] & 0xff;
            long indOff = u64(fh, 0x100), indSize = u64(fh, 0x108);
            if (indSize != 0) {
                need(fh[0x110] == 'B' && fh[0x111] == 'K' && fh[0x112] == 'T' && fh[0x113] == 'R', n.name + ": section " + i + ": no BKTR indirect table");
                if (base == null) throw new Nsp.Refused(Nx.NCA, n.name + ": section " + i + " is a patch, and the snapshot has no base Program NCA for it");
                need(base.fs(i) != null, base.name + ": no section " + i + " for the patch");
                st = new Indirect(table(raw, r, indOff, indSize, u32(fh, 0x118), 0x14), base.raw(i), new Sub(raw, 0, indOff));
            }
            Storage fsData;
            if (fsType == 0 && hashType == 3) {   // RomFS, IVFC: the data level is the last
                need(fh[8] == 'I' && fh[9] == 'V' && fh[10] == 'F' && fh[11] == 'C', n.name + ": section " + i + ": no IVFC header");
                long lv = u32(fh, 0x14);
                need(lv >= 3 && lv <= 7, n.name + ": section " + i + ": " + lv + " IVFC levels");
                int at = 0x18 + 24 * (int) (lv - 2);
                fsData = new Sub(st, u64(fh, at), u64(fh, at + 8));
            } else if (fsType == 1 && hashType == 2) fsData = new Sub(st, u64(fh, 0x40), u64(fh, 0x48));   // PartitionFS, HierarchicalSha256
            else throw new Nsp.Refused(Nx.NCA, n.name + ": section " + i + ": file system " + fsType + " with hash type " + hashType + ", which this app does not read");
            long cOff = u64(fh, 0x178), cSize = u64(fh, 0x180);
            if (cSize == 0) return fsData;
            need(fh[0x188] == 'B' && fh[0x189] == 'K' && fh[0x18A] == 'T' && fh[0x18B] == 'R', n.name + ": section " + i + ": no BKTR compression table");
            return new Compressed(table(fsData, r, cOff, cSize, u32(fh, 0x190), 0x18), new Sub(fsData, 0, cOff));
        }

        /** A PartitionFS's entries: names, offsets (from its data), sizes */
        static final class Pfs {
            final List<String> names = new ArrayList<>();
            final List<long[]> ents = new ArrayList<>();   // offset, size
            long data;
        }

        static Pfs pfs(Storage s, Nup.Source r, String what) throws IOException {
            need(s.size() >= 0x10, what + ": not a PartitionFS");
            byte[] h = read(s, r, 0, 0x10);
            need(h[0] == 'P' && h[1] == 'F' && h[2] == 'S' && h[3] == '0', what + ": not a PartitionFS");
            long n = u32(h, 4), ss = u32(h, 8);
            need(n <= 65536 && ss <= (1 << 20) && 0x10 + 0x18 * n + ss <= s.size(), what + ": the PartitionFS header is cut off");
            byte[] t = read(s, r, 0x10, 0x18 * n + ss);
            Pfs p = new Pfs();
            p.data = 0x10 + 0x18 * n + ss;
            for (int i = 0; i < n; i++) {
                long off = u64(t, 0x18 * i), sz = u64(t, 0x18 * i + 8), no = u32(t, 0x18 * i + 16);
                need(no < ss && off >= 0 && sz >= 0 && off <= s.size() - p.data && sz <= s.size() - p.data - off, what + ": entry " + i + " is outside the PartitionFS");
                int a = (int) (0x18 * n + no), e = a;
                while (e < t.length && t[e] != 0) e++;
                String name = new String(t, a, e - a, StandardCharsets.UTF_8);
                need(!name.isEmpty() && name.indexOf('/') < 0 && !name.equals(".") && !name.equals(".."), what + ": entry " + i + " has a bad name");
                p.names.add(name);
                p.ents.add(new long[] {off, sz});
            }
            return p;
        }

        /** A CNMT: title ID, version, type, its records (content ID as 32 lower-case hex digits, record type, ID offset) */
        static final class Cnmt {
            long id, version;
            int type;
            final List<Object[]> records = new ArrayList<>();
        }

        static Cnmt cnmt(Nup.Source r, Nca n) throws IOException {
            need(n.contentType == 1, n.name + ": not a meta NCA");
            int s = n.first(1);
            need(s >= 0, n.name + ": no PartitionFS section");
            Storage d = data(r, n, s, null);
            Pfs p = pfs(d, r, n.name);
            need(p.names.size() == 1 && p.names.get(0).endsWith(".cnmt") && p.ents.get(0)[1] >= 0x30 && p.ents.get(0)[1] <= (1 << 20), n.name + ": expected one .cnmt");
            byte[] c = read(d, r, p.data + p.ents.get(0)[0], p.ents.get(0)[1]);
            Cnmt m = new Cnmt();
            m.id = u64(c, 0);
            m.version = u32(c, 8);
            m.type = c[12] & 0xff;
            int exs = Nup.u16(c, 14), cnt = Nup.u16(c, 16);
            need(0x20L + exs + 0x38L * cnt <= c.length, n.name + ": the .cnmt is cut off");
            for (int j = 0; j < cnt; j++) {
                int at = 0x20 + exs + 0x38 * j;
                StringBuilder id = new StringBuilder();
                for (int q = 0; q < 16; q++) id.append(String.format(Locale.ROOT, "%02x", c[at + 0x20 + q] & 0xff));
                m.records.add(new Object[] {id.toString(), c[at + 0x36] & 0xff, c[at + 0x37] & 0xff});
            }
            return m;
        }

        // ---- Packages and their trees ----

        /** A RomFS of an NCA section (the package's romfs/, or its data/: the control NCA's), its tables read at the first use */
        static final class Rom {
            final Nca nca, base;
            final int sec;
            private Storage s;
            private byte[] dt, ft;
            private long data;

            Rom(Nca nca, int sec, Nca base) { this.nca = nca; this.sec = sec; this.base = base; }

            synchronized void open(Nup.Source r) throws IOException {
                if (dt != null) return;
                Storage st = data(r, nca, sec, base);
                String what = nca.name + " (RomFS)";
                need(st.size() >= 0x50, what + ": no RomFS header");
                byte[] h = read(st, r, 0, 0x50);
                need(u64(h, 0) == 0x50, what + ": no RomFS header");
                long dto = u64(h, 0x18), dts = u64(h, 0x20), fto = u64(h, 0x38), fts = u64(h, 0x40), d = u64(h, 0x48);
                need(dts >= 0x18 && dts <= (64 << 20) && fts <= (64 << 20) && dto <= st.size() && dts <= st.size() - dto && fto <= st.size() && fts <= st.size() - fto && d <= st.size(),
                        what + ": its tables are outside it");
                ft = read(st, r, fto, fts);
                dt = read(st, r, dto, dts);
                data = d;
                s = st;
            }
        }

        /**
         * One package's tree: exefs/ (the Program NCA's PartitionFS section), romfs/ (its RomFS section, patched for an update) and data/ (the control NCA's
         * RomFS: control.nacp and the icons, where an extracted snapshot keeps its control data), each opened at the first read
         */
        static final class View {
            final int id;
            final Nca exefsNca;
            final int exefsSec;
            final Rom romfs, ctl;   // null when the package has none
            private Storage exefs;
            private Pfs pfs;

            View(int id, Nca exefsNca, int exefsSec, Rom romfs, Rom ctl) { this.id = id; this.exefsNca = exefsNca; this.exefsSec = exefsSec; this.romfs = romfs; this.ctl = ctl; }

            synchronized Pfs exefs(Nup.Source r) throws IOException {
                if (pfs == null) {
                    exefs = data(r, exefsNca, exefsSec, null);
                    pfs = pfs(exefs, r, exefsNca.name + " (ExeFS)");
                }
                return pfs;
            }

            Rom tree(long local) { return (local & DATA) != 0 ? ctl : romfs; }
        }

        /** The flag of a local ID in the data/ tree (RomFS table offsets are below 64 MiB) */
        private static final long DATA = 1L << 30;

        /** The control NCA of a program (the CNMT's Control record of that ID offset) as a data/ tree, null when the CNMT names none */
        private static Rom control(Nx nx, Nup.Source r, Map<String, Nup.Node> files, Cnmt m, int prog, String where) throws IOException {
            for (Object[] rec : m.records)
                if ((int) rec[1] == 3 && (int) rec[2] == prog) {   // ncm content type 3: control
                    Nca c = need(nx, r, files, (String) rec[0], where);
                    int s = c.first(0);
                    return s < 0 ? null : new Rom(c, s, null);
                }
            return null;
        }

        /** The packages of an NCA snapshot: its CNMT of the snapshot's type (application, patch or add-on), one package per program (or the add-on's data) */
        static List<Nup.Pkg> packages(Nx nx, Nup.Source r, int snapType, Map<String, Nup.Node> files, String where) throws IOException {
            int want = snapType == 0 ? 0x80 : snapType == 2 ? 0x82 : 0x81;
            Cnmt main = null, base = null;
            List<Nca> metas = new ArrayList<>();
            for (Map.Entry<String, Nup.Node> e : files.entrySet()) if (e.getKey().endsWith(".cnmt.nca")) metas.add(nca(nx, r, e.getValue()));
            for (Nca m : metas) {
                Cnmt c = cnmt(r, m);
                if (c.type == want) {
                    if (main != null) throw new Nsp.Refused(Nx.UNKNOWN, where + ": two content metas of type 0x" + Integer.toHexString(want));
                    main = c;
                }
                if (c.type == 0x80 && want == 0x81) base = c;
            }
            if (main == null) throw new Nsp.Refused(Nx.UNKNOWN, where + ": no content meta of type 0x" + Integer.toHexString(want) + " for a snapshot of type " + snapType);
            List<Nup.Pkg> out = new ArrayList<>();
            if (want == 0x82) {
                Nca d = null;
                for (Object[] rec : main.records) if ((int) rec[1] == 2 && d == null) d = need(nx, r, files, (String) rec[0], where);   // ncm content type 2: data (as Nsp.java reads a DLC)
                if (d == null) throw new Nsp.Refused(Nx.UNKNOWN, where + ": the add-on's content meta names no data NCA");
                int s = d.first(0);
                int v = view(nx, new View(nx.views.size(), null, -1, s < 0 ? null : new Rom(d, s, null), null));
                out.add(new Nup.Pkg(0, main.id, vid(v, 0, ROOT), Nup.NONE, s < 0 ? Nup.NONE : vid(v, RDIR, 0), main.version, Nup.ADDON, 0));
                return out;
            }
            for (Object[] rec : main.records) {
                if ((int) rec[1] != 1) continue;   // the Program NCAs (control, data, delta fragments are not read)
                int prog = (int) rec[2];
                need(prog <= 15, where + ": a program of ID offset " + prog);
                Nca p = need(nx, r, files, (String) rec[0], where);
                Nca b = null;
                int rs = p.first(0), xs = p.first(1);
                if (rs >= 0 && u64(p.fs(rs), 0x108) != 0) {   // a patch: the base's Program NCA of the same program index, named by the base's CNMT in the snapshot
                    if (base != null) for (Object[] br : base.records) if ((int) br[1] == 1 && (int) br[2] == prog) b = need(nx, r, files, (String) br[0], where);
                    if (b == null) throw new Nsp.Refused(Nx.NCA, where + ": the update's RomFS of program " + prog + " is a patch, and the snapshot has no base Program NCA for it");
                }
                int v = view(nx, new View(nx.views.size(), xs < 0 ? null : p, xs, rs < 0 ? null : new Rom(p, rs, b), control(nx, r, files, main, prog, where)));
                Nup.Pkg pk = new Nup.Pkg(0, p.programId, vid(v, 0, ROOT), xs < 0 ? Nup.NONE : vid(v, 0, EXEFS), rs < 0 ? Nup.NONE : vid(v, RDIR, 0),
                        want == 0x80 ? 0 : main.version, want == 0x80 ? Nup.BASE : Nup.UPDATE, snapType == 3 ? 1 : 0);
                pk.program = prog;
                out.add(pk);
            }
            return out;
        }

        private static int view(Nx nx, View v) {
            nx.views.add(v);
            return v.id;
        }

        private static Nca need(Nx nx, Nup.Source r, Map<String, Nup.Node> files, String contentId, String where) throws IOException {
            Nup.Node f = files.get(contentId + ".nca");
            if (f == null) throw new Nsp.Refused(Nx.NCA, where + ": content " + contentId + ".nca, which its content meta names, is not in the snapshot");
            return nca(nx, r, f);
        }

        // ---- Nodes ----

        private static View viewOf(Nx nx, long id) throws IOException {
            long v = (id - VBASE) >>> 34;
            need(v < nx.views.size(), "a virtual node of no view");
            return nx.views.get((int) v);
        }

        private static int kind(long id) { return (int) (((id - VBASE) >>> 32) & 3); }

        private static long local(long id) { return (id - VBASE) & 0xFFFFFFFFL; }

        /** A virtual directory by its ID (its name is not known from the ID: "") with its entry count */
        static Nup.Node dir(Nx nx, Nup.Source r, long id) throws IOException {
            return new Nup.Node(true, id, "", 0, 0, 0, 0, null, 0, children(nx, r, id).size());
        }

        static List<Nup.Node> children(Nx nx, Nup.Source r, long id) throws IOException {
            View v = viewOf(nx, id);
            int k = kind(id);
            long l = local(id);
            List<Nup.Node> out = new ArrayList<>();
            if (k == 0 && l == ROOT) {
                if (v.ctl != null) out.add(new Nup.Node(true, vid(v.id, RDIR, DATA), "data", 0, 0, 0, 0, null, 0, romfsKids(v, r, DATA).size()));   // "data" < "exefs" < "romfs"
                if (v.exefsNca != null) out.add(new Nup.Node(true, vid(v.id, 0, EXEFS), "exefs", 0, 0, 0, 0, null, 0, v.exefs(r).names.size()));
                if (v.romfs != null) out.add(new Nup.Node(true, vid(v.id, RDIR, 0), "romfs", 0, 0, 0, 0, null, 0, romfsKids(v, r, 0).size()));
                return out;
            }
            if (k == 0 && l == EXEFS) {
                Pfs p = v.exefs(r);
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < p.names.size(); i++) order.add(i);
                order.sort((a, b) -> compare(p.names.get(a), p.names.get(b)));
                for (int i : order) out.add(new Nup.Node(false, vid(v.id, XFILE, i), p.names.get(i), p.ents.get(i)[1], 0, 0, Nup.CODEC_NCA, null, 0, 0));
                return out;
            }
            if (k == RDIR) return romfsKids(v, r, l);
            return out;
        }

        private static int compare(String a, String b) {
            byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < Math.min(x.length, y.length); i++) if (x[i] != y[i]) return (x[i] & 0xff) - (y[i] & 0xff);
            return x.length - y.length;
        }

        /** A RomFS directory's entries (its table offset d), sorted bytewise as a Manifest's are */
        private static List<Nup.Node> romfsKids(View v, Nup.Source r, long local) throws IOException {
            Rom t = v.tree(local);
            need(t != null, "no such tree");
            t.open(r);
            long flag = local & DATA, d = local & ~DATA;
            byte[] dt = t.dt, ft = t.ft;
            need(d + 0x18 <= dt.length, "a RomFS directory outside its table");
            List<Nup.Node> out = new ArrayList<>();
            long left = dt.length / 0x18 + 1;
            for (long c = u32(dt, (int) d + 8); c != 0xFFFFFFFFL; c = u32(dt, (int) c + 4)) {
                need(left-- > 0 && c + 0x18 <= dt.length, "a bad or cyclic RomFS directory");
                long nl = u32(dt, (int) c + 20);
                need(nl <= dt.length - c - 0x18, "a RomFS directory name out of bounds");
                String name = new String(dt, (int) c + 0x18, (int) nl, StandardCharsets.UTF_8);
                long kids = 0;
                for (long x = u32(dt, (int) c + 8); x != 0xFFFFFFFFL && kids <= dt.length; x = x + 0x18 <= dt.length ? u32(dt, (int) x + 4) : 0xFFFFFFFFL) kids++;
                for (long x = u32(dt, (int) c + 12); x != 0xFFFFFFFFL && kids <= dt.length + ft.length; x = x + 0x20 <= ft.length ? u32(ft, (int) x + 4) : 0xFFFFFFFFL) kids++;
                out.add(new Nup.Node(true, vid(v.id, RDIR, flag | c), name, 0, 0, 0, 0, null, 0, kids));
            }
            left = ft.length / 0x20 + 1;
            for (long c = u32(dt, (int) d + 12); c != 0xFFFFFFFFL; c = u32(ft, (int) c + 4)) {
                need(left-- > 0 && c + 0x20 <= ft.length, "a bad or cyclic RomFS file");
                long nl = u32(ft, (int) c + 28), sz = u64(ft, (int) c + 16);
                need(nl <= ft.length - c - 0x20, "a RomFS file name out of bounds");
                out.add(new Nup.Node(false, vid(v.id, RFILE, flag | c), new String(ft, (int) c + 0x20, (int) nl, StandardCharsets.UTF_8), sz, 0, 0, Nup.CODEC_NCA, null, 0, 0));
            }
            out.sort((a, b) -> compare(a.name, b.name));
            return out;
        }

        /** Bytes [off, off + n) of a virtual file */
        static byte[] read(Nx nx, Nup.Source r, Nup.Node f, long off, int n) throws IOException {
            View v = viewOf(nx, f.index);
            int k = kind(f.index);
            long l = local(f.index);
            need(off >= 0 && n >= 0 && off <= f.size && n <= f.size - off, f.name + ": a read past its end");
            byte[] b = new byte[n];
            if (n == 0) return b;
            if (k == XFILE) {
                Pfs p = v.exefs(r);
                v.exefs.read(r, p.data + p.ents.get((int) l)[0] + off, b, 0, n);
            } else if (k == RFILE) {
                Rom t = v.tree(l);
                need(t != null, f.name + ": no such tree");
                t.open(r);
                long fo = u64(t.ft, (int) (l & ~DATA) + 8);
                need(fo >= 0 && fo + f.size <= t.s.size() - t.data, f.name + ": outside the RomFS");
                t.s.read(r, t.data + fo + off, b, 0, n);
            } else throw new IOException(f.name + " is a directory");
            return b;
        }
    }
}
