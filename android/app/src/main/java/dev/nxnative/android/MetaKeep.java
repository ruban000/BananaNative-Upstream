package dev.nxnative.android;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * A game card's details kept on disk between runs (GameMeta: the app's cache folder), with no Android classes so the JVM test (GamesFolderJvmTest) checks it.
 * One file per card view, named by the SHA-256 of what it is for ({@link #keptAs}: the game's path, which control data (a Switch 2 Edition's program 1), the
 * phone's language and the app build); inside, the identity of the files it was read from (path or document, size, time) and the details: version,
 * publisher, and the icon's own bytes (the JPEG or PNG exactly as the game file has it; GameMeta scales it in memory to the size it is shown at). A start shows
 * what is kept at once; the files are read again only when their identity is not the kept one.
 */
final class MetaKeep {
    private MetaKeep() {}

    private static final String MAGIC = "GAMEMETA2";

    /** What one kept file holds */
    static final class Kept {
        /** The identity of the files the details were read from */
        final String key;
        /** Whether the files have any details (false: they were read and have none) */
        final boolean has;
        final String version, publisher;
        /** The icon's bytes as the game file has them, null when none */
        final byte[] icon;

        Kept(String key, boolean has, String version, String publisher, byte[] icon) {
            this.key = key; this.has = has; this.version = version; this.publisher = publisher; this.icon = icon;
        }

        /** Whether this was read from files of that identity (unchanged since: nothing to read again) */
        boolean current(String identity) { return identity != null && identity.equals(key); }
    }

    /** What a kept file is for: the game's path, its control data's name ("" for the game's own), the phone's language and the app build */
    static String keptAs(String path, String control, String language, String appVersion) {
        return path + "\n" + (control == null ? "" : control) + "\n" + (language == null ? "" : language) + "\n" + (appVersion == null ? "" : appVersion);
    }

    /** The identity of a folder dump's control data: control.nacp's path, size and time (the icons change with it) */
    static String folderKey(File control) {
        if (control == null) return null;
        File nacp = new File(control, "control.nacp");
        return nacp.isFile() ? nacp.getAbsolutePath() + "|" + nacp.length() + "|" + nacp.lastModified() : null;
    }

    static File file(File dir, String keptAs) {
        try {
            StringBuilder h = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(keptAs.getBytes(StandardCharsets.UTF_8))) h.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return new File(dir, h + ".meta");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What is kept for keptAs, null when nothing is (or another keptAs: another language or app build) */
    static Kept load(File dir, String keptAs) {
        if (dir == null) return null;
        File f = file(dir, keptAs);
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            if (!MAGIC.equals(in.readUTF()) || !keptAs.equals(in.readUTF())) return null;
            String key = in.readUTF();
            if (!in.readBoolean()) return new Kept(key, false, null, null, null);
            String version = in.readBoolean() ? in.readUTF() : null, publisher = in.readBoolean() ? in.readUTF() : null;
            int n = in.readInt();
            if (n < 0 || n > (16 << 20)) return null;
            byte[] icon = null;
            if (n > 0) {
                icon = new byte[n];
                in.readFully(icon);
            }
            return new Kept(key, true, version, publisher, icon);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Keeps k for keptAs (written whole, then renamed over the old one); nothing when it cannot be written (read from the files again next time) */
    static void keep(File dir, String keptAs, Kept k) {
        if (dir == null || k == null || k.key == null) return;
        File f = file(dir, keptAs);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeUTF(MAGIC);
            o.writeUTF(keptAs);
            o.writeUTF(k.key);
            o.writeBoolean(k.has);
            if (k.has) {
                o.writeBoolean(k.version != null);
                if (k.version != null) o.writeUTF(k.version);
                o.writeBoolean(k.publisher != null);
                if (k.publisher != null) o.writeUTF(k.publisher);
                o.writeInt(k.icon == null ? 0 : k.icon.length);
                if (k.icon != null) o.write(k.icon);
            }
            o.flush();
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(dir, f.getName() + ".tmp");
            Files.write(tmp.toPath(), bo.toByteArray());
            if (!tmp.renameTo(f)) {
                Files.deleteIfExists(f.toPath());
                if (!tmp.renameTo(f)) Files.deleteIfExists(tmp.toPath());
            }
        } catch (IOException | RuntimeException e) {
            // not kept: read from the files again next time
        }
    }
}
