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
package Controller.Monitoring;

import Controller.Monitoring.HistogramTree.Node;
import Model.MonitorFilterModel;
import Model.QualityDataModel;
import Utils.DataModelTransformer;
import Utils.Resources.ResourceLoader;
import View.Monitoring.MonitoringTab.JTreeNode;
import com.google.android.performanceparameters.v1.PerformanceParameters.DeviceSpec;
import com.google.android.performanceparameters.v1.PerformanceParameters.Telemetry;
import com.google.android.performanceparameters.v1.PerformanceParameters.UploadTelemetryRequest;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.fileChooser.ex.FileSaverDialogImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.util.ui.UIUtil;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

public class MonitoringController {

  private final PropertyChangeSupport monitoringPropertyChange;
  private HistogramTree histogramTree;
  private final Map<ByteString, Integer> annotationMap, fidelityMap;
  private List<MonitorFilterModel> filterModels;
  private final static String HISTOGRAMS_DATA_JSON = "histograms_data";
  private final static String FILTER_DATA_JSON = "filters_data";
  private final static ResourceLoader RESOURCE_LOADER = ResourceLoader.getInstance();

  public MonitoringController() {
    monitoringPropertyChange = new PropertyChangeSupport(this);
    histogramTree = new HistogramTree();
    annotationMap = new HashMap<>();
    fidelityMap = new HashMap<>();
    filterModels = new ArrayList<>();
  }

  private String getAnnotation(ByteString bytes) {
    try {
      DynamicMessage dynamicMessage = DynamicMessage
          .parseFrom(
              DataModelTransformer.getDevTuningforkDesc().findMessageTypeByName("Annotation"),
              bytes);
      return splitMessageIntoHTML(dynamicMessage.toString());
    } catch (InvalidProtocolBufferException e) {
      e.printStackTrace();
    }
    return "";
  }

  private String getFidelity(ByteString bytes) {
    try {
      Descriptor descriptor = DataModelTransformer.getDevTuningforkDesc()
          .findMessageTypeByName("FidelityParams");
      DynamicMessage dynamicMessage = DynamicMessage.parseFrom(descriptor, bytes);
      QualityDataModel newQuality = DataModelTransformer
          .transformToQuality(dynamicMessage, descriptor.getFields()).get();
      return splitMessageIntoHTML(newQuality.toString());
    } catch (InvalidProtocolBufferException e) {
      e.printStackTrace();
    }
    return "";
  }

  private String getFidelityNodeName(ByteString bytes) {
    if (fidelityMap.containsKey(bytes)) {
      return "Fidelity " + fidelityMap.get(bytes);
    }
    int sz = fidelityMap.size() + 1;
    fidelityMap.put(bytes, sz);
    return getFidelityNodeName(bytes);
  }

  private String getAnnotationNodeName(ByteString bytes) {
    if (annotationMap.containsKey(bytes)) {
      return "Annotation " + annotationMap.get(bytes);
    }
    // Starting at 1-indexed counting.
    int sz = annotationMap.size() + 1;
    annotationMap.put(bytes, sz);
    return getAnnotationNodeName(bytes);
  }

  private String splitMessageIntoHTML(String message) {
    String[] stringChunks = message.split("\n");
    StringBuilder newMessage = new StringBuilder("<html>");
    for (String chunk : stringChunks) {
      newMessage.append(chunk).append("<br>");
    }
    newMessage.append("</html>");
    return newMessage.toString();
  }

  public HistogramTree getHistogramTree() {
    return histogramTree;
  }

  private String getDeviceInfo(UploadTelemetryRequest telemetryRequest) {
    DeviceSpec deviceSpec = telemetryRequest.getSessionContext().getDevice();
    return String.format(RESOURCE_LOADER.get("monitoring_phone_tooltip"),
        telemetryRequest.getName(),
        deviceSpec.getBrand(),
        deviceSpec.getBuildVersion(),
        Arrays.toString(deviceSpec.getCpuCoreFreqsHzList().toArray()),
        deviceSpec.getDevice(),
        deviceSpec.getModel());
  }

  public void makeTree(UploadTelemetryRequest telemetryRequest) {
    String deviceName = telemetryRequest.getSessionContext().getDevice().getBrand() + "/" +
        telemetryRequest.getSessionContext().getDevice().getModel();
    Node deviceNode = new Node(deviceName, getDeviceInfo(telemetryRequest));
    for (Telemetry telemetry : telemetryRequest.getTelemetryList()) {
      ByteString annotationByteString = telemetry.getContext().getAnnotations();
      ByteString fidelityByteString = telemetry.getContext().getTuningParameters()
          .getSerializedFidelityParameters();

      Node annotation = new Node(getAnnotationNodeName(annotationByteString),
          getAnnotation(annotationByteString));
      Node fidelity = new Node(getFidelityNodeName(fidelityByteString),
          getFidelity(fidelityByteString));
      annotation.addChild(fidelity);
      deviceNode.addChild(annotation);
      telemetry.getReport().getRendering().getRenderTimeHistogramList().forEach(
          histogram -> {
            Node instrumentNode = new Node(String.valueOf(histogram.getInstrumentId()));
            instrumentNode.setValue(histogram.getCountsList());
            fidelity.addChild(instrumentNode);
          }
      );
    }
    histogramTree.addPath(deviceNode);
  }

