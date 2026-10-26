/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef EMUCOREH_SATURN_STATE_VALIDATION_H
#define EMUCOREH_SATURN_STATE_VALIDATION_H
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Structural/version preflight only; subsystem loaders validate their payloads. */
int saturn_state_has_supported_chunks(const void *buffer, size_t size);
#ifdef __cplusplus
}
#endif
#endif
