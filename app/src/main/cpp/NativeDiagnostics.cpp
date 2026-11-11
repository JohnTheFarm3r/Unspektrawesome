#include "NativeDiagnostics.h"

#include <android/log.h>
#include <jni.h>
#include <cstdarg>
#include <cstdio>

namespace {
JavaVM* diagnosticVm = nullptr;
jclass recorderClass = nullptr;
jmethodID recorderMethod = nullptr;
}

bool initializeNativeDiagnostics(JavaVM* vm, JNIEnv* environment)
{
    jclass localClass = environment->FindClass("com/unspektrawesome/diagnostics/InternalLogRecorder");
    if (localClass == nullptr) return false;
    recorderMethod = environment->GetStaticMethodID(localClass, "recordNative", "(ILjava/lang/String;Ljava/lang/String;)V");
    if (recorderMethod != nullptr) recorderClass = static_cast<jclass>(environment->NewGlobalRef(localClass));
    environment->DeleteLocalRef(localClass);
    if (recorderClass == nullptr) return false;
    diagnosticVm = vm;
    return true;
}

void recordNativeDiagnostic(int priority, const char* component, const char* format, ...) noexcept
{
    char message[4096];
    va_list arguments;
    va_start(arguments, format);
    std::vsnprintf(message, sizeof(message), format, arguments);
    va_end(arguments);

    JNIEnv* environment = nullptr;
    bool attached = false;
    bool recorded = false;
    if (diagnosticVm != nullptr && recorderClass != nullptr && recorderMethod != nullptr) {
        const jint status = diagnosticVm->GetEnv(reinterpret_cast<void**>(&environment), JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            attached = diagnosticVm->AttachCurrentThread(&environment, nullptr) == JNI_OK;
            if (!attached) environment = nullptr;
        } else if (status != JNI_OK) {
            environment = nullptr;
        }
        if (environment != nullptr && !environment->ExceptionCheck()) {
            jstring tag = environment->NewStringUTF(component);
            jstring detail = tag != nullptr ? environment->NewStringUTF(message) : nullptr;
            if (detail != nullptr) {
                environment->CallStaticVoidMethod(recorderClass, recorderMethod, priority, tag, detail);
                recorded = !environment->ExceptionCheck();
            }
            if (environment->ExceptionCheck()) environment->ExceptionClear();
            if (detail != nullptr) environment->DeleteLocalRef(detail);
            if (tag != nullptr) environment->DeleteLocalRef(tag);
        }
        if (attached) diagnosticVm->DetachCurrentThread();
    }
    if (!recorded) __android_log_write(priority, component, message);
}
