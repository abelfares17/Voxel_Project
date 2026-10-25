#version 150 core
in vec3 direction;
in float brightness;
uniform vec3 sunDirection, moonDirection;
uniform float visibility, sunCosRadius, moonCosRadius;
out vec4 fragColor;
void main() {
    vec3 ray = normalize(direction);
    if (ray.y <= 0.0 || dot(ray, sunDirection) >= sunCosRadius
            || dot(ray, moonDirection) >= moonCosRadius) discard;
    float coverage = 1.0 - smoothstep(0.2, 0.5, length(gl_PointCoord - vec2(0.5)));
    fragColor = vec4(vec3(0.8, 0.88, 1), coverage * brightness * visibility * smoothstep(0.0, 0.08, ray.y));
}
