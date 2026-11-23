package dev.nxnative.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Button;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

import java.util.Arrays;

/**
 * The on-screen controls over the game's SurfaceView: a virtual Pro Controller drawn by the launcher and driven by the host. The view lays the controls out in dp around
 * the letterboxed 16:9 picture, hands the layout to the host (nxhost_touch_controls_set) and passes every touch on (nxhost_input_touch); the host decides what each touch
 * drives, a control (its buttons and sticks go to the game as the handheld controller, dm_input_set) or the game's touchscreen, so a touch on a control never reaches the
 * touchscreen. The view reads back what each control holds to draw it and to give a haptic tick on each press.
 * The small button at the top centre (faint, its touch area larger than its drawing) shows or hides the controls; holding it opens the in-game panel. Settings ›
 * Show / hide button (and the panel's controls rows) turns it off: the controls then always show (in Auto, while no gamepad is connected). They hide by themselves
 * while a gamepad is connected and come back when the last one goes.
 * The "On-screen controls" setting (Settings, the in-game panel; for every game): Auto (the default) draws nothing while a gamepad is connected, not even the show /
 * hide button or the panel's handle (Back or the gamepad's Home button opens the panel), and everything comes back when the last one goes; On keeps them with a gamepad
 * too; Off never draws them, and every touch is the game's touchscreen.
 */
// Custom control artwork: minus, plus, X and combined D-pad.
final class TouchControls extends View {
    // DM_BTN_* (dm/host/dm_input.h, nn::hid::NpadButton order)
    private static final long A = 1, B = 1 << 1, X = 1 << 2, Y = 1 << 3, LS = 1 << 4, RS = 1 << 5, L = 1 << 6, R = 1 << 7, ZL = 1 << 8, ZR = 1 << 9,
            PLUS = 1 << 10, MINUS = 1 << 11, LEFT = 1 << 12, UP = 1 << 13, RIGHT = 1 << 14, DOWN = 1 << 15;
    private static final int BUTTON = 0, STICK = 1, DPAD = 2, MAX = 24;   // input_map.h NXT_*, NXT_MAX
    private static final int SHAPE_ROUND = 0, SHAPE_SHOULDER = 1;

    private final boolean input;   // the host takes touches (nxhost_input_touch)
    private SharedPreferences prefs() { return Prefs.open(getContext(), "touch_controls"); }   // read again when the other process wrote it (Prefs.open)
    private boolean available;     // the host takes a layout (nxhost_touch_controls_set): the controls can be drawn
    private boolean shown = true, gamepad;
    private boolean button = true;   // the show / hide button is there (toggleButton): without it the controls are never hidden by it
    private boolean clean;         // the clean picture: nothing drawn, every touch is the game's
    private int mode = MODE_AUTO;   // the "On-screen controls" setting (setMode)
    private float opacity, scale;
    private boolean haptics;
    private boolean dualStick;   // one setting: invisible floating-origin left and right sticks
    private boolean coordinateGrid;   // display-only 20x20 coordinate grid for precise control placement
    private int rightSwipeSensitivity;   // user-facing right-side swipe sensitivity (1..10)
    private static int nativeRightSwipeSensitivity(int level) { return 130 + Math.round((Math.max(1, Math.min(10, level)) - 1) * (110.0f / 9.0f)); }

    // The layout in pixels, as the host has it
    private int n;
    private final int[] kind = new int[MAX], stick = new int[MAX], shape = new int[MAX];
    private final long[] buttons = new long[MAX];
    private final float[] cx = new float[MAX], cy = new float[MAX], r = new float[MAX];
    private final String[] label = new String[MAX];
    private final boolean[] hid = new boolean[MAX];   // the control is hidden by the player (TouchHidden): drawn dimmed in the editor, left out of the layout otherwise
    private java.util.Set<String> hidden = new java.util.LinkedHashSet<>();   // the keys of the hidden controls on this screen shape (controlKey)
    private float tx, ty, tr;      // the show / hide button
    private float picX, picY, picW = 1, picH = 1;   // the game's picture in the view, normalized

    // The user's layout, one per screen shape (prof: the view's size in steps of 40 units, so a foldable's two screens keep one each): each group (the left stick with L3,
    // the D-pad, the face buttons, the right stick with R3, the left and the right shoulders with − / +) moved by (gdx, gdy) units and scaled by gsc around its centre,
    // and where the picture sits in the free space (placeX, placeY: 0 left / top, 0.5 centred, 1 right / bottom; nxhost_picture_place)
    private static final int GROUPS = 6;
    private static final String[] GROUP_NAME = {"left stick", "D-pad", "A B X Y", "right stick", "ZL L −", "ZR R +"};
    private String prof;
    private final float[] gdx = new float[GROUPS], gdy = new float[GROUPS], gsc = new float[GROUPS];
    private float placeX = 0.5f, placeY = 0.5f;
    private boolean stretch;   // the replay stretches the picture over the whole view (DM_VK_STRETCH)
    private int pictureSize = PictureFit.SIZE_MAX;   // the picture's share of its fitted or stretched size (DM_VK_PICTURE_SIZE), %
    /** Stretch to screen and the picture size (GameActivity: from the game's environment at start, and the in-game panel's Picture rows while it runs, which send the
     *  host the same values): touches map to the rectangle the present draws (PictureFit.rect) */
    private float aspect = 16f / 9f;   // the picture's shape when fitted (DM_VK_PICTURE_ASPECT: 4:3 with Game settings › Aspect ratio 4:3)
    void setAspect(float a) { if (a > 0 && a != aspect) { aspect = a; layout(getWidth(), getHeight()); invalidate(); } }
    void setPicture(boolean on, int sizePct) {
        int sz = PictureFit.size(sizePct);
        if (stretch == on && pictureSize == sz) return;
        stretch = on; pictureSize = sz;
        layout(getWidth(), getHeight()); invalidate();
    }
    private final RectF[] gbox = new RectF[GROUPS];
    // The layout editor (startEditLayout): touches move the groups and the picture instead of going to the host
    private boolean editing;
    private int dragG = -1, dragId = -1, pinchId = -1;   // dragG: a group, GROUPS for the picture
    private float lastX, lastY, pinchD0, pinchS0, downX, downY;
    private int tapI = -1;         // the control under the first touch of the editor: a tap without a drag hides or shows it (TouchHidden)
    private boolean moved;         // the touch moved past the tap slop: it drags the group, no tap
    private final RectF[] tool = new RectF[5];   // the editor's buttons: Top, Centre, Bottom, Reset, Done
    private static final String[] TOOL = {"Top", "Centre", "Bottom", "Reset", "Done"};

