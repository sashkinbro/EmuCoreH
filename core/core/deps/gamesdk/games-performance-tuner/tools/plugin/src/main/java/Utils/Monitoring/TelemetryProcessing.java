/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */
package Utils.Monitoring;

import com.google.android.performanceparameters.v1.PerformanceParameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TelemetryProcessing {

  private static void mergeHistograms(List<PerformanceParameters.RenderTimeHistogram> histograms,
      LinkedHashMap<String, List<Integer>> renderTimeHistograms) {
    for (PerformanceParameters.RenderTimeHistogram histogram : histograms) {
      String idToAdd = Integer.toString(histogram.getInstrumentId());

      if (!renderTimeHistograms.containsKey(idToAdd)) {
        renderTimeHistograms.put(idToAdd, histogram.getCountsList());
      } else {
        List<Integer> existingHistograms = renderTimeHistograms.get(idToAdd);
        List<Integer> mergedHistograms = IntStream.range(0, existingHistograms.size())
            .mapToObj(i -> existingHistograms.get(i) + histogram.getCounts(i))
            .collect(Collectors.toList());
        renderTimeHistograms.put(idToAdd, mergedHistograms);
      }
    }
  }

  /*
   * Each telemetryRequest has more than one histogram for each instrument ID.
   * Histograms for the same fidelity parameters and the same instrument ID are merged, and added
   * to renderTimeHIstograms map.
   */
  public static LinkedHashMap<String, List<Integer>> processTelemetryData(
      PerformanceParameters.UploadTelemetryRequest telemetryRequest) {
    List<PerformanceParameters.Telemetry> telemetry = telemetryRequest.getTelemetryList();
    List<PerformanceParameters.RenderTimeHistogram> histograms = new ArrayList<>();
    LinkedHashMap<String, List<Integer>> renderTimeHistograms = new LinkedHashMap<>();

    for (PerformanceParameters.Telemetry telemetryElem : telemetry) {
      histograms.addAll(telemetryElem.getReport().getRendering().getRenderTimeHistogramList());
    }

    mergeHistograms(histograms, renderTimeHistograms);
    return renderTimeHistograms;
  }
}
