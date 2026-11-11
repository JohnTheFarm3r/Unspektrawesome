#pragma once

#include <cstdint>
#include <array>
#include <string_view>

namespace raw_preview {

enum class ChipsetQuality { Unknown, Lightweight, Filtered };

constexpr int modelNumber(std::string_view name)
{
    int number = 0;
    int digits = 0;
    for (const char character : name) {
        if (character < '0' || character > '9') break;
        if (++digits > 5) return 0;
        number = number * 10 + character - '0';
    }
    return number;
}

constexpr ChipsetQuality chipsetQuality(std::string_view name)
{
    std::array<char, 128> characters{};
    size_t count = 0;
    for (char character : name) {
        if (character >= 'A' && character <= 'Z') character += 'a' - 'A';
        if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
            if (count == characters.size()) return ChipsetQuality::Unknown;
            characters[count++] = character;
        }
    }
    std::string_view normalized(characters.data(), count);
    if (normalized == "gs101" || normalized == "gs201" || normalized == "gs301" || normalized == "zuma")
        return ChipsetQuality::Lightweight;
    if (normalized == "gs401" || normalized == "zumapro") return ChipsetQuality::Filtered;
    const auto tensor = normalized.find("tensor");
    if (tensor != std::string_view::npos) {
        const auto generation = normalized.substr(tensor + 6);
        if (generation.empty()) return ChipsetQuality::Lightweight;
        if (generation.starts_with("g")) {
            const int model = modelNumber(generation.substr(1));
            if (model > 0) return model < 4 ? ChipsetQuality::Lightweight : ChipsetQuality::Filtered;
        }
    }
    const auto dimensity = normalized.find("dimensity");
    if (dimensity != std::string_view::npos) {
        const int model = modelNumber(normalized.substr(dimensity + 9));
        if (model >= 100) return model < 9000 ? ChipsetQuality::Lightweight : ChipsetQuality::Filtered;
    }
    if (normalized.starts_with("mediatek")) normalized.remove_prefix(8);
    if (normalized.starts_with("helio")) return ChipsetQuality::Lightweight;
    if (normalized.starts_with("mt")) {
        const int model = modelNumber(normalized.substr(2));
        // MT6983 is Dimensity 9000; lower mobile platform IDs use the lighter path.
        if (model >= 6500 && model < 7000)
            return model < 6983 ? ChipsetQuality::Lightweight : ChipsetQuality::Filtered;
    }
    return ChipsetQuality::Unknown;
}

constexpr bool useLightweightPreview(uint32_t vendor, std::string_view name,
                                     std::string_view soc = {}, std::string_view platform = {},
                                     std::string_view hardware = {})
{
    for (const auto identity : {soc, platform, hardware}) {
        const auto quality = chipsetQuality(identity);
        if (quality != ChipsetQuality::Unknown) return quality == ChipsetQuality::Lightweight;
    }
    if (vendor != 0x5143u) return false;
    const auto adreno = name.find("Adreno");
    if (adreno == std::string_view::npos) return false;
    auto digit = name.find_first_of("0123456789", adreno + 6);
    if (digit == std::string_view::npos) return false;
    int model = 0;
    int digits = 0;
    while (digit < name.size() && name[digit] >= '0' && name[digit] <= '9') {
        if (++digits > 3) return false;
        model = model * 10 + name[digit++] - '0';
    }
    // Snapdragon 8 Gen 1's Adreno 730 and newer GPUs retain filtered MHC previews.
    return digits == 3 && model >= 500 && model < 730;
}

}
