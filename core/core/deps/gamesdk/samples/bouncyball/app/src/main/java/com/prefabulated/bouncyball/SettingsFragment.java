/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.prefabulated.bouncyball;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import androidx.annotation.RequiresApi;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;

public class SettingsFragment
    extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
  private ListPreference mSwapIntervalPreference;
  private String mSwapIntervalKey;
  @TargetApi(Build.VERSION_CODES.M) private Display.Mode mCurrentMode;

  @TargetApi(Build.VERSION_CODES.M)
  private boolean modeMatchesCurrentResolution(Display.Mode mode) {
    return mode.getPhysicalHeight() == mCurrentMode.getPhysicalHeight()
        && mode.getPhysicalWidth() == mCurrentMode.getPhysicalWidth();
  }

  @Override
  public void onCreatePreferences(Bundle bundle, String s) {
    @SuppressWarnings("deprecation")
    Display display = getActivity().getWindowManager().getDefaultDisplay();

    mSwapIntervalKey = getResources().getString(R.string.swap_interval_key);
    addPreferencesFromResource(R.xml.preferences);

    PreferenceScreen preferenceScreen = getPreferenceScreen();
    for (int i = 0; i < preferenceScreen.getPreferenceCount(); ++i) {
      Preference preference = preferenceScreen.getPreference(i);
      final String key = preference.getKey();
      if (key != null && key.equals(mSwapIntervalKey)) {
        mSwapIntervalPreference = (ListPreference) preference;
      }
    }

    // fill the swap interval list based on the screen refresh rate(s)
    TreeSet<Float> fpsSet = new TreeSet<Float>();
    if (Build.VERSION.SDK_INT >= 23) {
      mCurrentMode = display.getMode();
      @SuppressWarnings("deprecation")
      Display.Mode[] supportedModes =
          getActivity().getWindowManager().getDefaultDisplay().getSupportedModes();
      for (Display.Mode mode : supportedModes) {
        if (!modeMatchesCurrentResolution(mode)) {
          continue;
        }
        float refreshRate = mode.getRefreshRate();
        for (int interval = 1; refreshRate / interval >= 20; interval++) {
          float fps = refreshRate / interval;
          // Round the precision to avoid values like 20.000000 & 20.000002 as
          //  different fps since we operate with 1ms noise ranges anyways.
          fps = (float) (Math.floor(fps * 1000) / 1000);
          fpsSet.add(fps);
        }
      }
    } else {
      @SuppressWarnings("deprecation")
      float refreshRate = getActivity().getWindowManager().getDefaultDisplay().getRefreshRate();
      for (int interval = 1; refreshRate / interval >= 20; interval++) {
        fpsSet.add(refreshRate / interval);
      }
    }

    int numEntries = fpsSet.size() + 1;
    String[] entries = new String[numEntries];
    String[] entryValues = new String[numEntries];

    Iterator<Float> fpsSetIterator = fpsSet.descendingIterator();
    for (int i = 0; i < numEntries - 1; i++) {
      float fps = fpsSetIterator.next();
      float ms = 1000 / fps;

      entries[i] = String.format(Locale.US, "%.2fms (%.1ffps)", ms, fps);
      entryValues[i] = Float.toString(ms);
    }

    entries[numEntries - 1] = String.format(Locale.US, "No pacing");
    entryValues[numEntries - 1] = Float.toString(100);

    mSwapIntervalPreference.setEntries(entries);
    mSwapIntervalPreference.setEntryValues(entryValues);
    mSwapIntervalPreference.setDefaultValue(entries[0]);

    Context context = getContext();
    if (context != null) {
      SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
      sharedPreferences.registerOnSharedPreferenceChangeListener(this);
      // set default if it doesn't exist
      if (sharedPreferences.getString(mSwapIntervalKey, null) == null)
        sharedPreferences.edit().putString(mSwapIntervalKey, entryValues[0]).apply();
      updateSwapIntervalSummary(sharedPreferences.getString(mSwapIntervalKey, null));
    }
  }

  private void updateSwapIntervalSummary(String swapIntervalString) {
    Resources resources;
    try {
      resources = getResources();
    } catch (IllegalStateException e) {
      // Swallow this and return early if we're not currently associated with an Activity
      return;
    }

    float swapInterval = Float.parseFloat(swapIntervalString);
    mSwapIntervalPreference.setSummary(String.format(Locale.US, "%s %.2f %s",
        resources.getString(R.string.swap_interval_summary_prologue), swapInterval,
        resources.getString(R.string.swap_interval_summary_epilogue)));
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    if (key.equals(mSwapIntervalKey)) {
      updateSwapIntervalSummary(sharedPreferences.getString(mSwapIntervalKey, null));
    }
  }
}