    private final long[] pressed = new long[MAX], prev = new long[MAX];
    private final float[] sticks = new float[4];
    private int toggleId = -1;
    private boolean toggleLong, toggleGesture;
    private final Runnable longPress = () -> { toggleLong = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); settings(); };

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    // The artwork replaces only the eight matching button faces; their existing layout and hit targets stay unchanged.
    private final Bitmap[] buttonIcons = new Bitmap[8];
    private Bitmap minusIcon, plusIcon;
    private final Bitmap[] dpadIcons = new Bitmap[4];
    private final Path path = new Path();
    private final RectF rect = new RectF();

    // Crop transparent padding so every supplied icon has the same visible footprint.
    private static Bitmap trimTransparent(Bitmap source) {
        if (source == null) return null;
        int w = source.getWidth(), h = source.getHeight();
        int[] pixels = new int[w * h];
        source.getPixels(pixels, 0, w, 0, 0, w, h);
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if ((pixels[y * w + x] >>> 24) != 0) {
                if (x < left) left = x; if (x > right) right = x;
                if (y < top) top = y; if (y > bottom) bottom = y;
            }
        }
        if (right < left || bottom < top) return source;
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return source;
        return Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1);
    }

    TouchControls(Context c, boolean input) {
        super(c);
        this.input = input;
        buttonIcons[0] = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.button_a);
        buttonIcons[1] = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.button_b);
        buttonIcons[2] = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.button_x);
        buttonIcons[3] = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.button_y);
        buttonIcons[4] = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.button_zr);
        buttonIcons[5] = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.button_zl);
        buttonIcons[6] = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.button_r);
        buttonIcons[7] = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.button_l);
        minusIcon = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.button_minus);
        for (int n = 0; n < buttonIcons.length; n++) buttonIcons[n] = trimTransparent(buttonIcons[n]);
        plusIcon = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.button_plus);
        dpadIcons[0] = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.dpad_up);
        dpadIcons[1] = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.dpad_right);
        dpadIcons[2] = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.dpad_down);
        dpadIcons[3] = BitmapFactory.decodeResource(getResources(), dev.nxnative.android.R.drawable.dpad_left);
        for (int n = 0; n < dpadIcons.length; n++) dpadIcons[n] = trimTransparent(dpadIcons[n]);
        opacity = prefs().getFloat("opacity", 0.6f);
        scale = prefs().getFloat("scale", 1f);
        haptics = prefs().getBoolean("haptics", true);
        dualStick = prefs().getBoolean("dual_stick", false);
        coordinateGrid = prefs().getBoolean("coordinate_grid", false);
        int savedSensitivity = prefs().getInt("right_swipe_sensitivity", 3);
        // Migrate the previous native 8..240 preference to the new 1..10 scale.
        rightSwipeSensitivity = savedSensitivity > 10
                ? Math.max(1, Math.min(10, Math.round(1 + (savedSensitivity - 130) * 9.0f / 110.0f)))
                : Math.max(1, savedSensitivity);
        prefs().edit().putInt("right_swipe_sensitivity", rightSwipeSensitivity).apply();
        shown = prefs().getBoolean("shown", true);
        line.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        setHapticFeedbackEnabled(true);
    }


    /** A gamepad connected (the controls hide) or the last one gone (they come back, unless the user hid them) */
    void setGamepad(boolean connected) {
        if (gamepad == connected) return;
        gamepad = connected;
        Native.note("on-screen controls " + (active() ? "shown" : "hidden") + (connected ? ": a gamepad is connected" : ": no gamepad"));
        tell();
        apply();
    }

    /** The clean picture: draw no controls and give every touch to the game. The view stays present so its touch path still runs; the user's
     *  shown / mode / gamepad state is untouched, so showing everything returns to it. */
    void setClean(boolean hidden) {
        if (clean == hidden) return;
        clean = hidden;
        apply();
        invalidate();
    }

    boolean normalVisible() { return mode == MODE_ON || mode == MODE_AUTO && !gamepad; }

    private boolean active() { return visible() && (shown || !button) && !clean && (mode == MODE_ON || !gamepad); }

    static final int MODE_AUTO = 0, MODE_ON = 1, MODE_OFF = 2;
    static final String[] MODE_NAMES = {"Auto", "On", "Off"};
    /** The "On-screen controls" setting, kept for every game: Auto (hidden while a gamepad is connected), On or Off */
    static int mode(Context c) { int m = Prefs.open(c, "touch_controls").getInt("mode", MODE_AUTO); return m >= 0 && m < MODE_NAMES.length ? m : MODE_AUTO; }
    static void mode(Context c, int m) { Prefs.open(c, "touch_controls").edit().putInt("mode", m).commit(); }
    /** A game's own mode if it has one, else the main screen's (key mode@<title ID>, same file) */
    static int mode(Context c, String titleId) {
        int m = Prefs.open(c, "touch_controls").getInt(Prefs.gameKey("mode", titleId), mode(c));
        return m >= 0 && m < MODE_NAMES.length ? m : mode(c);
    }
    static boolean ownMode(Context c, String titleId) { return Prefs.open(c, "touch_controls").contains(Prefs.gameKey("mode", titleId)); }
    /** null: back to the default */
    static void mode(Context c, String titleId, Integer m) {
        android.content.SharedPreferences.Editor e = Prefs.open(c, "touch_controls").edit();
        if (m == null) e.remove(Prefs.gameKey("mode", titleId)); else e.putInt(Prefs.gameKey("mode", titleId), m);
        e.commit();
    }
    /** Settings › Show / hide button: the small button at the top centre that shows or hides the controls (on by default), for every game */
    static boolean toggleButton(Context c) { return Prefs.open(c, "touch_controls").getBoolean("button", true); }
    static void toggleButton(Context c, boolean on) { Prefs.open(c, "touch_controls").edit().putBoolean("button", on).commit(); }
    /** The in-game panel's switch: kept, and applied at once */
    void setToggleButton(boolean on) {
        toggleButton(getContext(), on);
        button = on;
        Native.note("on-screen controls: show / hide button " + (on ? "on" : "off"));
        apply();
    }

    /** Whether anything is drawn, the show / hide button included; onSwitch gets each change (GameActivity: the panel's handle) */
    private boolean visible() { return available && (mode == MODE_ON || mode == MODE_AUTO && !gamepad); }
    java.util.function.Consumer<Boolean> onSwitch;
    private boolean told = true;
    private void tell() { boolean v = normalVisible(); if (v != told) { told = v; if (onSwitch != null) onSwitch.accept(v); } }

    void setMode(int m) {
        mode = m;
        Native.note("on-screen controls: " + MODE_NAMES[m] + " (" + (active() ? "shown" : "hidden") + ")");
        tell();
        apply();
    }

    private void apply() {
        if (input) {
            Native.touchControlsDualStick(dualStick);
            Native.touchControlsRightSwipeSensitivity(nativeRightSwipeSensitivity(rightSwipeSensitivity));
            Native.touchControlsEnable(active() && !editing);
        }   // off: every touch in the picture is the game's touchscreen
        Arrays.fill(pressed, 0); Arrays.fill(prev, 0); Arrays.fill(sticks, 0);
        invalidate();
    }

    private void setRightSwipeSensitivity(int value) {
        rightSwipeSensitivity = Math.max(1, Math.min(10, value));
        prefs().edit().putInt("right_swipe_sensitivity", rightSwipeSensitivity).commit();
        if (input) Native.touchControlsRightSwipeSensitivity(nativeRightSwipeSensitivity(rightSwipeSensitivity));
    }

    private void setCoordinateGrid(boolean on) {
        coordinateGrid = on;
        prefs().edit().putBoolean("coordinate_grid", on).commit();
        Native.note("coordinate grid " + (on ? "on (A-T, 1-20)" : "off"));
        invalidate();
    }

    private void setDualStick(boolean on) {
        dualStick = on;
        prefs().edit().putBoolean("dual_stick", on).commit();
        Native.note("invisible dual joysticks " + (on ? "on" : "off"));
        apply();
    }

    private void toggle() {
        shown = !shown;
        prefs().edit().putBoolean("shown", shown).commit();
        Native.note("on-screen controls " + (active() ? "shown" : "hidden") + " by the user");
        apply();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layout(w, h);
    }

    // ---- Layout: in units of 1/135 inch, scaled by the user's size, anchored to the screen's sides and its bottom, so the controls sit in the letterbox bars as far as they fit ----
    private int add(int k, long b, int s, int sh, float x, float y, float rad, String lb) {
        if (n >= MAX) return -1;
        kind[n] = k; buttons[n] = b; stick[n] = s; shape[n] = sh; cx[n] = x; cy[n] = y; r[n] = rad; label[n] = lb;
        return n++;
    }

    /** Pixels per layout unit: 1/135 inch from the screen's physical dpi, which is a dp on the phone the layout was tuned on (Honor PNM-N49's outer screen, 405 dpi at
     *  density 3), so the controls keep their physical size whatever the display size setting; the dp when the screen reports a dpi far from its density */
    private float unit() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float ppi = (dm.xdpi + dm.ydpi) / 2, u = ppi / 135f;
        return u > dm.density * 0.6f && u < dm.density * 1.6f ? u : dm.density;
    }

    /** Where the picture sits in the free space by default: the middle; in a portrait window (Settings › Screen rotation: Automatic) the top, so the controls have the free area below it */
    private float defY() { return getHeight() > getWidth() ? 0f : 0.5f; }

    private void layout(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics()), u = unit();
        String key = Math.round(w / u / 40) + "x" + Math.round(h / u / 40);
        if (!key.equals(prof)) { prof = key; load(); }   // a new screen shape (a fold, an unfold): its own layout
        float[] p = PictureFit.rect(w, h, placeX, placeY, stretch, pictureSize, aspect);
        picX = p[0]; picY = p[1]; picW = p[2]; picH = p[3];
        if (input) { Native.picturePlace(placeX, placeY); Native.touchLayout(w, h, picX, picY, picW, picH); }
        // On a short screen (under 340 units high) everything shrinks with it
        float fit = Math.min(1f, h / u / 340f), d = u * scale * fit, m = 14 * u;
        // The controls take a band along the bottom no taller than a phone held in landscape (380 units, 2.8 inches): on a taller screen (a foldable's inner screen,
        // a tablet) they stay where the thumbs are, in the bottom corners, instead of spreading over its height
        float y0 = Math.max(0, h - 380 * d), bh = h - y0;
        float bar = picX * w;   // the width of each letterbox bar
        float rStick = 60 * d, rRight = 54 * d, rPad = 50 * d, rFace = 25 * d, rSh = 25 * d, rSmall = 17 * d, rClick = 17 * d, off = 1.65f * rFace;
        n = 0;
        // Shoulders in the band's top corners, ZL / ZR outside, L / R inside, minus / plus next to them
        float shY = y0 + m + 0.7f * rSh;
        add(BUTTON, ZL, 0, SHAPE_SHOULDER, m + rSh, shY, rSh, "ZL");
        add(BUTTON, L, 0, SHAPE_SHOULDER, m + 3 * rSh + 10 * d, shY, rSh, "L");
        add(BUTTON, R, 0, SHAPE_SHOULDER, w - m - 3 * rSh - 10 * d, shY, rSh, "R");
        add(BUTTON, MINUS, 0, SHAPE_ROUND, m + 4 * rSh + 22 * d + rSmall, shY, rSmall, "−");
        add(BUTTON, PLUS, 0, SHAPE_ROUND, w - m - 4 * rSh - 22 * d - rSmall, shY, rSmall, "+");
        // Left: the stick high, the D-pad low, centred in the bar when it is wide enough
        float top = shY + rSh + 10 * d;
        float lx = Math.max(m + rStick, bar / 2), ly = Math.max(y0 + bh * 0.45f, top + rStick);
        int ls = add(STICK, 0, 0, SHAPE_ROUND, lx, ly, rStick, "");
        add(DPAD, UP | DOWN | LEFT | RIGHT, 0, SHAPE_ROUND, Math.max(m + rPad, bar / 2), h - m - rPad, rPad, "");
        float diag = (rStick + rClick + 6 * d) * 0.7071f;
        add(BUTTON, LS, 0, SHAPE_ROUND, lx + diag, ly + diag, rClick, "L3");
        // Right: the face buttons high (A right, B bottom, X top, Y left, as on a Switch), the right stick low
        float fx = Math.min(w - m - rFace - off, w - bar / 2), fy = ly;
        add(BUTTON, A, 0, SHAPE_ROUND, fx + off, fy, rFace, "A");
        add(BUTTON, B, 0, SHAPE_ROUND, fx, fy + off, rFace, "B");
        add(BUTTON, X, 0, SHAPE_ROUND, fx, fy - off, rFace, "X");
        add(BUTTON, Y, 0, SHAPE_ROUND, fx - off, fy, rFace, "Y");
        float rx = Math.min(w - m - rRight, w - bar / 2), ry = h - m - rRight;
        add(STICK, 0, 1, SHAPE_ROUND, rx, ry, rRight, "");
        float diagR = (rRight + rClick + 6 * d) * 0.7071f;
        add(BUTTON, RS, 0, SHAPE_ROUND, rx - diagR, ry - diagR, rClick, "R3");
        // ZR is an invisible, active shoulder-button hit target at the lower-left edge of the right stick.
        float diagZR = (rRight + rSh + 6 * d) * 0.7071f;
        add(BUTTON, ZR, 0, SHAPE_SHOULDER, rx - diagZR, ry + diagZR, rSh, "ZR");
        // Push apart what still overlaps (a large size on a small screen): the later control moves towards the middle
        for (int pass = 0; pass < 4; pass++)
            for (int j = 0; j < n; j++)
                for (int i = 0; i < j; i++) {
                    float dx = cx[j] - cx[i], dy = cy[j] - cy[i], need = r[i] + r[j] + 2 * d;
                    if (dx * dx + dy * dy >= need * need) continue;
                    float move = (float) Math.sqrt(Math.max(0, need * need - dy * dy)) - Math.abs(dx);
                    cx[j] += cx[j] < w / 2f ? move : -move;
                }
        groups(w, h, u);
        // Place the invisible ZR hit target at the centre of grid cell 17R (20x20 grid).
        for (int i = 0; i < n; i++) if (buttons[i] == ZR) {
            cx[i] = w * 16.5f / 20f;
            cy[i] = h * 17.5f / 20f;
            break;
        }
        tr = 10 * u; tx = w / 2f; ty = m + tr;   // small and faint (onDraw); its touch area is larger (onToggle)
        // The host's units: centres normalized to the view, radii in units of its height; a stick's grab ring is its own (input_map.c)
        float[] f = new float[n * 6];
        for (int i = 0; i < n; i++) {
            f[6 * i] = kind[i]; f[6 * i + 1] = buttons[i]; f[6 * i + 2] = stick[i];
            f[6 * i + 3] = cx[i] / w; f[6 * i + 4] = cy[i] / h; f[6 * i + 5] = r[i] / h;
        }
        int kept = input ? Native.touchControlsSet(f) : -1;
        boolean was = available;
        available = kept == n;
        if (!available && was != available) Native.note("on-screen controls: this host takes no layout (" + kept + "): not drawn");
        if (!editing) Native.note(String.format(java.util.Locale.ROOT, "on-screen controls: %d in a %dx%d view (%.1f dp high, %.2f px a unit, band from y %.0f, screen %s%s), picture x %.3f y %.3f, size %.2f, opacity %.2f, %s",
                n, w, h, h / dp, u, y0, prof, custom() ? ", the user's layout" : "", picX, picY, scale, opacity, active() ? "shown" : "hidden")
                + (stretch || pictureSize < PictureFit.SIZE_MAX ? ", picture " + (stretch ? "stretched" : "fitted") + " at " + pictureSize + " %" : ""));
        apply();
    }

    /** The key of control i in TouchHidden's set: a button's own name, "stick0" / "stick1", "dpad" */
    private String controlKey(int i) {
        if (kind[i] == STICK) return "stick" + stick[i];
        if (kind[i] == DPAD) return "dpad";
        return KEY_NAMES[Long.numberOfTrailingZeros(buttons[i])];
    }
    private static final String[] KEY_NAMES = {"a", "b", "x", "y", "l3", "r3", "l", "r", "zl", "zr", "plus", "minus", "left", "up", "right", "down"};   // DM_BTN_* bit order
    private static java.util.List<String> allKeys() {
        return java.util.Arrays.asList("zl", "l", "zr", "r", "minus", "plus", "stick0", "dpad", "l3", "a", "b", "x", "y", "stick1", "r3");
    }

    /** Leaves the hidden controls out of the layout (the arrays shrink; the groups' bounds were taken with them, so nothing else moves) */
    private void hideFromLayout() {
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (hid[i]) continue;
            if (j != i) { kind[j] = kind[i]; buttons[j] = buttons[i]; stick[j] = stick[i]; shape[j] = shape[i]; cx[j] = cx[i]; cy[j] = cy[i]; r[j] = r[i]; label[j] = label[i]; }
            hid[j] = false; j++;
        }
        n = j;
    }

    /** The control under a point in the editor, or -1 (the shoulders are wider than tall) */
    private int controlAt(float x, float y) {
        for (int i = n - 1; i >= 0; i--) {
            float dx = Math.abs(x - cx[i]), dy = Math.abs(y - cy[i]);
            if (shape[i] == SHAPE_SHOULDER ? dx <= r[i] && dy <= r[i] * 0.62f : dx * dx + dy * dy <= r[i] * r[i]) return i;
        }
        return -1;
    }

    // ---- The user's layout: groups moved and scaled, the picture placed, kept per screen shape ----
    private int groupOf(int i) {
        long b = buttons[i];
        if (kind[i] == STICK) return stick[i] == 0 ? 0 : 3;
        if (kind[i] == DPAD) return 1;
        if (b == LS) return 0;
        if (b == RS) return 3;
        if (b == ZR) return 3;
        if ((b & (A | B | X | Y)) != 0) return 2;
        if ((b & (ZL | L | MINUS)) != 0) return 4;
        return 5;
    }

    /** Moves and scales each group as the user left it, around the centre of where the automatic layout put it, kept inside the view; gbox gets each group's bounds */
    private void groups(int w, int h, float u) {
        for (int g = 0; g < GROUPS; g++) {
            float sx = 0, sy = 0; int k = 0;
            for (int i = 0; i < n; i++) if (groupOf(i) == g) { sx += cx[i]; sy += cy[i]; k++; }
            if (k == 0) { gbox[g] = null; continue; }
            float mx = sx / k, my = sy / k, sc = gsc[g];
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (groupOf(i) != g) continue;
                cx[i] = mx + (cx[i] - mx) * sc + gdx[g] * u; cy[i] = my + (cy[i] - my) * sc + gdy[g] * u; r[i] *= sc;
                x0 = Math.min(x0, cx[i] - r[i]); y0 = Math.min(y0, cy[i] - r[i]); x1 = Math.max(x1, cx[i] + r[i]); y1 = Math.max(y1, cy[i] + r[i]);
            }
            float ox = x0 < 0 ? -x0 : x1 > w ? w - x1 : 0, oy = y0 < 0 ? -y0 : y1 > h ? h - y1 : 0;   // off the view: back inside
            if (ox != 0 || oy != 0) for (int i = 0; i < n; i++) if (groupOf(i) == g) { cx[i] += ox; cy[i] += oy; }
            gbox[g] = new RectF(x0 + ox, y0 + oy, x1 + ox, y1 + oy);
        }
    }

    private boolean custom() {
        for (int g = 0; g < GROUPS; g++) if (gdx[g] != 0 || gdy[g] != 0 || gsc[g] != 1) return true;
        return placeX != 0.5f || placeY != defY() || !hidden.isEmpty();
    }

    private void load() {
        button = toggleButton(getContext());
        for (int g = 0; g < GROUPS; g++) {
            String[] v = prefs().getString("group" + g + "@" + prof, "0 0 1").split(" ");
            try { gdx[g] = Float.parseFloat(v[0]); gdy[g] = Float.parseFloat(v[1]); gsc[g] = Float.parseFloat(v[2]); }
            catch (RuntimeException e) { gdx[g] = 0; gdy[g] = 0; gsc[g] = 1; }
        }
        hidden = TouchHidden.parse(prefs().getString("hidden@" + prof, ""), allKeys());
        String[] v = prefs().getString("picture@" + prof, "0.5 " + defY()).split(" ");
        try { placeX = Float.parseFloat(v[0]); placeY = Float.parseFloat(v[1]); } catch (RuntimeException e) { placeX = 0.5f; placeY = defY(); }
    }

    private void saveLayout() {
        SharedPreferences.Editor e = prefs().edit();
        for (int g = 0; g < GROUPS; g++) e.putString("group" + g + "@" + prof, gdx[g] + " " + gdy[g] + " " + gsc[g]);
        e.putString("hidden@" + prof, TouchHidden.encode(hidden));
        e.putString("picture@" + prof, placeX + " " + placeY).commit();   // commit: the game process may end right after (Stop)
    }

    private void relayout() { layout(getWidth(), getHeight()); }

    /** Where the picture sits vertically on this screen: 0 top, 0.5 centred, 1 bottom (the panel's Picture row); never scaled or cropped */
    float picturePosition() { return placeY; }
    void setPicturePosition(float y) {
        if (prof == null) return;   // not laid out yet
        placeY = Math.max(0, Math.min(1, y));
        relayout(); saveLayout();
        Native.note(String.format(java.util.Locale.ROOT, "picture placed at y %.2f of the free space on screen %s", placeY, prof));
    }

    /** Run when the editor opens, before it draws: closes what opened it (the in-game panel sets it to its close) and hides what lies over the controls (Paused) */
    Runnable onEditStart;
    /** Run when the editor closes (Done): shows again what onEditStart hid */
    Runnable onEditEnd;

    boolean editing() { return editing; }

    /** The layout editor: drag a group of controls to move it, pinch it to resize it, drag the picture to move it into the black bars; Done keeps it for this screen shape */
    void startEditLayout() {
        if (editing || prof == null) return;
        if (onEditStart != null) onEditStart.run();
        editing = true; shown = true; gamepad = false;
        Native.note("on-screen controls: the layout editor is open (screen " + prof + ")");
        relayout();   // every control, the hidden ones too, so a tap can show them
        apply();
    }

    private void endEditLayout() {
        editing = false; dragG = -1; dragId = -1; pinchId = -1;
        saveLayout(); relayout();
        if (onEditEnd != null) onEditEnd.run();
    }

    private void resetLayout() {
        Arrays.fill(gdx, 0); Arrays.fill(gdy, 0); Arrays.fill(gsc, 1); placeX = 0.5f; placeY = defY(); hidden = new java.util.LinkedHashSet<>();
        relayout(); saveLayout();
    }

    /** The picture's position on this screen (the in-game panel's Picture rows): Top / Centre / Bottom of the free space */
    void positionRows(Context ctx, LinearLayout box) {
        TextView t = new TextView(ctx);
        t.setText(Tx.s(dev.nxnative.android.R.string.game_position_this_screen));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(ctx);
        rg.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {Tx.s(dev.nxnative.android.R.string.game_top), Tx.s(dev.nxnative.android.R.string.game_centre), Tx.s(dev.nxnative.android.R.string.game_bottom)};
        float[] at = {0, 0.5f, 1};
        for (int i = 0; i < 3; i++) {
            android.widget.RadioButton b = new android.widget.RadioButton(ctx);
            b.setText(names[i]); b.setId(4100 + i);   // fixed: the panel is built again on each open and each look change (generateViewId would climb into the other rows' ids)
            rg.addView(b);
            if (Math.abs(placeY - at[i]) < 0.01f) b.setChecked(true);
            float y = at[i];
            b.setOnCheckedChangeListener((v, on) -> { if (on) setPicturePosition(y); });
        }
        box.addView(t); box.addView(rg);
    }

    // ---- The editor's touches ----
    private boolean editTouch(MotionEvent e) {
        int act = e.getActionMasked(), i = e.getActionIndex(), id = e.getPointerId(i);
        float x = e.getX(i), y = e.getY(i);
        switch (act) {
            case MotionEvent.ACTION_DOWN: {
                for (int k = 0; k < tool.length; k++) if (tool[k] != null && tool[k].contains(x, y)) { toolTap(k); return true; }
                dragG = -1;
                float pad = 10 * unit();
                for (int g = 0; g < GROUPS; g++)
                    if (gbox[g] != null && x >= gbox[g].left - pad && x <= gbox[g].right + pad && y >= gbox[g].top - pad && y <= gbox[g].bottom + pad) { dragG = g; break; }
                if (dragG < 0 && x >= picX * getWidth() && x <= (picX + picW) * getWidth() && y >= picY * getHeight() && y <= (picY + picH) * getHeight()) dragG = GROUPS;
                dragId = id; lastX = x; lastY = y; downX = x; downY = y; moved = false; tapI = controlAt(x, y);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (dragG >= 0 && dragG < GROUPS && pinchId < 0) {
                    int j = e.findPointerIndex(dragId);
                    if (j < 0) return true;
                    pinchId = id; pinchD0 = (float) Math.hypot(x - e.getX(j), y - e.getY(j)); pinchS0 = gsc[dragG];
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                int j = e.findPointerIndex(dragId);
                if (dragG < 0 || j < 0) return true;
                float mx = e.getX(j), my = e.getY(j), u = unit();
                if (!moved && pinchId < 0) {   // a tap stays put: the drag starts only past a slop of 8 units, from there
                    if (Math.hypot(mx - downX, my - downY) < 8 * u) return true;
                    moved = true; lastX = mx; lastY = my;
                }
                if (pinchId >= 0) {
                    int q = e.findPointerIndex(pinchId);
                    if (q >= 0 && pinchD0 > 0) gsc[dragG] = Math.max(0.5f, Math.min(2f, pinchS0 * (float) Math.hypot(mx - e.getX(q), my - e.getY(q)) / pinchD0));
                } else if (dragG < GROUPS) {
                    gdx[dragG] += (mx - lastX) / u; gdy[dragG] += (my - lastY) / u;
                } else {
                    float fx = getWidth() * (1 - picW), fy = getHeight() * (1 - picH);
                    if (fx > 1) placeX = snap(placeX + (mx - lastX) / fx);
                    if (fy > 1) placeY = snap(placeY + (my - lastY) / fy);
                }
                lastX = mx; lastY = my;
                relayout();
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (id == pinchId) { pinchId = -1; moved = true; }
                else if (id == dragId) { dragG = -1; dragId = -1; pinchId = -1; }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (act == MotionEvent.ACTION_UP && !moved && tapI >= 0) toggleHidden(tapI);
                if (dragG == GROUPS) { placeX = snapEnd(placeX); placeY = snapEnd(placeY); relayout(); }
                dragG = -1; dragId = -1; pinchId = -1; tapI = -1;
                saveLayout(); invalidate();
                return true;
            default:
                return true;
        }
    }

    // The picture's position: free while dragged (clamped), pulled to the top, the centre and the bottom near them
    private static float snap(float v) { return Math.max(0, Math.min(1, v)); }
    private static float snapEnd(float v) {
        for (float t : new float[] {0, 0.5f, 1}) if (Math.abs(v - t) < 0.06f) return t;
        return v;
    }

    /** A tap on a control in the editor: hide it, or show it again (the last visible one stays); kept with the layout of this screen shape at Done and at each tap */
    private void toggleHidden(int i) {
        java.util.Set<String> now = TouchHidden.toggle(hidden, controlKey(i), allKeys());
        if (now.equals(hidden)) return;
        hidden = now;
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        Native.note("on-screen controls: " + controlKey(i) + (hidden.contains(controlKey(i)) ? " hidden" : " shown") + " on screen " + prof);
        relayout(); saveLayout();
    }

    private void toolTap(int k) {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (k <= 2) { placeY = k * 0.5f; relayout(); saveLayout(); }
        else if (k == 3) resetLayout();
        else { endEditLayout(); Native.note("on-screen controls: layout kept for screen " + prof + (custom() ? "" : " (the automatic one)")); }
        invalidate();
    }

    // ---- Touches: the show / hide button is the launcher's; every other pointer goes to the host ----
    private void send(int action, int id, float x, float y) { if (input) Native.touch(action, id, x / getWidth(), y / getHeight()); }

    private boolean onToggle(float x, float y) { float dx = x - tx, dy = y - ty, g = tr * 2.4f; return button && visible() && dx * dx + dy * dy <= g * g; }   // 24 units: a 48 dp target

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (editing) return editTouch(e);
        int act = e.getActionMasked();
        if (clean) {   // nothing is drawn; every touch in the picture is the game's touchscreen
            switch (act) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    int i = e.getActionIndex();
                    send(act, e.getPointerId(i), e.getX(i), e.getY(i));
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < e.getPointerCount(); i++) send(act, e.getPointerId(i), e.getX(i), e.getY(i));
                    break;
                case MotionEvent.ACTION_CANCEL:
                    send(act, 0, 0, 0);
                    break;
                default:
                    return true;
            }
            return true;
        }
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex(), id = e.getPointerId(i);
                if (toggleId < 0 && !toggleGesture && onToggle(e.getX(i), e.getY(i))) {
                    toggleId = id; toggleLong = false; toggleGesture = true;
                    postDelayed(longPress, 600);
                    if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    return true;
                }
                // Once this overlay owns the toggle gesture, forward any additional fingers
                // to the native touch layer. Ordinary gameplay touches fall through to the
                // dedicated GameSurfaceView below.
                if (toggleGesture) {
                    send(act, id, e.getX(i), e.getY(i));
                    if (act == MotionEvent.ACTION_UP) toggleGesture = false;
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE:
                if (!toggleGesture) return false;
                for (int i = 0; i < e.getPointerCount(); i++)
                    if (e.getPointerId(i) != toggleId) send(act, e.getPointerId(i), e.getX(i), e.getY(i));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex(), id = e.getPointerId(i);
                if (id == toggleId) {
                    toggleId = -1;
                    removeCallbacks(longPress);
                    if (!toggleLong && onToggle(e.getX(i), e.getY(i))) toggle();
                    if (act == MotionEvent.ACTION_UP) toggleGesture = false;
                    return true;
                }
                if (toggleGesture) {
                    send(act, id, e.getX(i), e.getY(i));
                    if (act == MotionEvent.ACTION_UP) toggleGesture = false;
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_CANCEL:
                if (!toggleGesture) return false;
                toggleId = -1; toggleGesture = false;
                removeCallbacks(longPress);
                send(MotionEvent.ACTION_CANCEL, 0, 0, 0);
                return true;
            default:
                return toggleGesture;
        }
    }

    /** What the controls hold after a touch: a tick for each new press, a redraw when anything changed */
    private void poll() {
        if (!active()) return;
        float s0 = sticks[0], s1 = sticks[1], s2 = sticks[2], s3 = sticks[3];
        Native.touchControlsState(pressed, sticks);
        boolean changed = s0 != sticks[0] || s1 != sticks[1] || s2 != sticks[2] || s3 != sticks[3], tick = false;
        for (int i = 0; i < n; i++) {
            if (pressed[i] != prev[i]) changed = true;
            if ((pressed[i] & ~prev[i]) != 0) tick = true;
            prev[i] = pressed[i];
        }
        if (tick && haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (changed) invalidate();
    }

    /** Called by GameSurfaceView after native gameplay touches update the host's control state. */
    void inputChanged() { poll(); }

    // ---- Drawing ----
    private float dim = 1f;   // 0.3 while a hidden control is drawn in the editor
    private int alpha(float a) { return Math.max(0, Math.min(255, Math.round(255 * a * opacity * dim))); }

    @Override protected void onDraw(Canvas c) {
        if (!available && !coordinateGrid) return;
        float dp = getResources().getDisplayMetrics().density;
        if (coordinateGrid) drawCoordinateGrid(c, dp);
        if (!available || !visible() && !editing) return;
        line.setStrokeWidth(Math.max(1.5f, 1.6f * dp));
        if (editing) { drawControls(c); drawEditor(c, dp); return; }
        // The show / hide button (when it is on): a small pad outline, mostly transparent over the game, fainter still while the controls are hidden
        boolean on = active();
        if (button) {
            float ta = on ? 1f : 0.7f;
            fill.setColor(Color.argb(Math.round(255 * 0.15f * ta), 0, 0, 0));
            c.drawCircle(tx, ty, tr, fill);
            line.setColor(Color.argb(Math.round(255 * 0.4f * ta), 255, 255, 255));
            c.drawCircle(tx, ty, tr, line);
            rect.set(tx - tr * 0.6f, ty - tr * 0.3f, tx + tr * 0.6f, ty + tr * 0.35f);
            c.drawRoundRect(rect, tr * 0.3f, tr * 0.3f, line);
            if (!on) c.drawLine(tx - tr * 0.65f, ty + tr * 0.65f, tx + tr * 0.65f, ty - tr * 0.65f, line);
        }
        if (!on) return;
        drawControls(c);
    }

    /** A transparent 20x20 reference grid. A-T identify rows from top to bottom; 1-20 identify columns left to right. It never handles touches. */
    private void drawCoordinateGrid(Canvas c, float dp) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        Paint grid = line;
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(Math.max(1f, dp));
        grid.setColor(Color.argb(115, 255, 255, 255));
        for (int col = 0; col <= 20; col++) {
            float x = w * col / 20f;
            c.drawLine(x, 0, x, h, grid);
        }
        for (int row = 0; row <= 20; row++) {
            float y = h * row / 20f;
            c.drawLine(0, y, w, y, grid);
        }
        Paint labels = text;
        labels.setStyle(Paint.Style.FILL);
        labels.setTextAlign(Paint.Align.CENTER);
        labels.setTextSize(13f * dp);
        labels.setFakeBoldText(true);
        labels.setColor(Color.WHITE);
        labels.setShadowLayer(2f * dp, 0, 1f * dp, Color.BLACK);
        for (int col = 0; col < 20; col++) {
            String number = String.valueOf(col + 1);
            c.drawText(number, w * (col + 0.5f) / 20f, 16f * dp, labels);
        }
        for (int row = 0; row < 20; row++) {
            String letter = String.valueOf((char) ('A' + row));
            c.drawText(letter, 14f * dp, h * (row + 0.5f) / 20f + 5f * dp, labels);
        }
        labels.clearShadowLayer();
    }

    private void drawControls(Canvas c) {
        for (int i = 0; i < n; i++) {
            boolean held = pressed[i] != 0;
            boolean off = editing && hid[i];
            // Keep ZR in the native touch layout, but never draw its icon, shape, or label.
            if (buttons[i] == ZR) continue;
            fill.setColor(held ? Color.argb(alpha(0.55f), 255, 255, 255) : Color.argb(alpha(0.35f), 0, 0, 0));
            line.setColor(Color.argb(alpha(0.9f), 255, 255, 255));
            if (kind[i] == STICK && dualStick && !editing) continue;
            if (kind[i] == STICK) drawStick(c, i);
            else if (kind[i] == DPAD) drawDpad(c, i);
            else {
                Bitmap icon = buttonIcon(buttons[i]);
                if (icon != null) {
                    // Fit the complete transparent PNG inside the original visual footprint.
                    // Enlarge the artwork within the same button centre; fit the whole image without cropping.
                    float iconScale = 1.00f;
                    // Identical square visual box for every face/shoulder icon; preserve aspect ratio.
                    float box = 2f * r[i] * iconScale;
                    float fit = Math.min(box / icon.getWidth(), box / icon.getHeight());
                    float drawW = icon.getWidth() * fit, drawH = icon.getHeight() * fit;
                    rect.set(cx[i] - drawW / 2f, cy[i] - drawH / 2f, cx[i] + drawW / 2f, cy[i] + drawH / 2f);
                    iconPaint.setAlpha(held ? 190 : 255);
                    c.drawBitmap(icon, null, rect, iconPaint);
                } else {
                    if (shape[i] == SHAPE_SHOULDER) {
                        rect.set(cx[i] - r[i], cy[i] - r[i] * 0.62f, cx[i] + r[i], cy[i] + r[i] * 0.62f);
                        c.drawRoundRect(rect, r[i] * 0.5f, r[i] * 0.5f, fill);
                        c.drawRoundRect(rect, r[i] * 0.5f, r[i] * 0.5f, line);
                    } else {
                        c.drawCircle(cx[i], cy[i], r[i], fill);
                        c.drawCircle(cx[i], cy[i], r[i], line);
                    }
                    text.setColor(held ? Color.argb(alpha(1f), 0, 0, 0) : Color.argb(alpha(1f), 255, 255, 255));
                    text.setTextSize(r[i] * (label[i].length() > 1 ? 0.72f : 0.95f));
                    c.drawText(label[i], cx[i], cy[i] - (text.descent() + text.ascent()) / 2, text);
                }
            }
            if (off) { line.setColor(Color.argb(Math.round(255 * 0.8f), 255, 120, 120)); c.drawLine(cx[i] - r[i] * 0.7f, cy[i] + r[i] * 0.7f, cx[i] + r[i] * 0.7f, cy[i] - r[i] * 0.7f, line); }
            dim = 1f;
        }
    }

    /** The PNG assigned to a Switch face or shoulder button, or null for the ordinary labelled controls. */
    private Bitmap buttonIcon(long button) {
        if (button == A) return buttonIcons[0];
        if (button == B) return buttonIcons[1];
        if (button == X) return buttonIcons[2];
        if (button == Y) return buttonIcons[3];
        if (button == ZR) return buttonIcons[4];
        if (button == ZL) return buttonIcons[5];
        if (button == R) return buttonIcons[6];
        if (button == L) return buttonIcons[7];
        if (button == MINUS) return minusIcon;
        if (button == PLUS) return plusIcon;
        return null;
    }

    /** The editor: the picture's outline, each group's frame (the one held filled), the buttons along the top and what to do */
    private void drawEditor(Canvas c, float dp) {
        int w = getWidth(), h = getHeight();
        float u = unit();
        line.setColor(Color.argb(200, 255, 210, 60));
        rect.set(picX * w, picY * h, (picX + picW) * w, (picY + picH) * h);
        if (dragG == GROUPS) { fill.setColor(Color.argb(50, 255, 210, 60)); c.drawRect(rect, fill); }
        c.drawRect(rect, line);
        for (int g = 0; g < GROUPS; g++) {
            if (gbox[g] == null) continue;
            line.setColor(g == dragG ? Color.argb(255, 255, 210, 60) : Color.argb(160, 255, 255, 255));
            rect.set(gbox[g]); rect.inset(-4 * u, -4 * u);
            if (g == dragG) { fill.setColor(Color.argb(60, 255, 210, 60)); c.drawRoundRect(rect, 8 * u, 8 * u, fill); }
            c.drawRoundRect(rect, 8 * u, 8 * u, line);
        }
        float bw = 74 * u, bh = 34 * u, gap = 8 * u, x = (w - (TOOL.length * bw + (TOOL.length - 1) * gap)) / 2, y = 14 * u;
        text.setTextSize(15 * u);
        for (int k = 0; k < TOOL.length; k++, x += bw + gap) {
            if (tool[k] == null) tool[k] = new RectF();
            tool[k].set(x, y, x + bw, y + bh);
            boolean cur = k <= 2 && Math.abs(placeY - k * 0.5f) < 0.01f;
            fill.setColor(k == 4 ? Color.argb(230, 255, 210, 60) : cur ? Color.argb(230, 255, 255, 255) : Color.argb(200, 40, 40, 40));
            c.drawRoundRect(tool[k], 8 * u, 8 * u, fill);
            line.setColor(Color.argb(220, 255, 255, 255));
            c.drawRoundRect(tool[k], 8 * u, 8 * u, line);
            text.setColor(k == 4 || cur ? Color.BLACK : Color.WHITE);
            c.drawText(Tx.arr(dev.nxnative.android.R.array.arr_touch_tool)[k], tool[k].centerX(), tool[k].centerY() - (text.descent() + text.ascent()) / 2, text);
        }
        text.setTextSize(13 * u);
        text.setColor(Color.argb(230, 255, 255, 255));
        String hint = dragG >= 0 && dragG < GROUPS ? Tx.s(dev.nxnative.android.R.string.game_group_drag_to_move, GROUP_NAME[dragG], Math.round(gsc[dragG] * 100))
                : dragG == GROUPS ? Tx.s(dev.nxnative.android.R.string.game_picture_drag_into_the_black) : Tx.s(dev.nxnative.android.R.string.game_drag_a_control_or_the);
        c.drawText(hint, w / 2f, y + bh + 22 * u, text);
        c.drawText(Tx.s(dev.nxnative.android.R.string.game_tap_a_control_to_hide_or_show), w / 2f, y + bh + 42 * u, text);
    }

    private void drawStick(Canvas c, int i) {
        float sx = sticks[2 * stick[i]], sy = sticks[2 * stick[i] + 1];
        boolean held = sx != 0 || sy != 0;
        fill.setColor(Color.argb(alpha(0.25f), 0, 0, 0));
        c.drawCircle(cx[i], cy[i], r[i], fill);
        c.drawCircle(cx[i], cy[i], r[i], line);
        float k = r[i] * 0.55f, kx = cx[i] + sx * k, ky = cy[i] - sy * k;
        fill.setColor(held ? Color.argb(alpha(0.6f), 255, 255, 255) : Color.argb(alpha(0.4f), 90, 90, 90));
        c.drawCircle(kx, ky, r[i] * 0.45f, fill);
        c.drawCircle(kx, ky, r[i] * 0.45f, line);
    }

    private void drawDpad(Canvas c, int i) {
        float x = cx[i], y = cy[i], rr = r[i];
        // Four uploaded directional PNGs; original touch regions remain unchanged.
        float iconSize = rr * 0.92f, offset = rr * 0.52f;
        float[] dx = {0f, offset, 0f, -offset};
        float[] dy = {-offset, 0f, offset, 0f};
        long[] dirs = {UP, RIGHT, DOWN, LEFT};
        for (int k = 0; k < 4; k++) {
            Bitmap icon = dpadIcons[k];
            float ix = x + dx[k], iy = y + dy[k];
            if (icon != null) {
                float fit = Math.min(iconSize / icon.getWidth(), iconSize / icon.getHeight());
                float dw = icon.getWidth() * fit, dh = icon.getHeight() * fit;
                rect.set(ix - dw / 2f, iy - dh / 2f, ix + dw / 2f, iy + dh / 2f);
                iconPaint.setAlpha((pressed[i] & dirs[k]) != 0 ? 190 : 255);
                c.drawBitmap(icon, null, rect, iconPaint);
            } else {
                boolean held = (pressed[i] & dirs[k]) != 0;
                fill.setColor(held ? Color.argb(alpha(0.9f), 255, 255, 255) : Color.argb(alpha(0.35f), 255, 255, 255));
                c.drawCircle(ix, iy, iconSize * 0.36f, fill);
            }
        }
    }

    // ---- Settings: opacity, size and the haptic tick, kept in the app's preferences ----
    /** What a long press on the show / hide button opens: GameActivity's in-game panel (SidePanel), which holds these rows (controlRows) */
    Runnable openSettings;
    private void settings() { if (openSettings != null) openSettings.run(); }

    /** The on-screen controls' rows of the in-game panel: Auto / On / Off, opacity, size, the haptic tick, the log over the game, Reset and the layout editor; each applies at once (kept for every game) */
    void controlRows(Context ctx, LinearLayout box) {
        RadioGroup mg = new RadioGroup(ctx);
        mg.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < MODE_NAMES.length; i++) { RadioButton b = new RadioButton(ctx); b.setId(4000 + i); b.setText(Tx.arr(dev.nxnative.android.R.array.arr_touch_mode)[i]); mg.addView(b); }
        mg.check(4000 + mode);
        mg.setOnCheckedChangeListener((g, id) -> { mode(ctx, id - 4000); setMode(id - 4000); });
        box.addView(mg);
        TextView ol = new TextView(ctx), sl = new TextView(ctx);
        SeekBar os = new SeekBar(ctx), ss = new SeekBar(ctx);
        CheckBox hb = new CheckBox(ctx), lb = new CheckBox(ctx), tb = new CheckBox(ctx), db = new CheckBox(ctx), gb = new CheckBox(ctx);
        TextView sensitivityLabel = new TextView(ctx);
        SeekBar sensitivityBar = new SeekBar(ctx);
        sensitivityBar.setMax(9);
        sensitivityBar.setProgress(rightSwipeSensitivity - 1);
        sensitivityBar.setKeyProgressIncrement(1);
        os.setMax(90); os.setProgress(Math.round(opacity * 100) - 10);   // 10..100 %
        ss.setMax(90); ss.setProgress(Math.round(scale * 100) - 60);     // 60..150 %
        os.setKeyProgressIncrement(5); ss.setKeyProgressIncrement(5);   // the D-pad's left / right: 5 % a press
        hb.setText(Tx.s(dev.nxnative.android.R.string.game_vibrate_on_press)); hb.setChecked(haptics);
        lb.setText(Tx.s(dev.nxnative.android.R.string.game_show_the_log_over_the)); lb.setChecked(showLog(ctx));
        tb.setText(Tx.s(dev.nxnative.android.R.string.game_show_hide_button_top_centre)); tb.setChecked(button);
        tb.setOnCheckedChangeListener((b, v) -> setToggleButton(v));
        db.setText("Invisible dual joysticks (left / right screen halves)");
        db.setChecked(dualStick);
        db.setOnCheckedChangeListener((b, v) -> setDualStick(v));
        gb.setText("Coordinate Grid (A-T / 1-20)");
        gb.setChecked(coordinateGrid);
        gb.setOnCheckedChangeListener((b, v) -> setCoordinateGrid(v));
        Runnable sensitivityText = () -> sensitivityLabel.setText("Right swipe sensitivity  " + (sensitivityBar.getProgress() + 1));
        sensitivityText.run();
        sensitivityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                sensitivityText.run();
                if (user) setRightSwipeSensitivity(value + 1);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        Runnable labels = () -> { ol.setText("Opacity " + (os.getProgress() + 10) + " %"); sl.setText("Size " + (ss.getProgress() + 60) + " %"); };
        labels.run();
        SeekBar.OnSeekBarChangeListener live = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean user) {
                labels.run();
                opacity = (os.getProgress() + 10) / 100f;
                float ns = (ss.getProgress() + 60) / 100f;
                if (ns != scale) { scale = ns; layout(getWidth(), getHeight()); }
                invalidate();
                save();
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        };
        os.setOnSeekBarChangeListener(live);
        ss.setOnSeekBarChangeListener(live);
        hb.setOnCheckedChangeListener((b, v) -> { haptics = v; save(); });
        lb.setOnCheckedChangeListener((b, v) -> prefs().edit().putBoolean("log", v).commit());
        Button reset = new Button(ctx);
        reset.setText("↻   Reset the controls");
        stylePanelAction(reset, ctx, false);
        reset.setOnClickListener(v -> { os.setProgress(50); ss.setProgress(40); hb.setChecked(true); });   // 60 % opacity, 100 % size, the tick on
        box.addView(ol); box.addView(os); box.addView(sl); box.addView(ss); box.addView(hb); box.addView(lb); box.addView(tb); box.addView(db); box.addView(gb);
        box.addView(sensitivityLabel); box.addView(sensitivityBar);
        addPanelAction(box, reset, ctx);
        Button ed = new Button(ctx);   // the layout editor (runs onEditStart); the picture's position is in the panel's Picture rows (positionRows)
        ed.setText("✥   Customize controls layout…");
        stylePanelAction(ed, ctx, true);
        ed.setOnClickListener(v -> startEditLayout());
        addPanelAction(box, ed, ctx);
        TextView editHint = new TextView(ctx);
        editHint.setText("Drag a control group to move it, or pinch to resize it. Your layout is saved for this screen shape.");
        editHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        editHint.setTextColor(Color.LTGRAY);
        box.addView(editHint);
    }

    /** Whether GameActivity keeps the newest log lines over the picture once the game draws (off by default; the settings dialog's last box) */
    /** Glass action buttons used by the in-game side panel. */
    private static void stylePanelAction(Button button, Context ctx, boolean primary) {
        float dp = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                primary
                        ? new int[] {0xCC25366A, 0xCC142C50, 0xCC10243D}
                        : new int[] {0xAA252A49, 0xCC17263D, 0xCC101D30});
        bg.setCornerRadius(18 * dp);
        bg.setStroke(Math.max(1, Math.round(dp)), primary ? 0x997D9EFF : 0x667F9BB8);
        button.setBackground(bg);
        button.setTextColor(0xFFF0F5FF);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setGravity(android.view.Gravity.CENTER);
        button.setMinHeight(Math.round(52 * dp));
        button.setPadding(Math.round(16 * dp), Math.round(11 * dp), Math.round(16 * dp), Math.round(11 * dp));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().cancel();
                v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(80).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
            return false;
        });
    }

    private static void addPanelAction(LinearLayout box, Button button, Context ctx) {
        float dp = ctx.getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(9 * dp);
        lp.bottomMargin = Math.round(3 * dp);
        box.addView(button, lp);
    }

    static boolean showLog(Context c) { return Prefs.open(c, "touch_controls").getBoolean("log", false); }
    static void showLog(Context c, boolean on) { Prefs.open(c, "touch_controls").edit().putBoolean("log", on).commit(); }

    private void save() { prefs().edit().putFloat("opacity", opacity).putFloat("scale", scale).putBoolean("haptics", haptics).commit(); }
}
