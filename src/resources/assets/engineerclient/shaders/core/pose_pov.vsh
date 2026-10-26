#version 330
#extension GL_ARB_separate_shader_objects : require

// Pose POV's blocks (CompactMesh): 16 bytes a corner, the texture looked up by number and repeated across merged faces.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

// Each block atlas sprite: u0, v0, width, height.
uniform samplerBuffer PoseSprites;

layout(location = 0) in uvec4 Position;
layout(location = 1) in uvec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 localUv;
layout(location = 1) flat out vec4 spriteRect;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out float viewDistance;

void main() {
    vec3 pos = vec3(Position.xyz) / 64.0 - 256.0;
    vec4 view = ModelViewMat * vec4(pos, 1.0);
    gl_Position = ProjMat * view;
    localUv = vec2(UV0) / 2048.0;
    spriteRect = texelFetch(PoseSprites, int(Position.w));
    vertexColor = Color;
    viewDistance = length(view.xyz);
}
