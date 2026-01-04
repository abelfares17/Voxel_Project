#version 150
in vec2 aPos;
in vec2 aUV;
uniform mat4 uProjection;
out vec2 vUV;
void main() {
    vUV = aUV;
    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);
}