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

import View.Decorator.DocumentFilters.NumberDocumentFilter;
import View.Decorator.RoundedCornerBorder;
import View.Decorator.TableRenderer;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.util.ui.UIUtil;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.swing.DefaultCellEditor;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableColumn;
import javax.swing.text.AbstractDocument;

public class TabLayout extends JPanel {

  private static final int PANEL_MIN_WIDTH = 600;
  private static final int PANEL_MIN_HEIGHT = 500;
  private static final int TABLE_MIN_WIDTH = 500;
  private static final int TABLE_MIN_HEIGHT = 230;
  private static final int FONT_BIG = 18;
  private static final int FONT_SMALL = 12;
  private static final Font MAIN_FONT = new Font(Font.SANS_SERIF, Font.BOLD, FONT_BIG);
  private static final Font SECONDARY_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, FONT_SMALL);

  public static Font getMainFont() {
    return MAIN_FONT;
  }

  public static Font getSecondaryFont() {
    return SECONDARY_FONT;
  }

  public void setDecoratorPanelSize(JPanel decoratorPanel) {
    decoratorPanel.setPreferredSize(new Dimension(TABLE_MIN_WIDTH, TABLE_MIN_HEIGHT));
    decoratorPanel.setMinimumSize(new Dimension(TABLE_MIN_WIDTH, TABLE_MIN_HEIGHT));
  }

  public void setSize() {
    this.setMinimumSize(new Dimension(PANEL_MIN_WIDTH, PANEL_MIN_HEIGHT));
    this.setPreferredSize(new Dimension(PANEL_MIN_WIDTH, PANEL_MIN_HEIGHT));
  }

  public static void setTableSettings(JTable table) {
    table.setFillsViewportHeight(true);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.getTableHeader().setReorderingAllowed(false);
    table.setRowSelectionAllowed(true);
    table.setSelectionBackground(null);
    table.setSelectionForeground(null);
    table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
    table.setSurrendersFocusOnKeystroke(true);
    table.setIntercellSpacing(new Dimension(0, 0));
    table.setShowGrid(false);
  }

  public void initComboBoxColumns(JTable table, int col, List<String> options) {
    TableColumn column = table.getColumnModel().getColumn(col);
    column.setCellEditor(getComboBoxModel(options));
    column.setCellRenderer(new TableRenderer.ComboBoxRenderer());
  }

  private DefaultCellEditor getComboBoxModel(List<String> options) {
    ComboBox<String> comboBoxModel = new ComboBox<>();
    for (String option : options) {
      comboBoxModel.addItem(option);
    }
    DefaultCellEditor boxEditor = new DefaultCellEditor(comboBoxModel);
    boxEditor.setClickCountToStart(1);
    return boxEditor;
  }

  public static DefaultCellEditor getTextFieldModel() {
    JTextField textFieldModel = new JTextField();
    textFieldModel.setOpaque(true);
    textFieldModel.setBorder(new RoundedCornerBorder());
    DefaultCellEditor textEditor = new CellEditorWithBackground(textFieldModel);
    textEditor.setClickCountToStart(1);
    return textEditor;
  }

  public TableCellEditor getIntegerTextFieldModel() {
    JTextField textFieldModel = new JTextField();
    textFieldModel.setOpaque(true);
    DefaultCellEditor defaultCellEditor = new CellEditorWithBackground(textFieldModel);
    ((AbstractDocument) textFieldModel.getDocument())
        .setDocumentFilter(new NumberDocumentFilter());
    textFieldModel.setBorder(new RoundedCornerBorder());
    defaultCellEditor.setClickCountToStart(1);
    return defaultCellEditor;
  }

  public boolean isViewValid() {
    return true;
  }

  private static final class CellEditorWithBackground extends DefaultCellEditor {

    public CellEditorWithBackground(JTextField textField) {
      super(textField);
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
        int row, int column) {
      JComponent jComponent = (JComponent) super
          .getTableCellEditorComponent(table, value, isSelected, row, column);
      jComponent.setBackground(UIUtil.getTableGridColor());
      return jComponent;
    }
  }

}
