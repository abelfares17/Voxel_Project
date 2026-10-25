#version 150 core
in vec2 screenPosition;
out vec4 fragColor;
uniform mat4 inverseProjection, inverseViewRotation;
uniform vec3 horizonColor, zenithColor, sunDirection, moonDirection;
uniform float sunElevation, daylight, sunCosRadius, moonCosRadius, moonSinRadius;

float disc(float alignment, float cosRadius) {
    float edge = max(fwidth(alignment), 0.0000001);
    return smoothstep(cosRadius - edge, cosRadius + edge, alignment);
}
void main() {
    vec4 view = inverseProjection * vec4(screenPosition, 1, 1);
    vec3 ray = normalize((inverseViewRotation * vec4(view.xyz, 0)).xyz);
    float height = max(ray.y, 0.0);
    vec3 color = mix(horizonColor, zenithColor, pow(height, 0.55));
    float sunDot = dot(ray, sunDirection), moonDot = dot(ray, moonDirection);
    float twilight = (1.0 - smoothstep(0.0, 18.0, abs(sunElevation)));
    // Warm, directional horizon; the uniform fog color remains its base horizon component.
    float towardSun = pow(max(dot(normalize(vec3(ray.x, 0.0001, ray.z)),
                normalize(vec3(sunDirection.x, 0.0001, sunDirection.z))), 0.0), 4.0);
    color += vec3(0.32, 0.065, 0.012) * twilight * towardSun * exp(-height * 12.0);
    color += vec3(0.11, 0.07, 0.025) * pow(max(sunDot, 0.0), 256.0)
                * smoothstep(-6.0, 0.0, sunElevation);
    float horizonMask = smoothstep(-max(fwidth(ray.y), 0.0001), 0.0, ray.y);
    float warmth = 1.0 - smoothstep(0.0, 18.0, sunElevation);
    vec3 sunColor = mix(vec3(1.0, 0.95, 0.8), vec3(1.0, 0.32, 0.06), warmth);
    color = mix(color, sunColor, disc(sunDot, sunCosRadius) * horizonMask);
    // Mild deterministic markings in the moon's tangent plane, not an external bitmap.
    vec3 tangent = normalize(cross(vec3(0, 1, 0), moonDirection));
    vec3 bitangent = cross(moonDirection, tangent);
    vec2 uv = vec2(dot(ray, tangent), dot(ray, bitangent)) / moonSinRadius;
    float detail = 0.89 + 0.06 * sin(uv.x * 8.0 + sin(uv.y * 5.0)) * cos(uv.y * 7.0);
    color = mix(color, vec3(0.65, 0.71, 0.82) * detail, disc(moonDot, moonCosRadius) * horizonMask);
    fragColor = vec4(color, 1); // linear RGB; GL_FRAMEBUFFER_SRGB performs the only transfer.
}
