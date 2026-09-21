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
package View.Fidelity;

import com.intellij.openapi.ui.ComboBox;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JPanel;

public class FidelityTablePanel extends JPanel {

  private final JComboBox<String> enumTypes;
  private final FidelityValidatablePanelWithTextField enumName;
  private FidelityTableData fidelityData;

  public FidelityTablePanel() {
    setLayout(new GridLayout(1, 2));
    enumTypes = new ComboBox<>();
    enumName = new FidelityValidatablePanelWithTextField();
    fidelityData = new FidelityTableData();
    add(enumTypes);
    add(enumName);
  }

  public JComboBox<String> getEnumTypes() {
    return enumTypes;
  }

  public FidelityValidatablePanelWithTextField getTextFieldPanel() {
    return enumName;
  }

  public String getEnumName() {
    return enumName.getTextField().getText();
  }

  public String getEnumType() {
    return enumTypes.getSelectedIndex() == -1 ? "" : enumTypes.getSelectedItem().toString();
  }

  public FidelityTableData getFidelityData() {
    return new FidelityTableData(fidelityData.getFieldType(), getEnumType(), getEnumName());
  }

  public void setComboBoxChoices(List<String> choices) {
    for (String string : choices) {
      enumTypes.addItem(string);
    }
  }

  public void updateData(FidelityTableData fidelityData) {
    this.fidelityData = fidelityData;
    enumTypes.setSelectedItem(this.fidelityData.getFieldEnumName());
    enumName.getTextField().setText(this.fidelityData.getFieldParamName());
  }
}
