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

package View.DebugInfo;

import Controller.DebugInfo.DebugInfoController;
import Utils.Monitoring.RequestServer;
import Utils.UI.UIUtils;
import View.Decorator.LabelScrollPane;
import View.Decorator.TreeSelections.NonLeafSelection;
import View.TabLayout;
import com.google.common.collect.Streams;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.tuningfork.Tuningfork.Settings;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.UIUtil;
import java.awt.Dimension;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.TreeSelectionModel;
import org.jdesktop.swingx.HorizontalLayout;
import org.jdesktop.swingx.VerticalLayout;

public class debugInfoTab extends TabLayout {

  private final JLabel debugInfoLabel = new JLabel("Debug Info");
  private final DebugInfoController debugInfoController;
  private final Dimension treePanelDimension = new Dimension(300, 200);
  private LabelScrollPane descriptorPanel;
  private LabelScrollPane settingsPanel;
  private JTree jTree;

  public debugInfoTab() {
    debugInfoController = new DebugInfoController();
    this.setLayout(new VerticalLayout());
    initComponents();
    addComponents();
    initRequestServer();
  }

  private void initRequestServer() {
    RequestServer.getInstance().setDebugInfoAction(jsonElement -> {
      String devTuningforkDescriptor = jsonElement.get("dev_tuningfork_descriptor").getAsString();
      String settingsBase64 = jsonElement.get("settings").getAsString();
      List<String> fidelityStrings = jsonArrayToList(
          jsonElement.get("fidelity_param_sets").getAsJsonArray());
      byte[] devTuningforkBytes = Base64.getDecoder().decode(devTuningforkDescriptor);
      byte[] settingsBytes = Base64.getDecoder().decode(settingsBase64);
      try {
        FileDescriptorSet fileDescriptorSet = FileDescriptorSet.parseFrom(devTuningforkBytes);
        FileDescriptorProto proto = fileDescriptorSet.getFile(0);
        FileDescriptor fileDescriptor = FileDescriptor.buildFrom(proto, new FileDescriptor[]{});
        Settings settings = Settings.parseFrom(settingsBytes);
        debugInfoController.setDebugInfo(descriptorPanel, proto.toString());
        debugInfoController.setDebugInfo(settingsPanel, settings.toBuilder().toString());
        reloadTree(jTree, fidelityStrings, fileDescriptor);
      } catch (InvalidProtocolBufferException | DescriptorValidationException e) {
        e.printStackTrace();
      }
    });
  }

  private List<String> jsonArrayToList(JsonArray jsonArray) {
    return Streams.stream(jsonArray.iterator())
        .map(JsonElement::getAsString)
        .collect(Collectors.toList());
  }

  private void initComponents() {
    jTree = new Tree();
    jTree.setRootVisible(false);
    jTree.setSelectionModel(new NonLeafSelection());
    jTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    debugInfoLabel.setFont(getMainFont());
    descriptorPanel = new LabelScrollPane(275, 200);
    settingsPanel = new LabelScrollPane(275, 200);
    jTree.setBackground(UIUtil.getWindowColor());
  }

  private void addComponents() {
    this.add(debugInfoLabel);
    JPanel mainPanel = new JPanel(new VerticalLayout(10));
    JPanel childPanel = new JPanel(new HorizontalLayout(0));
    childPanel.add(descriptorPanel.getPanel());
    descriptorPanel.getPanel().setBorder(BorderFactory.createTitledBorder("Descriptor"));
    childPanel.add(Box.createHorizontalStrut(15));
    childPanel.add(settingsPanel.getPanel());
    settingsPanel.getPanel().setBorder(BorderFactory.createTitledBorder("Settings"));
    mainPanel.setBorder(BorderFactory.createTitledBorder("Debug Info"));
    mainPanel.add(childPanel);
    JPanel decoratorPanel = ToolbarDecorator
        .createDecorator(jTree)
        .setPreferredSize(treePanelDimension)
        .createPanel();
    decoratorPanel.setBorder(BorderFactory.createTitledBorder("Quality Settings"));
    mainPanel.add(decoratorPanel);
    this.add(mainPanel);
  }

  public void reloadTree(JTree jTree, List<String> fidelityStrings, FileDescriptor descriptor) {
    UIUtils.reloadTreeAndKeepState(jTree, debugInfoController.getQualityAsTree(
        debugInfoController.convertByteStringToModel(fidelityStrings, descriptor)));
  }
}
