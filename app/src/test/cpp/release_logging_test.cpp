#include "release_logging.h"
#include "log/Log.h"

static int android_messages, core_messages, ordinary_args, failure_args, metadata_args;
int __android_log_print(int, const char*, const char*, ...) { return ++android_messages; }
void GenericLog(LogTypes::LOG_LEVELS, LogTypes::LOG_TYPE, const char*, int, const char*, ...) {
    ++core_messages;
}

int main() {
    EMUCOREH_LOG_INFO("test", "%d", ++ordinary_args);
    EMUCOREH_LOG_WARN("test", "%d", ++ordinary_args);
    NOTICE_LOG(BOOT, "%d", ++ordinary_args);
    WARN_LOG(BOOT, "%d", ++ordinary_args);
    INFO_LOG(BOOT, "%d", ++ordinary_args);
    DEBUG_LOG(BOOT, "%d", ++ordinary_args);
#ifdef NDEBUG
    if (ordinary_args != 0 || android_messages != 0 || core_messages != 0) return 1;
#else
    if (ordinary_args != 6 || android_messages != 2 || core_messages != 4) return 2;
#endif
    android_messages = core_messages = 0;
    for (int i = 0; i < 3; ++i) {
        EMUCOREH_LOG_ERROR("failure", "%d", ++failure_args);
        ERROR_LOG(BOOT, "%d", ++failure_args);
    }
#ifdef NDEBUG
    if (failure_args != 2 || android_messages != 1 || core_messages != 1) return 3;
#else
    if (failure_args != 6 || android_messages != 3 || core_messages != 3) return 4;
#endif
    core_messages = 0;
    GAME_ID_LOG(BOOT, "Game ID is [%d]", ++metadata_args);
    GAME_ID_LOG(NAOMI, "NAOMI GAME ID [%d]", ++metadata_args);
    if (metadata_args != 2 || core_messages != 2) return 5;
    return 0;
}
