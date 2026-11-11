cmake_minimum_required(VERSION 3.22.1)

project(unspektrawesome_vulkan LANGUAGES C CXX)
option(UNSPEKTRA_VULKAN_VALIDATION "Enable bundled Vulkan validation for diagnostic builds" OFF)
option(UNSPEKTRA_HOST_TESTS "Build host rejection fixtures for QA" OFF)
if(NOT UNSPEKTRA_IDENTITY_DIR)
    message(FATAL_ERROR "The native signing identity must be supplied by the app build")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT ANDROID)
    message(FATAL_ERROR "The Unspektrawesome Vulkan library requires the Android NDK")
endif()

if(CMAKE_HOST_WIN32)
    set(GLSLC "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe")
elseif(CMAKE_HOST_APPLE)
    set(GLSLC "${CMAKE_ANDROID_NDK}/shader-tools/darwin-x86_64/glslc")
else()
    set(GLSLC "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64/glslc")
endif()

if(NOT EXISTS "${GLSLC}")
    message(FATAL_ERROR "NDK glslc was not found at ${GLSLC}")
endif()

string(REPLACE "/glslc" "/spirv-val" SPIRV_VAL "${GLSLC}")
if(NOT EXISTS "${SPIRV_VAL}")
    message(FATAL_ERROR "NDK spirv-val was not found at ${SPIRV_VAL}")
endif()
string(REPLACE "/glslc" "/spirv-opt" SPIRV_OPT "${GLSLC}")
if(NOT EXISTS "${SPIRV_OPT}")
    message(FATAL_ERROR "NDK spirv-opt was not found at ${SPIRV_OPT}")
endif()

