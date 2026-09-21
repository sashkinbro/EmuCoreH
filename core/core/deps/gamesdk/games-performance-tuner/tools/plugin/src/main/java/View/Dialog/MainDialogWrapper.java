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

package View.Dialog;

import Controller.Annotation.AnnotationTabController;
import Controller.Fidelity.FidelityTabController;
import Controller.InstrumentationSettings.InstrumentationSettingsTabController;
import Controller.Quality.QualityTabController;
import Model.EnumDataModel;
import Model.MessageDataModel;
import Model.QualityDataModel;
import Utils.Assets.AssetsFinder;
import Utils.Assets.AssetsWriter;
import Utils.Monitoring.RequestServer;
import Utils.Proto.ProtoCompiler;
import Utils.Resources.ResourceLoader;
import View.PluginLayout;
import com.google.tuningfork.Tuningfork.Settings;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationDisplayType;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ui.JBEmptyBorder;
import com.intellij.util.ui.JBUI;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.border.Border;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class MainDialogWrapper extends DialogWrapper {

  private final ResourceLoader resourceLoader = ResourceLoader.getInstance();
  private static PluginLayout pluginLayout;
  private final Project project;
  private final NotificationGroup NOTIFICATION_GROUP =
      new NotificationGroup(resourceLoader.get("android_performance_tuner"),
          NotificationDisplayType.BALLOON, true);
  private AnnotationTabController annotationTabController;
  private FidelityTabController fidelityTabController;
  private QualityTabController qualityTabController;
  private InstrumentationSettingsTabController instrumentationController;
  private MessageDataModel annotationData;
  private MessageDataModel fidelityData;
  private List<EnumDataModel> enumData;
  private List<QualityDataModel> qualityData;
  private final Settings settingsData;
  private ProtoCompiler compiler;

  private void addNotification(String errorMessage) {
    Notification notification = NOTIFICATION_GROUP.createNotification(
        errorMessage,
        NotificationType.ERROR);
    notification.notify(project);
  }


  @Nullable
  @Override
  protected Border createContentPaneBorder() {
    return new JBEmptyBorder(JBUI.insetsRight(10));
  }

  @Override
  protected void doOKAction() {
    pluginLayout.saveSettings();
    if (!pluginLayout.isViewValid()) {
      Messages
          .showErrorDialog(resourceLoader.get("fix_errors_first"),
              resourceLoader.get("unable_to_close_title"));
      return;
    }
    AssetsWriter assetsWriter = new AssetsWriter(
        AssetsFinder.findAssets(project.getProjectFilePath().split(".idea")[0]).getAbsolutePath());
    annotationTabController = pluginLayout.getAnnotationTabController();
    fidelityTabController = pluginLayout.getFidelityTabController();
    qualityTabController = pluginLayout.getQualityTabController();
    instrumentationController = pluginLayout.getInstrumentationSettingsTabController();
    List<EnumDataModel> annotationEnums = annotationTabController.getEnums();

    if (annotationEnums == null) {
      addNotification(resourceLoader.get("unable_to_save_annotation_and_quality_empty_error"));
      return;
    }

    MessageDataModel fidelityModel = fidelityTabController.getFidelityData();
    MessageDataModel annotationModel = annotationTabController.getAnnotationData();
    boolean writeOK = true;

    if (!assetsWriter.saveDevTuningForkProto(annotationEnums, annotationModel, fidelityModel)) {
      addNotification(resourceLoader.get("unable_to_save_annotation_and_quality"));
      writeOK = false;
    }
    Settings dataModel = instrumentationController.getDataModel();
    if (!assetsWriter.saveInstrumentationSettings(dataModel)) {
      addNotification("Unable to write instrumentation settings to txt files.");
      writeOK = false;
    }
    List<QualityDataModel> qualityDataModels = qualityTabController.getQualityDataModels();
    assetsWriter.saveDevFidelityParams(compiler, qualityDataModels);

    if (writeOK) {
      Notification notification = NOTIFICATION_GROUP
          .createNotification(resourceLoader.get("save_successful"), NotificationType.INFORMATION);
      notification.notify(project);
      super.doOKAction();
    }
  }

  public MainDialogWrapper(@Nullable Project project, MessageDataModel annotationData,
      MessageDataModel fidelityData, List<EnumDataModel> enumData,
      List<QualityDataModel> qualityData, Settings settingsData, ProtoCompiler compiler) {
    super(project);

    this.annotationData = annotationData;
    this.enumData = enumData;
    this.fidelityData = fidelityData;
    this.qualityData = qualityData;
    this.settingsData = settingsData;
    this.project = project;
    this.compiler = compiler;
    setTitle(resourceLoader.get("android_performance_tuner_plugin"));
    Disposer.register(this.getDisposable(), () -> RequestServer.getInstance().stopListening());
    init();
  }

  @NotNull
  @Override
  protected DialogStyle getStyle() {
    return DialogStyle.COMPACT;
  }

  @Override
  @Nullable
  protected JComponent createCenterPanel() {
    pluginLayout = new PluginLayout(annotationData, fidelityData, enumData, qualityData,
        settingsData, getDisposable());
    return pluginLayout;
  }
}
