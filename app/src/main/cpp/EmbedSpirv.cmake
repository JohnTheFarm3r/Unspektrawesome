if(NOT DEFINED INPUT_FILE OR NOT DEFINED OUTPUT_FILE OR NOT DEFINED SYMBOL_NAME)
    message(FATAL_ERROR "INPUT_FILE, OUTPUT_FILE, and SYMBOL_NAME are required")
endif()

file(READ "${INPUT_FILE}" shader_hex HEX)
string(LENGTH "${shader_hex}" shader_hex_length)
math(EXPR trailing_bytes "${shader_hex_length} % 8")
if(NOT trailing_bytes EQUAL 0)
    message(FATAL_ERROR "SPIR-V input size is not a multiple of four bytes")
endif()

math(EXPR word_count "${shader_hex_length} / 8")
string(REGEX REPLACE "(..)(..)(..)(..)" "    0x\\4\\3\\2\\1u,\n" shader_words "${shader_hex}")

set(header "#pragma once\n\n#include <array>\n#include <cstdint>\n\n")
string(APPEND header "inline constexpr std::array<std::uint32_t, ${word_count}> ${SYMBOL_NAME} = {\n")
string(APPEND header "${shader_words}};\n")
file(WRITE "${OUTPUT_FILE}" "${header}")
