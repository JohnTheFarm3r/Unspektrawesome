#include "NativeBindings.h"
#define VK_USE_PLATFORM_ANDROID_KHR 1

#include <jni.h>

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "preview_bilinear_mhc_compute_spv.h"
#include "amaze_normalize_buffer_compute_spv.h"
#include "amaze_stage0_buffer_compute_spv.h"
#include "amaze_stage1_buffer_compute_spv.h"
#include "amaze_stage2_buffer_compute_spv.h"
#include "amaze_stage3_buffer_compute_spv.h"
#include "amaze_stage4_buffer_compute_spv.h"
#include "amaze_stage5_buffer_compute_spv.h"
#include "amaze_stage6_buffer_compute_spv.h"
#include "amaze_stage7_buffer_compute_spv.h"
#include "NativeDiagnostics.h"

namespace {

constexpr const char* kLogTag = "UnspektrawesomeVk";

std::mutex gDiagnosticsMutex;
std::string gDiagnostics = "Vulkan probe has not run";

std::string versionString(uint32_t version)
{
    std::ostringstream output;
    output << VK_VERSION_MAJOR(version) << '.'
           << VK_VERSION_MINOR(version) << '.'
           << VK_VERSION_PATCH(version);
    return output.str();
}

bool hasExtension(
    const std::vector<VkExtensionProperties>& extensions,
    const char* requiredName)
{
    return std::any_of(
        extensions.begin(), extensions.end(),
        [requiredName](const VkExtensionProperties& extension) {
            return std::string(extension.extensionName) == requiredName;
        });
}

uint32_t firstMemoryType(uint32_t memoryTypeBits)
{
    for (uint32_t index = 0; index < 32; ++index) {
        if ((memoryTypeBits & (1u << index)) != 0)
            return index;
    }
    return UINT32_MAX;
}

std::string probeVulkan(JNIEnv* environment, jobject hardwareBufferObject)
{
    std::ostringstream diagnostics;

    uint32_t loaderVersion = VK_API_VERSION_1_0;
    PFN_vkEnumerateInstanceVersion enumerateInstanceVersion =
        reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    if (enumerateInstanceVersion != nullptr) {
        VkResult result = enumerateInstanceVersion(&loaderVersion);
        if (result != VK_SUCCESS) {
            diagnostics << "Unsupported: vkEnumerateInstanceVersion failed (" << result << ')';
            return diagnostics.str();
        }
    }

    diagnostics << "Loader " << versionString(loaderVersion);
    diagnostics << "; shaders preview=" << kPreviewMhcSpirv.size()
                << " words stagedAmaze="
                << kAmazeNormalizeBufferSpirv.size() +
                   kAmazeCandidatesBufferSpirv.size() +
                   kAmazeDifferencesBufferSpirv.size() +
                   kAmazeWeightsBufferSpirv.size() +
                   kAmazeGreenBufferSpirv.size() +
                   kAmazeRefineBufferSpirv.size() +
                   kAmazeDiagonalBufferSpirv.size() +
                   kAmazeChromaBufferSpirv.size() +
                   kAmazeOutputBufferSpirv.size()
                << " words";
    if (loaderVersion < VK_API_VERSION_1_1) {
        diagnostics << "; unsupported: Vulkan 1.1 is required";
        return diagnostics.str();
    }

    VkApplicationInfo applicationInfo{};
    applicationInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    applicationInfo.pApplicationName = "Unspektrawesome";
    applicationInfo.applicationVersion = VK_MAKE_API_VERSION(0, 1, 0, 0);
    applicationInfo.pEngineName = "Spektra";
    applicationInfo.engineVersion = VK_MAKE_API_VERSION(0, 1, 0, 0);
    applicationInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &applicationInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vkCreateInstance(&instanceInfo, nullptr, &instance);
    if (result != VK_SUCCESS) {
        diagnostics << "; unsupported: vkCreateInstance failed (" << result << ')';
        return diagnostics.str();
    }

    uint32_t physicalDeviceCount = 0;
    result = vkEnumeratePhysicalDevices(instance, &physicalDeviceCount, nullptr);
    if (result != VK_SUCCESS || physicalDeviceCount == 0) {
        diagnostics << "; unsupported: no Vulkan physical device";
        vkDestroyInstance(instance, nullptr);
        return diagnostics.str();
    }

    std::vector<VkPhysicalDevice> physicalDevices(physicalDeviceCount);
    result = vkEnumeratePhysicalDevices(
        instance, &physicalDeviceCount, physicalDevices.data());
    if (result != VK_SUCCESS) {
        diagnostics << "; unsupported: physical-device enumeration failed (" << result << ')';
        vkDestroyInstance(instance, nullptr);
        return diagnostics.str();
    }

    VkPhysicalDevice selectedDevice = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties selectedProperties{};
    std::vector<VkExtensionProperties> selectedExtensions;
    uint32_t selectedQueueFamily = UINT32_MAX;

    for (VkPhysicalDevice physicalDevice : physicalDevices) {
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(physicalDevice, &properties);
        if (properties.apiVersion < VK_API_VERSION_1_1)
            continue;

        uint32_t extensionCount = 0;
        if (vkEnumerateDeviceExtensionProperties(
                physicalDevice, nullptr, &extensionCount, nullptr) != VK_SUCCESS) {
            continue;
        }
        std::vector<VkExtensionProperties> extensions(extensionCount);
        if (vkEnumerateDeviceExtensionProperties(
                physicalDevice, nullptr, &extensionCount, extensions.data()) != VK_SUCCESS) {
            continue;
        }
        if (!hasExtension(
                extensions,
                VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)) {
            continue;
        }

        uint32_t queueFamilyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(
            physicalDevice, &queueFamilyCount, nullptr);
        std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(
            physicalDevice, &queueFamilyCount, queueFamilies.data());
        auto queueFamily = std::find_if(
            queueFamilies.begin(), queueFamilies.end(),
            [](const VkQueueFamilyProperties& properties) {
                return properties.queueCount > 0 &&
                       (properties.queueFlags & VK_QUEUE_COMPUTE_BIT) != 0;
            });
        if (queueFamily == queueFamilies.end())
            continue;

        selectedDevice = physicalDevice;
        selectedProperties = properties;
        selectedExtensions = std::move(extensions);
        selectedQueueFamily = static_cast<uint32_t>(
            std::distance(queueFamilies.begin(), queueFamily));
        break;
    }

    if (selectedDevice == VK_NULL_HANDLE) {
        diagnostics << "; unsupported: no Vulkan 1.1 device exposes "
                    << VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME;
        vkDestroyInstance(instance, nullptr);
        return diagnostics.str();
    }

    diagnostics << "; device " << selectedProperties.deviceName
                << " API " << versionString(selectedProperties.apiVersion)
                << "; AHardwareBuffer extension available";

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = selectedQueueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &queuePriority;

    std::vector<const char*> enabledExtensions = {
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME
    };
    if (hasExtension(selectedExtensions, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME))
        enabledExtensions.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);

    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = static_cast<uint32_t>(enabledExtensions.size());
    deviceInfo.ppEnabledExtensionNames = enabledExtensions.data();