  public JTreeNode getTree() {
    return histogramTree.getAsTreeNode();
  }

  public JTreeNode getFilterTree() {
    JTreeNode root = new JTreeNode();
    for (int i = 0; i < filterModels.size(); i++) {
      MonitorFilterModel model = filterModels.get(i);
      JTreeNode filterNode = new JTreeNode(String.format(RESOURCE_LOADER.get("filter"), i + 1));
      filterNode.add(
          new JTreeNode(String.format(RESOURCE_LOADER.get("phone_model"), model.getPhoneModel())));
      JTreeNode annotationsNode = new JTreeNode(RESOURCE_LOADER.get("annotations"));
      filterNode.add(annotationsNode);
      model.getAnnotations().forEach(annotationsNode::add);
      filterNode.add(model.getFidelity());
      root.add(filterNode);
    }
    return root;
  }

  public void addMonitoringPropertyChangeListener(PropertyChangeListener listener) {
    monitoringPropertyChange.addPropertyChangeListener(listener);
  }

  public void saveReport() {
    FileSaverDescriptor descriptor = new FileSaverDescriptor(RESOURCE_LOADER.get("save_report"),
        "", "json");
    final FileSaverDialog dialog = new FileSaverDialogImpl(descriptor, (Project) null);
    final VirtualFileWrapper save = dialog
        .save(LocalFileSystem.getInstance().findFileByPath(System.getProperty("user.home")), "");
    Gson gson = new Gson();
    if (save != null) {
      File selectedFile = save.getFile();
      JsonObject jsonObject = new JsonObject();
      jsonObject.add(HISTOGRAMS_DATA_JSON, gson.toJsonTree(histogramTree, HistogramTree.class));
      Gson filterGson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
      jsonObject.add(FILTER_DATA_JSON, filterGson.toJsonTree(filterModels));
      try (FileOutputStream writer = new FileOutputStream(selectedFile)) {
        writer.write(jsonObject.toString().getBytes(StandardCharsets.UTF_16));
      } catch (IOException e) {
        e.printStackTrace();
        Messages.showErrorDialog(
            RESOURCE_LOADER.get("fail_save_report_message"),
            RESOURCE_LOADER.get("fail_save_report_title"));
      }
    }
  }

  public boolean loadReport() {
    JsonObject data = loadReportData();
    if (data == null) {
      return false;
    }
    try {
      loadJsonMonitoringData(data);
      return true;
    } catch (InvalidMonitoringDataException e) {
      // e.printStackTrace();
      Messages.showErrorDialog(
          String.format(RESOURCE_LOADER.get("fail_load_report_message"),
              "Invalid monitoring file."),
          RESOURCE_LOADER.get("fail_load_report_title"));
    }
    return false;
  }

  private JsonObject loadReportData() {
    FileChooserDescriptor descriptor = FileChooserDescriptorFactory
        .createSingleFileDescriptor("json");
    VirtualFile file = FileChooser.chooseFile(descriptor, null, null);
    if (file != null) {
      File selectedFile = new File(file.getPath());
      try {
        String data = FileUtils.readFileToString(selectedFile, StandardCharsets.UTF_16);
        return (JsonObject) new JsonParser().parse(data);
      } catch (IOException e) {
        e.printStackTrace();
        Messages.showErrorDialog(
            String.format(RESOURCE_LOADER.get("fail_load_report_message"),
                "Unable to read file."),
            RESOURCE_LOADER.get("fail_load_report_title"));
      } catch (JsonSyntaxException e) {
        e.printStackTrace();
        Messages.showErrorDialog(
            String.format(RESOURCE_LOADER.get("fail_load_report_message"),
                "Unable to parse file as JSON object"),
            RESOURCE_LOADER.get("fail_load_report_title"));
      }
    }
    /*
     * If the loading reached here without throwing exception then user clicked "Cancel"
     * On the loading dialog. If it threw an exception. then the file is probably
     * not JSON-parsable.
     */
    return null;
  }

  public void clearData() {
    histogramTree.clear();
    annotationMap.clear();
    fidelityMap.clear();
    filterModels.clear();
  }

  public boolean isValidData(JsonObject data) {
    return data.has(HISTOGRAMS_DATA_JSON) && data.has(FILTER_DATA_JSON);
  }

  public void loadJsonMonitoringData(JsonObject jsonData) throws InvalidMonitoringDataException {
    if (!isValidData(jsonData)) {
      throw new InvalidMonitoringDataException();
    }
    clearData();
    Gson histogramJson = new Gson();
    Gson filterJson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    JsonElement histogramJsonElement = jsonData.get(HISTOGRAMS_DATA_JSON);
    JsonElement filterJsonElement = jsonData.get(FILTER_DATA_JSON);
    histogramTree = histogramJson.fromJson(histogramJsonElement, HistogramTree.class);
    Type listType = new TypeToken<List<MonitorFilterModel>>() {
    }.getType();
    filterModels = filterJson.fromJson(filterJsonElement, listType);
  }

