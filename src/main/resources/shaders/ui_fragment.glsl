#version 150 core

in vec2 vTexCoord; // Texture coordinates from vertex shader

uniform vec4 uColor;        // Uniform color for the primitive
uniform sampler2D uTexture; // Texture sampler
uniform int uUseTexture;    // 0 = color only, 1 = texture * color

out vec4 fragColor; // Final fragment color

void main() {
    if (uUseTexture == 1) {
        // Sample texture and multiply by color (for tinting)
        fragColor = texture(uTexture, vTexCoord) * uColor;
    } else {
        // Use solid color only
        fragColor = uColor;
    }
}