set(SHADER_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../shaders")
set(SHADER_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated/shaders")
file(MAKE_DIRECTORY "${SHADER_OUTPUT_DIR}")

function(embed_shader source_file shader_stage symbol_name output_variable)
    get_filename_component(shader_name "${source_file}" NAME_WE)
    set(spirv_file "${SHADER_OUTPUT_DIR}/${shader_name}_${shader_stage}.spv")
    set(header_file "${SHADER_OUTPUT_DIR}/${shader_name}_${shader_stage}_spv.h")
    set(shader_optimization)
    if(source_file STREQUAL "bayer_remosaic.comp" OR source_file STREQUAL "preview_bilinear.comp" OR source_file STREQUAL "focus_peaking.comp" OR source_file STREQUAL "present.frag")
        set(shader_optimization -O)
    endif()

    add_custom_command(
        OUTPUT "${header_file}"
        BYPRODUCTS "${spirv_file}"
        COMMAND "${GLSLC}"
            --target-env=vulkan1.1
            -fshader-stage=${shader_stage}
            ${shader_optimization}
            -DUNSPEKTRA_RAW_DIAGNOSTICS=$<IF:$<CONFIG:Debug>,1,0>
            -I "${SHADER_SOURCE_DIR}"
            -o "${spirv_file}"
            "${SHADER_SOURCE_DIR}/${source_file}"
        COMMAND "${CMAKE_COMMAND}"
            "-DINPUT_FILE=${spirv_file}"
            "-DOUTPUT_FILE=${header_file}"
            "-DSYMBOL_NAME=${symbol_name}"
            -P "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
        DEPENDS
            "${SHADER_SOURCE_DIR}/${source_file}"
            "${SHADER_SOURCE_DIR}/raw_input.glsl"
            "${SHADER_SOURCE_DIR}/raw_packed_quad.glsl"
            "${SHADER_SOURCE_DIR}/raw_source.glsl"
            "${SHADER_SOURCE_DIR}/raw_demosaic.glsl"
            "${SHADER_SOURCE_DIR}/raw_mhc.glsl"
            "${SHADER_SOURCE_DIR}/raw_preview_mhc.glsl"
            "${SHADER_SOURCE_DIR}/raw_preview_downsample.glsl"
            "${SHADER_SOURCE_DIR}/raw_color.glsl"
            "${SHADER_SOURCE_DIR}/shadows.glsl"
            "${SHADER_SOURCE_DIR}/raw_dcp.glsl"
            "${SHADER_SOURCE_DIR}/frame_geometry.glsl"
            "${SHADER_SOURCE_DIR}/amaze/amaze_common.glsl"
            "${SHADER_SOURCE_DIR}/highlight_recovery.glsl"
            "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
        VERBATIM)

    set(${output_variable} "${header_file}" PARENT_SCOPE)
endfunction()

function(embed_shader_variant source_file shader_stage symbol_name variant_name definition output_variable)
    get_filename_component(shader_name "${source_file}" NAME_WE)
    set(spirv_file "${SHADER_OUTPUT_DIR}/${shader_name}_${variant_name}_${shader_stage}.spv")
    set(header_file "${SHADER_OUTPUT_DIR}/${shader_name}_${variant_name}_${shader_stage}_spv.h")
    set(shader_optimization)
    if(source_file STREQUAL "bayer_remosaic.comp" OR source_file STREQUAL "recording_native.comp" OR source_file STREQUAL "recording_downsample.comp" OR source_file STREQUAL "preview_bayer_cell.comp" OR source_file STREQUAL "preview_bilinear.comp" OR source_file STREQUAL "preview_native.comp" OR source_file STREQUAL "raw_unpack.comp" OR source_file STREQUAL "raw_area.comp" OR source_file STREQUAL "post_chroma_denoise.comp" OR source_file STREQUAL "focus_peaking.comp" OR source_file STREQUAL "preview_hr.comp")
        set(shader_optimization -O)
    endif()
    # Resolve the scalar remosaic options before optimizing this fallback.
    if(variant_name STREQUAL "light_remosaic" OR variant_name MATCHES "^compat_")
        set(shader_optimization)
    endif()
    set(shader_definitions)
    foreach(shader_definition IN LISTS definition)
        list(APPEND shader_definitions "-D${shader_definition}")
    endforeach()
    set(shader_extra_dependencies)
    if(source_file STREQUAL "recording_downsample.comp")
        list(APPEND shader_extra_dependencies "${SHADER_SOURCE_DIR}/recording_tile.glsl")
    endif()
    if(source_file STREQUAL "panorama.comp")
        list(APPEND shader_extra_dependencies "${SHADER_SOURCE_DIR}/panorama_flow.glsl" "${SHADER_SOURCE_DIR}/panorama_seam.glsl" "${SHADER_SOURCE_DIR}/panorama_crop.glsl" "${SHADER_SOURCE_DIR}/panorama_exposure.glsl" "${SHADER_SOURCE_DIR}/panorama_sweep.glsl" "${SHADER_SOURCE_DIR}/panorama_blend.glsl")
    endif()

    add_custom_command(
        OUTPUT "${header_file}"
        BYPRODUCTS "${spirv_file}"
        COMMAND "${GLSLC}"
            --target-env=vulkan1.1
            -fshader-stage=${shader_stage}
            -DUNSPEKTRA_RAW_DIAGNOSTICS=$<IF:$<CONFIG:Debug>,1,0>
            ${shader_definitions}
            ${shader_optimization}
            -I "${SHADER_SOURCE_DIR}"
            -o "${spirv_file}"
            "${SHADER_SOURCE_DIR}/${source_file}"
        COMMAND "${CMAKE_COMMAND}"
            "-DINPUT_FILE=${spirv_file}"
            "-DOUTPUT_FILE=${header_file}"
            "-DSYMBOL_NAME=${symbol_name}"
            -P "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
        DEPENDS
            "${SHADER_SOURCE_DIR}/${source_file}"
            ${shader_extra_dependencies}
            "${SHADER_SOURCE_DIR}/raw_input.glsl"
            "${SHADER_SOURCE_DIR}/raw_packed_quad.glsl"
            "${SHADER_SOURCE_DIR}/raw_source.glsl"
            "${SHADER_SOURCE_DIR}/raw_demosaic.glsl"
            "${SHADER_SOURCE_DIR}/raw_mhc.glsl"
            "${SHADER_SOURCE_DIR}/raw_preview_mhc.glsl"
            "${SHADER_SOURCE_DIR}/raw_preview_downsample.glsl"
            "${SHADER_SOURCE_DIR}/raw_color.glsl"
            "${SHADER_SOURCE_DIR}/shadows.glsl"
            "${SHADER_SOURCE_DIR}/raw_dcp.glsl"
            "${SHADER_SOURCE_DIR}/frame_geometry.glsl"
            "${SHADER_SOURCE_DIR}/amaze/amaze_common.glsl"
            "${SHADER_SOURCE_DIR}/highlight_recovery.glsl"
            "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
        VERBATIM)

    set(${output_variable} "${header_file}" PARENT_SCOPE)
endfunction()

embed_shader(bayer_remosaic.comp compute kRemosaicBufferSpirv REMOSAIC_BUFFER_SHADER_HEADER)
embed_shader_variant(bayer_remosaic.comp compute kRemosaicImageSpirv raw_image
    UNSPEKTRA_RAW_IMAGE_INPUT=1 REMOSAIC_IMAGE_SHADER_HEADER)

# Resolve the larger per-sample demosaic paths before the device driver compiles them.
embed_shader_variant(
    preview_bilinear.comp
    compute
    kPreviewBilinearSpirv
    bilinear
    UNSPEKTRA_PREVIEW_DEMOSAIC=0
    PREVIEW_SHADER_HEADER)
embed_shader_variant(
    preview_bilinear.comp compute kPreviewMhcSpirv mhc
    UNSPEKTRA_PREVIEW_DEMOSAIC=1 PREVIEW_MHC_SHADER_HEADER)
embed_shader(post_chroma_denoise.comp compute kPostChromaDenoiseSpirv CHROMA_DENOISE_SHADER_HEADER)
embed_shader(video_chroma.comp compute kVideoChromaSpirv VIDEO_CHROMA_SHADER_HEADER)
embed_shader(raw_hot_pixels.comp compute kRawHotPixelsBufferSpirv RAW_HOT_PIXELS_BUFFER_SHADER_HEADER)
embed_shader_variant(raw_hot_pixels.comp compute kRawHotPixelsImageSpirv image
    UNSPEKTRA_RAW_IMAGE_INPUT=1 RAW_HOT_PIXELS_IMAGE_SHADER_HEADER)
embed_shader(shadhi_luma.comp compute kShadowLumaSpirv SHADOW_LUMA_SHADER_HEADER)
embed_shader(shadhi_bilateral.comp compute kShadowBilateralSpirv SHADOW_BILATERAL_SHADER_HEADER)
embed_shader(shadow_color.comp compute kShadowColorSpirv SHADOW_COLOR_SHADER_HEADER)
embed_shader_variant(preview_hr.comp compute kPreviewHrSeedSpirv seed PREVIEW_HR_STAGE=0 PREVIEW_HR_SEED_SHADER_HEADER)
embed_shader_variant(preview_hr.comp compute kPreviewHrReduceSpirv reduce PREVIEW_HR_STAGE=1 PREVIEW_HR_REDUCE_SHADER_HEADER)
embed_shader_variant(preview_hr.comp compute kPreviewHrApplySpirv apply PREVIEW_HR_STAGE=2 PREVIEW_HR_APPLY_SHADER_HEADER)
embed_shader(preview_average.comp compute kPreviewAverageSpirv PREVIEW_AVERAGE_SHADER_HEADER)
embed_shader(histogram.comp compute kHistogramSpirv HISTOGRAM_SHADER_HEADER)
set(PANORAMA_SHADER_HEADERS)
foreach(PANORAMA_FAMILY RANGE 0 6)
    embed_shader_variant(panorama.comp compute kPanorama${PANORAMA_FAMILY}Spirv family${PANORAMA_FAMILY}
        PANORAMA_FAMILY=${PANORAMA_FAMILY} PANORAMA_SHADER_HEADER)
    list(APPEND PANORAMA_SHADER_HEADERS "${PANORAMA_SHADER_HEADER}")
endforeach()
embed_shader_variant(panorama.comp compute kPanoramaBlendSamplingSpirv compat_blend_sampling
    "PANORAMA_FAMILY=6;PANORAMA_BLEND_SAMPLING_ONLY=1" PANORAMA_BLEND_SAMPLING_HEADER)
list(APPEND PANORAMA_SHADER_HEADERS "${PANORAMA_BLEND_SAMPLING_HEADER}")
set(MULTIFRAME_SHADER_HEADERS)
foreach(MERGE_STAGE RANGE 0 7)
    embed_shader_variant(multiframe_merge.comp compute kMultiFrameMerge${MERGE_STAGE}Spirv stage${MERGE_STAGE}
        MERGE_STAGE=${MERGE_STAGE} MULTIFRAME_SHADER_HEADER)
    list(APPEND MULTIFRAME_SHADER_HEADERS "${MULTIFRAME_SHADER_HEADER}")
endforeach()
embed_shader(gpu_performance.comp compute kGpuPerformanceSpirv GPU_PERFORMANCE_SHADER_HEADER)
embed_shader(raw_image_probe.comp compute kRawImageProbeSpirv RAW_IMAGE_PROBE_SHADER_HEADER)
embed_shader_variant(isp_preview.comp compute kIspPreviewImageSpirv image
    "UNSPEKTRA_ISP_IMAGE_INPUT=1" ISP_PREVIEW_IMAGE_SHADER_HEADER)
embed_shader_variant(isp_preview.comp compute kIspPreviewBufferSpirv buffer
    "UNSPEKTRA_ISP_BUFFER_INPUT=1" ISP_PREVIEW_BUFFER_SHADER_HEADER)
embed_shader_variant(isp_preview.comp compute kIspMeterImageSpirv meter_image
    "UNSPEKTRA_ISP_IMAGE_INPUT=1;UNSPEKTRA_SENSOR_METER=1" ISP_METER_IMAGE_SHADER_HEADER)
embed_shader_variant(isp_preview.comp compute kIspMeterBufferSpirv meter_buffer
    "UNSPEKTRA_ISP_BUFFER_INPUT=1;UNSPEKTRA_SENSOR_METER=1" ISP_METER_BUFFER_SHADER_HEADER)
set(ISP_PREVIEW_SHADER_HEADERS
    "${ISP_PREVIEW_IMAGE_SHADER_HEADER}"
    "${ISP_PREVIEW_BUFFER_SHADER_HEADER}"
    "${ISP_METER_IMAGE_SHADER_HEADER}"
    "${ISP_METER_BUFFER_SHADER_HEADER}")
embed_shader(focus_peaking.comp compute kFocusPeakingSpirv FOCUS_PEAKING_SHADER_HEADER)
embed_shader_variant(focus_peaking.comp compute kFocusOverlaySpirv overlay FOCUS_OVERLAY=1 FOCUS_OVERLAY_SHADER_HEADER)
# Focus loupe programs: unoptimized and without specialization constants so every GPU, including the
# Adreno 740 compatibility path, compiles the same code. The demosaic follows the preview quality path.
embed_shader_variant(focus_loupe.comp compute kFocusLoupePrepareSpirv prepare "LOUPE_PASS=0" FOCUS_LOUPE_PREPARE_SHADER_HEADER)
embed_shader_variant(focus_loupe.comp compute kFocusLoupeDemosaicSpirv demosaic "LOUPE_PASS=1" FOCUS_LOUPE_DEMOSAIC_SHADER_HEADER)
embed_shader_variant(focus_loupe.comp compute kFocusLoupeLightSpirv light "LOUPE_PASS=1;UNSPEKTRA_PREVIEW_LIGHTWEIGHT=1" FOCUS_LOUPE_LIGHT_SHADER_HEADER)
embed_shader_variant(focus_loupe.comp compute kFocusLoupeRemosaicSpirv remosaic "LOUPE_PASS=4" FOCUS_LOUPE_REMOSAIC_SHADER_HEADER)
embed_shader_variant(focus_loupe.comp compute kFocusLoupeScoreSpirv score "LOUPE_PASS=2" FOCUS_LOUPE_SCORE_SHADER_HEADER)
embed_shader_variant(focus_loupe.comp compute kFocusLoupeFinishSpirv finish "LOUPE_PASS=3" FOCUS_LOUPE_FINISH_SHADER_HEADER)
embed_shader(focus_loupe.frag fragment kFocusLoupeFragmentSpirv FOCUS_LOUPE_FRAGMENT_SHADER_HEADER)
embed_shader_variant(
    preview_bilinear.comp
    compute
    kPreviewBilinearImageSpirv
    raw_image
    "UNSPEKTRA_RAW_IMAGE_INPUT=1;UNSPEKTRA_PREVIEW_DEMOSAIC=0"
    PREVIEW_IMAGE_SHADER_HEADER)
embed_shader_variant(
    preview_bilinear.comp compute kPreviewMhcImageSpirv mhc_raw_image
    "UNSPEKTRA_RAW_IMAGE_INPUT=1;UNSPEKTRA_PREVIEW_DEMOSAIC=1" PREVIEW_MHC_IMAGE_SHADER_HEADER)
embed_shader_variant(
    preview_bilinear.comp compute kPreviewShadingSpirv shading
    UNSPEKTRA_PREVIEW_SHADING_REUSE=1 PREVIEW_SHADING_SHADER_HEADER)
embed_shader_variant(
    preview_bilinear.comp compute kPreviewMeterSpirv meter
    UNSPEKTRA_SENSOR_METER=1 PREVIEW_METER_SHADER_HEADER)
embed_shader_variant(
    preview_bilinear.comp compute kPreviewMeterImageSpirv meter_image
    "UNSPEKTRA_SENSOR_METER=1;UNSPEKTRA_RAW_IMAGE_INPUT=1" PREVIEW_METER_IMAGE_SHADER_HEADER)

# Resolve the remosaic meter's fixed options before the device driver compiles it.
# Keep glslc's unoptimized control flow: optimizing this variant crashes some Adreno compilers.
set(PREVIEW_METER_REMOSAIC_SPIRV "${SHADER_OUTPUT_DIR}/preview_bilinear_meter_remosaic_compute.spv")
set(PREVIEW_METER_REMOSAIC_SHADER_HEADER "${SHADER_OUTPUT_DIR}/preview_bilinear_meter_remosaic_compute_spv.h")
add_custom_command(
    OUTPUT "${PREVIEW_METER_REMOSAIC_SHADER_HEADER}"
    BYPRODUCTS "${PREVIEW_METER_REMOSAIC_SPIRV}"
    COMMAND "${GLSLC}" --target-env=vulkan1.1 -fshader-stage=compute
        -DUNSPEKTRA_SENSOR_METER=1 -I "${SHADER_SOURCE_DIR}"
        -o "${PREVIEW_METER_REMOSAIC_SPIRV}"
        "${SHADER_SOURCE_DIR}/preview_bilinear.comp"
    COMMAND "${SPIRV_OPT}"
        --set-spec-const-default-value "6:true"
        --freeze-spec-const --fold-spec-const-op-composite --eliminate-dead-branches
        "${PREVIEW_METER_REMOSAIC_SPIRV}"
        -o "${PREVIEW_METER_REMOSAIC_SPIRV}"
    COMMAND "${SPIRV_VAL}" --target-env vulkan1.1 "${PREVIEW_METER_REMOSAIC_SPIRV}"
    COMMAND "${CMAKE_COMMAND}"
        "-DINPUT_FILE=${PREVIEW_METER_REMOSAIC_SPIRV}"
        "-DOUTPUT_FILE=${PREVIEW_METER_REMOSAIC_SHADER_HEADER}"
        "-DSYMBOL_NAME=kPreviewMeterRemosaicSpirv"
        -P "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
    DEPENDS "${PREVIEW_METER_SHADER_HEADER}" "${SPIRV_OPT}" "${SPIRV_VAL}"
        "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
    VERBATIM)

# Meter pipelines use fixed sampling options, including the original per-pixel shading.
function(freeze_meter_shader variant_name symbol_name output_variable)
    set(input_header "${${output_variable}}")
    set(input_spirv "${SHADER_OUTPUT_DIR}/preview_bilinear_${variant_name}_compute.spv")
    set(output_spirv "${SHADER_OUTPUT_DIR}/preview_bilinear_${variant_name}_fixed_compute.spv")
    set(output_header "${SHADER_OUTPUT_DIR}/preview_bilinear_${variant_name}_fixed_compute_spv.h")
    add_custom_command(
        OUTPUT "${output_header}"
        BYPRODUCTS "${output_spirv}"
        COMMAND "${SPIRV_OPT}" --freeze-spec-const --fold-spec-const-op-composite -O
            "${input_spirv}" -o "${output_spirv}"
        COMMAND "${SPIRV_VAL}" --target-env vulkan1.1 "${output_spirv}"
        COMMAND "${CMAKE_COMMAND}"
            "-DINPUT_FILE=${output_spirv}"
            "-DOUTPUT_FILE=${output_header}"
            "-DSYMBOL_NAME=${symbol_name}"
            -P "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
        DEPENDS "${input_header}" "${SPIRV_OPT}" "${SPIRV_VAL}"
            "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
        VERBATIM)
    set(${output_variable} "${output_header}" PARENT_SCOPE)
endfunction()
freeze_meter_shader(meter kPreviewMeterSpirv PREVIEW_METER_SHADER_HEADER)
freeze_meter_shader(meter_image kPreviewMeterImageSpirv PREVIEW_METER_IMAGE_SHADER_HEADER)

embed_shader_variant(
    preview_bilinear.comp compute kPreviewShadingImageSpirv shading_raw_image
    "UNSPEKTRA_PREVIEW_SHADING_REUSE=1;UNSPEKTRA_RAW_IMAGE_INPUT=1" PREVIEW_SHADING_IMAGE_SHADER_HEADER)
embed_shader(raw_area_weights.comp compute kRawAreaWeightsSpirv RAW_AREA_WEIGHTS_SHADER_HEADER)
list(APPEND PREVIEW_FAST_SHADER_HEADERS "${RAW_AREA_WEIGHTS_SHADER_HEADER}")
# Keep the larger downsampling shaders out of older drivers' preview programs.
foreach(raw_image RANGE 0 1)
    foreach(shading RANGE 0 1)
        set(lightweight_definitions "UNSPEKTRA_PREVIEW_LIGHTWEIGHT=1;UNSPEKTRA_PREVIEW_SHADING_REUSE=${shading}")
        if(raw_image)
            list(APPEND lightweight_definitions "UNSPEKTRA_RAW_IMAGE_INPUT=1")
        endif()
        embed_shader_variant(preview_bilinear.comp compute kPreviewLight${raw_image}${shading}Spirv light_${raw_image}_${shading}
            "${lightweight_definitions}" PREVIEW_LIGHT_SHADER_HEADER)
        list(APPEND PREVIEW_FAST_SHADER_HEADERS "${PREVIEW_LIGHT_SHADER_HEADER}")
    endforeach()
endforeach()
embed_shader_variant(preview_bilinear.comp compute kPreviewLightRemosaicSpirv light_remosaic
    "UNSPEKTRA_PREVIEW_LIGHTWEIGHT=1;UNSPEKTRA_PREVIEW_SHADING_REUSE=0;UNSPEKTRA_PREVIEW_REMOSAIC_INPUT=1"
    PREVIEW_LIGHT_REMOSAIC_SHADER_HEADER)
set(PREVIEW_LIGHT_REMOSAIC_FIXED_SPIRV "${SHADER_OUTPUT_DIR}/preview_bilinear_light_remosaic_fixed_compute.spv")
set(PREVIEW_LIGHT_REMOSAIC_FIXED_HEADER "${SHADER_OUTPUT_DIR}/preview_bilinear_light_remosaic_fixed_compute_spv.h")
add_custom_command(
    OUTPUT "${PREVIEW_LIGHT_REMOSAIC_FIXED_HEADER}"
    BYPRODUCTS "${PREVIEW_LIGHT_REMOSAIC_FIXED_SPIRV}"
    COMMAND "${SPIRV_OPT}"
        --freeze-spec-const --fold-spec-const-op-composite -O
        "${SHADER_OUTPUT_DIR}/preview_bilinear_light_remosaic_compute.spv"
        -o "${PREVIEW_LIGHT_REMOSAIC_FIXED_SPIRV}"
    COMMAND "${SPIRV_VAL}" --target-env vulkan1.1 "${PREVIEW_LIGHT_REMOSAIC_FIXED_SPIRV}"
    COMMAND "${CMAKE_COMMAND}"
        "-DINPUT_FILE=${PREVIEW_LIGHT_REMOSAIC_FIXED_SPIRV}"
        "-DOUTPUT_FILE=${PREVIEW_LIGHT_REMOSAIC_FIXED_HEADER}"
        "-DSYMBOL_NAME=kPreviewLightRemosaicSpirv"
        -P "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
    DEPENDS "${PREVIEW_LIGHT_REMOSAIC_SHADER_HEADER}" "${SPIRV_OPT}" "${SPIRV_VAL}"
        "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
    VERBATIM)
list(APPEND PREVIEW_FAST_SHADER_HEADERS "${PREVIEW_LIGHT_REMOSAIC_FIXED_HEADER}")
# Preserve simple control flow and resolve unused options before the affected driver sees them.
foreach(quality RANGE 0 2)
    foreach(input RANGE 0 2)
        set(compat_definitions "UNSPEKTRA_PREVIEW_SHADING_REUSE=0")
        if(quality EQUAL 0)
            list(APPEND compat_definitions "UNSPEKTRA_PREVIEW_LIGHTWEIGHT=1")
        elseif(quality EQUAL 1)
            list(APPEND compat_definitions "UNSPEKTRA_PREVIEW_DEMOSAIC=0")
        else()
            list(APPEND compat_definitions "UNSPEKTRA_PREVIEW_DEMOSAIC=1")
        endif()
        if(input EQUAL 1)
            list(APPEND compat_definitions "UNSPEKTRA_RAW_IMAGE_INPUT=1")
        elseif(input EQUAL 2)
            list(APPEND compat_definitions "UNSPEKTRA_PREVIEW_REMOSAIC_INPUT=1")
        endif()
        set(compat_variant "compat_${quality}_${input}")
        set(compat_symbol "kPreviewCompat${quality}${input}Spirv")
        embed_shader_variant(preview_bilinear.comp compute ${compat_symbol} ${compat_variant}
            "${compat_definitions}" PREVIEW_COMPAT_INPUT_HEADER)
        set(compat_spirv "${SHADER_OUTPUT_DIR}/preview_bilinear_${compat_variant}_fixed_compute.spv")
        set(compat_header "${SHADER_OUTPUT_DIR}/preview_bilinear_${compat_variant}_fixed_compute_spv.h")
        add_custom_command(
            OUTPUT "${compat_header}"
            BYPRODUCTS "${compat_spirv}"
            COMMAND "${SPIRV_OPT}" --freeze-spec-const --fold-spec-const-op-composite
                --eliminate-dead-branches --eliminate-dead-functions
                "${SHADER_OUTPUT_DIR}/preview_bilinear_${compat_variant}_compute.spv" -o "${compat_spirv}"
            COMMAND "${SPIRV_VAL}" --target-env vulkan1.1 "${compat_spirv}"
            COMMAND "${CMAKE_COMMAND}"
                "-DINPUT_FILE=${compat_spirv}" "-DOUTPUT_FILE=${compat_header}"
                "-DSYMBOL_NAME=${compat_symbol}" -P "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
            DEPENDS "${PREVIEW_COMPAT_INPUT_HEADER}" "${SPIRV_OPT}" "${SPIRV_VAL}"
                "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
            VERBATIM)
        list(APPEND PREVIEW_FAST_SHADER_HEADERS "${compat_header}")
    endforeach()
endforeach()
foreach(input RANGE 0 3)
    set(bayer_cell_definitions "")
    if(input EQUAL 1)
        set(bayer_cell_definitions "UNSPEKTRA_RAW_IMAGE_INPUT=1")
    elseif(input EQUAL 2)
        set(bayer_cell_definitions "UNSPEKTRA_PREVIEW_REMOSAIC_INPUT=1")
    elseif(input EQUAL 3)
        set(bayer_cell_definitions "UNSPEKTRA_RAW_IMAGE_INPUT=1;UNSPEKTRA_RAW_CELL_INPUT=1")
    endif()
    embed_shader_variant(preview_bayer_cell.comp compute kBayerCell${input}Spirv input_${input}
        "${bayer_cell_definitions}" BAYER_CELL_HEADER)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${BAYER_CELL_HEADER}")
    embed_shader_variant(preview_bayer_cell.comp compute kBayerCellCompat${input}Spirv compat_${input}
        "${bayer_cell_definitions}" BAYER_CELL_COMPAT_INPUT_HEADER)
    set(bayer_cell_spirv "${SHADER_OUTPUT_DIR}/preview_bayer_cell_compat_${input}_fixed_compute.spv")
    set(bayer_cell_header "${SHADER_OUTPUT_DIR}/preview_bayer_cell_compat_${input}_fixed_compute_spv.h")
    add_custom_command(
        OUTPUT "${bayer_cell_header}"
        BYPRODUCTS "${bayer_cell_spirv}"
        COMMAND "${SPIRV_OPT}" --freeze-spec-const --fold-spec-const-op-composite
            --eliminate-dead-branches --eliminate-dead-functions
            "${SHADER_OUTPUT_DIR}/preview_bayer_cell_compat_${input}_compute.spv" -o "${bayer_cell_spirv}"
        COMMAND "${SPIRV_VAL}" --target-env vulkan1.1 "${bayer_cell_spirv}"
        COMMAND "${CMAKE_COMMAND}" "-DINPUT_FILE=${bayer_cell_spirv}" "-DOUTPUT_FILE=${bayer_cell_header}"
            "-DSYMBOL_NAME=kBayerCellCompat${input}Spirv" -P "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
        DEPENDS "${BAYER_CELL_COMPAT_INPUT_HEADER}" "${SPIRV_OPT}" "${SPIRV_VAL}"
            "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
        VERBATIM)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${bayer_cell_header}")
endforeach()
embed_shader_variant(preview_bayer_cell.comp fragment kBayerCellRasterSpirv raster
    "UNSPEKTRA_PREVIEW_FRAGMENT=1" BAYER_CELL_RASTER_HEADER)
list(APPEND PREVIEW_FAST_SHADER_HEADERS "${BAYER_CELL_RASTER_HEADER}")
foreach(input RANGE 0 2)
    set(recording_definitions "")
    if(input EQUAL 1)
        set(recording_definitions "UNSPEKTRA_RAW_IMAGE_INPUT=1")
    elseif(input EQUAL 2)
        set(recording_definitions "UNSPEKTRA_RAW_IMAGE_INPUT=1;UNSPEKTRA_RAW_CELL_INPUT=1")
    endif()
    embed_shader_variant(recording_downsample.comp compute kRecording${input}Spirv input_${input}
        "${recording_definitions}" RECORDING_HEADER)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${RECORDING_HEADER}")
    set(recording_tile_definitions ${recording_definitions} UNSPEKTRA_RECORDING_TILE=1)
    embed_shader_variant(recording_downsample.comp compute kRecordingTile${input}Spirv tile_${input}
        "${recording_tile_definitions}" RECORDING_TILE_HEADER)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${RECORDING_TILE_HEADER}")
    embed_shader_variant(recording_downsample.comp compute kRecordingCompat${input}Spirv compat_${input}
        "${recording_definitions}" RECORDING_COMPAT_INPUT_HEADER)
    set(recording_spirv "${SHADER_OUTPUT_DIR}/recording_downsample_compat_${input}_fixed_compute.spv")
    set(recording_header "${SHADER_OUTPUT_DIR}/recording_downsample_compat_${input}_fixed_compute_spv.h")
    add_custom_command(
        OUTPUT "${recording_header}"
        BYPRODUCTS "${recording_spirv}"
        COMMAND "${SPIRV_OPT}" --freeze-spec-const --fold-spec-const-op-composite
            --eliminate-dead-branches --eliminate-dead-functions
            "${SHADER_OUTPUT_DIR}/recording_downsample_compat_${input}_compute.spv" -o "${recording_spirv}"
        COMMAND "${SPIRV_VAL}" --target-env vulkan1.1 "${recording_spirv}"
        COMMAND "${CMAKE_COMMAND}" "-DINPUT_FILE=${recording_spirv}" "-DOUTPUT_FILE=${recording_header}"
            "-DSYMBOL_NAME=kRecordingCompat${input}Spirv" -P "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
        DEPENDS "${RECORDING_COMPAT_INPUT_HEADER}" "${SPIRV_OPT}" "${SPIRV_VAL}"
            "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
        VERBATIM)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${recording_header}")
endforeach()
embed_shader_variant(recording_downsample.comp fragment kRecordingRasterSpirv raster
    "UNSPEKTRA_PREVIEW_FRAGMENT=1" RECORDING_RASTER_HEADER)
list(APPEND PREVIEW_FAST_SHADER_HEADERS "${RECORDING_RASTER_HEADER}")
foreach(quality RANGE 0 1)
    foreach(input RANGE 0 2)
        set(native_recording_definitions "")
        if(quality EQUAL 0)
            list(APPEND native_recording_definitions "UNSPEKTRA_PREVIEW_LIGHTWEIGHT=1")
        endif()
        if(input GREATER 0)
            list(APPEND native_recording_definitions "UNSPEKTRA_RAW_IMAGE_INPUT=1")
        endif()
        if(input EQUAL 2)
            list(APPEND native_recording_definitions "UNSPEKTRA_RAW_CELL_INPUT=1")
        endif()
        embed_shader_variant(recording_native.comp compute kRecordingNative${quality}${input}Spirv input_${quality}_${input}
            "${native_recording_definitions}" NATIVE_RECORDING_HEADER)
        list(APPEND PREVIEW_FAST_SHADER_HEADERS "${NATIVE_RECORDING_HEADER}")
        embed_shader_variant(recording_native.comp compute kRecordingNativeCompat${quality}${input}Spirv compat_${quality}_${input}
            "${native_recording_definitions}" NATIVE_RECORDING_COMPAT_INPUT_HEADER)
        set(native_recording_spirv "${SHADER_OUTPUT_DIR}/recording_native_compat_${quality}_${input}_fixed_compute.spv")
        set(native_recording_header "${SHADER_OUTPUT_DIR}/recording_native_compat_${quality}_${input}_fixed_compute_spv.h")
        add_custom_command(
            OUTPUT "${native_recording_header}"
            BYPRODUCTS "${native_recording_spirv}"
            COMMAND "${SPIRV_OPT}" --freeze-spec-const --fold-spec-const-op-composite
                --eliminate-dead-branches --eliminate-dead-functions
                "${SHADER_OUTPUT_DIR}/recording_native_compat_${quality}_${input}_compute.spv" -o "${native_recording_spirv}"
            COMMAND "${SPIRV_VAL}" --target-env vulkan1.1 "${native_recording_spirv}"
            COMMAND "${CMAKE_COMMAND}" "-DINPUT_FILE=${native_recording_spirv}" "-DOUTPUT_FILE=${native_recording_header}"
                "-DSYMBOL_NAME=kRecordingNativeCompat${quality}${input}Spirv" -P "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
            DEPENDS "${NATIVE_RECORDING_COMPAT_INPUT_HEADER}" "${SPIRV_OPT}" "${SPIRV_VAL}"
                "${CMAKE_CURRENT_LIST_DIR}/EmbedSpirv.cmake"
            VERBATIM)
        list(APPEND PREVIEW_FAST_SHADER_HEADERS "${native_recording_header}")
    endforeach()
endforeach()
embed_shader_variant(preview_bilinear.comp compute kCellMeterSpirv cells_meter
    "UNSPEKTRA_SENSOR_METER=1;UNSPEKTRA_PREVIEW_SHADING_REUSE=0;UNSPEKTRA_RAW_IMAGE_INPUT=1;UNSPEKTRA_RAW_CELL_INPUT=1"
    CELL_METER_HEADER)
list(APPEND PREVIEW_FAST_SHADER_HEADERS "${CELL_METER_HEADER}")
embed_shader_variant(preview_bilinear.comp compute kRecordingMeterSpirv recording_meter
    "UNSPEKTRA_SENSOR_METER=1;UNSPEKTRA_PREVIEW_SHADING_REUSE=0;UNSPEKTRA_RAW_IMAGE_INPUT=1;UNSPEKTRA_RAW_CELL_INPUT=1;UNSPEKTRA_RAW_CELL_FULL=1"
    RECORDING_METER_HEADER)
list(APPEND PREVIEW_FAST_SHADER_HEADERS "${RECORDING_METER_HEADER}")

foreach(raw_format RANGE 0 2)
    embed_shader_variant(preview_bilinear.comp compute kPreviewLightFast${raw_format}Spirv light_fast_${raw_format}
        "UNSPEKTRA_PREVIEW_LIGHTWEIGHT=1;UNSPEKTRA_PREVIEW_SHADING_REUSE=1;UNSPEKTRA_PREVIEW_RAW_FORMAT=${raw_format};UNSPEKTRA_PREVIEW_NO_DCP=1"
        PREVIEW_LIGHT_FAST_SHADER_HEADER)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${PREVIEW_LIGHT_FAST_SHADER_HEADER}")
    embed_shader_variant(preview_bilinear.comp fragment kPreviewLightRaster${raw_format}Spirv light_raster_${raw_format}
        "UNSPEKTRA_PREVIEW_LIGHTWEIGHT=1;UNSPEKTRA_PREVIEW_FRAGMENT=1;UNSPEKTRA_PREVIEW_SHADING_REUSE=1;UNSPEKTRA_PREVIEW_RAW_FORMAT=${raw_format};UNSPEKTRA_PREVIEW_NO_DCP=1"
        PREVIEW_LIGHT_RASTER_SHADER_HEADER)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${PREVIEW_LIGHT_RASTER_SHADER_HEADER}")
    embed_shader_variant(preview_native.comp compute kPreviewPrefilter${raw_format}Spirv prefilter_${raw_format}
        "UNSPEKTRA_PREVIEW_RAW_FORMAT=${raw_format};UNSPEKTRA_MHC_PREFILTER=1" PREVIEW_PREFILTER_SHADER_HEADER)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${PREVIEW_PREFILTER_SHADER_HEADER}")
    embed_shader_variant(preview_native.comp compute kPreviewNative${raw_format}Spirv format_${raw_format}
        "UNSPEKTRA_PREVIEW_RAW_FORMAT=${raw_format}" PREVIEW_NATIVE_SHADER_HEADER)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${PREVIEW_NATIVE_SHADER_HEADER}")
    embed_shader_variant(
        raw_unpack.comp compute kRawUnpack${raw_format}Spirv format_${raw_format}
        "UNSPEKTRA_PREVIEW_SHADING_REUSE=1;UNSPEKTRA_PREVIEW_RAW_FORMAT=${raw_format};UNSPEKTRA_PREVIEW_NO_DCP=1"
        RAW_UNPACK_SHADER_HEADER)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${RAW_UNPACK_SHADER_HEADER}")
    embed_shader_variant(raw_area.comp compute kRawArea${raw_format}Spirv format_${raw_format}
        "UNSPEKTRA_PREVIEW_RAW_FORMAT=${raw_format}" RAW_AREA_SHADER_HEADER)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${RAW_AREA_SHADER_HEADER}")
    embed_shader_variant(
        preview_bilinear.comp compute kPreviewFast${raw_format}Spirv fast_${raw_format}
        "UNSPEKTRA_PREVIEW_SHADING_REUSE=1;UNSPEKTRA_PREVIEW_RAW_FORMAT=${raw_format};UNSPEKTRA_PREVIEW_NO_DCP=1"
        PREVIEW_FAST_SHADER_HEADER)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${PREVIEW_FAST_SHADER_HEADER}")
    embed_shader_variant(
        preview_bilinear.comp fragment kPreviewRaster${raw_format}Spirv raster_${raw_format}
        "UNSPEKTRA_PREVIEW_FRAGMENT=1;UNSPEKTRA_PREVIEW_SHADING_REUSE=1;UNSPEKTRA_PREVIEW_RAW_FORMAT=${raw_format};UNSPEKTRA_PREVIEW_NO_DCP=1"
        PREVIEW_RASTER_SHADER_HEADER)
    list(APPEND PREVIEW_FAST_SHADER_HEADERS "${PREVIEW_RASTER_SHADER_HEADER}")
endforeach()
embed_shader_variant(amaze/amaze.comp compute kAmazeNormalizeBufferSpirv normalize_buffer
    "AMAZE_STAGE=-1" AMAZE_NORMALIZE_BUFFER_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeNormalizeImageSpirv normalize_image
    "AMAZE_STAGE=-1;UNSPEKTRA_RAW_IMAGE_INPUT=1" AMAZE_NORMALIZE_IMAGE_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeCandidatesBufferSpirv stage0_buffer
    "AMAZE_STAGE=0" AMAZE_CANDIDATES_BUFFER_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeCandidatesImageSpirv stage0_image
    "AMAZE_STAGE=0;UNSPEKTRA_RAW_IMAGE_INPUT=1" AMAZE_CANDIDATES_IMAGE_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeDifferencesBufferSpirv stage1_buffer
    "AMAZE_STAGE=1" AMAZE_DIFFERENCES_BUFFER_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeDifferencesImageSpirv stage1_image
    "AMAZE_STAGE=1;UNSPEKTRA_RAW_IMAGE_INPUT=1" AMAZE_DIFFERENCES_IMAGE_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeWeightsBufferSpirv stage2_buffer
    "AMAZE_STAGE=2" AMAZE_WEIGHTS_BUFFER_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeWeightsImageSpirv stage2_image
    "AMAZE_STAGE=2;UNSPEKTRA_RAW_IMAGE_INPUT=1" AMAZE_WEIGHTS_IMAGE_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeGreenBufferSpirv stage3_buffer
    "AMAZE_STAGE=3" AMAZE_GREEN_BUFFER_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeGreenImageSpirv stage3_image
    "AMAZE_STAGE=3;UNSPEKTRA_RAW_IMAGE_INPUT=1" AMAZE_GREEN_IMAGE_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeRefineBufferSpirv stage4_buffer
    "AMAZE_STAGE=4" AMAZE_REFINE_BUFFER_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeRefineImageSpirv stage4_image
    "AMAZE_STAGE=4;UNSPEKTRA_RAW_IMAGE_INPUT=1" AMAZE_REFINE_IMAGE_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeDiagonalBufferSpirv stage5_buffer
    "AMAZE_STAGE=5" AMAZE_DIAGONAL_BUFFER_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeDiagonalImageSpirv stage5_image
    "AMAZE_STAGE=5;UNSPEKTRA_RAW_IMAGE_INPUT=1" AMAZE_DIAGONAL_IMAGE_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeChromaBufferSpirv stage6_buffer
    "AMAZE_STAGE=6" AMAZE_CHROMA_BUFFER_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeChromaImageSpirv stage6_image
    "AMAZE_STAGE=6;UNSPEKTRA_RAW_IMAGE_INPUT=1" AMAZE_CHROMA_IMAGE_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeOutputBufferSpirv stage7_buffer
    "AMAZE_STAGE=7" AMAZE_OUTPUT_BUFFER_SHADER_HEADER)
embed_shader_variant(amaze/amaze.comp compute kAmazeOutputImageSpirv stage7_image
    "AMAZE_STAGE=7;UNSPEKTRA_RAW_IMAGE_INPUT=1" AMAZE_OUTPUT_IMAGE_SHADER_HEADER)
embed_shader(amaze/amaze_hr_opposed_seed.comp compute kAmazeHrSeedBufferSpirv AMAZE_HRSEED_BUFFER_SHADER_HEADER)
embed_shader_variant(amaze/amaze_hr_opposed_seed.comp compute kAmazeHrSeedImageSpirv raw_image UNSPEKTRA_RAW_IMAGE_INPUT=1 AMAZE_HRSEED_IMAGE_SHADER_HEADER)
embed_shader(amaze/amaze_hr_opposed_reduce.comp compute kAmazeHrReduceBufferSpirv AMAZE_HRREDUCE_BUFFER_SHADER_HEADER)
embed_shader_variant(amaze/amaze_hr_opposed_reduce.comp compute kAmazeHrReduceImageSpirv raw_image UNSPEKTRA_RAW_IMAGE_INPUT=1 AMAZE_HRREDUCE_IMAGE_SHADER_HEADER)
embed_shader(amaze/amaze_recovery.comp compute kAmazeRecoveryBufferSpirv AMAZE_RECOVERY_BUFFER_SHADER_HEADER)
embed_shader_variant(amaze/amaze_recovery.comp compute kAmazeRecoveryImageSpirv raw_image UNSPEKTRA_RAW_IMAGE_INPUT=1 AMAZE_RECOVERY_IMAGE_SHADER_HEADER)
embed_shader(
    capture_color.comp
    compute
    kCaptureColorSpirv
    CAPTURE_COLOR_SHADER_HEADER)
embed_shader(
    present.vert
    vertex
    kPresentVertexSpirv
    PRESENT_VERTEX_SHADER_HEADER)
embed_shader(
    present.frag
    fragment
    kPresentFragmentSpirv
    PRESENT_FRAGMENT_SHADER_HEADER)
embed_shader(video.frag fragment kVideoFragmentSpirv VIDEO_FRAGMENT_SHADER_HEADER)

find_package(Python3 REQUIRED COMPONENTS Interpreter)

set(SPEKTRA_DIR "${CMAKE_CURRENT_LIST_DIR}/spektrafilm")
set(SPEKTRA_SHADER_DIR "${SPEKTRA_DIR}/shaders")
set(SPEKTRA_GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated/spektrafilm")
set(SPEKTRA_GENERATED_PROFILE_CURVES
    "${SPEKTRA_GENERATED_DIR}/SpektraGeneratedProfileCurves.cpp")
set(SPEKTRA_GENERATED_PROFILE_COUNTS
    "${SPEKTRA_GENERATED_DIR}/SpektraGeneratedProfileCounts.h")
set(SPEKTRA_GENERATED_HANATOS_LUT
    "${SPEKTRA_GENERATED_DIR}/SpektraHanatos2025Spectra.f32")
set(SPEKTRA_GENERATED_OUTPUT_GAMUT
    "${SPEKTRA_GENERATED_DIR}/SpektraOutputGamutCompression.f32")
set(SPEKTRA_GENERATED_HANATOS_HEADER
    "${SHADER_OUTPUT_DIR}/SpektraHanatos2025Spectra_f32.h")
set(SPEKTRA_GENERATED_OUTPUT_GAMUT_HEADER
    "${SHADER_OUTPUT_DIR}/SpektraOutputGamutCompression_f32.h")
set(SPEKTRA_DATA_DIR "${SPEKTRA_DIR}/Resources/data")

file(GLOB SPEKTRA_PROFILE_JSON CONFIGURE_DEPENDS
    "${SPEKTRA_DATA_DIR}/profiles/*.json"
    "${SPEKTRA_DATA_DIR}/profiles/archive/*.json")
file(GLOB_RECURSE SPEKTRA_RESOURCE_DATA CONFIGURE_DEPENDS
    "${SPEKTRA_DATA_DIR}/filters/*"
    "${SPEKTRA_DATA_DIR}/luts/*")

add_custom_command(
    OUTPUT
        "${SPEKTRA_GENERATED_PROFILE_CURVES}"
        "${SPEKTRA_GENERATED_PROFILE_COUNTS}"
        "${SPEKTRA_GENERATED_HANATOS_LUT}"
        "${SPEKTRA_GENERATED_OUTPUT_GAMUT}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${SPEKTRA_GENERATED_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E env "SPEKTRAFILM_DATA_DIR=${SPEKTRA_DATA_DIR}"
        "${Python3_EXECUTABLE}"
        "${SPEKTRA_DIR}/tools/generate_profile_curves.py"
        --output "${SPEKTRA_GENERATED_PROFILE_CURVES}"
        --counts-output "${SPEKTRA_GENERATED_PROFILE_COUNTS}"
        --hanatos-output "${SPEKTRA_GENERATED_HANATOS_LUT}"
        --output-gamut-compression-output "${SPEKTRA_GENERATED_OUTPUT_GAMUT}"
    DEPENDS
        "${SPEKTRA_DIR}/tools/generate_profile_curves.py"
        "${SPEKTRA_DIR}/tools/ofx_stock_lists.py"
        "${SPEKTRA_DATA_DIR}/monochrome_presets.json"
        ${SPEKTRA_PROFILE_JSON}
        ${SPEKTRA_RESOURCE_DATA}
    VERBATIM)

add_custom_command(
    OUTPUT "${SPEKTRA_GENERATED_HANATOS_HEADER}"
    COMMAND "${Python3_EXECUTABLE}"
        "${SPEKTRA_DIR}/tools/bin_to_header.py"
        --input "${SPEKTRA_GENERATED_HANATOS_LUT}"
        --output "${SPEKTRA_GENERATED_HANATOS_HEADER}"
        --var-name SpektraHanatos2025Spectra_f32
    DEPENDS "${SPEKTRA_GENERATED_HANATOS_LUT}"
    VERBATIM)

add_custom_command(
    OUTPUT "${SPEKTRA_GENERATED_OUTPUT_GAMUT_HEADER}"
    COMMAND "${Python3_EXECUTABLE}"
        "${SPEKTRA_DIR}/tools/bin_to_header.py"
        --input "${SPEKTRA_GENERATED_OUTPUT_GAMUT}"
        --output "${SPEKTRA_GENERATED_OUTPUT_GAMUT_HEADER}"
        --var-name SpektraOutputGamutCompression_f32
    DEPENDS "${SPEKTRA_GENERATED_OUTPUT_GAMUT}"
    VERBATIM)

set(SPEKTRA_SHADER_NAMES
    SpektraCopy
    SpektraFormatConvert
    SpektraImageFormatConvert
    SpektraAutoExposure
    SpektraFilmExposure
    SpektraFilmExposurePreview
    SpektraCurveDevelop
    SpektraCurveDevelopPreview
    SpektraFilmExposureFourStage
    SpektraFilmExposureFourStageCompact
    SpektraFilmExposureFourStageImage
    SpektraFilmExposureFourStageImageCompact
    SpektraCurveDevelopFourStage
    SpektraCurveDevelopFourStageCompact
    SpektraPrintFourStage
    SpektraPrintFourStageCompact
    SpektraPrintFourStageImage
    SpektraPrintFourStageImageCompact
    SpektraPrintFourStageBuild
    SpektraPrintFourStageBuildCompact
    SpektraFilmExposureFourStageTexture
    SpektraFilmExposureFourStageTextureCompact
    SpektraFilmExposureFourStageTextureImage
    SpektraFilmExposureFourStageTextureImageCompact
    SpektraCurveDevelopFourStageTexture
    SpektraCurveDevelopFourStageTextureCompact
    SpektraPrintFourStageTexture
    SpektraPrintFourStageTextureCompact
    SpektraPrintFourStageTextureImage
    SpektraPrintFourStageTextureImageCompact
    SpektraPreviewColor
    SpektraComposedPreviewColor
    SpektraVideoEffects
    SpektraComposeLut
    SpektraPrintComposedImage
    SpektraPrintComposedImageCompact
    SpektraPrintScan
    SpektraPrintScanCompact
    SpektraPrintConstantsCompact
    SpektraPrintPixelsCompact
    SpektraPrintLut
    SpektraPrintLutCompact
    SpektraPrintLutBuild
    SpektraPrintLutBuildCompact
    SpektraPrintProbe
    SpektraPrintProbeCompact
    SpektraPrintAccessProbe
    SpektraHalation
    SpektraDiffusion
    SpektraDiffusionRegions
    SpektraDir
    SpektraScannerPost
    SpektraScannerPostImage
    SpektraGrain
    SpektraGrainPreview
    SpektraPreviewOutput
    SpektraHalationPreview
    SpektraDiffusionPreview
    SpektraPrintScanPreview)
set(SPEKTRA_SHADER_HEADERS "")
file(GLOB SPEKTRA_SHADER_INCLUDES CONFIGURE_DEPENDS "${SPEKTRA_SHADER_DIR}/*.glsl" "${SPEKTRA_SHADER_DIR}/*.h")
foreach(SPEKTRA_SHADER_NAME ${SPEKTRA_SHADER_NAMES})
    string(REGEX REPLACE "(Preview|Compact)$" "" SPEKTRA_SOURCE_NAME "${SPEKTRA_SHADER_NAME}")
    if(SPEKTRA_SHADER_NAME MATCHES "^SpektraPrint((Constants|Pixels)Compact|Lut(Build)?(Compact)?)$")
        set(SPEKTRA_SOURCE_NAME SpektraPrintScan)
    endif()
    if(SPEKTRA_SHADER_NAME MATCHES "^SpektraPrintFourStage")
        set(SPEKTRA_SOURCE_NAME SpektraPrintScan)
    elseif(SPEKTRA_SHADER_NAME MATCHES "FourStage(Texture)?(Image)?(Compact)?$")
        string(REGEX REPLACE "FourStage(Texture)?(Image)?(Compact)?$" "" SPEKTRA_SOURCE_NAME "${SPEKTRA_SHADER_NAME}")
    elseif(SPEKTRA_SHADER_NAME STREQUAL "SpektraScannerPostImage")
        set(SPEKTRA_SOURCE_NAME SpektraScannerPost)
    elseif(SPEKTRA_SHADER_NAME STREQUAL "SpektraDiffusionRegions")
        set(SPEKTRA_SOURCE_NAME SpektraDiffusion)
    elseif(SPEKTRA_SHADER_NAME MATCHES "^Spektra(Composed)?PreviewColor$")
        set(SPEKTRA_SOURCE_NAME SpektraFilmExposure)
    endif()
    set(SPEKTRA_SHADER_SOURCE "${SPEKTRA_SHADER_DIR}/${SPEKTRA_SOURCE_NAME}.comp")
    set(SPEKTRA_DEFINITIONS)
    if(SPEKTRA_SHADER_NAME STREQUAL "SpektraDiffusionRegions")
        list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_DIFFUSION_REGIONS=1)
    endif()
    if(SPEKTRA_SHADER_NAME MATCHES "^Spektra(Composed)?PreviewColor$")
        list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_FOUR_STAGE_LUT=1 -DSPEKTRA_FOUR_STAGE_LUT_TEXTURE=1
            -DSPEKTRA_PREVIEW_IMAGE_INPUT=1 -DSPEKTRA_PREVIEW_COLOR=1)
    endif()
    if(SPEKTRA_SHADER_NAME STREQUAL "SpektraComposedPreviewColor")
        list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_COMPOSED_PREVIEW_COLOR=1)
    endif()
    if(SPEKTRA_SHADER_NAME MATCHES "Preview$")
        set(SPEKTRA_DEFINITIONS -DSPEKTRA_PREVIEW_OPTIMIZATIONS=1)
    endif()
    if(SPEKTRA_SHADER_NAME MATCHES "^Spektra(FilmExposure|CurveDevelop)Preview$")
        list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_SINGLE_STORAGE_BUFFER=1)
    endif()
    if(SPEKTRA_SHADER_NAME MATCHES "Compact$")
        list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_SINGLE_STORAGE_BUFFER=1)
    endif()
    if(SPEKTRA_SHADER_NAME STREQUAL "SpektraPrintConstantsCompact")
        list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_PRINT_CONSTANTS_ONLY=1)
    elseif(SPEKTRA_SHADER_NAME STREQUAL "SpektraPrintPixelsCompact")
        list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_PRINT_PIXELS_ONLY=1)
    endif()
    if(SPEKTRA_SHADER_NAME MATCHES "^SpektraPrintLut")
        list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_PRINT_PIXELS_ONLY=1)
        if(SPEKTRA_SHADER_NAME MATCHES "Build")
            list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_PREVIEW_LUT_BUILD=1)
        else()
            list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_PREVIEW_COLOR_LUT=1 -DSPEKTRA_PREVIEW_OPTIMIZATIONS=1)
        endif()
    endif()
    if(SPEKTRA_SHADER_NAME MATCHES "FourStage")
        if(SPEKTRA_SHADER_NAME MATCHES "Build")
            list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_FOUR_STAGE_LUT_BUILD=1)
        else()
            list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_FOUR_STAGE_LUT=1 -DSPEKTRA_PREVIEW_OPTIMIZATIONS=1)
        endif()
        if(SPEKTRA_SHADER_NAME MATCHES "^SpektraPrint")
            list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_PRINT_PIXELS_ONLY=1)
        endif()
    endif()
    if(SPEKTRA_SHADER_NAME MATCHES "FourStageTexture")
        list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_FOUR_STAGE_LUT_TEXTURE=1)
    endif()
    if(SPEKTRA_SHADER_NAME MATCHES "^SpektraFilmExposureFourStage(Texture)?Image")
        list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_PREVIEW_IMAGE_INPUT=1)
    elseif(SPEKTRA_SHADER_NAME MATCHES "^Spektra(PrintFourStage(Texture)?|ScannerPost)Image")
        list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_PREVIEW_IMAGE_OUTPUT=1)
    endif()
    set(SPEKTRA_SHADER_SPIRV "${SHADER_OUTPUT_DIR}/${SPEKTRA_SHADER_NAME}.spv")
    set(SPEKTRA_SHADER_HEADER "${SHADER_OUTPUT_DIR}/${SPEKTRA_SHADER_NAME}_spv.h")
    set(SPEKTRA_OPTIMIZATION -O)
    if(SPEKTRA_SOURCE_NAME STREQUAL "SpektraPrintScan")
        set(SPEKTRA_OPTIMIZATION -O0)
        if(CMAKE_BUILD_TYPE STREQUAL "Debug")
            list(APPEND SPEKTRA_DEFINITIONS -DSPEKTRA_PRINT_DIAGNOSTICS=1)
        endif()
    endif()
    add_custom_command(
        OUTPUT "${SPEKTRA_SHADER_HEADER}"
        BYPRODUCTS "${SPEKTRA_SHADER_SPIRV}"
        COMMAND "${GLSLC}"
            --target-env=vulkan1.1
            -fshader-stage=compute
            ${SPEKTRA_OPTIMIZATION}
            ${SPEKTRA_DEFINITIONS}
            -I "${SPEKTRA_SHADER_DIR}"
            -o "${SPEKTRA_SHADER_SPIRV}"
            "${SPEKTRA_SHADER_SOURCE}"
        COMMAND "${SPIRV_VAL}" --target-env vulkan1.1 "${SPEKTRA_SHADER_SPIRV}"
        COMMAND "${CMAKE_COMMAND}"
            "-DSPV_FILE=${SPEKTRA_SHADER_SPIRV}"
            "-DHEADER_FILE=${SPEKTRA_SHADER_HEADER}"
            "-DVAR_NAME=${SPEKTRA_SHADER_NAME}_spv"
            -P "${CMAKE_CURRENT_LIST_DIR}/SpektraSpvToHeader.cmake"
        DEPENDS
            "${SPEKTRA_SHADER_SOURCE}"
            ${SPEKTRA_SHADER_INCLUDES}
            "${CMAKE_CURRENT_LIST_DIR}/SpektraSpvToHeader.cmake"
        VERBATIM)
    list(APPEND SPEKTRA_SHADER_HEADERS "${SPEKTRA_SHADER_HEADER}")
