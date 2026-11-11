#include "NativeBindings.h"
#include "NativeDiagnostics.h"
#include "NativeHost.h"

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*)
{
    JNIEnv* environment = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&environment), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    if (!authorizeNativeHost(environment)) return JNI_ERR;
    if (!registerVulkanRenderer(environment) || !registerVulkanBridge(environment) ||
        !registerRecordingRawBuffer(environment) || !registerSpektraFilmRenderer(environment)) return JNI_ERR;
    return initializeNativeDiagnostics(vm, environment) ? JNI_VERSION_1_6 : JNI_ERR;
}