    VkDevice device = VK_NULL_HANDLE;
    result = vkCreateDevice(selectedDevice, &deviceInfo, nullptr, &device);
    if (result != VK_SUCCESS) {
        diagnostics << "; unsupported: vkCreateDevice failed (" << result << ')';
        vkDestroyInstance(instance, nullptr);
        return diagnostics.str();
    }

    if (hardwareBufferObject == nullptr) {
        diagnostics << "; import not tested: HardwareBuffer was null";
        vkDestroyDevice(device, nullptr);
        vkDestroyInstance(instance, nullptr);
        return diagnostics.str();
    }

    AHardwareBuffer* hardwareBuffer =
        AHardwareBuffer_fromHardwareBuffer(environment, hardwareBufferObject);
    if (hardwareBuffer == nullptr) {
        diagnostics << "; import failed: invalid HardwareBuffer object";
        vkDestroyDevice(device, nullptr);
        vkDestroyInstance(instance, nullptr);
        return diagnostics.str();
    }

    AHardwareBuffer_Desc bufferDescription{};
    AHardwareBuffer_describe(hardwareBuffer, &bufferDescription);
    diagnostics << "; buffer " << bufferDescription.width << 'x'
                << bufferDescription.height << " layers=" << bufferDescription.layers
                << " format=" << bufferDescription.format
                << " usage=0x" << std::hex << bufferDescription.usage << std::dec;

