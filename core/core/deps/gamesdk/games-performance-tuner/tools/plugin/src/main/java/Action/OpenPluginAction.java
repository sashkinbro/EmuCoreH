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
package Action;

import Model.EnumDataModel;
import Model.MessageDataModel;
import Model.QualityDataModel;
import Utils.DataModelTransformer;
import Utils.Proto.CompilationException;
import Utils.Proto.ProtoCompiler;
import Utils.Resources.ResourceLoader;
import View.Dialog.MainDialogWrapper;
import com.google.tuningfork.Tuningfork.Settings;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import java.io.IOException;
import java.util.List;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.NotNull;

public class OpenPluginAction extends AnAction {

  private final ResourceLoader resourceLoader = ResourceLoader.getInstance();

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    ProgressManager.getInstance()
        .run(
            new Task.Backgroundable(e.getProject(), resourceLoader.get("start_apt")) {
              public void run(@NotNull ProgressIndicator progressIndicator) {
                ProtoCompiler protoCompiler = ProtoCompiler.getInstance();
                String projectPath = e.getProject().getProjectFilePath().split(".idea")[0];
                progressIndicator.setIndeterminate(true);
                progressIndicator.setText(resourceLoader.get("load_assets"));

                try {
                  DataModelTransformer transformer = new DataModelTransformer(projectPath,
                      protoCompiler);
                  MessageDataModel annotationData = transformer.initAnnotationData();
                  MessageDataModel fidelityTableData = transformer.initFidelityData();
                  List<EnumDataModel> enumData = transformer.initEnumData();
                  List<QualityDataModel> qualityData = transformer.initQualityData();
                  Settings settingsData = transformer.initProtoSettings();
                  SwingUtilities.invokeLater(() -> {
                    MainDialogWrapper dialogWrapper = new MainDialogWrapper(e.getProject(),
                        annotationData,
                        fidelityTableData,
                        enumData,
                        qualityData,
                        settingsData,
                        protoCompiler);
                    dialogWrapper.show();
                    dialogWrapper.disposeIfNeeded();
                  });
                } catch (IOException | CompilationException ex) {
                  ex.printStackTrace();
                }
              }
            });
  }
}
