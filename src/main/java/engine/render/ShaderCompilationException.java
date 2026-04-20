package engine.render;

/**
 * Custom exception for shader compilation and linking errors.
 * Provides detailed context for debugging shader issues.
 */
public class ShaderCompilationException extends RuntimeException {
    private final String shaderPath;
    private final String stage;
    private final String shaderLog;

    /**
     * Create a shader compilation exception.
     *
     * @param stage Shader stage ("VERTEX", "FRAGMENT", or "LINK")
     * @param shaderPath Path to the shader file
     * @param shaderLog Compilation/linking error log from OpenGL
     */
    public ShaderCompilationException(String stage, String shaderPath, String shaderLog) {
        super(buildMessage(stage, shaderPath, shaderLog));
        this.stage = stage;
        this.shaderPath = shaderPath;
        this.shaderLog = shaderLog;
    }

    private static String buildMessage(String stage, String shaderPath, String shaderLog) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n═══════════════════════════════════════════════════════════\n");
        sb.append("SHADER ").append(stage).append(" ERROR\n");
        sb.append("═══════════════════════════════════════════════════════════\n");
        sb.append("File: ").append(shaderPath).append("\n");
        sb.append("Stage: ").append(stage).append("\n");
        sb.append("───────────────────────────────────────────────────────────\n");
        sb.append("Error Log:\n");
        sb.append(shaderLog);
        sb.append("\n═══════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    public String getShaderPath() {
        return shaderPath;
    }

    public String getStage() {
        return stage;
    }

    public String getShaderLog() {
        return shaderLog;
    }
}