endforeach()

list(APPEND SPEKTRA_SHADER_HEADERS
    "${SPEKTRA_GENERATED_HANATOS_HEADER}"
    "${SPEKTRA_GENERATED_OUTPUT_GAMUT_HEADER}")

set_source_files_properties(
    spektrafilm/src/SpektraVulkanRenderer.cpp
    PROPERTIES COMPILE_OPTIONS "-Wno-unused-function")

set_source_files_properties(
    VulkanBridge.cpp
    NativeDiagnostics.cpp
    VulkanRenderer.cpp
    PROPERTIES COMPILE_OPTIONS "$<$<CONFIG:Debug>:-O2>")

# Compiled programs include their shader dependencies, variants and compiler options.
# Host code and app metadata must not invalidate otherwise reusable GPU programs.
set(PIPELINE_CACHE_SHADER_HEADERS
    ${PANORAMA_SHADER_HEADERS}
    "${GPU_PERFORMANCE_SHADER_HEADER}"
    "${PREVIEW_HR_SEED_SHADER_HEADER}"
    "${PREVIEW_HR_REDUCE_SHADER_HEADER}"
    "${PREVIEW_HR_APPLY_SHADER_HEADER}"
    "${SHADOW_LUMA_SHADER_HEADER}"
    "${SHADOW_BILATERAL_SHADER_HEADER}"
    "${SHADOW_COLOR_SHADER_HEADER}"
    "${PREVIEW_METER_SHADER_HEADER}"
    "${PREVIEW_METER_IMAGE_SHADER_HEADER}"
    "${PREVIEW_METER_REMOSAIC_SHADER_HEADER}"
    "${CHROMA_DENOISE_SHADER_HEADER}"
    "${VIDEO_CHROMA_SHADER_HEADER}"
    "${RAW_HOT_PIXELS_BUFFER_SHADER_HEADER}"
    "${RAW_HOT_PIXELS_IMAGE_SHADER_HEADER}"
    "${HISTOGRAM_SHADER_HEADER}"
    "${RAW_IMAGE_PROBE_SHADER_HEADER}"
    ${ISP_PREVIEW_SHADER_HEADERS}
    "${FOCUS_PEAKING_SHADER_HEADER}"
    "${PREVIEW_AVERAGE_SHADER_HEADER}"
    "${FOCUS_OVERLAY_SHADER_HEADER}"
    "${FOCUS_LOUPE_PREPARE_SHADER_HEADER}"
    "${FOCUS_LOUPE_DEMOSAIC_SHADER_HEADER}"
    "${FOCUS_LOUPE_LIGHT_SHADER_HEADER}"
    "${FOCUS_LOUPE_REMOSAIC_SHADER_HEADER}"
    "${FOCUS_LOUPE_SCORE_SHADER_HEADER}"
    "${FOCUS_LOUPE_FINISH_SHADER_HEADER}"
    "${FOCUS_LOUPE_FRAGMENT_SHADER_HEADER}"
    ${SPEKTRA_SHADER_HEADERS}
    ${PANORAMA_SHADER_HEADERS}
    "${REMOSAIC_BUFFER_SHADER_HEADER}"
    "${REMOSAIC_IMAGE_SHADER_HEADER}"
    "${PREVIEW_SHADER_HEADER}"
    "${PREVIEW_IMAGE_SHADER_HEADER}"
    "${PREVIEW_MHC_SHADER_HEADER}"
    "${PREVIEW_MHC_IMAGE_SHADER_HEADER}"
    "${PREVIEW_SHADING_SHADER_HEADER}"
    "${PREVIEW_SHADING_IMAGE_SHADER_HEADER}"
    ${PREVIEW_FAST_SHADER_HEADERS}
    "${AMAZE_NORMALIZE_BUFFER_SHADER_HEADER}"
    "${AMAZE_NORMALIZE_IMAGE_SHADER_HEADER}"
    "${AMAZE_CANDIDATES_BUFFER_SHADER_HEADER}"
    "${AMAZE_CANDIDATES_IMAGE_SHADER_HEADER}"
    "${AMAZE_DIFFERENCES_BUFFER_SHADER_HEADER}"
    "${AMAZE_DIFFERENCES_IMAGE_SHADER_HEADER}"
    "${AMAZE_WEIGHTS_BUFFER_SHADER_HEADER}"
    "${AMAZE_WEIGHTS_IMAGE_SHADER_HEADER}"
    "${AMAZE_GREEN_BUFFER_SHADER_HEADER}"
    "${AMAZE_GREEN_IMAGE_SHADER_HEADER}"
    "${AMAZE_REFINE_BUFFER_SHADER_HEADER}"
    "${AMAZE_REFINE_IMAGE_SHADER_HEADER}"
    "${AMAZE_DIAGONAL_BUFFER_SHADER_HEADER}"
    "${AMAZE_DIAGONAL_IMAGE_SHADER_HEADER}"
    "${AMAZE_CHROMA_BUFFER_SHADER_HEADER}"
    "${AMAZE_CHROMA_IMAGE_SHADER_HEADER}"
    "${AMAZE_OUTPUT_BUFFER_SHADER_HEADER}"
    "${AMAZE_OUTPUT_IMAGE_SHADER_HEADER}"
    "${AMAZE_HRSEED_BUFFER_SHADER_HEADER}"
    "${AMAZE_HRSEED_IMAGE_SHADER_HEADER}"
    "${AMAZE_HRREDUCE_BUFFER_SHADER_HEADER}"
    "${AMAZE_HRREDUCE_IMAGE_SHADER_HEADER}"
    "${AMAZE_RECOVERY_BUFFER_SHADER_HEADER}"
    "${AMAZE_RECOVERY_IMAGE_SHADER_HEADER}"
    "${CAPTURE_COLOR_SHADER_HEADER}"
    "${PRESENT_VERTEX_SHADER_HEADER}"
    "${PRESENT_FRAGMENT_SHADER_HEADER}"
    "${VIDEO_FRAGMENT_SHADER_HEADER}")
