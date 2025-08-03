#version 150

in vec3 aPos;    // Position du vertex
in vec2 aTex;    // Coordonnée UV
in vec3 aNormal; // Normale

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 modelMatrix;

out vec2 vTexCoord;
out vec3 vNormal;

void main() {
    gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(aPos, 1.0);
    vTexCoord = aTex;
    vNormal = aNormal;
}