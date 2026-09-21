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

package View.FidelityChanger;

import Controller.FidelityChanger.FidelityChangerController;
import Utils.Monitoring.RequestServer;
import Utils.UI.UIUtils;
import View.Decorator.TreeSelections.NonLeafSelection;
import View.TabLayout;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.UIUtil;
import java.awt.Dimension;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeSelectionModel;
import org.jdesktop.swingx.VerticalLayout;
import org.jetbrains.annotations.NotNull;

public class FidelityChanger extends TabLayout {

  private final JLabel experimentalLabel = new JLabel("Fidelity Changer");
  private final FidelityChangerController controller;
  private final Dimension treePanelDimension = new Dimension(300, 200);
  private JTree jTree;
  private JPanel decoratorPanel;

  public FidelityChanger(FidelityChangerController controller) {
    this.controller = controller;
    this.setLayout(new VerticalLayout());
    initComponents();
    addComponents();
  }

  private void initComponents() {
    jTree = new Tree(controller.getQualityAsTree());
    jTree.setRootVisible(false);
    jTree.setSelectionModel(new NonLeafSelection());
    jTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    experimentalLabel.setFont(getMainFont());
    decoratorPanel = ToolbarDecorator.createDecorator(jTree)
        .addExtraAction(new ChangeButton())
        .addExtraAction(new RefreshButton())
        .setPreferredSize(treePanelDimension)
        .createPanel();
    decoratorPanel.setBorder(BorderFactory.createTitledBorder("Fidelity Changer"));
    jTree.setBackground(UIUtil.getWindowColor());
  }

  private void addComponents() {
    this.add(experimentalLabel);
    this.add(decoratorPanel);
  }

  public void reloadTree(JTree jTree) {
    UIUtils.reloadTreeAndKeepState(jTree, controller.getQualityAsTree());
  }

  private final class RefreshButton extends AnActionButton {

    public RefreshButton() {
      super("Refresh", AllIcons.Actions.Refresh);
    }

    @Override
    public void updateButton(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(false);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
      reloadTree(jTree);
    }
  }

  private final class ChangeButton extends AnActionButton {

    public ChangeButton() {
      super("Set Fidelity", AllIcons.Actions.SetDefault);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
      DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) Objects
          .requireNonNull(jTree.getSelectionPath())
          .getLastPathComponent();
      int qualityIndex = treeNode.getParent().getIndex(treeNode);
      controller.setCurrentByteString(
          controller.getQualityAsByteString(qualityIndex));
      RequestServer.getInstance().setFidelitySupplier(
          () -> controller.getQualityAsByteString(qualityIndex));
      reloadTree(jTree);
    }

    @Override
    public void updateButton(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(jTree.getSelectionPath() != null);
    }
  }
}
