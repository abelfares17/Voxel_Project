package engine;

import engine.core.Application;

/**
 * Entry point for the Voxel Game.
 * Delegates to Application for all logic.
 */
public class Main {
    public static void main(String[] args) {
        new Application().run();
    }
}
