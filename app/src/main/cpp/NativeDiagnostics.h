#pragma once

#include <jni.h>

bool initializeNativeDiagnostics(JavaVM* vm, JNIEnv* environment);

void recordNativeDiagnostic(int priority, const char* component, const char* format, ...) noexcept
    __attribute__((format(printf, 3, 4)));
