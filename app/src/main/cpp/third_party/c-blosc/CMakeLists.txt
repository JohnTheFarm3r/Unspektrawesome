set(BLOSC_SOURCE_COMMIT "616f4b7343a8479f7e71dd3d7025bd92c9a6bbd0")
set(HAVE_ZSTD 1)
configure_file(blosc/config.h.in "${CMAKE_CURRENT_BINARY_DIR}/config.h")
file(GLOB ZSTD_SOURCES CONFIGURE_DEPENDS zstd/common/*.c zstd/compress/*.c zstd/decompress/*.c)
add_library(spektra_blosc STATIC
    blosc/blosc.c blosc/blosclz.c blosc/fastcopy.c blosc/shuffle.c
    blosc/shuffle-generic.c blosc/bitshuffle-generic.c ${ZSTD_SOURCES})
set_target_properties(spektra_blosc PROPERTIES POSITION_INDEPENDENT_CODE ON C_VISIBILITY_PRESET hidden)
target_include_directories(spektra_blosc PUBLIC blosc PRIVATE "${CMAKE_CURRENT_BINARY_DIR}" zstd zstd/common)
target_compile_definitions(spektra_blosc PRIVATE USING_CMAKE ZSTD_DISABLE_ASM ZSTD_LEGACY_SUPPORT=0)
target_compile_options(spektra_blosc PRIVATE -O3 -Wno-error)
