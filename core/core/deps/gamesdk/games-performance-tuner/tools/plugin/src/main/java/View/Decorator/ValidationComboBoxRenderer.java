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

package View.Decorator;

import static com.intellij.openapi.ui.cellvalidators.ValidatingTableCellRendererWrapper.CELL_VALIDATION_PROPERTY;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.awt.Component;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.border.Border;

public class ValidationComboBoxRenderer extends DefaultListCellRenderer {

  private final JPanel jPanel;
  private final JLabel textLabel;
  private final JLabel errorLabel;
  private final JComboBox<?> jComboBox;

  public ValidationComboBoxRenderer(JComboBox<?> comboBox) {
    this.jComboBox = comboBox;
    jPanel = new JPanel(new BorderLayout());
    textLabel = new JLabel();
    errorLabel = new JLabel();
    textLabel.setOpaque(false);
    errorLabel.setOpaque(false);
    jPanel.add(textLabel, BorderLayout.WEST);
    jPanel.add(errorLabel, BorderLayout.EAST);
    jPanel.setBackground(null);
    jPanel.setForeground(null);
    jPanel.setOpaque(false);
  }

  @Override
  public Component getListCellRendererComponent(JList<?> list, Object value, int index,
      boolean isSelected, boolean cellHasFocus) {
    if (index == -1) {
      ValidationInfo cellInfo = (ValidationInfo) jComboBox
          .getClientProperty(CELL_VALIDATION_PROPERTY);
      errorLabel.setIcon(cellInfo == null ? null
          : cellInfo.warning ? AllIcons.General.BalloonWarning : AllIcons.General.BalloonError);
      errorLabel.setBorder(cellInfo == null ? null : iconBorder());
      textLabel.setText(value == null ? "" : value.toString());
      return jPanel;
    }
    return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
  }

  private static Border iconBorder() {
    return JBUI.Borders.emptyRight(UIUtil.isUnderWin10LookAndFeel() ? 4 : 3);
  }
}
