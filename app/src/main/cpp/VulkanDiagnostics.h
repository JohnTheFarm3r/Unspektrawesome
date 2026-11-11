#pragma once

#include <vulkan/vulkan.h>
#include <array>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

void nameDiagnosticShader(VkDevice device, VkShaderModule module, const char* name);

class VulkanDiagnostics {
public:
    void configure(VkInstanceCreateInfo& info, std::vector<const char*>& extensions);
    void attach(VkInstance instance);
    void detach(VkInstance instance);

private:
    static VKAPI_ATTR VkBool32 VKAPI_CALL onMessage(
        VkDebugUtilsMessageSeverityFlagBitsEXT severity, VkDebugUtilsMessageTypeFlagsEXT types,
        const VkDebugUtilsMessengerCallbackDataEXT* data, void* user);
    VkDebugUtilsMessengerCreateInfoEXT messengerInfo_{};
#if defined(UNSPEKTRA_VULKAN_VALIDATION)
    const char* layer_ = "VK_LAYER_KHRONOS_validation";
    VkValidationFeaturesEXT features_{};
    std::array<VkValidationFeatureEnableEXT, 1> checks_{
        VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT};
#endif
    VkDebugUtilsMessengerEXT messenger_ = VK_NULL_HANDLE;
    bool enabled_ = false;
    std::mutex messagesMutex_;
    std::unordered_map<std::string, uint64_t> messageCounts_;
};
