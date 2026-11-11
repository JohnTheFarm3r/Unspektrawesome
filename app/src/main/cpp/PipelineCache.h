#pragma once

#include <vulkan/vulkan.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include "PipelineCacheIdentity.h"
#include "NativeDiagnostics.h"

class PipelineCache {
public:
    VkPipelineCache handle = VK_NULL_HANDLE;
    std::atomic<uint64_t> revision{0};
    bool loadedCompatibleData = false;

    std::string preparationIdentity() const {
        std::string value = formatIdentity() + "/" + kShaderPreparationIdentity + "/" +
            std::to_string(kPreparationRevision) + "/" +
            std::to_string(properties_.vendorID) + "/" + std::to_string(properties_.deviceID) + "/" +
            std::to_string(properties_.driverVersion);
        for (uint8_t byte : properties_.pipelineCacheUUID) value += "/" + std::to_string(byte);
        return value;
    }

    void initialize(VkDevice device, VkPhysicalDevice physical, const std::string& prefix) {
        vkGetPhysicalDeviceProperties(physical, &properties_);
        device_ = device;
        revision.store(0, std::memory_order_relaxed);
        savedRevision_ = 0;
        path_ = prefix.empty() ? "" : prefix + "-" + std::to_string(properties_.vendorID) + "-" +
            std::to_string(properties_.deviceID) + "-" + std::to_string(properties_.driverVersion) + ".bin";
        std::lock_guard<std::mutex> lock(fileMutex());
        auto bytes = readCompatible();
        observedGeneration_ = generations()[path_];
        VkPipelineCacheCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};
        info.initialDataSize = bytes.size();
        info.pInitialData = bytes.empty() ? nullptr : bytes.data();
        if (vkCreatePipelineCache(device_, &info, nullptr, &handle) != VK_SUCCESS) {
            bytes.clear();
            info.initialDataSize = 0;
            info.pInitialData = nullptr;
            vkCreatePipelineCache(device_, &info, nullptr, &handle);
        }
        loadedCompatibleData = !bytes.empty();
        recordNativeDiagnostic(ANDROID_LOG_INFO, "CameraStartup", "Pipeline cache loaded: %zu bytes", bytes.size());
    }

    void markDirty() { revision.fetch_add(1, std::memory_order_relaxed); }

    // Called while this renderer is idle, before it can create more pipelines.
    void refresh() {
        if (handle == VK_NULL_HANDLE || path_.empty()) return;
        std::lock_guard<std::mutex> lock(fileMutex());
        if (observedGeneration_ == generations()[path_]) return;
        auto bytes = readCompatible();
        if (bytes.empty()) return;
        VkPipelineCacheCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};
        info.initialDataSize = bytes.size();
        info.pInitialData = bytes.data();
        VkPipelineCache prepared = VK_NULL_HANDLE;
        if (vkCreatePipelineCache(device_, &info, nullptr, &prepared) != VK_SUCCESS) return;
        const auto result = vkMergePipelineCaches(device_, handle, 1, &prepared);
        vkDestroyPipelineCache(device_, prepared, nullptr);
        if (result == VK_SUCCESS) observedGeneration_ = generations()[path_];
    }

    void save() {
        if (handle == VK_NULL_HANDLE || path_.empty()) return;
        const uint64_t currentRevision = revision.load(std::memory_order_relaxed);
        if (currentRevision == savedRevision_) return;
        std::lock_guard<std::mutex> lock(fileMutex());
        auto existing = readCompatible();
        VkPipelineCache merged = VK_NULL_HANDLE;
        if (!existing.empty()) {
            VkPipelineCacheCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};
            info.initialDataSize = existing.size();
            info.pInitialData = existing.data();
            if (vkCreatePipelineCache(device_, &info, nullptr, &merged) != VK_SUCCESS) return;
            // Merge into a temporary cache so preview and capture retain each other's entries.
            if (vkMergePipelineCaches(device_, merged, 1, &handle) != VK_SUCCESS) {
                vkDestroyPipelineCache(device_, merged, nullptr);
                return;
            }
        }
        const VkPipelineCache source = merged != VK_NULL_HANDLE ? merged : handle;
        size_t size = 0;
        std::vector<char> bytes;
        if (vkGetPipelineCacheData(device_, source, &size, nullptr) == VK_SUCCESS && size > 0 && size <= kMaximumBytes) {
            bytes.resize(size);
            if (vkGetPipelineCacheData(device_, source, &size, bytes.data()) != VK_SUCCESS) bytes.clear();
        }
        if (merged != VK_NULL_HANDLE) vkDestroyPipelineCache(device_, merged, nullptr);
        if (bytes.empty()) return;
        const std::string temporary = path_ + ".tmp";
        std::ofstream output(temporary, std::ios::binary | std::ios::trunc);
        const auto& identity = formatIdentity();
        if (!output.write(identity.c_str(), static_cast<std::streamsize>(identity.size() + 1))) return;
        if (!output.write(bytes.data(), static_cast<std::streamsize>(size))) return;
        output.close();
        if (!output.fail() && std::rename(temporary.c_str(), path_.c_str()) == 0) {
            savedRevision_ = currentRevision;
            ++generations()[path_];
        }
    }

    void destroy() {
        if (handle != VK_NULL_HANDLE) {
            save();
            vkDestroyPipelineCache(device_, handle, nullptr);
        }
        handle = VK_NULL_HANDLE;
        device_ = VK_NULL_HANDLE;
    }

