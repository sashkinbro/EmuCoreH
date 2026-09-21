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

import com.intellij.icons.AllIcons.General;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.cellvalidators.TableCellValidator;
import com.intellij.ui.CellRendererPanel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.JBUI.Borders;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.border.Border;
import javax.swing.table.TableCellRenderer;
import org.jetbrains.annotations.NotNull;

public class ValidationTextFieldRendererWrapper extends CellRendererPanel implements
    TableCellRenderer {

  private final TableCellRenderer delegate;
  private final JLabel iconLabel = new JLabel();
  private final Supplier<? extends Dimension> editorSizeSupplier = JBUI::emptySize;
  private TableCellValidator cellValidator;

  public ValidationTextFieldRendererWrapper(TableCellRenderer delegate) {
    this.delegate = delegate;
    this.setLayout(new BorderLayout(0, 0));
    this.add(this.iconLabel, BorderLayout.EAST);
    this.iconLabel.setOpaque(false);
  }

  public ValidationTextFieldRendererWrapper withCellValidator(
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
    JComponent delegateRenderer = (JComponent) this.delegate
        .getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
    if (cellValidator != null) {
      ValidationInfo result = cellValidator.validate(value, row, column);
      iconLabel.setIcon(
          result == null ? null : (result.warning ? General.BalloonWarning : General.BalloonError));
      iconLabel.setBorder(result == null ? null : iconBorder());
      putClientProperty("CellRenderer.validationInfo", result);
      if (result != null) {
        setToolTipText(result.message);
      } else {
        setToolTipText("");
      }
    }

    add(delegateRenderer, BorderLayout.CENTER);
    setBorder(delegateRenderer.getBorder());
    delegateRenderer.setBorder((Border) null);
    setBackground(delegateRenderer.getBackground());
    return this;
  }

  private static Border iconBorder() {
    return Borders.emptyRight(UIUtil.isUnderWin10LookAndFeel() ? 4 : 3);
  }

  @Override
  protected void paintComponent(Graphics g) {
    g.setColor(getBackground());
    g.fillRect(0, 0, getWidth(), getHeight());
  }
}