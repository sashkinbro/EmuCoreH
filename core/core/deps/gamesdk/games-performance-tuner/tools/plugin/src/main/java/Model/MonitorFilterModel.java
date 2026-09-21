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

package Model;

import View.Monitoring.MonitoringTab.JTreeNode;
import com.google.gson.annotations.Expose;
import java.util.List;

public class MonitorFilterModel {

  @Expose
  private String phoneModel;
  @Expose
  private List<JTreeNode> annotations;
  @Expose
  private JTreeNode fidelity;

  public MonitorFilterModel(String phoneModel, List<JTreeNode> annotations,
      JTreeNode fidelity) {
    this.phoneModel = phoneModel;
    this.annotations = annotations;
    this.fidelity = fidelity;
  }

  public String getPhoneModel() {
    return phoneModel;
  }

  public List<JTreeNode> getAnnotations() {
    return annotations;
  }

  public JTreeNode getFidelity() {
    return fidelity;
  }

  public void setFidelity(JTreeNode fidelity) {
    this.fidelity = fidelity;
  }

  public void setPhoneMode(String phoneModel) {
    this.phoneModel = phoneModel;
  }

  public void setAnnotations(List<JTreeNode> annotations) {
    this.annotations = annotations;
  }

  @Override
  public String toString() {
    return "MonitorFilterModel{" +
        "phoneModel='" + phoneModel + '\'' +
        ", annotations=" + annotations +
        ", fidelity=" + fidelity +
        '}';
  }
}
