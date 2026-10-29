package engine.render.shadow;

/** Immutable post-upload frame snapshot; owns no GPU resource. */
public record ShadowCoverage(boolean fadeEnabled, float fadeStart, float fadeEnd,
        boolean coherent, int requiredReady, int required, int prefetchReady, int prefetch,
        boolean visibleReady, boolean guardValid, boolean budgetLimited) {
    public boolean complete() { return coherent && visibleReady && guardValid && requiredReady==required; }
    public ShadowCoverage withGuard(boolean valid) {
        return new ShadowCoverage(fadeEnabled,fadeStart,fadeEnd,coherent,requiredReady,required,
                prefetchReady,prefetch,visibleReady,valid,budgetLimited);
    }
}
