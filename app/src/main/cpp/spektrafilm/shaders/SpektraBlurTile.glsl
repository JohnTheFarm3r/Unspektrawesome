shared vec4 blurTile[768];
ivec2 blurTileOrigin;
ivec2 blurTileSize;

void prepareBlurTile(ivec2 imageOrigin, bool horizontal, int radius) {
  const int haloCapacity = horizontal
    ? (768 / int(gl_WorkGroupSize.y) - int(gl_WorkGroupSize.x)) / 2
    : (768 / int(gl_WorkGroupSize.x) - int(gl_WorkGroupSize.y)) / 2;
  const int halo = min(radius, haloCapacity);
  blurTileOrigin = ivec2(gl_WorkGroupID.xy * gl_WorkGroupSize.xy) + imageOrigin -
    (horizontal ? ivec2(halo, 0) : ivec2(0, halo));
  blurTileSize = ivec2(gl_WorkGroupSize.xy) +
    (horizontal ? ivec2(2 * halo, 0) : ivec2(0, 2 * halo));
  const uint count = uint(blurTileSize.x * blurTileSize.y);
  for (uint i = gl_LocalInvocationIndex; i < count; i += gl_WorkGroupSize.x * gl_WorkGroupSize.y) {
    const ivec2 position = blurTileOrigin + ivec2(int(i) % blurTileSize.x, int(i) / blurTileSize.x);
    blurTile[i] = loadBlurSource(position.x, position.y);
  }
  barrier();
}

vec4 sampleBlurTile(int x, int y) {
  const ivec2 local = ivec2(x, y) - blurTileOrigin;
  if (all(greaterThanEqual(local, ivec2(0))) && all(lessThan(local, blurTileSize))) {
    return blurTile[local.y * blurTileSize.x + local.x];
  }
  return loadBlurSource(x, y);
}
