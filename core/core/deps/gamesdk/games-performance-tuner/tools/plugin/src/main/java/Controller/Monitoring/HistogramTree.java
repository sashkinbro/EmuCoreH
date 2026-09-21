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

import View.Monitoring.MonitoringTab.JTreeNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.swing.tree.TreePath;

public final class HistogramTree {

  private Node root = new Node("");

  public HistogramTree() {
  }

  public Node getRoot() {
    return root;
  }

  public void setRoot(Node node) {
    root = node;
  }

  public void addPath(Node node) {
    addPath(node, root);
  }

  private void addPath(Node currentNode, Node parentNode) {
    Optional<Node> foundNode = parentNode.findChild(currentNode);
    List<Node> children = currentNode.childrenNodes;
    currentNode = currentNode.getNoChildrenCopy();
    if (foundNode.isPresent()) {
      mergeNodeValue(currentNode, foundNode.get());
      currentNode = foundNode.get();
    } else {
      parentNode.addChild(currentNode);
    }
    for (Node child : children) {
      addPath(child, currentNode);
    }
  }

  // Merges the new node into the old node
  private void mergeNodeValue(Node newNode, Node oldNode) {
    List<Integer> newValue = newNode.getValue();
    List<Integer> oldValue = oldNode.getValue();
    for (int i = 0; i < oldValue.size(); i++) {
      oldValue.set(i, oldValue.get(i) + newValue.get(i));
    }
  }

  public Node findNodeByName(String name) {
    return findNodeByName(root, name);
  }

  public Node findNodeByName(String name, Node startNode) {
    return findNodeByName(startNode, name);
  }

  private Node findNodeByName(Node currentNode, String name) {
    if (currentNode.getNodeName().equals(name)) {
      return currentNode;
    }
    Node foundNode;
    for (Node childNode : currentNode.childrenNodes) {
      if ((foundNode = findNodeByName(childNode, name)) != null) {
        return foundNode;
      }
    }
    return null;
  }

  public JTreeNode getAsTreeNode(int maxDepth) {
    return getAsTreeNode(root, new JTreeNode(), maxDepth, 0);
  }

  public JTreeNode getAsTreeNode() {
    return getAsTreeNode(root, new JTreeNode(), Integer.MAX_VALUE, 0);
  }

  public JTreeNode getAsTreeNode(Node currentNode, JTreeNode treeNode, int maxDepth,
      int currentDepth) {
    if (currentDepth >= maxDepth) {
      return treeNode;
    }
    for (Node childNode : currentNode.childrenNodes) {
      JTreeNode childTreeNode = new JTreeNode(childNode);
      treeNode.add(childTreeNode);
      getAsTreeNode(childNode, childTreeNode, maxDepth, currentDepth + 1);
    }
    return treeNode;
  }

  public Map<String, List<Integer>> getFidelityCount(Node annotationNode, String fidelity) {
    Optional<Node> fidelityNode = annotationNode.childrenNodes.stream()
        .filter(node -> node.getNodeName().equals(fidelity)).findFirst();
    if (!fidelityNode.isPresent()) {
      return new HashMap<>();
    }
    Map<String, List<Integer>> bucketList = new HashMap<>();
    fidelityNode.get().childrenNodes.forEach(instrumentNode -> {
      String key = instrumentNode.getNodeName();
      List<Integer> value = instrumentNode.getValue();
      bucketList.put(key, value);
    });
    return bucketList;
  }

  public void clear() {
    root = new Node("");
  }

  public List<Node> getAllPhoneModels() {
    return new ArrayList<>(root.childrenNodes);
  }

  public List<Node> getAllAnnotations(Node phoneModel) {
    return new ArrayList<>(phoneModel.childrenNodes);
  }

  public Set<Node> getAllFidelity(List<Node> annotations) {
    Set<Node> fidelitySet = new HashSet<>();
    annotations.forEach(annotationNode -> fidelitySet.addAll(annotationNode.childrenNodes));
    return fidelitySet;
  }

  public void toggleLastNode(TreePath path) {
    Node currentNode = root;
    for (int i = 1; i < path.getPathCount(); i++) {
      JTreeNode currentJTreeNode = (JTreeNode) path.getPathComponent(i);
      currentNode = currentNode.findChild(new Node(currentJTreeNode.getNodeName())).get();
    }
    currentNode.toggleSelectedState();
  }

  public HistogramTree getCopy() {
    HistogramTree copyTree = new HistogramTree();
    copyTree.setRoot(copyTree.root.getDeepCopy());
    return copyTree;
  }

  public static final class Node {

    private List<Node> childrenNodes;
    private String nodeName;
    private String nodeToolTip;
    private List<Integer> value;
    private boolean selectedState;

    public Node(String nodeName) {
      this.childrenNodes = new ArrayList<>();
      this.nodeName = nodeName;
      this.nodeToolTip = "";
      this.value = new ArrayList<>();
      selectedState = false;
    }

    public Node(String nodeName, String nodeToolTip) {
      this(nodeName);
      this.nodeToolTip = nodeToolTip;
    }

    public List<Integer> getValue() {
      return this.value;
    }

    public void setValue(List<Integer> value) {
      this.value = value;
    }

    public void toggleSelectedState() {
      selectedState = !selectedState;
    }

    public Optional<Node> findChild(Node nodeToFind) {
      return childrenNodes.stream()
          .filter(node -> node.equals(nodeToFind))
          .findFirst();
    }

    // Gets a copy of the node without children
    public Node getNoChildrenCopy() {
      Node copyNode = new Node(nodeName, nodeToolTip);
      copyNode.setValue(new ArrayList<>(this.value));
      return copyNode;
    }

    public Node getDeepCopy() {
      Node copyNode = new Node(nodeName, nodeToolTip);
      copyNode.setValue(new ArrayList<>(this.value));
      for (int i = 0; i < childrenNodes.size(); i++) {
        Node childCopy = childrenNodes.get(i).getDeepCopy();
        copyNode.childrenNodes.add(childCopy);
      }
      return copyNode;
    }

    public String getNodeName() {
      return nodeName;
    }

    public void setNodeName(String name) {
      nodeName = name;
    }

    public String getNodeToolTip() {
      return nodeToolTip;
    }

    public void setNodeToolTip(String toolTip) {
      nodeToolTip = toolTip;
    }

    public boolean getNodeState() {
      return selectedState;
    }

    public void setNodeState(boolean state) {
      selectedState = state;
    }

    public List<Node> getChildrenNodes() {
      return childrenNodes;
    }

    public void setChildrenNodes(List<Node> childrenNodes) {
      this.childrenNodes = childrenNodes;
    }

    public void addChild(Node node) {
      childrenNodes.add(node);
    }

    public JTreeNode asJTreeNode() {
      return new JTreeNode(this);
    }

    @Override
    public String toString() {
      return nodeName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      return nodeName.equals(((Node) o).nodeName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(nodeName);
    }
  }
}
