package dev.nxnative.android;

import java.io.File;

/** CopyPlan on the JVM (dm/tools/tests/test_android_copyplan.py): where copies go, safe names, the space check. Prints "ok" or throws */
public final class CopyPlanJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    /** A path with '/', as the phone writes it (a Windows host writes its parts with '\') */
    private static String slashed(File f) { return f.getPath().replace('\\', '/'); }

    public static void main(String[] args) {
        File root = new File("/storage/emulated/0/Android/data/dev.nxnative.android/files");
        check(slashed(CopyPlan.games(root)).equals(slashed(root) + "/games"), "games folder");
        check(CopyPlan.isCopy(root, root + "/games/DKB"), "a copy");
        check(CopyPlan.isCopy(root, root + "/games/game.nup"), "a copied .nup");
        check(!CopyPlan.isCopy(root, root + "/games"), "the games folder itself is no copy");
        check(!CopyPlan.isCopy(root, root + "/gamesX/a"), "a sibling with the same prefix is no copy");
        check(!CopyPlan.isCopy(root, "/storage/emulated/0/Switch2/DKB"), "shared storage is no copy");
        check(!CopyPlan.isCopy(root, null), "null");
        check(CopyPlan.inApp(root, root + "/packs/x") && !CopyPlan.inApp(root, root + "x/y") && !CopyPlan.inApp(root, "/sdcard/x"), "inApp");
        check("base".equals(CopyPlan.safeName(" base ")) && "a b.nup".equals(CopyPlan.safeName("a b.nup")), "plain names");
        for (String bad : new String[] {null, "", " ", ".", "..", "a/b", "a\\b", "a\0b"}) check(CopyPlan.safeName(bad) == null, "refused: " + bad);
        check(CopyPlan.shortBy(100, 100, "x") == null && CopyPlan.shortBy(0, 5, "x") == null && CopyPlan.shortBy(-1, 5, "x") == null, "a full copy, or a provider that gave no size, passes");
        check(CopyPlan.shortBy(100, 60, "g.nup").contains("40 bytes short") && CopyPlan.shortBy(100, 130, "g.nup").contains("30 bytes long"), "a short or long copy is named");
        check(slashed(CopyPlan.partOf(new File("/x/games/DKB"))).equals("/x/games/DKB.part"), "part");
        long g = 1L << 30;
        check(CopyPlan.fits(10 * g, 13 * g) == null, "fits");
        check(CopyPlan.fits(10 * g, 11 * g) != null && CopyPlan.fits(10 * g, 11 * g).contains("10.0 GiB"), "does not fit with the reserve: " + CopyPlan.fits(10 * g, 11 * g));
        System.out.println("ok");
    }
}
