#version 150
in vec3 aPos;
in vec2 aTexCoord;

out vec2 vTexCoord;

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 modelMatrix;

void main() {
    vTexCoord = aTexCoord;
    gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(aPos, 1.0);
}