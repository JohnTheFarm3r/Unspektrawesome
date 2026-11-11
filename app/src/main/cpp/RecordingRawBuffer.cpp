#include "NativeBindings.h"
#include <jni.h>
#include <cstdlib>

extern "C" jobject JNICALL
Java_com_unspektrawesome_preview_RecordingRawBuffer_nativeAllocate(JNIEnv* env, jobject, jint bytes)
{
    if (bytes <= 0) return nullptr;
    void* pixels = std::malloc(static_cast<size_t>(bytes));
    if (!pixels) return nullptr;
    jobject buffer = env->NewDirectByteBuffer(pixels, bytes);
    if (!buffer) std::free(pixels);
    return buffer;
}

extern "C" void JNICALL
Java_com_unspektrawesome_preview_RecordingRawBuffer_nativeRelease(JNIEnv* env, jobject, jobject buffer)
{
    std::free(env->GetDirectBufferAddress(buffer));
}

bool registerRecordingRawBuffer(JNIEnv* environment) {
    const JNINativeMethod methods0[] = {
        {"nativeAllocate", "(I)Ljava/nio/ByteBuffer;", reinterpret_cast<void*>(Java_com_unspektrawesome_preview_RecordingRawBuffer_nativeAllocate)},
        {"nativeRelease", "(Ljava/nio/ByteBuffer;)V", reinterpret_cast<void*>(Java_com_unspektrawesome_preview_RecordingRawBuffer_nativeRelease)},
    };
    if (!registerNativeMethods(environment, "com/unspektrawesome/preview/RecordingRawBuffer", methods0)) return false;
    return true;
}
