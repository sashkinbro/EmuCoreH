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

package Utils.UI;

import com.intellij.util.ui.tree.TreeUtil;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.function.Consumer;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

public class UIUtils {

  public static void reloadTreeAndKeepState(JTree jTree, DefaultMutableTreeNode newRoot) {
    final HashSet<String> pathsStrings = new HashSet<>();
    visitAllPaths((TreeNode) jTree.getModel().getRoot(),
        treePath ->
        {
          if (jTree.isExpanded(treePath)) {
            pathsStrings.add(treePath.toString());
          }
        });
    TreePath selectedPath = jTree.getSelectionPath();
    ((DefaultTreeModel) jTree.getModel()).setRoot(newRoot);
    visitAllPaths((TreeNode) jTree.getModel().getRoot(),
        treePath ->
        {
          if (pathsStrings.contains(treePath.toString())) {
            jTree.expandPath(treePath);
          }
          if (selectedPath != null && selectedPath.toString().equals(treePath.toString())) {
            jTree.setSelectionPath(treePath);
          }
        });
  }

  public static void visitAllPaths(TreeNode node, Consumer<TreePath> consumer) {
    consumer.accept(TreeUtil.getPathFromRoot(node));
    if (node.getChildCount() >= 0) {
        for (Enumeration<? extends TreeNode> e = node.children(); e.hasMoreElements();) {
            TreeNode newNode = e.nextElement();
            visitAllPaths(newNode, consumer);
        }
    }
  }
}
