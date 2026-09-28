package engine;

import engine.core.Application;
import engine.core.config.GameConfig;
import org.lwjgl.system.Configuration;

/**
 * Entry point for the Voxel Game.
 * Delegates to Application for all logic.
 */
public class Main {
    public static void main(String[] args) {
        if (GameConfig.DEBUG_MEMORY_ALLOCATOR) {
            Configuration.DEBUG.set(true);
            Configuration.DEBUG_MEMORY_ALLOCATOR.set(true);
            Configuration.DEBUG_STACK.set(true);
        }

        new Application().run();
    }
}