list(REMOVE_ITEM PIPELINE_CACHE_SHADER_HEADERS
    "${SPEKTRA_GENERATED_HANATOS_HEADER}" "${SPEKTRA_GENERATED_OUTPUT_GAMUT_HEADER}")
set(PIPELINE_CACHE_INPUTS ${PIPELINE_CACHE_SHADER_HEADERS})
list(TRANSFORM PIPELINE_CACHE_INPUTS REPLACE "_spv\\.h$" ".spv")
list(REMOVE_DUPLICATES PIPELINE_CACHE_INPUTS)
list(SORT PIPELINE_CACHE_INPUTS)
list(JOIN PIPELINE_CACHE_INPUTS "\n" PIPELINE_CACHE_INPUT_LIST)
set(PIPELINE_CACHE_LIST_FILE "${CMAKE_CURRENT_BINARY_DIR}/pipeline-cache-inputs.txt")
file(GENERATE OUTPUT "${PIPELINE_CACHE_LIST_FILE}" CONTENT "${PIPELINE_CACHE_INPUT_LIST}\n")
set(PIPELINE_CACHE_IDENTITY_HEADER "${SHADER_OUTPUT_DIR}/PipelineCacheIdentity.h")
add_custom_command(
    OUTPUT "${PIPELINE_CACHE_IDENTITY_HEADER}"
    COMMAND "${CMAKE_COMMAND}"
        "-DINPUT_LIST=${PIPELINE_CACHE_LIST_FILE}"
        "-DOUTPUT_FILE=${PIPELINE_CACHE_IDENTITY_HEADER}"
        -P "${CMAKE_CURRENT_LIST_DIR}/GeneratePipelineCacheIdentity.cmake"
    DEPENDS ${PIPELINE_CACHE_INPUTS} ${PIPELINE_CACHE_SHADER_HEADERS} "${PIPELINE_CACHE_LIST_FILE}"
        "${CMAKE_CURRENT_LIST_DIR}/GeneratePipelineCacheIdentity.cmake"
    VERBATIM)

