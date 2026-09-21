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

package Controller.InstrumentationSettings;

import com.google.tuningfork.Tuningfork.Settings;
import com.google.tuningfork.Tuningfork.Settings.AggregationStrategy;
import com.google.tuningfork.Tuningfork.Settings.AggregationStrategy.Submission;
import com.google.tuningfork.Tuningfork.Settings.Histogram;
import java.util.ArrayList;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextField;

public class InstrumentationSettingsTabController {

  private Settings settingsModel;

  public InstrumentationSettingsTabController(
      Settings settingsModel) {
    this.settingsModel = settingsModel;
  }

  public void setInitialData(JTable jTable) {
    InstrumentationSettingsTableModel model = (InstrumentationSettingsTableModel) jTable.getModel();
    ArrayList<String[]> histograms = new ArrayList<>();
    for (Histogram histogram : settingsModel.getHistogramsList()) {
      histograms.add(new String[]{String.valueOf(histogram.getInstrumentKey()),
          String.valueOf(histogram.getBucketMin()),
          String.valueOf(histogram.getBucketMax()),
          String.valueOf(histogram.getNBuckets())});
    }
    model.setData(histograms);
  }

  public void setAggregation(JRadioButton timeButton, JRadioButton tickButton,
      JSlider intervalSlider) {
    if (settingsModel.getAggregationStrategy().getMethod().equals(Submission.TIME_BASED)) {
      timeButton.setSelected(true);
      intervalSlider
          .setValue(settingsModel.getAggregationStrategy().getIntervalmsOrCount() / (10 * 1000));
    } else if (settingsModel.getAggregationStrategy().getMethod().equals(Submission.TICK_BASED)) {
      tickButton.setSelected(true);
      intervalSlider.setValue(settingsModel.getAggregationStrategy().getIntervalmsOrCount());
    }
  }

  public void setBaseUrlTextBox(JTextField baseUrl) {
    baseUrl.setText(settingsModel.getBaseUri());
  }

  public void setApiKeyTextBox(JTextField apiKey) {
    apiKey.setText(settingsModel.getApiKey());
  }

  public void setEnumData() {
    Settings.Builder settingsBuilder = settingsModel.toBuilder();
    AggregationStrategy.Builder strategy = settingsBuilder.getAggregationStrategy().toBuilder();
    settingsModel = settingsBuilder.setAggregationStrategy(strategy.setMaxInstrumentationKeys(
        settingsModel.getHistogramsCount()
    )).build();
  }

  public void addRowAction(JTable jTable) {
    InstrumentationSettingsTableModel model = (InstrumentationSettingsTableModel) jTable.getModel();
    model.addRow();
  }

  public void removeRowAction(JTable jTable) {
    InstrumentationSettingsTableModel model = (InstrumentationSettingsTableModel) jTable.getModel();
    int row = jTable.getSelectedRow();
    if (jTable.getCellEditor() != null) {
      jTable.getCellEditor().stopCellEditing();
    }
    model.removeRow(row);
  }

  public void setDefaultQuality(int value) {
    Settings.Builder settingsBuilder = settingsModel.toBuilder();
    // + 1 was added to shift to 1-indexing
    settingsModel = settingsBuilder.setDefaultFidelityParametersFilename(
        "dev_tuningfork_fidelityparams_" + (value + 1) + ".bin").build();
  }

  public void addNewHistogram() {
    Settings.Builder settingsBuilder = settingsModel.toBuilder();
    settingsModel = settingsBuilder.addHistograms(Histogram.newBuilder().build()).build();
  }

  public void removeHistogram(int row) {
    Settings.Builder settingsBuilder = settingsModel.toBuilder();
    settingsModel = settingsBuilder.removeHistograms(row).build();
  }

  public void setHistogramInstrumentID(int histogramRow, String id) {
    if (!id.isEmpty()) {
      Settings.Builder settingsBuilder = settingsModel.toBuilder();
      Histogram histogram = settingsBuilder.getHistograms(histogramRow);
      settingsModel = settingsModel.toBuilder().setHistograms(histogramRow,
          histogram.toBuilder().setInstrumentKey(Integer.parseInt(id))).build();
    }
  }

  public void setHistogramMinimumBucketSize(int histogramRow, String bucketSize) {
    if (!bucketSize.isEmpty()) {
      Settings.Builder settingsBuilder = settingsModel.toBuilder();
      Histogram histogram = settingsBuilder.getHistograms(histogramRow);
      settingsModel = settingsModel.toBuilder().setHistograms(histogramRow,
          histogram.toBuilder().setBucketMin(Float.parseFloat(bucketSize))).build();
    }
  }

  public void setHistogramMaximumBucketSize(int histogramRow, String bucketSize) {
    if (!bucketSize.isEmpty()) {
      Settings.Builder settingsBuilder = settingsModel.toBuilder();
      Histogram histogram = settingsBuilder.getHistograms(histogramRow);
      settingsModel = settingsModel.toBuilder().setHistograms(histogramRow,
          histogram.toBuilder().setBucketMax(Float.parseFloat(bucketSize))).build();
    }
  }

  public void setHistogramNumberOfBuckets(int histogramRow, String buckets) {
    if (!buckets.isEmpty()) {
      Settings.Builder settingsBuilder = settingsModel.toBuilder();
      Histogram histogram = settingsBuilder.getHistograms(histogramRow);
      settingsModel = settingsModel.toBuilder().setHistograms(histogramRow,
          histogram.toBuilder().setNBuckets(Integer.parseInt(buckets))).build();
    }
  }

  public void setAggregationMethod(String uploadMethod) {
    Settings.Builder settingsBuilder = settingsModel.toBuilder();
    AggregationStrategy.Builder strategy = settingsBuilder.getAggregationStrategy().toBuilder();
    Submission submission = (uploadMethod.equals("Time Based") ? Submission.TIME_BASED
        : Submission.TICK_BASED);
    settingsModel = settingsBuilder.setAggregationStrategy(strategy.setMethod(submission).build())
        .build();
  }

  public void setBaseUrl(String url) {
    Settings.Builder settingsBuilder = settingsModel.toBuilder();
    settingsModel = settingsBuilder.setBaseUri(url).build();
  }

  public void setMaxInstrumentationKeys(int keys) {
    Settings.Builder settingsBuilder = settingsModel.toBuilder();
    AggregationStrategy.Builder strategy = settingsBuilder.getAggregationStrategy().toBuilder();
    settingsModel = settingsBuilder
        .setAggregationStrategy(strategy.setMaxInstrumentationKeys(keys))
        .build();
  }

  public void setApiKey(String key) {
    Settings.Builder settingsBuilder = settingsModel.toBuilder();
    settingsModel = settingsBuilder.setApiKey(key).build();
  }

  public void setUploadInterval(Integer uploadInterval) {
    Settings.Builder settingsBuilder = settingsModel.toBuilder();
    AggregationStrategy.Builder strategy = settingsBuilder.getAggregationStrategy().toBuilder();
    settingsModel = settingsBuilder
        .setAggregationStrategy(strategy.setIntervalmsOrCount(uploadInterval))
        .build();
  }

  public Settings getDataModel() {
    return settingsModel;
  }
}