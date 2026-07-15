#include <dlfcn.h>
#include <jni.h>

namespace {
using RequestLogMessages = int (*)(void *, const char *);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_jellyfin_mobile_player_mpv_MpvLogControl_disableNativeLogMessages(
    JNIEnv *,
    jobject) {
    void *player = dlopen("libplayer.so", RTLD_NOW);
    void *mpv = dlopen("libmpv.so", RTLD_NOW);
    if (player == nullptr || mpv == nullptr) {
        if (player != nullptr) dlclose(player);
        if (mpv != nullptr) dlclose(mpv);
        return JNI_FALSE;
    }

    auto **handle = reinterpret_cast<void **>(dlsym(player, "g_mpv"));
    auto requestLogMessages = reinterpret_cast<RequestLogMessages>(
        dlsym(mpv, "mpv_request_log_messages"));

    const bool disabled = handle != nullptr && *handle != nullptr &&
        requestLogMessages != nullptr && requestLogMessages(*handle, "no") == 0;

    dlclose(mpv);
    dlclose(player);
    return disabled ? JNI_TRUE : JNI_FALSE;
}