add_subdirectory(third_party/c-blosc)

add_library(unspektra_archive SHARED burst/RawFrameCompression.cpp burst/Panorama.cpp burst/PanoramaGpu.cpp
    burst/MultiFrameMerge.cpp NativeHost.cpp NativeDiagnostics.cpp ${PANORAMA_SHADER_HEADERS} ${MULTIFRAME_SHADER_HEADERS})
target_include_directories(unspektra_archive PRIVATE "${SHADER_OUTPUT_DIR}")
target_compile_options(unspektra_archive PRIVATE -O3 -Wno-missing-field-initializers)

add_library(
    unspektrawesome_vulkan
    SHARED
    "${GPU_PERFORMANCE_SHADER_HEADER}"
    "${PREVIEW_HR_SEED_SHADER_HEADER}"
    "${PREVIEW_HR_REDUCE_SHADER_HEADER}"
    "${PREVIEW_HR_APPLY_SHADER_HEADER}"
    "${SHADOW_LUMA_SHADER_HEADER}"
    "${SHADOW_BILATERAL_SHADER_HEADER}"
    "${SHADOW_COLOR_SHADER_HEADER}"
    "${PREVIEW_METER_SHADER_HEADER}"
    "${PREVIEW_METER_IMAGE_SHADER_HEADER}"
    "${PREVIEW_METER_REMOSAIC_SHADER_HEADER}"
    "${CHROMA_DENOISE_SHADER_HEADER}"
    "${VIDEO_CHROMA_SHADER_HEADER}"
    "${RAW_HOT_PIXELS_BUFFER_SHADER_HEADER}"
    "${RAW_HOT_PIXELS_IMAGE_SHADER_HEADER}"
    "${HISTOGRAM_SHADER_HEADER}"
    "${RAW_IMAGE_PROBE_SHADER_HEADER}"
    ${ISP_PREVIEW_SHADER_HEADERS}
    "${FOCUS_PEAKING_SHADER_HEADER}"
    "${PREVIEW_AVERAGE_SHADER_HEADER}"
    "${FOCUS_OVERLAY_SHADER_HEADER}"
    "${FOCUS_LOUPE_PREPARE_SHADER_HEADER}"
    "${FOCUS_LOUPE_DEMOSAIC_SHADER_HEADER}"
    "${FOCUS_LOUPE_LIGHT_SHADER_HEADER}"
    "${FOCUS_LOUPE_REMOSAIC_SHADER_HEADER}"
    "${FOCUS_LOUPE_SCORE_SHADER_HEADER}"
    "${FOCUS_LOUPE_FINISH_SHADER_HEADER}"
    "${FOCUS_LOUPE_FRAGMENT_SHADER_HEADER}"
    VulkanBridge.cpp
    NativeDiagnostics.cpp
    NativeHost.cpp
    VulkanInitialization.cpp
    VulkanDiagnostics.cpp
    VulkanRenderer.cpp
    RecordingRawBuffer.cpp
    spektrafilm_android_jni.cpp
    spektrafilm/src/SpektraVulkanRenderer.cpp
    "${PIPELINE_CACHE_IDENTITY_HEADER}"
    "${SPEKTRA_GENERATED_PROFILE_CURVES}"
    ${SPEKTRA_SHADER_HEADERS}
    ${PREVIEW_FAST_SHADER_HEADERS}
    "${REMOSAIC_BUFFER_SHADER_HEADER}"
    "${REMOSAIC_IMAGE_SHADER_HEADER}"
    "${PREVIEW_SHADER_HEADER}"
    "${PREVIEW_IMAGE_SHADER_HEADER}"
    "${PREVIEW_MHC_SHADER_HEADER}"
    "${PREVIEW_MHC_IMAGE_SHADER_HEADER}"
    "${PREVIEW_SHADING_SHADER_HEADER}"
    "${PREVIEW_SHADING_IMAGE_SHADER_HEADER}"
    "${AMAZE_NORMALIZE_BUFFER_SHADER_HEADER}"
    "${AMAZE_NORMALIZE_IMAGE_SHADER_HEADER}"
    "${AMAZE_CANDIDATES_BUFFER_SHADER_HEADER}"
    "${AMAZE_CANDIDATES_IMAGE_SHADER_HEADER}"
    "${AMAZE_DIFFERENCES_BUFFER_SHADER_HEADER}"
    "${AMAZE_DIFFERENCES_IMAGE_SHADER_HEADER}"
    "${AMAZE_WEIGHTS_BUFFER_SHADER_HEADER}"
    "${AMAZE_WEIGHTS_IMAGE_SHADER_HEADER}"
    "${AMAZE_GREEN_BUFFER_SHADER_HEADER}"
    "${AMAZE_GREEN_IMAGE_SHADER_HEADER}"
    "${AMAZE_REFINE_BUFFER_SHADER_HEADER}"
    "${AMAZE_REFINE_IMAGE_SHADER_HEADER}"
    "${AMAZE_DIAGONAL_BUFFER_SHADER_HEADER}"
    "${AMAZE_DIAGONAL_IMAGE_SHADER_HEADER}"
    "${AMAZE_CHROMA_BUFFER_SHADER_HEADER}"
    "${AMAZE_CHROMA_IMAGE_SHADER_HEADER}"
    "${AMAZE_OUTPUT_BUFFER_SHADER_HEADER}"
    "${AMAZE_OUTPUT_IMAGE_SHADER_HEADER}"
    "${AMAZE_HRSEED_BUFFER_SHADER_HEADER}"
    "${AMAZE_HRSEED_IMAGE_SHADER_HEADER}"
    "${AMAZE_HRREDUCE_BUFFER_SHADER_HEADER}"
    "${AMAZE_HRREDUCE_IMAGE_SHADER_HEADER}"
    "${AMAZE_RECOVERY_BUFFER_SHADER_HEADER}"
    "${AMAZE_RECOVERY_IMAGE_SHADER_HEADER}"
    "${CAPTURE_COLOR_SHADER_HEADER}"
    "${PRESENT_VERTEX_SHADER_HEADER}"
    "${PRESENT_FRAGMENT_SHADER_HEADER}"
    "${VIDEO_FRAGMENT_SHADER_HEADER}")

