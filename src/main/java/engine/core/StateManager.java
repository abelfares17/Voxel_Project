package engine.core;

import engine.render.core.RenderContext;

/**
 * Manages game state transitions (Menu -> Loading -> Playing -> Paused).
 * Implements State Pattern.
 */
public class StateManager {
    private GameState currentState;
    private GameState nextState; // For deferred transitions
    private boolean transitionPending;

    /**
     * Create state manager with initial state.
     * @param initialState Starting state (usually MenuState)
     */
    public StateManager(GameState initialState) {
        this.currentState = null;
        this.nextState = initialState;
        this.transitionPending = true;
    }

    /**
     * Request transition to a new state.
     * Transition will occur at the end of the current frame.
     * @param newState State to transition to
     */
    public void setState(GameState newState) {
        this.nextState = newState;
        this.transitionPending = true;
    }

    /**
     * Update current state.
     * @param deltaTime Time since last frame
     * @return true after a transition; caller must reset its clock after blocking enter work
     */
    public boolean update(float deltaTime) {
        boolean transitioned = transitionPending;
        // Process pending transition
        if (transitionPending) {
            performTransition();
        }

        // Update current state
        if (currentState != null) {
            // A delta measured in the previous state does not belong to the new simulation.
            currentState.update(transitioned ? 0f : deltaTime);
        }
        return transitioned;
    }

    /**
     * Render current state.
     * @param context Rendering context
     */
    public void render(RenderContext context) {
        if (currentState != null) {
            currentState.render(context);
        }
    }

    /**
     * Perform the state transition.
     */
    private void performTransition() {
        if (currentState != null) {
            currentState.exit();
        }
        currentState = nextState;
        if (currentState != null) {
            currentState.enter();
        }
        transitionPending = false;
    }

    /**
     * Notify current state of window resize.
     * @param newWidth New framebuffer width
     * @param newHeight New framebuffer height
     */
    public void onResize(int newWidth, int newHeight) {
        if (currentState != null) {
            currentState.onResize(newWidth, newHeight);
        }
    }

    /**
     * Cleanup current state and manager.
     */
    public void cleanup() {
        if (currentState != null) {
            currentState.exit();
            currentState = null;
        }
    }

    public GameState getCurrentState() {
        return currentState;
    }
}
