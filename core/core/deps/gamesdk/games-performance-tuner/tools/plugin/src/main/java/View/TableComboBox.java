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

package View;

import View.Decorator.ValidationComboBoxRenderer;
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil;
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil.Outline;
import com.intellij.ide.ui.laf.darcula.ui.DarculaComboBoxUI;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.swing.AbstractCellEditor;
import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;

/*
 * The original combobox of Swing had a bug which leads to instant closing of popup due to focus
 * transfer being called twice. Where the user had to click twice on the same combobox to see the popup
 * So this combobox is a better version of the original combobox as it has error balloon and validation
 * And for table cell editor/renderer just extend the BaseComboBoxTableCell and override the methods
 */
public class TableComboBox<T> extends JComboBox<T> {

  private boolean addFocusBorder = false;

  public TableComboBox() {
    super();
    this.setRenderer(new ValidationComboBoxRenderer(this));
    this.setUI(new CustomFocusBorderUI(this));
    this.setFocusable(false);
  }

  public boolean isAddFocusBorder() {
    return addFocusBorder;
  }

  public void setAddFocusBorder(boolean state) {
    addFocusBorder = state;
  }

  public static class BaseComboBoxTableCell extends AbstractCellEditor implements
      TableCellRenderer, TableCellEditor {

    TableComboBox<?> tableComboBox;

    public BaseComboBoxTableCell(TableComboBox<?> tableComboBox) {
      this.tableComboBox = tableComboBox;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
        boolean hasFocus, int row, int column) {
      tableComboBox.setAddFocusBorder(hasFocus);
      return tableComboBox;
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
        int row, int column) {
      tableComboBox.setAddFocusBorder(true);
      return tableComboBox;
    }

    @Override
    public Object getCellEditorValue() {
      return tableComboBox.getSelectedIndex() == -1 ? ""
          : tableComboBox.getSelectedItem().toString();
    }
  }

  private static final class CustomFocusBorderUI extends DarculaComboBoxUI {

    private final TableComboBox<?> holderBox;

    public CustomFocusBorderUI(TableComboBox<?> holderBox) {
      this.holderBox = holderBox;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
      super.paintBorder(c, g, x, y, width, height);
      Graphics2D g2 = (Graphics2D) g.create();
      Rectangle r = new Rectangle(x, y, width, height);
      float arc = DarculaUIUtil.COMPONENT_ARC.getFloat();
      if (holderBox.isAddFocusBorder()) {
        DarculaUIUtil.paintOutlineBorder(g2, r.width, r.height, arc, true, true, Outline.focus);
      }
      g2.dispose();
    }
  }
}
