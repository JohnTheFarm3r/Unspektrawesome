#include "VulkanDiagnostics.h"
#include "NativeDiagnostics.h"

#include <android/log.h>
#include <algorithm>
#include <cstring>

void nameDiagnosticShader(VkDevice device, VkShaderModule module, const char* name)
{
#if defined(UNSPEKTRA_VULKAN_VALIDATION)
    const auto setName = reinterpret_cast<PFN_vkSetDebugUtilsObjectNameEXT>(
        vkGetDeviceProcAddr(device, "vkSetDebugUtilsObjectNameEXT"));
    if (!setName) return;
    VkDebugUtilsObjectNameInfoEXT info{};
    info.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_OBJECT_NAME_INFO_EXT;
    info.objectType = VK_OBJECT_TYPE_SHADER_MODULE;
    info.objectHandle = reinterpret_cast<uint64_t>(module);
    info.pObjectName = name;
    setName(device, &info);
#else
    (void)device;
    (void)module;
    (void)name;
#endif
}

void VulkanDiagnostics::configure(VkInstanceCreateInfo& info, std::vector<const char*>& extensions)
{
#if defined(UNSPEKTRA_VULKAN_VALIDATION)
    uint32_t count = 0;
    VkResult result = vkEnumerateInstanceLayerProperties(&count, nullptr);
    std::vector<VkLayerProperties> layers(count);
    if (result == VK_SUCCESS) result = vkEnumerateInstanceLayerProperties(&count, layers.data());
    const auto layer = std::find_if(layers.begin(), layers.end(), [&](const auto& candidate) {
        return std::strcmp(candidate.layerName, layer_) == 0;
    });
    if (result != VK_SUCCESS || layer == layers.end()) {
        recordNativeDiagnostic(ANDROID_LOG_ERROR, "VulkanValidation", "Bundled validation layer unavailable: result=%d", result);
        return;
    }
    auto supports = [&](const char* name) {
        for (const char* source : {static_cast<const char*>(nullptr), layer_}) {
            uint32_t extensionCount = 0;
            if (vkEnumerateInstanceExtensionProperties(source, &extensionCount, nullptr) != VK_SUCCESS) continue;
            std::vector<VkExtensionProperties> available(extensionCount);
            if (vkEnumerateInstanceExtensionProperties(source, &extensionCount, available.data()) != VK_SUCCESS) continue;
            if (std::any_of(available.begin(), available.end(), [&](const auto& extension) {
                return std::strcmp(extension.extensionName, name) == 0;
            })) return true;
        }
        return false;
    };
    if (!supports(VK_EXT_DEBUG_UTILS_EXTENSION_NAME) || !supports(VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME)) {
        recordNativeDiagnostic(ANDROID_LOG_ERROR, "VulkanValidation", "Validation extensions unavailable");
        return;
    }
    extensions.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    extensions.push_back(VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME);
    messengerInfo_.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
    messengerInfo_.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT |
        VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
    messengerInfo_.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
        VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
    messengerInfo_.pfnUserCallback = onMessage;
    messengerInfo_.pUserData = this;
    messengerInfo_.pNext = info.pNext;
    features_.sType = VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT;
    features_.pNext = &messengerInfo_;
    features_.enabledValidationFeatureCount = static_cast<uint32_t>(checks_.size());
    features_.pEnabledValidationFeatures = checks_.data();
    info.pNext = &features_;
    info.enabledLayerCount = 1;
    info.ppEnabledLayerNames = &layer_;
    enabled_ = true;
    recordNativeDiagnostic(ANDROID_LOG_INFO, "VulkanValidation", "Requested core=1 synchronization=1 gpu_assisted=0 layer_api=%u implementation=%u",
        layer->specVersion, layer->implementationVersion);
#else
    (void)info;
    (void)extensions;
#endif
}

void VulkanDiagnostics::attach(VkInstance instance)
{
    if (!enabled_) return;
    const auto create = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
        vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT"));
    messengerInfo_.pNext = nullptr;
    const VkResult result = create ? create(instance, &messengerInfo_, nullptr, &messenger_) : VK_ERROR_EXTENSION_NOT_PRESENT;
    recordNativeDiagnostic(result == VK_SUCCESS ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, "VulkanValidation",
        "active=%d core=1 synchronization=1 direct_recorder=1 gpu_assisted_requested=0 result=%d", result == VK_SUCCESS, result);
    if (result == VK_SUCCESS) {
        const auto submit = reinterpret_cast<PFN_vkSubmitDebugUtilsMessageEXT>(
            vkGetInstanceProcAddr(instance, "vkSubmitDebugUtilsMessageEXT"));
        if (submit) {
            VkDebugUtilsMessengerCallbackDataEXT data{};
            data.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CALLBACK_DATA_EXT;
            data.pMessageIdName = "UnspektraValidationCaptureCheck";
            data.pMessage = "Validation callback capture check passed; this is a diagnostic marker.";
            submit(instance, VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT,
                VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT, &data);
        }
    }
}

void VulkanDiagnostics::detach(VkInstance instance)
{
    if (messenger_ == VK_NULL_HANDLE) return;
    const auto destroy = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
        vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT"));
    if (destroy) destroy(instance, messenger_, nullptr);
    messenger_ = VK_NULL_HANDLE;
}

VKAPI_ATTR VkBool32 VKAPI_CALL VulkanDiagnostics::onMessage(
    VkDebugUtilsMessageSeverityFlagBitsEXT severity, VkDebugUtilsMessageTypeFlagsEXT types,
    const VkDebugUtilsMessengerCallbackDataEXT* data, void* user)
{
    if (!data || !user) return VK_FALSE;
    auto& self = *static_cast<VulkanDiagnostics*>(user);
    const char* id = data->pMessageIdName ? data->pMessageIdName : "unnamed";
    uint64_t count;
    {
        std::lock_guard<std::mutex> lock(self.messagesMutex_);
        count = ++self.messageCounts_[id];
    }
    // Retain initial examples and periodic counts without flooding the rolling log.
    if (count > 4 && (count & (count - 1)) != 0) return VK_FALSE;
    const bool captureCheck = std::strcmp(id, "UnspektraValidationCaptureCheck") == 0;
    const int priority = captureCheck ? ANDROID_LOG_INFO :
        (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT ? ANDROID_LOG_ERROR :
            (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT ? ANDROID_LOG_WARN : ANDROID_LOG_INFO));
    const char* message = data->pMessage ? data->pMessage : "";
    const size_t length = std::strlen(message);
    for (size_t offset = 0; offset < std::max(size_t{1}, length); offset += 3000) {
        recordNativeDiagnostic(priority, "VulkanValidation", "id=%s count=%llu types=%u part=%zu %.*s",
            id, static_cast<unsigned long long>(count), types, offset / 3000 + 1,
            static_cast<int>(std::min(size_t{3000}, length - offset)), message + offset);
    }
    return VK_FALSE;
}
