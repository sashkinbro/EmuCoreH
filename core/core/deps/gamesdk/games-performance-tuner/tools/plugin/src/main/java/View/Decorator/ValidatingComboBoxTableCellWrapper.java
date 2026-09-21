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

import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.cellvalidators.TableCellValidator;
import com.intellij.ui.CellRendererPanel;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;
import org.jetbrains.annotations.NotNull;

/*
ValidatingComboBoxTableCellWrapper is designed specifically for ValidatableComboBox and any
object that inherits from it.
 */
public class ValidatingComboBoxTableCellWrapper extends CellRendererPanel implements
    TableCellRenderer {

  public static final String CELL_VALIDATION_PROPERTY = "CellRenderer.validationInfo";

  private final TableCellRenderer delegate;

  private final Supplier<? extends Dimension> editorSizeSupplier = JBUI::emptySize;
  private TableCellValidator cellValidator;

  public ValidatingComboBoxTableCellWrapper(TableCellRenderer delegate) {
    this.delegate = delegate;
    setLayout(new BorderLayout(0, 0));
  }

  public ValidatingComboBoxTableCellWrapper withCellValidator(
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

  @Override
  public final Component getTableCellRendererComponent(JTable table, Object value,
      boolean isSelected, boolean hasFocus, int row, int column) {
    JComponent delegateRenderer = (JComponent) delegate
        .getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
    if (cellValidator != null) {
      ValidationInfo result = cellValidator.validate(value, row, column);
      delegateRenderer.putClientProperty(CELL_VALIDATION_PROPERTY, result);
      if (result != null) {
        delegateRenderer.setToolTipText(result.message);
      } else {
        delegateRenderer.setToolTipText("");
      }
    }
    return delegateRenderer;
  }

  @Override
  protected void paintComponent(Graphics g) {
    g.setColor(getBackground());
    g.fillRect(0, 0, getWidth(), getHeight());
  }
}
