package dev.nxnative.android;

import java.util.HashMap;
import java.util.Map;

/**
 * OwnSetting on the JVM (dm/tools/tests/test_android_ownsetting.py): a game without its own value takes the main screen's default, key by key; a game's own value
 * wins; titleId null is the default; a group is the game's own when any key is; "Use Settings' default" removes exactly the game's keys. And PictureFit's size
 * choices (largest first) that Settings and Game settings list. Prints "ok".
 */
public final class OwnSettingJvmTest {
    private static final class Mem implements OwnSetting.Store {
        final Map<String, Object> m = new HashMap<>();
        @Override public boolean has(String k) { return m.containsKey(k); }
        @Override public boolean bool(String k, boolean d) { Object v = m.get(k); return v instanceof Boolean ? (Boolean) v : d; }
        @Override public int num(String k, int d) { Object v = m.get(k); return v instanceof Integer ? (Integer) v : d; }
        @Override public float real(String k, float d) { Object v = m.get(k); return v instanceof Float ? (Float) v : d; }
    }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] args) {
        String[] lsfg = {"lsfg", "lsfg_mult", "lsfg_target", "lsfg_flow", "lsfg_quality"};
        String game = "0400A3A01EE4C000", other = "040057C02159A000";
        Mem s = new Mem();

        // nothing set: the built-in defaults, for the main screen and every game
        check(!OwnSetting.bool(s, "lsfg", null, false), "built-in default off");
        check(!OwnSetting.bool(s, "lsfg", game, false), "a game takes the built-in default");
        check(OwnSetting.num(s, "lsfg_mult", game, 2) == 2, "built-in multiplier");
        check(!OwnSetting.own(s, lsfg, game), "no own value yet");

        // the main screen turns frame generation on: every game without its own follows
        s.m.put(OwnSetting.key("lsfg", null), true);
        s.m.put(OwnSetting.key("lsfg_mult", null), 3);
        check(OwnSetting.bool(s, "lsfg", game, false) && OwnSetting.bool(s, "lsfg", other, false), "games follow the default");
        check(OwnSetting.num(s, "lsfg_mult", game, 2) == 3, "multiplier follows the default");

        // one game turns it off (the in-game panel writes the game's own): only that game changes, its other keys still follow the default
        s.m.put(OwnSetting.key("lsfg", game), false);
        check(!OwnSetting.bool(s, "lsfg", game, false), "the game's own value wins");
        check(OwnSetting.bool(s, "lsfg", other, false), "another game keeps the default");
        check(OwnSetting.bool(s, "lsfg", null, false), "the default is unchanged");
        check(OwnSetting.num(s, "lsfg_mult", game, 2) == 3, "a key the game did not set follows the default");
        check(OwnSetting.own(s, lsfg, game) && !OwnSetting.own(s, lsfg, other) && !OwnSetting.own(s, lsfg, null), "own: any key of the group, never for the default");
        check(OwnSetting.key("lsfg", game).equals("lsfg@" + game), "the game's key is <key>@<title ID> (Prefs.gameKey)");

        // Use Settings' default: exactly the game's keys go, the default and other games stay
        for (String k : OwnSetting.ownKeys(lsfg, game)) s.m.remove(k);
        check(!OwnSetting.own(s, lsfg, game) && OwnSetting.bool(s, "lsfg", game, false), "back to the default");
        check(s.m.containsKey("lsfg") && s.m.containsKey("lsfg_mult"), "the defaults are kept");

        // a float key (the on-screen controls' opacity)
        s.m.put(OwnSetting.key("opacity", null), 0.4f);
        check(OwnSetting.real(s, "opacity", game, 0.6f) == 0.4f, "opacity follows the default");
        s.m.put(OwnSetting.key("opacity", game), 0.9f);
        check(OwnSetting.real(s, "opacity", game, 0.6f) == 0.9f && OwnSetting.real(s, "opacity", other, 0.6f) == 0.4f, "a game's own opacity");

        // Picture size choices: 100 % first, SIZE_MIN last, and back
        check(PictureFit.sizeOfChoice(0) == PictureFit.SIZE_MAX, "first choice 100 %");
        check(PictureFit.sizeOfChoice(PictureFit.steps()) == PictureFit.SIZE_MIN, "last choice the smallest");
        for (int i = 0; i <= PictureFit.steps(); i++) check(PictureFit.choiceOfSize(PictureFit.sizeOfChoice(i)) == i, "choice round trip " + i);
        check(PictureFit.choiceOfSize(0) == 0, "an unset size (0) is 100 %");
        System.out.println("ok");
    }
}