private:
    // Bump only when the cache format or a driver workaround requires a full rebuild.
    static constexpr uint32_t kFormatVersion = 1;
    // Bump when warmup coverage changes without changing any compiled shader.
    static constexpr uint32_t kPreparationRevision = 2;
    static constexpr size_t kMaximumBytes = 64 * 1024 * 1024;
    static const std::string& formatIdentity() {
        static const std::string value = "unspektrawesome-vulkan-pipeline-cache/" + std::to_string(kFormatVersion);
        return value;
    }
    static std::mutex& fileMutex() {
        static std::mutex mutex;
        return mutex;
    }
    static std::unordered_map<std::string, uint64_t>& generations() {
        static std::unordered_map<std::string, uint64_t> values;
        return values;
    }

    std::vector<char> readCompatible() const {
        std::ifstream input(path_, std::ios::binary | std::ios::ate);
        if (!input) return {};
        const auto length = input.tellg();
        const auto& expected = formatIdentity();
        constexpr size_t legacyIdentitySize = 65;
        if (length < static_cast<std::streamoff>(expected.size() + 1 + sizeof(VkPipelineCacheHeaderVersionOne)) ||
            length > static_cast<std::streamoff>(kMaximumBytes + legacyIdentitySize)) return {};
        input.seekg(0);
        char identity[legacyIdentitySize]{};
        if (!input.read(identity, sizeof(identity))) return {};
        size_t identitySize = expected.size() + 1;
        if (std::memcmp(identity, expected.c_str(), identitySize) != 0) {
            // Older files used a build hash around the same driver-owned cache data.
            const bool legacy = kFormatVersion == 1 && identity[64] == '\0' &&
                std::all_of(identity, identity + 64, [](char c) {
                    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
                });
            if (!legacy) return {};
            identitySize = legacyIdentitySize;
        }
        const size_t payloadSize = static_cast<size_t>(length) - identitySize;
        if (payloadSize < sizeof(VkPipelineCacheHeaderVersionOne) || payloadSize > kMaximumBytes) return {};
        input.seekg(static_cast<std::streamoff>(identitySize));
        std::vector<char> bytes(payloadSize);
        if (!input.read(bytes.data(), static_cast<std::streamsize>(bytes.size()))) return {};
        VkPipelineCacheHeaderVersionOne header{};
        std::memcpy(&header, bytes.data(), sizeof(header));
        if (header.headerSize < sizeof(header) || header.headerSize > bytes.size() ||
            header.headerVersion != VK_PIPELINE_CACHE_HEADER_VERSION_ONE ||
            header.vendorID != properties_.vendorID || header.deviceID != properties_.deviceID ||
            std::memcmp(header.pipelineCacheUUID, properties_.pipelineCacheUUID, VK_UUID_SIZE) != 0) return {};
        return bytes;
    }

    VkDevice device_ = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties properties_{};
    uint64_t savedRevision_ = 0;
    uint64_t observedGeneration_ = 0;
    std::string path_;
};
