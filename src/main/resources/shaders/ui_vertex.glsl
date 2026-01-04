#version 150 core

in vec2 aPos;      // Vertex position (2D)
in vec2 aTexCoord; // Texture coordinates

uniform mat4 uProjection; // Orthographic projection matrix

out vec2 vTexCoord; // Pass texture coordinates to fragment shader

void main() {
    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
