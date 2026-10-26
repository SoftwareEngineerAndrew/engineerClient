#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

uniform sampler2D Sampler0;

layout(location = 0) in vec2 localUv;
layout(location = 1) flat in vec4 spriteRect;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in float viewDistance;

layout(location = 0) out vec4 fragColor;

// The views' sky (PosePov.SKY), which the fog fades into.
const vec3 SKY = vec3(120.0 / 255.0, 167.0 / 255.0, 1.0);

void main() {
    // The texture repeated across a merged face, sampled at the mip level the unrepeated coordinates call for, so the seams
    // between repeats don't flash to the smallest mip.
    vec2 g = localUv * spriteRect.zw;
    // Where in its repeat: 0..1, a whole number past 0 being the end of one (1) rather than the start of the next, so a face's
    // far edge never shows the texture's first texel.
    vec2 inTile = clamp(localUv - max(ceil(localUv) - 1.0, 0.0), 0.0, 0.99999);
    vec2 uv = spriteRect.xy + inTile * spriteRect.zw;
    vec4 color = textureGrad(Sampler0, uv, dFdx(g), dFdy(g)) * vertexColor;
    if (color.a < 0.1) {
        discard;
    }
    // Fog over the last stretch before the view distance: ModelOffset.x where it starts, .y where it's whole (equal: none).
    float fog = ModelOffset.y > ModelOffset.x ? smoothstep(ModelOffset.x, ModelOffset.y, viewDistance) : 0.0;
    fragColor = vec4(mix(color.rgb, SKY, fog), 1.0) * ColorModulator;
}
