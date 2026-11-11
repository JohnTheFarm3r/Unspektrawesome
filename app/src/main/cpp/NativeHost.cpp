#include "NativeHost.h"
#include "NativeIdentity.h"

#include <cstring>
#include <unistd.h>

namespace {
bool matchesPackage(JNIEnv* env, jstring value) {
    if (value == nullptr) return false;
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (text == nullptr) return false;
    const bool matches = std::strcmp(text, native_identity::packageName) == 0;
    env->ReleaseStringUTFChars(value, text);
    return matches;
}

bool checkHost(JNIEnv* env) {
    const jclass hostClass = env->FindClass("com/unspektrawesome/NativeHost");
    if (hostClass == nullptr) return false;
    const jmethodID applicationMethod = env->GetStaticMethodID(hostClass, "application", "()Landroid/app/Application;");
    if (applicationMethod == nullptr) return false;
    const jobject application = env->CallStaticObjectMethod(hostClass, applicationMethod);
    if (env->ExceptionCheck() || application == nullptr) return false;

    const jclass contextClass = env->FindClass("android/content/Context");
    if (contextClass == nullptr) return false;
    const jmethodID packageMethod = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    if (packageMethod == nullptr) return false;
    const auto package = static_cast<jstring>(env->CallObjectMethod(application, packageMethod));
    if (env->ExceptionCheck() || !matchesPackage(env, package)) return false;

    const jmethodID infoMethod = env->GetMethodID(contextClass, "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");
    if (infoMethod == nullptr) return false;
    const jobject info = env->CallObjectMethod(application, infoMethod);
    if (env->ExceptionCheck() || info == nullptr) return false;
    const jclass infoClass = env->FindClass("android/content/pm/ApplicationInfo");
    if (infoClass == nullptr) return false;
    const jfieldID uidField = env->GetFieldID(infoClass, "uid", "I");
    if (uidField == nullptr) return false;
    const jint uid = static_cast<jint>(getuid());
    if (env->GetIntField(info, uidField) != uid) return false;
    const jfieldID flagsField = env->GetFieldID(infoClass, "flags", "I");
    if (flagsField == nullptr) return false;
    constexpr jint debuggableFlag = 2;
    const bool debuggable = (env->GetIntField(info, flagsField) & debuggableFlag) != 0;
    if (debuggable != native_identity::debuggable) return false;

    const jmethodID managerMethod = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    if (managerMethod == nullptr) return false;
    const jobject manager = env->CallObjectMethod(application, managerMethod);
    if (env->ExceptionCheck() || manager == nullptr) return false;
    const jclass managerClass = env->FindClass("android/content/pm/PackageManager");
    if (managerClass == nullptr) return false;
    const jclass actualManagerClass = env->GetObjectClass(manager);
    const jclass classClass = env->FindClass("java/lang/Class");
    if (actualManagerClass == nullptr || classClass == nullptr) return false;
    const jmethodID loaderMethod = env->GetMethodID(classClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    if (loaderMethod == nullptr) return false;
    const jobject platformLoader = env->CallObjectMethod(managerClass, loaderMethod);
    if (env->ExceptionCheck()) return false;
    const jobject managerLoader = env->CallObjectMethod(actualManagerClass, loaderMethod);
    if (env->ExceptionCheck() || !env->IsSameObject(platformLoader, managerLoader)) return false;

    const jmethodID packagesMethod = env->GetMethodID(managerClass, "getPackagesForUid", "(I)[Ljava/lang/String;");
    if (packagesMethod == nullptr) return false;
    const auto packages = static_cast<jobjectArray>(env->CallObjectMethod(manager, packagesMethod, uid));
    if (env->ExceptionCheck() || packages == nullptr || env->GetArrayLength(packages) != 1) return false;
    const auto ownedPackage = static_cast<jstring>(env->GetObjectArrayElement(packages, 0));
    if (env->ExceptionCheck() || !matchesPackage(env, ownedPackage)) return false;

    // Check this process's UID, not another installed copy of the application.
    const jmethodID certificateMethod = env->GetMethodID(managerClass, "hasSigningCertificate", "(I[BI)Z");
    if (certificateMethod == nullptr) return false;
    const jbyteArray certificate = env->NewByteArray(sizeof(native_identity::certificate));
    if (certificate == nullptr) return false;
    env->SetByteArrayRegion(certificate, 0, sizeof(native_identity::certificate),
        reinterpret_cast<const jbyte*>(native_identity::certificate));
    if (env->ExceptionCheck()) return false;
    constexpr jint certificateInputSha256 = 1;
    const jboolean trusted = env->CallBooleanMethod(manager, certificateMethod, uid, certificate, certificateInputSha256);
    return !env->ExceptionCheck() && trusted == JNI_TRUE;
}
}

bool authorizeNativeHost(JNIEnv* environment) {
    if (environment->PushLocalFrame(32) != JNI_OK) return false;
    const bool authorized = checkHost(environment);
    environment->PopLocalFrame(nullptr);
    return authorized;
}