    auto getBufferProperties =
        reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (getBufferProperties == nullptr) {
        diagnostics << "; import failed: entry point is unavailable";
        vkDestroyDevice(device, nullptr);
        vkDestroyInstance(instance, nullptr);
        return diagnostics.str();
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProperties{};
    formatProperties.sType =
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID bufferProperties{};
    bufferProperties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    bufferProperties.pNext = &formatProperties;
    result = getBufferProperties(device, hardwareBuffer, &bufferProperties);
    if (result != VK_SUCCESS) {
        diagnostics << "; import failed: property query returned " << result;
        vkDestroyDevice(device, nullptr);
        vkDestroyInstance(instance, nullptr);
        return diagnostics.str();
    }

    diagnostics << "; vkFormat=" << formatProperties.format
                << "; externalFormat=" << formatProperties.externalFormat
                << "; formatFeatures=0x" << std::hex << formatProperties.formatFeatures << std::dec;
    if (bufferDescription.format != AHARDWAREBUFFER_FORMAT_BLOB &&
        formatProperties.format == VK_FORMAT_UNDEFINED && formatProperties.externalFormat == 0) {
        diagnostics << "; import unavailable: the buffer exposes no Vulkan image format";
        vkDestroyDevice(device, nullptr);
        vkDestroyInstance(instance, nullptr);
        return diagnostics.str();
    }

    uint32_t memoryTypeIndex = firstMemoryType(bufferProperties.memoryTypeBits);
    if (memoryTypeIndex == UINT32_MAX || bufferProperties.allocationSize == 0) {
        diagnostics << "; import failed: no compatible Vulkan memory type";
        vkDestroyDevice(device, nullptr);
        vkDestroyInstance(instance, nullptr);
        return diagnostics.str();
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = hardwareBuffer;

    VkMemoryAllocateInfo allocationInfo{};
    allocationInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocationInfo.pNext = &importInfo;
    allocationInfo.allocationSize = bufferProperties.allocationSize;
    allocationInfo.memoryTypeIndex = memoryTypeIndex;

    VkDeviceMemory importedMemory = VK_NULL_HANDLE;
    result = vkAllocateMemory(device, &allocationInfo, nullptr, &importedMemory);
    if (result == VK_SUCCESS) {
        diagnostics << "; import succeeded without CPU mapping"
                    << "; allocation=" << bufferProperties.allocationSize
                    << " bytes; memoryType=" << memoryTypeIndex;
        vkFreeMemory(device, importedMemory, nullptr);
    } else {
        diagnostics << "; import failed: vkAllocateMemory returned " << result;
    }

    vkDestroyDevice(device, nullptr);
    vkDestroyInstance(instance, nullptr);
    return diagnostics.str();
}

jstring toJavaString(JNIEnv* environment, const std::string& value)
{
    return environment->NewStringUTF(value.c_str());
}

} // namespace

extern "C" jstring JNICALL
Java_com_unspektrawesome_vulkan_VulkanBridge_nativeProbeVulkan(
    JNIEnv* environment,
    jclass,
    jobject hardwareBuffer)
{
    std::string diagnostics = probeVulkan(environment, hardwareBuffer);
    {
        std::lock_guard<std::mutex> lock(gDiagnosticsMutex);
        gDiagnostics = diagnostics;
    }
    recordNativeDiagnostic(ANDROID_LOG_INFO, kLogTag, "%s", diagnostics.c_str());
    return toJavaString(environment, diagnostics);
}

extern "C" jstring JNICALL
Java_com_unspektrawesome_vulkan_VulkanBridge_nativeGetDiagnosticString(
    JNIEnv* environment,
    jclass)
{
    std::lock_guard<std::mutex> lock(gDiagnosticsMutex);
    return toJavaString(environment, gDiagnostics);
}

bool registerVulkanBridge(JNIEnv* environment) {
    const JNINativeMethod methods0[] = {
        {"nativeProbeVulkan", "(Landroid/hardware/HardwareBuffer;)Ljava/lang/String;", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanBridge_nativeProbeVulkan)},
        {"nativeGetDiagnosticString", "()Ljava/lang/String;", reinterpret_cast<void*>(Java_com_unspektrawesome_vulkan_VulkanBridge_nativeGetDiagnosticString)},
    };
    if (!registerNativeMethods(environment, "com/unspektrawesome/vulkan/VulkanBridge", methods0)) return false;
    return true;
}
