// GR port, native half.
//
// The GR mode reuses the professional (master) capture pipeline. GR frames are recognised by the
// gr_effect_size APS setting (63) and get:
//   * BasicTone: the GR LMT/SC/CWCM tree (staged by service.sh) instead of the host ODM tree;
//   * no artistic vignette from the V1 vignette parameters;
//   * the Meishe V1 filter node (clarity, vignette, grain), run with the GR capture mode.
// Offsets are for PMA110 Camera 7.006.77 (libAlgoInterface.so / libBasicTonePhoto*.so).
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <unordered_map>

#include "native_hooks.h"

namespace {

constexpr char kTag[] = "RicohGrPort";
constexpr char kGrLmtPath[] = "/data/user/0/com.oplus.camera/files/ricoh_gr/lmt";
constexpr char kHostLmtPath[] = "/odm/etc/camera/basictone/lmt";
constexpr uint32_t kGrCaptureMode = 37;

// APS setting keys use libc++'s 24-byte short-string layout on this firmware.
struct alignas(8) SettingKey {
    uint8_t length;
    char text[23];
};
const SettingKey kGrSizeKey = {28, "gr_effect_size"};

int (*getSettingInt)(void *settings, const void *key, int fallback);
void (*originalGetLmtParams)(void *, void *, int);
void (*originalGetVigParams)(void *, void *, void *);
int (*originalFilterProcessV2)(void *, int);
int (*filterProcessV1)(void *, int);
int (*filterRegisterV1)(void *, int);
int (*filterUnregisterV1)(void *, int);
uintptr_t (*originalProcessCore)(void *, void *, void *, void *, void *);
uintptr_t (*originalLoadLut)(void *, int);

// getLmtParamsV1 runs per frame before BasicTone; remember which LUT tree belongs to each parameter
// block so processCore/loadLut (which no longer see the frame) can pick it.
enum Profile { kUnknown = -1, kHost = 0, kGr = 1 };
struct FrameProfile {
    Profile profile = kHost;
};
std::mutex profileMutex;
std::unordered_map<void *, FrameProfile> frameProfiles;
thread_local int activeProfile = kUnknown;

bool isGrFrame(void *frame) {
    void *settings = *reinterpret_cast<void **>(static_cast<char *>(frame) + 0x8110);
    return getSettingInt(settings, &kGrSizeKey, 0) == 63;
}

void getLmtParams(void *parameters, void *frame, int stage) {
    bool gr = isGrFrame(frame);
    originalGetLmtParams(parameters, frame, stage);
    FrameProfile profile;
    profile.profile = gr ? kGr : kHost;
    std::lock_guard<std::mutex> lock(profileMutex);
    frameProfiles[parameters] = profile;
}

void getVigParams(void *parameters, void *frame, void *planes) {
    originalGetVigParams(parameters, frame, planes);
    if (isGrFrame(frame)) static_cast<uint8_t *>(parameters)[0x1a] = 0;
}

int withGrCaptureMode(int (*operation)(void *, int), void *frame, int stage) {
    auto *mode = reinterpret_cast<uint32_t *>(static_cast<char *>(frame) + 0x18);
    uint32_t previous = *mode;
    *mode = kGrCaptureMode;
    int result = operation(frame, stage);
    *mode = previous;
    return result;
}

int filterProcessV2(void *frame, int stage) {
    if (!isGrFrame(frame)) return originalFilterProcessV2(frame, stage);
    // Registration happens before gr_effect_size is attached, so the V1 node's whole lifecycle
    // is scoped to this call, where the frame is known to be GR. The per-image object is
    // consumed after capture; session and request modes are untouched.
    int registration = withGrCaptureMode(filterRegisterV1, frame, stage);
    if (registration != 0) return registration;
    int result = withGrCaptureMode(filterProcessV1, frame, stage);
    withGrCaptureMode(filterUnregisterV1, frame, stage);
    return result;
}

uintptr_t processCore(void *object, void *input, void *output, void *parameters, void *metadata) {
    int previous = activeProfile;
    activeProfile = kUnknown;
    FrameProfile profile;
    bool known = false;
    {
        std::lock_guard<std::mutex> lock(profileMutex);
        auto found = frameProfiles.find(parameters);
        if (found != frameProfiles.end()) {
            profile = found->second;
            known = true;
            frameProfiles.erase(found);
        }
    }
    if (known) {
        activeProfile = profile.profile;
    }
    uintptr_t result = originalProcessCore(object, input, output, parameters, metadata);
    activeProfile = previous;
    return result;
}

uintptr_t loadLut(void *object, int mode) {
    // Intermediate ODT passes carry no profile; they keep the object's current LUTs.
    if (activeProfile != kUnknown) {
        char *lmtPath = static_cast<char *>(object) + 0x008;  // 256-byte path buffer
        const char *desired = activeProfile == kGr ? kGrLmtPath : kHostLmtPath;
        if (std::strlen(desired) < 240 && std::strcmp(lmtPath, desired) != 0) {
            std::strcpy(lmtPath, desired);
            // The path is not part of the vendor cache key, so a profile switch forces a reload.
            *reinterpret_cast<int *>(static_cast<char *>(object) + 0xeb0) = -1;
            __android_log_print(ANDROID_LOG_INFO, kTag, "BasicTone profile=%s path=%s",
                                activeProfile == kGr ? "GR" : "host", desired);
        }
    }
    return originalLoadLut(object, mode);
}

void hookAlgoInterface(HookFunction hook, void *handle) {
    if (originalGetLmtParams) return;
    const char *lmtSymbol = "_ZN7android14getLmtParamsV1ER12ProcessParamPNS_15AlgoProcessDataEi";
    Dl_info info{};
    void *lmtAddress = dlsym(handle, lmtSymbol);
    if (!lmtAddress || !dladdr(lmtAddress, &info)) return;
    // Same setting accessor that getLmtParamsV1 calls at 0x2035b78.
    getSettingInt = reinterpret_cast<decltype(getSettingInt)>(
        static_cast<char *>(info.dli_fbase) + 0x150cdd0);
    hookExport(hook, handle, lmtSymbol, reinterpret_cast<void *>(getLmtParams),
               reinterpret_cast<void **>(&originalGetLmtParams), kTag);
    filterProcessV1 = reinterpret_cast<decltype(filterProcessV1)>(
        dlsym(handle, "_ZN7android15doFilterProcessEPNS_15AlgoProcessDataEi"));
    filterRegisterV1 = reinterpret_cast<decltype(filterRegisterV1)>(
        dlsym(handle, "_ZN7android16doFilterRegisterEPNS_15AlgoProcessDataEi"));
    filterUnregisterV1 = reinterpret_cast<decltype(filterUnregisterV1)>(
        dlsym(handle, "_ZN7android18doFilterUnregisterEPNS_15AlgoProcessDataEi"));
    hookExport(hook, handle, "_ZN7android17doFilterProcessV2EPNS_15AlgoProcessDataEi",
               reinterpret_cast<void *>(filterProcessV2),
               reinterpret_cast<void **>(&originalFilterProcessV2), kTag);
    hookExport(hook, handle,
               "_ZN7android14getVigParamsV1ER12ProcessParamPNS_15AlgoProcessDataERK15ApsBufferPlanes",
               reinterpret_cast<void *>(getVigParams),
               reinterpret_cast<void **>(&originalGetVigParams), kTag);
}

void hookBasicTone(HookFunction hook, void *handle) {
    if (originalProcessCore && originalLoadLut) return;
    // The X10 router exports getAlgoAPI only; its X9 backend keeps these BasicTone_OGL entries.
    const char *coreSymbol = "_ZN13BasicTone_OGL11processCoreEP5ImageS1_PvS2_";
    if (!dlsym(handle, coreSymbol)) return;
    hookExport(hook, handle, coreSymbol, reinterpret_cast<void *>(processCore),
               reinterpret_cast<void **>(&originalProcessCore), kTag);
    hookExport(hook, handle, "_ZN13BasicTone_OGL7loadLutE7CamMode",
               reinterpret_cast<void *>(loadLut), reinterpret_cast<void **>(&originalLoadLut), kTag);
}

}  // namespace

void onLibraryLoadedForGr(HookFunction hook, const char *name, void *handle) {
    if (std::strstr(name, "libAlgoInterface.so")) {
        hookAlgoInterface(hook, handle);
    } else if (std::strstr(name, "libBasicTonePhotoX9.so") || std::strstr(name, "libBasicTonePhoto.so")) {
        hookBasicTone(hook, handle);
    }
}
