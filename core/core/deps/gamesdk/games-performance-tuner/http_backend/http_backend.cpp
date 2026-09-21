#include "http_backend.h"

#define LOG_TAG "TuningFork.GE"
#include "Log.h"
#include "core/runnable.h"
#include "core/tuningfork_utils.h"
#include "http_request.h"
#include "proto/protobuf_util.h"
#include "ultimate_uploader.h"

namespace tuningfork {

constexpr Duration kRequestTimeout = std::chrono::seconds(10);

TuningFork_ErrorCode HttpBackend::Init(const Settings& settings) {
    if (settings.EndpointUri().empty()) {
        ALOGW("The base URI in Tuning Fork TuningFork_Settings is invalid");
        return TUNINGFORK_ERROR_BAD_PARAMETER;
    }
    if (settings.api_key.empty()) {
        ALOGW("The API key in Tuning Fork TuningFork_Settings is invalid");
        return TUNINGFORK_ERROR_BAD_PARAMETER;
    }

    HttpRequest request(settings.EndpointUri(), settings.api_key,
                        kRequestTimeout);

    persister_ = settings.c_settings.persistent_cache;

    // TODO(b/140367226): Initialize a Java JobScheduler if we can

    if (ultimate_uploader_.get() == nullptr) {
        ultimate_uploader_ =
            std::make_shared<UltimateUploader>(persister_, request);
        ultimate_uploader_->Start();
    }

    return TUNINGFORK_ERROR_OK;
}

HttpBackend::~HttpBackend() {}

// This persists the histograms and the ultimate uploader, above, uploads them.
TuningFork_ErrorCode HttpBackend::UploadTelemetry(const std::string& evt_ser) {
    ALOGV("HttpBackend::Process %s", evt_ser.c_str());

    // Save event to file
    TuningFork_CProtobufSerialization uploading_hists_ser;
    ToCProtobufSerialization(evt_ser, uploading_hists_ser);
    auto ret = persister_->set(HISTOGRAMS_UPLOADING, &uploading_hists_ser,
                               persister_->user_data);

    TuningFork_CProtobufSerialization_free(&uploading_hists_ser);

    return ret;
}

void HttpBackend::Stop() {
    if (ultimate_uploader_) ultimate_uploader_->Stop();
}

}  // namespace tuningfork
