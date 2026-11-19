package dev.nxnative.android;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The launcher's file work off the main thread, on a few named threads rather than one per card: a games folder on an SD card read by every tile at once (a
 * thread each) made each read slower than reading them in turn. No game file (.nup, .nsp, .nx) is opened on the main thread for a tile or a look.
 */
final class Background {
    private Background() {}

    private static ExecutorService pool(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /** The tiles' icons and details (GameMeta): one at a time */
    static final ExecutorService META = pool("nx-game-meta");
    /** The looks through the games folders and the checks of what they found (GamesFolders): one at a time, so two looks never add a game twice */
    static final ExecutorService LOOK = pool("nx-games-folders");
}
