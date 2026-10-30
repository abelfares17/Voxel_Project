#version 150 core

in vec2 aPos;
in vec2 aTexCoord;
in vec4 aColor;
in float aTexMode;

uniform mat4 uProjection;

out vec2 vTexCoord;
out vec4 vColor;
flat out int vTexMode;

void main() {
    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);
    vTexCoord = aTexCoord;
    vColor = aColor;
    vTexMode = int(aTexMode + 0.5);
}
