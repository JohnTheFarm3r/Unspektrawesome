#pragma once

#include <jni.h>
#include <cstddef>

template <std::size_t Count>
bool registerNativeMethods(JNIEnv* env, const char* className, const JNINativeMethod (&methods)[Count]) {
    const jclass type = env->FindClass(className);
    if (type == nullptr) return false;
    const bool registered = env->RegisterNatives(type, methods, static_cast<jint>(Count)) == JNI_OK;
    env->DeleteLocalRef(type);
    return registered;
}

bool registerVulkanRenderer(JNIEnv* environment);
bool registerVulkanBridge(JNIEnv* environment);
bool registerRecordingRawBuffer(JNIEnv* environment);
bool registerSpektraFilmRenderer(JNIEnv* environment);
