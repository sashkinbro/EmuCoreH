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

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.cellvalidators.TableCellValidator;
import com.intellij.ui.CellRendererPanel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.border.Border;
import javax.swing.table.TableCellRenderer;
import org.jetbrains.annotations.NotNull;

/*
  This class is responsible for validation of Fidelity second column once it's an enum.
  It's JPanel enclosing a JComboBox and another JPanel(This panel encloses a textbox and label for error)
  The class works by finding the smaller JPanel and setting error label and adding tooltip to the
  parent panel and putting client property to the Smaller jpanel with validation data

  Only meant to be used with fidelity panel.
 */
public class FidelityCellPanelValidationRendererWrapper extends CellRendererPanel implements
    TableCellRenderer {

  public static final String CELL_VALIDATION_PROPERTY = "CellRenderer.validationInfo";

  private final TableCellRenderer delegate;

  private final Supplier<? extends Dimension> editorSizeSupplier = JBUI::emptySize;
  private TableCellValidator cellValidator;

  public FidelityCellPanelValidationRendererWrapper(TableCellRenderer delegate) {
    this.delegate = delegate;
    setLayout(new BorderLayout(0, 0));
  }

  public FidelityCellPanelValidationRendererWrapper withCellValidator(
      @NotNull TableCellValidator cellValidator) {
    this.cellValidator = cellValidator;
    return this;
  }

  @Override
  public Dimension getPreferredSize() {
    Dimension size = super.getPreferredSize();
    size.height = Math.max(size.height, editorSizeSupplier.get().height);
    return size;
  }


  private JPanel getTextFieldPanel(JComponent jPanel) {
    for (Component component : jPanel.getComponents()) {
      if (component instanceof JPanel) {
        return (JPanel) component;
      }
    }
    return null;
  }

  @Override
  public final Component getTableCellRendererComponent(JTable table, Object value,
      boolean isSelected, boolean hasFocus, int row, int column) {
    JComponent delegateRenderer = (JComponent) delegate
        .getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
    if (!(delegateRenderer instanceof JPanel)) {
      throw new IllegalStateException("Unknown Component. Expected JPanel");
    }
    JPanel validatablePanel = getTextFieldPanel(delegateRenderer);
    if (cellValidator != null) {
      ValidationInfo result = cellValidator.validate(value, row, column);
      if (validatablePanel instanceof FidelityValidatablePanelWithTextField) {
        FidelityValidatablePanelWithTextField validatableTextField
            = (FidelityValidatablePanelWithTextField) validatablePanel;
        validatableTextField.getErrorLabel().setIcon(result == null ? null
            : result.warning ? AllIcons.General.BalloonWarning : AllIcons.General.BalloonError);
        validatableTextField.getErrorLabel().setBorder(result == null ? null : iconBorder());
        validatableTextField.putClientProperty(CELL_VALIDATION_PROPERTY, result);
        if (result != null) {
          delegateRenderer.setToolTipText(result.message);
        } else {
          delegateRenderer.setToolTipText("");
        }
      }
    }
    return delegateRenderer;
  }

  private static Border iconBorder() {
    return JBUI.Borders.emptyRight(UIUtil.isUnderWin10LookAndFeel() ? 4 : 3);
  }

  @Override
  protected void paintComponent(Graphics g) {
    g.setColor(getBackground());
    g.fillRect(0, 0, getWidth(), getHeight());
  }
}
