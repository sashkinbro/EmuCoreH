package com.sbro.emucoreh.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sbro.emucoreh.R
import com.sbro.emucoreh.data.AppPreferences

@Composable
internal fun gyroStickTargetOptions(): List<Pair<Int, String>> = listOf(
    AppPreferences.GYRO_STICK_RIGHT to stringResource(R.string.settings_gyro_stick_right),
    AppPreferences.GYRO_STICK_LEFT to stringResource(R.string.settings_gyro_stick_left)
)

@Composable
internal fun lightGunAimSourceOptions(): List<Pair<Int, String>> = listOf(
    AppPreferences.LIGHT_GUN_AIM_GYRO to stringResource(R.string.settings_light_gun_aim_gyro),
    AppPreferences.LIGHT_GUN_AIM_LEFT_STICK to stringResource(R.string.settings_gyro_stick_left),
    AppPreferences.LIGHT_GUN_AIM_RIGHT_STICK to stringResource(R.string.settings_gyro_stick_right),
    AppPreferences.LIGHT_GUN_AIM_TOUCH to stringResource(R.string.settings_light_gun_aim_touch)
)

@Composable
internal fun arcadeDeviceOptions(): List<Pair<Int, String>> = listOf(
    AppPreferences.ARCADE_DEVICE_AUTO to stringResource(R.string.settings_arcade_device_auto),
    AppPreferences.ARCADE_DEVICE_CONTROLLER to stringResource(R.string.settings_arcade_device_gamepad),
    AppPreferences.ARCADE_DEVICE_LIGHT_GUN to stringResource(R.string.settings_arcade_device_light_gun)
)