  public void addFilter(MonitorFilterModel model) {
    filterModels.add(model);
  }

  public void removeFilter(int index) {
    filterModels.remove(index);
  }

  public Set<String> prepareFiltering() {
    List<Map<String, List<Integer>>> allModelsHistograms = new ArrayList<>();
    filterModels.forEach(model -> allModelsHistograms.add(getModelHistogram(model)));
    Set<String> allInstrumentKeys = getAllKeys(allModelsHistograms);
    for (String key : allInstrumentKeys) {
      XYSeriesCollection collection = new XYSeriesCollection();
      for (int i = 0; i < allModelsHistograms.size(); i++) {
        Map<String, List<Integer>> filterData = allModelsHistograms.get(i);
        List<Integer> histogram = filterData.getOrDefault(key, new ArrayList<>());
        collection.addSeries(makeSeries(histogram, i));
      }
      JFreeChart histogram = ChartFactory
          .createXYBarChart(RESOURCE_LOADER.get("render_time_histograms"),
              RESOURCE_LOADER.get("frame_time"), false, RESOURCE_LOADER.get("count"), collection,
              PlotOrientation.VERTICAL, true, false, false);
      ChartPanel chartPanel = new ChartPanel(histogram);
      setTheme(histogram, chartPanel);
      monitoringPropertyChange.firePropertyChange("addChart", key, chartPanel);
    }

    return allInstrumentKeys;
  }

  private void setTheme(JFreeChart jChart, ChartPanel chartPanel) {
    jChart.getTitle().setPaint(UIUtil.getTextAreaForeground());
    chartPanel.getChart().setBackgroundPaint(UIUtil.getWindowColor());
    jChart.getPlot().setBackgroundPaint(UIUtil.getWindowColor());
    ((XYPlot) jChart.getPlot()).setDomainGridlinePaint(UIUtil.getTextAreaForeground());
    ((XYPlot) jChart.getPlot()).setRangeGridlinePaint(UIUtil.getTextAreaForeground());
    ((XYPlot) jChart.getPlot()).getDomainAxis().setTickLabelPaint(UIUtil.getTextAreaForeground());
    ((XYPlot) jChart.getPlot()).getRangeAxis().setTickLabelPaint(UIUtil.getTextAreaForeground());
    ((XYPlot) jChart.getPlot()).getDomainAxis().setLabelPaint(UIUtil.getTextAreaForeground());
    ((XYPlot) jChart.getPlot()).getRangeAxis().setLabelPaint(UIUtil.getTextAreaForeground());
    jChart.getLegend().setBackgroundPaint(UIUtil.getWindowColor());
    jChart.getLegend().setItemPaint(UIUtil.getTextAreaForeground());
  }

  private XYSeries makeSeries(List<Integer> seriesData, int filterNumber) {
    XYSeries xySeries = new XYSeries(
        String.format(RESOURCE_LOADER.get("filter"), filterNumber + 1));
    int totalSum = seriesData.stream().mapToInt(val -> val).sum();
    for (int i = 0; i < seriesData.size(); i++) {
      xySeries.add(i, (1.00 * seriesData.get(i)) / (double) (totalSum));
    }
    return xySeries;
  }

  private Set<String> getAllKeys(List<Map<String, List<Integer>>> allModels) {
    Set<String> keys = new HashSet<>();
    allModels.forEach(mp -> keys.addAll(mp.keySet()));
    return keys;
  }

  /*
   * This method returns a List<Map<String, List<Integer> >, the outer list represents fidelity
   * while Each Map<String, List<> > Represents a different chosen fidelity,
   * and the map entry set is the merged instrument keys
   * So if you choose [Annotation1, Annotation2], Fidelity 1, then The list size will be just 1
   * and Map will have all instrument key, and each instrument key represent the merging of
   * Annotation1-fidelity1 + Annotation2-fidelity1
   */
  private Map<String, List<Integer>> getModelHistogram(MonitorFilterModel model) {
    Node phoneNode = histogramTree.findNodeByName(model.getPhoneModel());
    String fidelity = model.getFidelity().getNodeName();
    Map<String, List<Integer>> singleFidelityBucket = new HashMap<>();
    model.getAnnotations().forEach(annotation -> {
      Node annotationNode = histogramTree.findNodeByName(annotation.getNodeName(), phoneNode);
      Map<String, List<Integer>> buckets = histogramTree
          .getFidelityCount(annotationNode, fidelity);
      mergeMaps(singleFidelityBucket, buckets);
    });
    return singleFidelityBucket;
  }

  private void mergeMaps
      (Map<String, List<Integer>> mainMap, Map<String, List<Integer>> toMerge) {
    toMerge.forEach((key, value) -> mainMap.merge(key, value, (v1, v2) -> {
      List<Integer> mergedList = new ArrayList<>();
      for (int i = 0; i < v1.size(); i++) {
        mergedList.add(v1.get(i) + v2.get(i));
      }
      return mergedList;
    }));
  }

}
