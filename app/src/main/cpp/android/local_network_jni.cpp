#include <android/multinetwork.h>
#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_me_danielstiner_dumble_mumble_net_LocalNetworkBlockNative_isBlocked(JNIEnv*, jobject, jint fd) {
    if (__builtin_available(android 37, *)) {
        return android_getnetworkblockedreason(fd) == ANDROID_NETWORK_BLOCKED_REASON_LNP;
    }
    return false;
}
