/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef EMUCOREH_SATURN_STATE_VALIDATION_H
#define EMUCOREH_SATURN_STATE_VALIDATION_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Structural/version preflight only; subsystem loaders validate their payloads. */
int saturn_state_has_supported_chunks(const void *buffer, size_t size);
/* Wire mode: bit 0=synchronous execution, bit 1=new SCSP backend required. */
uint32_t saturn_state_current_audio_mode(void);
#ifdef __cplusplus
}
#endif
#endif