target_include_directories(
    unspektrawesome_vulkan
    PRIVATE
    "${SHADER_OUTPUT_DIR}"
    "${SPEKTRA_GENERATED_DIR}"
    "${SPEKTRA_DIR}/src")

if(UNSPEKTRA_VULKAN_VALIDATION AND CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_definitions(unspektrawesome_vulkan PRIVATE UNSPEKTRA_VULKAN_VALIDATION=1)
endif()

find_library(ANDROID_LIBRARY android REQUIRED)
find_library(NATIVE_WINDOW_LIBRARY nativewindow REQUIRED)
find_library(LOG_LIBRARY log REQUIRED)
find_library(VULKAN_LIBRARY vulkan REQUIRED)
find_library(JNIGRAPHICS_LIBRARY jnigraphics REQUIRED)
target_link_libraries(unspektra_archive PRIVATE spektra_blosc "${JNIGRAPHICS_LIBRARY}" "${LOG_LIBRARY}" "${VULKAN_LIBRARY}")
find_library(EGL_LIBRARY EGL REQUIRED)
find_library(GLES_LIBRARY GLESv3 REQUIRED)

target_link_libraries(
    unspektrawesome_vulkan
    PRIVATE
    "${ANDROID_LIBRARY}"
    "${NATIVE_WINDOW_LIBRARY}"
    "${LOG_LIBRARY}"
    "${JNIGRAPHICS_LIBRARY}"
    "${EGL_LIBRARY}"
    "${GLES_LIBRARY}"
    "${VULKAN_LIBRARY}")

target_compile_options(
    unspektrawesome_vulkan
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Wno-missing-field-initializers
    -Werror)

function(protect_native_library target identity_dir)
    target_include_directories(${target} PRIVATE "${identity_dir}")
    set_target_properties(${target} PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES)
    target_compile_options(${target} PRIVATE -fstack-protector-strong)
    target_link_options(${target} PRIVATE
        "-Wl,--version-script,${CMAKE_CURRENT_LIST_DIR}/native-exports.map"
        -Wl,--no-undefined-version -Wl,-z,relro -Wl,-z,now)
    set_property(TARGET ${target} APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_LIST_DIR}/native-exports.map")
endfunction()

protect_native_library(unspektrawesome_vulkan "${UNSPEKTRA_IDENTITY_DIR}")
protect_native_library(unspektra_archive "${UNSPEKTRA_IDENTITY_DIR}")

if(UNSPEKTRA_HOST_TESTS)
    foreach(case package certificate)
        add_library(unspektra_rejected_${case} SHARED NativeHost.cpp ../../qa/cpp/RejectedNativeHost.cpp)
        protect_native_library(unspektra_rejected_${case} "${UNSPEKTRA_IDENTITY_DIR}/rejected_${case}")
    endforeach()
endif()
