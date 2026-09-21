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

package View.Quality;

import Utils.Resources.ResourceLoader;
import com.intellij.icons.AllIcons;
import com.intellij.icons.AllIcons.Actions;
import java.awt.Component;
import java.awt.Font;
import java.util.List;
import javax.swing.AbstractCellEditor;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.ToolTipManager;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;

public class QualityDecorators {

  public static final class HeaderCenterLabel implements TableCellRenderer {

    JLabel label;

    public HeaderCenterLabel() {
      label = new JLabel();
      label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    }

    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      label.setHorizontalAlignment(SwingConstants.CENTER);
      label.setText(value.toString());
      return label;
    }
  }

  public static final class ParameterNameRenderer implements TableCellRenderer {

    JLabel label;

    public ParameterNameRenderer() {
      label = new JLabel();
    }

    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      label.setHorizontalAlignment(SwingConstants.CENTER);
      label.setText(value.toString());
      return label;
    }
  }

  public static final class TrendRenderer implements TableCellRenderer {

    JLabel label;

    public TrendRenderer() {
      ToolTipManager.sharedInstance().setInitialDelay(150);
      label = new JLabel();
    }

    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      label.setHorizontalAlignment(SwingConstants.CENTER);
      String strValue = value.toString();
      label.setToolTipText(null);
      if (strValue.equals("increase")) {
        label.setIcon(AllIcons.Actions.FindAndShowPrevMatches);
      } else if (strValue.equals("decrease")) {
        label.setIcon(AllIcons.Actions.FindAndShowNextMatches);
      } else {
        label.setIcon(Actions.IntentionBulb);
        label.setToolTipText(ResourceLoader.getInstance().get("quality_settings_not_monotonic"));
      }
      return label;
    }
  }

  public static class EnumOptionsDecorator extends AbstractCellEditor
      implements TableCellEditor, TableCellRenderer {

    List<String> enumOptions;
    JComboBox<String> comboBox;

    public EnumOptionsDecorator(List<String> enumsTemp) {
      this.enumOptions = enumsTemp;
      this.comboBox = new JComboBox<>();
      // Used to update the UI.
      comboBox.addItemListener(itemEvent -> fireEditingStopped());
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
        int row, int column) {
      String strValue = value.toString();
      setComboBoxChoices(enumOptions);
      comboBox.setSelectedItem(strValue);
      return comboBox;
    }

    private void setComboBoxChoices(List<String> choices) {
      comboBox.removeAllItems();
      for (String option : choices) {
        comboBox.addItem(option);
      }
    }

    @Override
    public Object getCellEditorValue() {
      return comboBox.getSelectedIndex() == -1 ? "" : comboBox.getSelectedItem().toString();
    }

    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      String strValue = value.toString();
      setComboBoxChoices(enumOptions);
      if (enumOptions.stream()
          .anyMatch(option -> option.equals(strValue))) {
        comboBox.setSelectedItem(strValue);
      } else {
        comboBox.setSelectedIndex(-1);
      }
      return comboBox;
    }
  }
}
