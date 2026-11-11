#if defined(SPEKTRA_SINGLE_STORAGE_BUFFER)

#define SPEKTRA_BUFFER_DECL(bindingIndex, qualifier, elementType, name) \
layout(std430, binding = bindingIndex) qualifier buffer name##Storage { \
  elementType values[]; \
} name##Buffer;

#define SPEKTRA_BUFFER_READ(elementType, elementBytes, name) \
elementType read_##name(uint index) { return name##Buffer.values[index]; }

#define SPEKTRA_BUFFER_WRITE(elementType, elementBytes, name) \
void write_##name(uint index, elementType value) { name##Buffer.values[index] = value; }

#else

layout(constant_id = 30) const uint storageChunkCount = 1u;
layout(constant_id = 31) const uint storageChunkBytes = 16u;

// Each descriptor covers an aligned section within the device's buffer range limit.
#define SPEKTRA_BUFFER_DECL(bindingIndex, qualifier, elementType, name) \
layout(std430, binding = bindingIndex) qualifier buffer name##Storage { \
  elementType values[]; \
} name##Chunks[storageChunkCount];

#define SPEKTRA_BUFFER_READ(elementType, elementBytes, name) \
elementType read_##name(uint index) { \
  const uint elements = storageChunkBytes / elementBytes; \
  const uint localIndex = index % elements; \
  switch (index / elements) { \
    case 0u: return name##Chunks[(storageChunkCount > 0u ? 0u : storageChunkCount - 1u)].values[localIndex]; \
    case 1u: return name##Chunks[(storageChunkCount > 1u ? 1u : storageChunkCount - 1u)].values[localIndex]; \
    case 2u: return name##Chunks[(storageChunkCount > 2u ? 2u : storageChunkCount - 1u)].values[localIndex]; \
    case 3u: return name##Chunks[(storageChunkCount > 3u ? 3u : storageChunkCount - 1u)].values[localIndex]; \
    case 4u: return name##Chunks[(storageChunkCount > 4u ? 4u : storageChunkCount - 1u)].values[localIndex]; \
    case 5u: return name##Chunks[(storageChunkCount > 5u ? 5u : storageChunkCount - 1u)].values[localIndex]; \
    case 6u: return name##Chunks[(storageChunkCount > 6u ? 6u : storageChunkCount - 1u)].values[localIndex]; \
    case 7u: return name##Chunks[(storageChunkCount > 7u ? 7u : storageChunkCount - 1u)].values[localIndex]; \
  } \
  return elementType(0); \
}

#define SPEKTRA_BUFFER_WRITE(elementType, elementBytes, name) \
void write_##name(uint index, elementType value) { \
  const uint elements = storageChunkBytes / elementBytes; \
  const uint localIndex = index % elements; \
  switch (index / elements) { \
    case 0u: name##Chunks[(storageChunkCount > 0u ? 0u : storageChunkCount - 1u)].values[localIndex] = value; return; \
    case 1u: name##Chunks[(storageChunkCount > 1u ? 1u : storageChunkCount - 1u)].values[localIndex] = value; return; \
    case 2u: name##Chunks[(storageChunkCount > 2u ? 2u : storageChunkCount - 1u)].values[localIndex] = value; return; \
    case 3u: name##Chunks[(storageChunkCount > 3u ? 3u : storageChunkCount - 1u)].values[localIndex] = value; return; \
    case 4u: name##Chunks[(storageChunkCount > 4u ? 4u : storageChunkCount - 1u)].values[localIndex] = value; return; \
    case 5u: name##Chunks[(storageChunkCount > 5u ? 5u : storageChunkCount - 1u)].values[localIndex] = value; return; \
    case 6u: name##Chunks[(storageChunkCount > 6u ? 6u : storageChunkCount - 1u)].values[localIndex] = value; return; \
    case 7u: name##Chunks[(storageChunkCount > 7u ? 7u : storageChunkCount - 1u)].values[localIndex] = value; return; \
  } \
}

#endif

#define SPEKTRA_READ_BUFFER(bindingIndex, elementType, elementBytes, name) \
SPEKTRA_BUFFER_DECL(bindingIndex, readonly, elementType, name) \
SPEKTRA_BUFFER_READ(elementType, elementBytes, name)

#define SPEKTRA_WRITE_BUFFER(bindingIndex, elementType, elementBytes, name) \
SPEKTRA_BUFFER_DECL(bindingIndex, writeonly, elementType, name) \
SPEKTRA_BUFFER_WRITE(elementType, elementBytes, name)

#define SPEKTRA_BUFFER(bindingIndex, elementType, elementBytes, name) \
SPEKTRA_BUFFER_DECL(bindingIndex, , elementType, name) \
SPEKTRA_BUFFER_READ(elementType, elementBytes, name) \
SPEKTRA_BUFFER_WRITE(elementType, elementBytes, name)

// Small lookup tables fit entirely within the first descriptor.
#if defined(SPEKTRA_SINGLE_STORAGE_BUFFER)
#define SPEKTRA_TABLE_READ_BUFFER(bindingIndex, elementType, elementBytes, name) \
SPEKTRA_READ_BUFFER(bindingIndex, elementType, elementBytes, name)
#else
#define SPEKTRA_TABLE_READ_BUFFER(bindingIndex, elementType, elementBytes, name) \
SPEKTRA_BUFFER_DECL(bindingIndex, readonly, elementType, name) \
elementType read_##name(uint index) { return name##Chunks[0].values[index]; }
#endif
